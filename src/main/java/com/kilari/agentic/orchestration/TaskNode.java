package com.kilari.agentic.orchestration;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One unit of agent work in the graph.
 *
 * <p>Mutable, because a node's state genuinely evolves during a run and every
 * transition is checkpointed. Mutation is confined to the executor thread that
 * owns the task, and the owning {@link WorkflowGraph} is the synchronisation
 * point — nodes are never mutated from two threads at once.
 */
public class TaskNode {

    private final String id;
    private final AgentType agentType;
    private final Set<String> dependsOn;
    private final int maxAttempts;

    /**
     * Which context revision this node was planned against. A node planned
     * against a stale revision is a re-planning candidate: it was designed for
     * assumptions that no longer hold.
     */
    private final int plannedAtRevision;

    private volatile TaskState state = TaskState.PENDING;
    private volatile int attempts = 0;
    private volatile String lastFailureReason;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;

    public TaskNode(String id, AgentType agentType, Set<String> dependsOn, int maxAttempts, int plannedAtRevision) {
        this.id = Objects.requireNonNull(id, "id");
        this.agentType = Objects.requireNonNull(agentType, "agentType");
        this.dependsOn = Set.copyOf(Objects.requireNonNull(dependsOn, "dependsOn"));
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1 for task " + id);
        }
        if (dependsOn.contains(id)) {
            throw new IllegalArgumentException("task " + id + " cannot depend on itself");
        }
        this.maxAttempts = maxAttempts;
        this.plannedAtRevision = plannedAtRevision;
    }

    public static TaskNode of(String id, AgentType agentType, String... dependsOn) {
        return new TaskNode(id, agentType, new LinkedHashSet<>(Set.of(dependsOn)), 3, 0);
    }

    public String id() {
        return id;
    }

    public AgentType agentType() {
        return agentType;
    }

    public Set<String> dependsOn() {
        return dependsOn;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int plannedAtRevision() {
        return plannedAtRevision;
    }

    public TaskState state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    /** True while the bounded-retry budget still permits another attempt. */
    public boolean hasAttemptsRemaining() {
        return attempts < maxAttempts;
    }

    void markReady() {
        this.state = TaskState.READY;
    }

    void markRunning() {
        this.state = TaskState.RUNNING;
        this.attempts++;
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    void markSucceeded() {
        this.state = TaskState.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    /**
     * Records a failed attempt. The node lands in FAILED while retries remain so
     * the executor can pick it up again, and EXHAUSTED once the budget is spent —
     * bounded retries are enforced here rather than left to the caller.
     */
    void markFailed(String reason) {
        this.lastFailureReason = reason;
        if (hasAttemptsRemaining()) {
            this.state = TaskState.FAILED;
        } else {
            this.state = TaskState.EXHAUSTED;
            this.finishedAt = Instant.now();
        }
    }

    void markBlocked(String reason) {
        this.lastFailureReason = reason;
        this.state = TaskState.BLOCKED;
        this.finishedAt = Instant.now();
    }

    void markSuperseded() {
        this.state = TaskState.SUPERSEDED;
        this.finishedAt = Instant.now();
    }

    void markAwaitingHuman() {
        this.state = TaskState.AWAITING_HUMAN;
    }

    /** Used by recovery to restore a persisted node without replaying its history. */
    void restore(TaskState state, int attempts, String lastFailureReason, Instant startedAt, Instant finishedAt) {
        this.state = state;
        this.attempts = attempts;
        this.lastFailureReason = lastFailureReason;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    @Override
    public String toString() {
        return "%s[%s, %s, attempt %d/%d]".formatted(id, agentType, state, attempts, maxAttempts);
    }
}
