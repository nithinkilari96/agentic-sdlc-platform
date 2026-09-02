package com.kilari.agentic.persistence;

import com.kilari.agentic.orchestration.WorkflowRun;

import java.util.List;
import java.util.Optional;

/**
 * Durable storage for workflow state.
 *
 * <p>The engine checkpoints after every batch of tasks, which is what makes a
 * run recoverable rather than merely auditable. Storing only the audit trail
 * would tell you what a crashed run <em>had</em> done while leaving you unable
 * to continue it.
 */
public interface WorkflowStore {

    /** Persists the current state of a run. Called after every state change. */
    void checkpoint(WorkflowRun run);

    Optional<WorkflowRun> load(String workflowId);

    /** Runs that were mid-flight or parked when the process last stopped. */
    List<String> findResumable();

    List<String> findAll();
}
