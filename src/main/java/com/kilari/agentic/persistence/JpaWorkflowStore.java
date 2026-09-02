package com.kilari.agentic.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.orchestration.Artifact;
import com.kilari.agentic.orchestration.DecisionRecord;
import com.kilari.agentic.orchestration.RecoverySupport;
import com.kilari.agentic.orchestration.TaskNode;
import com.kilari.agentic.orchestration.TaskState;
import com.kilari.agentic.orchestration.WorkflowContext;
import com.kilari.agentic.orchestration.WorkflowGraph;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.orchestration.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Durable workflow storage backed by JPA.
 *
 * <p>Checkpointing writes the run's full state, so a process that dies mid-flight
 * leaves behind enough to continue rather than only enough to explain. Recovery
 * rehydrates the aggregate and hands it back to the engine, which resumes from
 * whatever the graph says is unblocked.
 *
 * <p>What is deliberately <em>not</em> resumed automatically: a task recorded as
 * RUNNING when the process died. Its side effects are unknown — it may have
 * written half a patch — so it is reset to a retryable state and re-run from a
 * verified snapshot rather than assumed complete. Assuming completion is how a
 * recovery mechanism silently skips work.
 */
public class JpaWorkflowStore implements WorkflowStore {

    private static final Logger log = LoggerFactory.getLogger(JpaWorkflowStore.class);

    private final WorkflowCheckpointRepository checkpoints;
    private final DecisionRecordRepository decisions;
    private final ObjectMapper mapper;

