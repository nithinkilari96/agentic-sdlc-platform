package com.kilari.agentic.orchestration;

import java.util.Set;

/**
 * Lifecycle of a whole workflow run.
 *
 * <p>The two "awaiting" states are the controlled-autonomy boundary: the engine
 * parks the run and performs no side effects until a human acts. They are
 * distinct because they are resumed by different operations and by different
 * roles — clarification comes from the requester, approval from an approver.
 */
public enum WorkflowState {

    /** Accepted, graph not yet planned. */
    CREATED,

    /** The requirement agent is deciding what the graph should look like. */
    PLANNING,

    /** Tasks are executing. */
    RUNNING,

    /** Parked: the requirement was too ambiguous to plan against. No code is generated here. */
    AWAITING_CLARIFICATION,

    /** Parked: executable evidence exists and a human must approve the outcome. */
    AWAITING_APPROVAL,

    /** Validation failed; the repair loop is generating a revised change. */
    REPAIRING,

    /** Finished with an approved, reviewable outcome. Terminal. */
    COMPLETED,

    /** Finished unsuccessfully after exhausting the recovery paths. Terminal. */
    FAILED,

    /**
     * Halted deliberately by a policy guard or safety control rather than by an
     * error. Distinct from FAILED because the system stopped itself on purpose,
     * and the distinction matters when reporting reliability. Terminal.
     */
    SAFE_STOPPED,

    /** A human cancelled the run. Terminal. */
    CANCELLED;

    private static final Set<WorkflowState> TERMINAL =
            Set.of(COMPLETED, FAILED, SAFE_STOPPED, CANCELLED);

    private static final Set<WorkflowState> AWAITING_HUMAN =
            Set.of(AWAITING_CLARIFICATION, AWAITING_APPROVAL);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** True when the engine is parked and will not act until a human does. */
    public boolean isAwaitingHuman() {
        return AWAITING_HUMAN.contains(this);
    }

    /**
     * True when a crashed run in this state can be picked up and continued by a
     * recovery pass. Parked runs are recoverable too — they simply stay parked
     * until the human acts.
     */
    public boolean isResumable() {
        return !isTerminal();
    }
}
