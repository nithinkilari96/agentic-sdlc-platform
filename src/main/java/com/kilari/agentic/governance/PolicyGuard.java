package com.kilari.agentic.governance;

import com.kilari.agentic.orchestration.TaskNode;
import com.kilari.agentic.orchestration.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Entry gate evaluated before any task runs.
 *
 * <p>These are the checks that have to hold regardless of what the graph says or
 * what an agent intends. The graph controls ordering; this controls permission —
 * and the separation matters, because a re-planning pass can change the graph
 * but cannot change what the platform is willing to allow.
 *
 * <p>A violation raises rather than returning false. There is no sensible way for
 * a caller to continue past a refused action, and an ignorable guard is not a
 * guard.
 */
public class PolicyGuard {

    private static final Logger log = LoggerFactory.getLogger(PolicyGuard.class);

    /**
     * Ceiling on total wall-clock time for one run.
     *
     * <p>Independent of per-task retry budgets: a run can stay inside every local
     * limit and still make no useful progress for an hour by cycling through
     * cheap tasks. This is the backstop that bounds the whole thing.
     */
    public static final Duration MAX_WORKFLOW_DURATION = Duration.ofMinutes(30);

    /** Ceiling on graph growth, so re-planning cannot expand a run without limit. */
    public static final int MAX_TASKS_PER_WORKFLOW = 40;

    public void beforeAgentRun(WorkflowRun run, TaskNode node) {
        if (run.elapsed().compareTo(MAX_WORKFLOW_DURATION) > 0) {
            throw new PolicyViolationException(
                    "workflow %s exceeded the %d minute ceiling; stopping before %s"
                            .formatted(run.workflowId(), MAX_WORKFLOW_DURATION.toMinutes(), node.id()));
        }

        int taskCount = run.graph().nodes().size();
        if (taskCount > MAX_TASKS_PER_WORKFLOW) {
            throw new PolicyViolationException(
                    "workflow %s has grown to %d tasks, exceeding the limit of %d; re-planning is not converging"
                            .formatted(run.workflowId(), taskCount, MAX_TASKS_PER_WORKFLOW));
        }

        if (run.workspace() == null) {
            throw new PolicyViolationException(
                    "workflow %s has no workspace; refusing to run %s".formatted(run.workflowId(), node.id()));
        }

        log.debug("Entry gate passed for {} on workflow {}", node.id(), run.workflowId());
    }

    public static class PolicyViolationException extends RuntimeException {
        public PolicyViolationException(String message) {
            super(message);
        }
    }
}
