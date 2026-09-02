package com.kilari.agentic.orchestration;

import com.kilari.agentic.tools.WorkspaceSnapshot;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The complete state of one workflow execution.
 *
 * <p>This aggregate is what has to survive a crash. Everything needed to decide
 * what happens next lives here — graph, context, workspace, snapshot, counters
 * and the human decisions received so far — so recovery is a matter of
 * rehydrating this object rather than replaying the run from the beginning.
 */
public class WorkflowRun {

    private final String workflowId;
    private final String requirement;
    private final WorkflowGraph graph;
    private final WorkflowContext context;
    private final Path workspace;

    /**
     * Not final: recovery has to restore the original start time. A rehydrated
     * run that reports the restart as its beginning would make end-to-end
     * latency measure how long ago the pod came up.
     */
    private volatile Instant startedAt;

    private final Map<String, WorkspaceSnapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicInteger repairRounds = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();
    private final AtomicInteger retryCount = new AtomicInteger();

    private volatile WorkflowState state = WorkflowState.CREATED;
    private volatile String clarification;
    private volatile String approver;
    private volatile String terminalReason;
    private volatile Instant finishedAt;

    /**
     * When this run first hit a validation failure.
     *
     * <p>Recorded once and never overwritten: MTTR measures recovery from the
     * moment things first went wrong, so a second failure must not reset the
     * clock and flatter the number.
     */
    private volatile Instant firstFailureAt;

    public WorkflowRun(String workflowId, String requirement, WorkflowGraph graph, Path workspace) {
        this.workflowId = workflowId;
        this.requirement = requirement;
        this.graph = graph;
        this.context = new WorkflowContext(workflowId);
        this.workspace = workspace;
        this.startedAt = Timestamps.now();
    }

    public String workflowId() {
        return workflowId;
    }

    public String requirement() {
        return requirement;
    }

    public WorkflowGraph graph() {
        return graph;
    }

    public WorkflowContext context() {
        return context;
    }

    public Path workspace() {
        return workspace;
    }

    public WorkflowState state() {
        return state;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    /** Wall-clock duration so far, which is the end-to-end latency metric. */
    public Duration elapsed() {
        return Duration.between(startedAt, finishedAt == null ? Timestamps.now() : finishedAt);
    }

    public void transitionTo(WorkflowState next, String reason) {
        this.state = next;
        this.terminalReason = reason;
        if (next.isTerminal()) {
            this.finishedAt = Timestamps.now();
        }
    }

    public Optional<String> terminalReason() {
        return Optional.ofNullable(terminalReason);
    }

    public Optional<String> clarification() {
        return Optional.ofNullable(clarification);
    }

    public void recordClarification(String text) {
        this.clarification = text;
    }

    public Optional<String> approver() {
        return Optional.ofNullable(approver);
    }

    public void recordApprover(String approver) {
        this.approver = approver;
    }

    public void putSnapshot(String key, WorkspaceSnapshot snapshot) {
        snapshots.put(key, snapshot);
    }

    public Optional<WorkspaceSnapshot> snapshot(String key) {
        return Optional.ofNullable(snapshots.get(key));
    }

    public Optional<Instant> firstFailureAt() {
        return Optional.ofNullable(firstFailureAt);
    }

    /** Records the first failure only; later failures do not reset the MTTR clock. */
    public void recordFailureMoment() {
        if (firstFailureAt == null) {
            firstFailureAt = Timestamps.now();
        }
    }

    /**
     * Restores execution counters and timing after a crash.
     *
     * <p>The repair count is the one that matters most. If it resets, a workflow
     * that keeps dying mid-repair gets a fresh budget on every restart, and a
     * bound that resets is not a bound.
     */
    void restoreExecutionState(int repairRounds, int rollbackCount, int retryCount,
                               Instant startedAt, Instant firstFailureAt, Instant finishedAt) {
        this.repairRounds.set(repairRounds);
        this.rollbackCount.set(rollbackCount);
        this.retryCount.set(retryCount);
        if (startedAt != null) {
            this.startedAt = startedAt;
        }
        this.firstFailureAt = firstFailureAt;
        this.finishedAt = finishedAt;
    }

    public int repairRounds() {
        return repairRounds.get();
    }

    public int nextRepairRound() {
        return repairRounds.incrementAndGet();
    }

    public int rollbackCount() {
        return rollbackCount.get();
    }

    public void recordRollback() {
        rollbackCount.incrementAndGet();
    }

    public int retryCount() {
        return retryCount.get();
    }

    public void recordRetry() {
        retryCount.incrementAndGet();
    }

    /** True when the run finished with an approved, validated outcome. */
    public boolean succeeded() {
        return state == WorkflowState.COMPLETED;
    }
}