    public JpaWorkflowStore(WorkflowCheckpointRepository checkpoints,
                            DecisionRecordRepository decisions) {
        this.checkpoints = checkpoints;
        this.decisions = decisions;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    @Transactional
    public void checkpoint(WorkflowRun run) {
        WorkflowCheckpointEntity entity = checkpoints.findById(run.workflowId())
                .orElseGet(() -> new WorkflowCheckpointEntity(run.workflowId()));

        entity.setState(run.state().name());
        entity.setRequirement(run.requirement());
        entity.setClarification(run.clarification().orElse(null));
        entity.setApprover(run.approver().orElse(null));
        entity.setTerminalReason(run.terminalReason().orElse(null));
        entity.setWorkspacePath(run.workspace() == null ? null : run.workspace().toString());
        entity.setContextRevision(run.context().revision());
        entity.setGraphJson(serialiseGraph(run.graph()));
        entity.setArtifactsJson(serialiseArtifacts(run.context()));
        entity.setRepairRounds(run.repairRounds());
        entity.setRollbackCount(run.rollbackCount());
        entity.setRetryCount(run.retryCount());
        entity.setStartedAt(run.startedAt());
        entity.setFirstFailureAt(run.firstFailureAt().orElse(null));
        entity.setFinishedAt(run.finishedAt().orElse(null));
        entity.setCheckpointedAt(Instant.now());

        checkpoints.save(entity);
        appendNewDecisions(run);
    }

    /**
     * Appends lineage entries not yet persisted.
     *
     * <p>Compares against the stored count rather than re-inserting everything,
     * which keeps the table append-only and stops a checkpoint from duplicating
     * history it has already written.
     */
    private void appendNewDecisions(WorkflowRun run) {
        long alreadyStored = decisions.countByWorkflowId(run.workflowId());
        List<DecisionRecord> lineage = run.context().lineage();

        if (lineage.size() <= alreadyStored) {
            return;
        }

        List<DecisionRecordEntity> toInsert = lineage.subList((int) alreadyStored, lineage.size())
                .stream()
                .map(record -> new DecisionRecordEntity(
                        record.workflowId(),
                        record.taskId(),
                        record.actor().name(),
                        record.type().name(),
                        record.contextRevision(),
                        record.summary(),
                        writeJson(record.evidence()),
                        record.recordedAt()))
                .toList();

        decisions.saveAll(toInsert);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowRun> load(String workflowId) {
        return checkpoints.findById(workflowId).map(this::rehydrate);
    }

    private WorkflowRun rehydrate(WorkflowCheckpointEntity entity) {
        WorkflowGraph graph = deserialiseGraph(entity.getGraphJson());

        WorkflowRun run = new WorkflowRun(
                entity.getWorkflowId(),
                entity.getRequirement(),
                graph,
                entity.getWorkspacePath() == null ? null : Path.of(entity.getWorkspacePath()));

        if (entity.getClarification() != null) {
            run.recordClarification(entity.getClarification());
        }
        if (entity.getApprover() != null) {
            run.recordApprover(entity.getApprover());
        }

        // Counters and timing are part of the aggregate, not decoration. Skipping
        // them resets the repair budget and restarts the latency clock.
        RecoverySupport.restoreExecutionState(run,
                entity.getRepairRounds(),
                entity.getRollbackCount(),
                entity.getRetryCount(),
                entity.getStartedAt(),
                entity.getFirstFailureAt(),
                entity.getFinishedAt());

        restoreContext(run.context(), entity);
        run.transitionTo(WorkflowState.valueOf(entity.getState()), entity.getTerminalReason());

        log.info("Rehydrated workflow {} in state {} at revision {}",
                entity.getWorkflowId(), entity.getState(), entity.getContextRevision());
        return run;
    }

    private void restoreContext(WorkflowContext context, WorkflowCheckpointEntity entity) {
        Map<String, Artifact> artifacts = new LinkedHashMap<>();
        for (SerialisedArtifact serialised : readJson(entity.getArtifactsJson(),
                new TypeReference<List<SerialisedArtifact>>() {
                })) {
            artifacts.put(serialised.key(), new Artifact(
                    serialised.key(),
                    AgentType.valueOf(serialised.producedBy()),
                    serialised.taskId(),
                    serialised.revision(),
                    serialised.content(),
                    serialised.producedAt()));
        }

        List<DecisionRecord> lineage = decisions.findByWorkflowIdOrderByRecordedAtAsc(entity.getWorkflowId())
                .stream()
                .map(row -> new DecisionRecord(
                        row.getWorkflowId(),
                        row.getTaskId(),
                        DecisionRecord.Actor.valueOf(row.getActor()),
                        DecisionRecord.DecisionType.valueOf(row.getDecisionType()),
                        row.getContextRevision(),
                        row.getSummary(),
                        readJson(row.getEvidenceJson(), new TypeReference<Map<String, String>>() {
                        }),
                        row.getRecordedAt()))
                .toList();

        RecoverySupport.restoreContext(context, entity.getContextRevision(), artifacts, lineage);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findResumable() {
        return checkpoints.findAll().stream()
                .filter(entity -> !WorkflowState.valueOf(entity.getState()).isTerminal())
                .map(WorkflowCheckpointEntity::getWorkflowId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findAll() {
        return checkpoints.findAll().stream()
                .map(WorkflowCheckpointEntity::getWorkflowId)
                .toList();
    }

    // ---- serialisation -----------------------------------------------------

    private String serialiseGraph(WorkflowGraph graph) {
        List<SerialisedNode> nodes = graph.nodes().stream()
                .map(node -> new SerialisedNode(
                        node.id(),
                        node.agentType().name(),
                        List.copyOf(node.dependsOn()),
                        node.state().name(),
                        node.attempts(),
                        node.maxAttempts(),
                        node.plannedAtRevision(),
                        node.lastFailureReason(),
                        node.startedAt(),
                        node.finishedAt()))
                .toList();
        return writeJson(nodes);
    }

    private WorkflowGraph deserialiseGraph(String json) {
        List<SerialisedNode> nodes = readJson(json, new TypeReference<List<SerialisedNode>>() {
        });

        List<TaskNode> restored = new ArrayList<>();
        for (SerialisedNode node : nodes) {
            TaskNode taskNode = new TaskNode(
                    node.id(),
                    AgentType.valueOf(node.agentType()),
                    Set.copyOf(node.dependsOn()),
                    node.maxAttempts(),
                    node.plannedAtRevision());

            TaskState state = TaskState.valueOf(node.state());
            // A task that was RUNNING when the process died has unknown side
            // effects. Resetting it to PENDING re-runs it from a verified
            // snapshot rather than assuming it finished.
            if (state == TaskState.RUNNING) {
                log.warn("Task {} was RUNNING at crash time; resetting to PENDING for safe re-execution",
                        node.id());
                state = TaskState.PENDING;
            }

            RecoverySupport.restoreNode(taskNode, state, node.attempts(), node.lastFailureReason(),
                    node.startedAt(), node.finishedAt());
            restored.add(taskNode);
        }

        return new WorkflowGraph(restored);
    }

    private String serialiseArtifacts(WorkflowContext context) {
        List<SerialisedArtifact> artifacts = context.artifacts().values().stream()
                .map(artifact -> new SerialisedArtifact(
                        artifact.key(),
                        artifact.producedBy().name(),
                        artifact.taskId(),
                        artifact.revision(),
                        artifact.content(),
                        artifact.producedAt()))
                .toList();
        return writeJson(artifacts);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise workflow state", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            if (json == null || json.isBlank()) {
                return mapper.readValue("[]".equals(type.getType().getTypeName()) ? "[]" : "{}", type);
            }
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("could not deserialise workflow state", e);
        }
    }

    private record SerialisedNode(String id, String agentType, List<String> dependsOn, String state,
                                  int attempts, int maxAttempts, int plannedAtRevision,
                                  String lastFailureReason, Instant startedAt, Instant finishedAt) {
    }

    private record SerialisedArtifact(String key, String producedBy, String taskId, int revision,
                                      String content, Instant producedAt) {
    }
}
