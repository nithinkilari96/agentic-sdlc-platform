package com.kilari.agentic.orchestration;

/**
 * Lifecycle of a single node in the workflow graph.
 *
 * <p>Deliberately explicit rather than a boolean "done" flag: the reliability
 * metrics (retry frequency, rollback frequency, MTTR) are derived by replaying
 * transitions between these states, so every meaningful state a task can occupy
 * has to be nameable and persistable.
 */
public enum TaskState {

    /** Declared in the graph, dependencies not yet satisfied. */
    PENDING,

    /** All dependencies succeeded and the entry gate allowed execution. */
    READY,

    /** Currently executing on a worker. */
    RUNNING,

    /** Produced output that passed its exit gate. */
    SUCCEEDED,

    /** Execution or exit gate failed; may be retried while attempts remain. */
    FAILED,

    /** Retries exhausted, or a non-retryable failure. Terminal. */
    EXHAUSTED,

    /** An upstream dependency failed terminally, so this task can never run. */
    BLOCKED,

    /** Removed from the effective plan by a re-planning pass. Terminal. */
    SUPERSEDED,

    /** Held pending a human decision (approval or clarification). */
    AWAITING_HUMAN;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == EXHAUSTED || this == BLOCKED || this == SUPERSEDED;
    }

    public boolean isSuccessful() {
        return this == SUCCEEDED;
    }

    /** A task that can never contribute output, whether it failed or was planned away. */
    public boolean isTerminalFailure() {
        return this == EXHAUSTED || this == BLOCKED;
    }
}
