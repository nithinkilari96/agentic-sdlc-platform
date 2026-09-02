package com.kilari.agentic.agent;

import java.util.List;
import java.util.Map;

/**
 * What an agent produced and what the orchestrator should do next.
 *
 * <p>Agents do not change workflow state themselves. They return a signal, and
 * the executor decides what it means — which is what keeps state transitions in
 * one auditable place instead of scattered across ten agent implementations,
 * each free to invent its own idea of what "done" is.
 */
public record AgentOutcome(
        Signal signal,
        String summary,
        Map<String, String> artifacts,
        List<String> questions,
        Map<String, String> evidence) {

    public enum Signal {
        /** Work completed; the graph may advance. */
        CONTINUE,

        /** The requirement is too ambiguous to act on. Park for a human. */
        NEEDS_CLARIFICATION,

        /** Executable evidence exists and a human must approve before completion. */
        NEEDS_APPROVAL,

        /** Validation failed. Evidence is attached for the repair path. */
        VALIDATION_FAILED,

        /**
         * A guardrail refused the work outright. Distinct from a failure because
         * retrying cannot help — the answer will be the same next time.
         */
        SAFE_STOP
    }

    public AgentOutcome {
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        questions = questions == null ? List.of() : List.copyOf(questions);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public static AgentOutcome carryOn(String summary, Map<String, String> artifacts) {
        return new AgentOutcome(Signal.CONTINUE, summary, artifacts, List.of(), Map.of());
    }

    public static AgentOutcome needsClarification(String summary, List<String> questions) {
        return new AgentOutcome(Signal.NEEDS_CLARIFICATION, summary, Map.of(), questions, Map.of());
    }

    public static AgentOutcome needsApproval(String summary, Map<String, String> evidence) {
        return new AgentOutcome(Signal.NEEDS_APPROVAL, summary, Map.of(), List.of(), evidence);
    }

    public static AgentOutcome validationFailed(String summary, Map<String, String> evidence) {
        return new AgentOutcome(Signal.VALIDATION_FAILED, summary, Map.of(), List.of(), evidence);
    }

    public static AgentOutcome safeStop(String summary) {
        return new AgentOutcome(Signal.SAFE_STOP, summary, Map.of(), List.of(), Map.of());
    }
}
