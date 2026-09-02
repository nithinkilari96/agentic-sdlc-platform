package com.kilari.agentic.orchestration;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One entry in the decision lineage: an append-only record of why the workflow
 * did what it did.
 *
 * <p>This is the audit substrate. The requirement is not merely "log things" but
 * traceability — for any outcome, a reviewer should be able to reconstruct which
 * actor made which call, against which context revision, and on what evidence.
 * So every record names its actor and revision rather than relying on log order.
 */
public record DecisionRecord(
        String workflowId,
        String taskId,
        Actor actor,
        DecisionType type,
        int contextRevision,
        String summary,
        Map<String, String> evidence,
        Instant recordedAt) {

    public enum Actor {
        /** A generative agent backed by the model provider. */
        AGENT,
        /** The deterministic orchestration layer. */
        ORCHESTRATOR,
        /** A policy guard that permitted or denied an action. */
        POLICY,
        /** A person, via an approval or clarification endpoint. */
        HUMAN
    }

    public enum DecisionType {
        PLAN_CREATED,
        PLAN_REVISED,
        GATE_EVALUATED,
        TASK_STARTED,
        TASK_SUCCEEDED,
        TASK_FAILED,
        RETRY_SCHEDULED,
        VALIDATION_RESULT,
        PATCH_APPLIED,
        ROLLBACK_PERFORMED,
        POLICY_DENIED,
        SAFE_STOP,
        CLARIFICATION_REQUESTED,
        CLARIFICATION_RECEIVED,
        APPROVAL_REQUESTED,
        APPROVAL_GRANTED,
        APPROVAL_REJECTED,
        RECOVERY_RESUMED
    }

    public DecisionRecord {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(summary, "summary");
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    public static DecisionRecord of(String workflowId, String taskId, Actor actor,
                                    DecisionType type, int contextRevision, String summary) {
        return new DecisionRecord(workflowId, taskId, actor, type, contextRevision, summary, Map.of(), Timestamps.now());
    }

    public static DecisionRecord of(String workflowId, String taskId, Actor actor, DecisionType type,
                                    int contextRevision, String summary, Map<String, String> evidence) {
        return new DecisionRecord(workflowId, taskId, actor, type, contextRevision, summary, evidence, Timestamps.now());
    }
}
