package com.kilari.agentic.orchestration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-stage context: what agents have learned so far, plus the append-only
 * lineage of how the run got here.
 *
 * <p>The revision counter is the mechanism behind dynamic re-planning. Agents
 * never mutate each other's outputs; they publish artifacts at the current
 * revision. When new information arrives that invalidates earlier assumptions —
 * a human clarification, a validation failure that changes the approach — the
 * revision is bumped, which marks every artifact produced before it as stale and
 * lets the planner decide which parts of the graph must be rebuilt.
 */
public class WorkflowContext {

    private final String workflowId;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final List<DecisionRecord> lineage = new ArrayList<>();

    private int revision = 0;

    public WorkflowContext(String workflowId) {
        this.workflowId = workflowId;
    }

    public String workflowId() {
        return workflowId;
    }

    public int revision() {
        lock.lock();
        try {
            return revision;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publishes an artifact at the current revision. Replacing an existing key is
     * legitimate — a repair agent supersedes an implementation — and the previous
     * value stays in the lineage rather than being silently lost.
     */
    public Artifact publish(String key, AgentType producedBy, String taskId, String content) {
        lock.lock();
        try {
            Artifact artifact = Artifact.of(key, producedBy, taskId, revision, content);
            artifacts.put(key, artifact);
            return artifact;
        } finally {
            lock.unlock();
        }
    }

    public Optional<Artifact> get(String key) {
        lock.lock();
        try {
            return Optional.ofNullable(artifacts.get(key));
        } finally {
            lock.unlock();
        }
    }

    public Optional<String> content(String key) {
        return get(key).map(Artifact::content);
    }

    public Map<String, Artifact> artifacts() {
        lock.lock();
        try {
            return Map.copyOf(artifacts);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Bumps the revision, marking every existing artifact as stale.
     *
     * @param reason why the world changed; recorded in the lineage so a reviewer
     *               can see what triggered the re-plan
     * @return the new revision
     */
    public int bumpRevision(String reason) {
        lock.lock();
        try {
            revision++;
            lineage.add(DecisionRecord.of(workflowId, null, DecisionRecord.Actor.ORCHESTRATOR,
                    DecisionRecord.DecisionType.PLAN_REVISED, revision,
                    "Context revision %d: %s".formatted(revision, reason)));
            return revision;
        } finally {
            lock.unlock();
        }
    }

    /** Artifacts produced before the current revision, i.e. built on stale assumptions. */
    public List<Artifact> staleArtifacts() {
        lock.lock();
        try {
            return artifacts.values().stream()
                    .filter(a -> a.isStaleAt(revision))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public void record(DecisionRecord record) {
        lock.lock();
        try {
            lineage.add(record);
        } finally {
            lock.unlock();
        }
    }

    /** The full decision lineage, oldest first. */
    public List<DecisionRecord> lineage() {
        lock.lock();
        try {
            return List.copyOf(lineage);
        } finally {
            lock.unlock();
        }
    }

    /** Used by recovery to restore persisted state without replaying execution. */
    void restore(int revision, Map<String, Artifact> artifacts, List<DecisionRecord> lineage) {
        lock.lock();
        try {
            this.revision = revision;
            this.artifacts.clear();
            this.artifacts.putAll(artifacts);
            this.lineage.clear();
            this.lineage.addAll(lineage);
        } finally {
            lock.unlock();
        }
    }
}
