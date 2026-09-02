package com.kilari.agentic.orchestration;

import com.kilari.agentic.agent.Agent;
import com.kilari.agentic.agent.AgentExecution;
import com.kilari.agentic.agent.AgentOutcome;
import com.kilari.agentic.governance.PolicyGuard;
import com.kilari.agentic.metrics.WorkflowMetrics;
import com.kilari.agentic.persistence.WorkflowStore;
import com.kilari.agentic.tools.WorkspaceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.kilari.agentic.orchestration.DecisionRecord.Actor;
import static com.kilari.agentic.orchestration.DecisionRecord.DecisionType;

/**
 * Drives workflow graphs to completion.
 *
 * <p>This is the deterministic control plane. It decides what runs, in what
 * order, how many times, what happens on failure, when to stop, and when a human
 * must be involved. No model output influences any of those decisions — agents
 * return an outcome, and the rules for interpreting it are written here in
 * ordinary Java that can be read, tested and argued with.
 *
 * <p>The loop is short on purpose. Take the tasks that are currently unblocked,
 * run them, interpret what came back, checkpoint, repeat. Everything that makes
 * the system non-linear — parallel branches, synchronisation, re-planning,
 * repair rounds, parking for a human — is a consequence of the graph and the
 * signals, not of extra control flow bolted on here.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /**
     * How many repair rounds a run may attempt before giving up.
     *
     * <p>Bounded because an agent that cannot fix a failure in two informed
     * attempts is unlikely to fix it in ten, and an unbounded repair loop is an
     * unbounded bill and an unbounded wait.
     */
    public static final int MAX_REPAIR_ROUNDS = 2;

    private final Map<AgentType, Agent> agents;
    private final WorkflowStore store;
    private final WorkflowMetrics metrics;
    private final PolicyGuard policyGuard;

    public WorkflowEngine(Map<AgentType, Agent> agents,
                          WorkflowStore store,
                          WorkflowMetrics metrics,
                          PolicyGuard policyGuard) {
        this.agents = Map.copyOf(agents);
        this.store = store;
        this.metrics = metrics;
        this.policyGuard = policyGuard;
    }

    /** Runs until the graph settles or the workflow parks for a human. */
    public void drive(WorkflowRun run) {
        if (run.state() == WorkflowState.CREATED) {
            run.transitionTo(WorkflowState.RUNNING, "execution started");
            record(run, null, Actor.ORCHESTRATOR, DecisionType.PLAN_CREATED,
                    "Initial plan: %d tasks".formatted(run.graph().nodes().size()));
            store.checkpoint(run);
        }

        while (true) {
            if (run.state().isTerminal() || run.state().isAwaitingHuman()) {
                return;
            }

            List<TaskNode> ready = run.graph().readyTasks();
            if (ready.isEmpty()) {
                settle(run);
                return;
            }

            runBatch(run, ready);
            store.checkpoint(run);
        }
    }

    /**
     * Executes every currently-unblocked task concurrently.
     *
     * <p>Virtual threads because these tasks are almost entirely waiting — on a
     * model API or on a build subprocess. Sizing a platform-thread pool for work
     * that is IO-bound anyway would only add a tuning parameter nobody can
     * choose well.
     */
    private void runBatch(WorkflowRun run, List<TaskNode> ready) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TaskExecution>> futures = ready.stream()
                    .map(node -> {
                        prepareTask(run, node);
                        return executor.submit(() -> executeTask(run, node));
                    })
                    .toList();

            for (Future<TaskExecution> future : futures) {
                try {
                    applyOutcome(run, future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while executing workflow", e);
                } catch (Exception e) {
                    log.error("Unexpected failure collecting task result", e);
                    throw new IllegalStateException("workflow execution failed", e);
                }
            }
        }
    }

    private void prepareTask(WorkflowRun run, TaskNode node) {
        node.markRunning();
        if (node.attempts() > 1) {
            run.recordRetry();
            metrics.recordRetry(node.agentType());
            record(run, node.id(), Actor.ORCHESTRATOR, DecisionType.RETRY_SCHEDULED,
                    "Attempt %d of %d for %s".formatted(node.attempts(), node.maxAttempts(), node.id()));
        }
        record(run, node.id(), Actor.ORCHESTRATOR, DecisionType.TASK_STARTED,
                "Started %s (%s)".formatted(node.id(), node.agentType()));

        // Anything that mutates the workspace gets a verified restore point first.
        // Taken here rather than inside the agent so the guarantee does not depend
        // on an agent implementation remembering to ask for it.
        if (node.agentType().mutatesWorkspace()) {
            WorkspaceSnapshot snapshot =
                    WorkspaceSnapshot.capture(node.id(), run.workspace());
            run.putSnapshot(node.id(), snapshot);
            log.debug("Captured pre-mutation snapshot for {} ({} files)",
                    node.id(), snapshot.fileCount());
        }
    }

    private TaskExecution executeTask(WorkflowRun run, TaskNode node) {
        Agent agent = agents.get(node.agentType());
        if (agent == null) {
            return TaskExecution.failed(node,
                    new IllegalStateException("no agent registered for " + node.agentType()));
        }

        AgentExecution execution = new AgentExecution(
                run.workflowId(),
                node.id(),
                run.requirement(),
                run.clarification().orElse(null),
                run.context(),
                run.workspace(),
                node.attempts());

        long start = System.nanoTime();
        try {
            policyGuard.beforeAgentRun(run, node);
            AgentOutcome outcome = agent.execute(execution);
            metrics.recordTaskDuration(node.agentType(), System.nanoTime() - start, true);
            return TaskExecution.completed(node, outcome);
        } catch (Exception e) {
            metrics.recordTaskDuration(node.agentType(), System.nanoTime() - start, false);
            log.warn("Task {} failed on attempt {}: {}", node.id(), node.attempts(), e.getMessage());
            return TaskExecution.failed(node, e);
        }
    }

    /** Interprets one task result. Every workflow state change originates here. */
    private void applyOutcome(WorkflowRun run, TaskExecution execution) {
        TaskNode node = execution.node();

        if (execution.error() != null) {
            failTask(run, node, execution.error().getMessage());
            return;
        }

        AgentOutcome outcome = execution.outcome();
        switch (outcome.signal()) {
            case CONTINUE -> {
                outcome.artifacts().forEach((key, value) ->
                        run.context().publish(key, node.agentType(), node.id(), value));
                node.markSucceeded();
                metrics.recordTaskSuccess(node.agentType());
                record(run, node.id(), Actor.AGENT, DecisionType.TASK_SUCCEEDED,
                        outcome.summary(), outcome.evidence());
            }

            case NEEDS_CLARIFICATION -> {
                node.markAwaitingHuman();
                run.transitionTo(WorkflowState.AWAITING_CLARIFICATION, outcome.summary());
                run.context().publish(
                        com.kilari.agentic.agent.ContextKeys.REQUIREMENT_QUESTIONS,
                        node.agentType(), node.id(), String.join("\n", outcome.questions()));
                metrics.recordClarificationRequested();
                record(run, node.id(), Actor.AGENT, DecisionType.CLARIFICATION_REQUESTED,
                        outcome.summary(),
                        Map.of("questions", String.join(" | ", outcome.questions())));
                log.info("Workflow {} parked awaiting clarification", run.workflowId());
            }

            case NEEDS_APPROVAL -> {
                node.markAwaitingHuman();
                run.transitionTo(WorkflowState.AWAITING_APPROVAL, outcome.summary());
                run.context().publish(
                        com.kilari.agentic.agent.ContextKeys.RELEASE_EVIDENCE,
                        node.agentType(), node.id(), outcome.summary());
                record(run, node.id(), Actor.ORCHESTRATOR, DecisionType.APPROVAL_REQUESTED,
                        outcome.summary(), outcome.evidence());
                log.info("Workflow {} parked awaiting approval", run.workflowId());
            }

            case VALIDATION_FAILED -> {
                outcome.artifacts().forEach((key, value) ->
                        run.context().publish(key, node.agentType(), node.id(), value));
                // The validation task did its job: it produced a truthful answer.
                // The change is what failed, so the node succeeds and the plan changes.
                node.markSucceeded();
                record(run, node.id(), Actor.ORCHESTRATOR, DecisionType.VALIDATION_RESULT,
                        outcome.summary(), outcome.evidence());
                metrics.recordValidationFailure();
                run.recordFailureMoment();
                planRepair(run, node);
            }

            case SAFE_STOP -> {
                node.markBlocked(outcome.summary());
                safeStop(run, outcome.summary());
            }
        }
    }

    private void failTask(WorkflowRun run, TaskNode node, String reason) {
        node.markFailed(reason);
        record(run, node.id(), Actor.ORCHESTRATOR, DecisionType.TASK_FAILED,
                "Task %s failed: %s".formatted(node.id(), reason));

        if (node.state() == TaskState.EXHAUSTED) {
            metrics.recordTaskExhausted(node.agentType());
            List<TaskNode> blocked = run.graph().propagateBlocked();
            log.warn("Task {} exhausted its retries; {} downstream tasks blocked",
                    node.id(), blocked.size());
        }
    }

    /**
     * Replaces the remaining plan with a repair round.
     *
     * <p>Rolling back before repairing matters: the repair agent proposes changes
     * against the code as it was written, not against a workspace already
     * carrying a half-working previous attempt. Without the restore, each round
     * would layer edits on top of the last and the diff would stop being
     * reviewable by round two.
     */
    private void planRepair(WorkflowRun run, TaskNode failedValidation) {
        if (run.repairRounds() >= MAX_REPAIR_ROUNDS) {
            rollbackToLastSnapshot(run, "repair budget exhausted");
            run.transitionTo(WorkflowState.FAILED,
                    "validation still failing after %d repair rounds".formatted(MAX_REPAIR_ROUNDS));
            metrics.recordWorkflowOutcome(run);
            store.checkpoint(run);
            return;
        }

        int round = run.nextRepairRound();
        rollbackToLastSnapshot(run, "restoring workspace before repair round " + round);

        run.transitionTo(WorkflowState.REPAIRING, "repair round " + round);
        int revision = run.context().bumpRevision(
                "validation failed; repair round %d planned from build evidence".formatted(round));

        WorkflowGraph.PlanDelta delta = run.graph().applyRevision(
                WorkflowPlanner.repairRevision(round, failedValidation.id()));

        record(run, null, Actor.ORCHESTRATOR, DecisionType.PLAN_REVISED,
                "Re-planned at revision %d: added %s, superseded %s"
                        .formatted(revision, delta.added(), delta.superseded()),
                Map.of("round", String.valueOf(round)));

        run.transitionTo(WorkflowState.RUNNING, "executing repair round " + round);
        log.info("Workflow {} re-planned for repair round {}: +{} tasks",
                run.workflowId(), round, delta.added().size());
    }

    private void rollbackToLastSnapshot(WorkflowRun run, String reason) {
        run.graph().nodes().stream()
                .filter(node -> node.agentType().mutatesWorkspace())
                .map(node -> run.snapshot(node.id()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .reduce((first, second) -> second)
                .ifPresent(snapshot -> {
                    WorkspaceSnapshot.RollbackResult result = snapshot.restore();
                    run.recordRollback();
                    metrics.recordRollback();
                    record(run, null, Actor.ORCHESTRATOR, DecisionType.ROLLBACK_PERFORMED,
                            "%s: restored %d files, removed %d (verified)".formatted(
                                    reason, result.restored().size(), result.removed().size()));
                });
    }

    /** Decides the terminal state once no task can make further progress. */
    private void settle(WorkflowRun run) {
        if (run.graph().allSucceeded()) {
            run.transitionTo(WorkflowState.COMPLETED, "all tasks succeeded");
        } else {
            rollbackToLastSnapshot(run, "workflow could not complete");
            String failed = run.graph().nodes().stream()
                    .filter(node -> node.state().isTerminalFailure())
                    .map(TaskNode::id)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("unknown");
            run.transitionTo(WorkflowState.FAILED, "tasks did not complete: " + failed);
        }
        metrics.recordWorkflowOutcome(run);
        store.checkpoint(run);
        log.info("Workflow {} settled as {} after {}ms",
                run.workflowId(), run.state(), run.elapsed().toMillis());
    }

    private void safeStop(WorkflowRun run, String reason) {
        rollbackToLastSnapshot(run, "safe stop");
        run.transitionTo(WorkflowState.SAFE_STOPPED, reason);
        record(run, null, Actor.POLICY, DecisionType.SAFE_STOP, reason);
        metrics.recordSafeStop();
        metrics.recordWorkflowOutcome(run);
        store.checkpoint(run);
        log.warn("Workflow {} safe-stopped: {}", run.workflowId(), reason);
    }

    // ---- human decisions ---------------------------------------------------

    /**
     * Resumes a parked run with the human's answer.
     *
     * <p>The clarification bumps the context revision before re-planning, so
     * every artifact produced under the old understanding is marked stale rather
     * than silently reused.
     */
    public void resumeWithClarification(WorkflowRun run, String clarification) {
        if (run.state() != WorkflowState.AWAITING_CLARIFICATION) {
            throw new IllegalStateException(
                    "workflow %s is %s, not awaiting clarification"
                            .formatted(run.workflowId(), run.state()));
        }

        run.recordClarification(clarification);
        int revision = run.context().bumpRevision("human clarification received");

        record(run, null, Actor.HUMAN, DecisionType.CLARIFICATION_RECEIVED,
                "Clarification received; re-planning at revision " + revision,
                Map.of("clarification", clarification));

        WorkflowGraph.PlanDelta delta =
                run.graph().applyRevision(WorkflowPlanner.clarificationRevision());

        record(run, null, Actor.ORCHESTRATOR, DecisionType.PLAN_REVISED,
                "Plan expanded from %d to %d tasks after clarification"
                        .formatted(run.graph().nodes().size() - delta.added().size(),
                                run.graph().nodes().size()));

        run.transitionTo(WorkflowState.RUNNING, "resumed after clarification");
        store.checkpoint(run);
        drive(run);
    }

    public void approve(WorkflowRun run, String approver, String comment) {
        if (run.state() != WorkflowState.AWAITING_APPROVAL) {
            throw new IllegalStateException(
                    "workflow %s is %s, not awaiting approval".formatted(run.workflowId(), run.state()));
        }

        run.recordApprover(approver);
        run.graph().nodes().stream()
                .filter(node -> node.state() == TaskState.AWAITING_HUMAN)
                .forEach(TaskNode::markSucceeded);

        record(run, null, Actor.HUMAN, DecisionType.APPROVAL_GRANTED,
                "Approved by %s%s".formatted(approver, comment == null ? "" : ": " + comment));

        run.transitionTo(WorkflowState.COMPLETED, "approved by " + approver);
        metrics.recordApproval(true);
        metrics.recordWorkflowOutcome(run);
        store.checkpoint(run);
    }

    /**
     * Rejects the change and restores the workspace.
     *
     * <p>Rejection rolls back rather than merely marking the run failed. An
     * approver saying no should leave nothing behind — otherwise the workspace
     * still contains rejected code, and the next person to look at it has no way
     * to know it was refused.
     */
    public void reject(WorkflowRun run, String approver, String reason) {
        if (run.state() != WorkflowState.AWAITING_APPROVAL) {
            throw new IllegalStateException(
                    "workflow %s is %s, not awaiting approval".formatted(run.workflowId(), run.state()));
        }

        run.recordApprover(approver);
        rollbackToLastSnapshot(run, "change rejected by " + approver);

        record(run, null, Actor.HUMAN, DecisionType.APPROVAL_REJECTED,
                "Rejected by %s: %s".formatted(approver, reason));

        run.transitionTo(WorkflowState.FAILED, "rejected by " + approver + ": " + reason);
        metrics.recordApproval(false);
        metrics.recordWorkflowOutcome(run);
        store.checkpoint(run);
    }

    private void record(WorkflowRun run, String taskId, Actor actor, DecisionType type, String summary) {
        record(run, taskId, actor, type, summary, Map.of());
    }

    private void record(WorkflowRun run, String taskId, Actor actor, DecisionType type,
                        String summary, Map<String, String> evidence) {
        run.context().record(new DecisionRecord(
                run.workflowId(), taskId, actor, type,
                run.context().revision(), summary, evidence, java.time.Instant.now()));
    }

    /** One task's result, successful or not. */
    private record TaskExecution(TaskNode node, AgentOutcome outcome, Exception error) {

        static TaskExecution completed(TaskNode node, AgentOutcome outcome) {
            return new TaskExecution(node, outcome, null);
        }

        static TaskExecution failed(TaskNode node, Exception error) {
            return new TaskExecution(node, null, error);
        }
    }
}
