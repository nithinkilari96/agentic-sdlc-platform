package com.kilari.agentic.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The narrow, deliberate hole in the orchestration package's encapsulation.
 *
 * <p>Restoring a run from storage means writing directly into state that nothing
 * else is allowed to touch — a task's attempt count, the context revision, the
 * lineage. Rather than making those setters public and trusting everyone to
 * leave them alone, the capability lives here: one class, named for its only
 * legitimate caller, so a reviewer asking "what else can rewrite execution
 * state?" gets a complete answer by reading a single file.
 */
public final class RecoverySupport {

    private RecoverySupport() {
    }

    /** Restores a persisted node's execution state without replaying its history. */
    public static void restoreNode(TaskNode node, TaskState state, int attempts,
                                   String lastFailureReason, Instant startedAt, Instant finishedAt) {
        node.restore(state, attempts, lastFailureReason, startedAt, finishedAt);
    }

    /** Restores persisted context: revision, artifacts and the audit lineage. */
    public static void restoreContext(WorkflowContext context, int revision,
                                      Map<String, Artifact> artifacts, List<DecisionRecord> lineage) {
        context.restore(revision, artifacts, lineage);
    }
}
