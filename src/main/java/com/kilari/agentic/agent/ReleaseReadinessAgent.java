package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assembles the reviewable outcome and hands it to a human.
 *
 * <p>The approval gate sits here, after validation, rather than in front of every
 * agent step. Asking a human to approve each intermediate action produces
 * rubber-stamping — the reviewer has no basis to judge a design document in
 * isolation, so they approve it, and the approval means nothing. Asking once,
 * when there is a passing build and a complete change to look at, is a decision
 * a person can actually make.
 *
 * <p>That is the controlled-autonomy boundary in one sentence: agents run the
 * work, the platform proves it, and a human owns the outcome.
 */
public class ReleaseReadinessAgent implements Agent {

    @Override
    public AgentType type() {
        return AgentType.RELEASE_READINESS;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        Map<String, String> evidence = new LinkedHashMap<>();

        execution.context().content(ContextKeys.REQUIREMENT_NORMALIZED)
                .ifPresent(v -> evidence.put("requirement", v));
        execution.context().content(ContextKeys.REQUIREMENT_CRITERIA)
                .ifPresent(v -> evidence.put("acceptanceCriteria", v));
        execution.context().content(ContextKeys.REQUIREMENT_ASSUMPTIONS)
                .ifPresent(v -> evidence.put("assumptions", v));
        execution.context().content(ContextKeys.ARCHITECTURE_DESIGN)
                .ifPresent(v -> evidence.put("design", v));
        execution.context().content(ContextKeys.PATCH_APPLIED_SUMMARY)
                .ifPresent(v -> evidence.put("filesChanged", v));
        execution.context().content(ContextKeys.VALIDATION_RESULT)
                .ifPresent(v -> evidence.put("validation", v));

        evidence.put("contextRevision", String.valueOf(execution.context().revision()));
        evidence.put("decisionsRecorded", String.valueOf(execution.context().lineage().size()));

        String summary = """
                Change is ready for review.

                Requirement: %s
                Files changed: %s
                Validation: %s
                Decisions recorded: %d (context revision %d)"""
                .formatted(
                        execution.context().content(ContextKeys.REQUIREMENT_NORMALIZED)
                                .orElse("(not recorded)"),
                        execution.context().content(ContextKeys.PATCH_APPLIED_SUMMARY)
                                .orElse("(none)"),
                        execution.context().content(ContextKeys.VALIDATION_RESULT)
                                .orElse("(not validated)"),
                        execution.context().lineage().size(),
                        execution.context().revision());

        return AgentOutcome.needsApproval(summary, evidence);
    }
}
