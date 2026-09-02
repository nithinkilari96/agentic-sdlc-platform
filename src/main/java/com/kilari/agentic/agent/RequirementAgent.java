package com.kilari.agentic.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.provider.ModelProvider;
import com.kilari.agentic.provider.ModelRequest;
import com.kilari.agentic.provider.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interprets the requirement and decides whether the system knows enough to act.
 *
 * <p>This is the most consequential agent in the platform, and the value is in
 * what it refuses to do. Everything downstream — design, code, tests, a patch
 * applied to a real workspace — inherits whatever interpretation is fixed here.
 * An agent that resolves ambiguity by picking the most likely reading produces
 * work that looks complete and cannot be reviewed, because no human ever agreed
 * to the requirement it actually built.
 *
 * <p>So the confidence threshold is enforced by the platform, not by the model.
 * The model reports how sure it is; deterministic code decides whether that is
 * good enough to proceed.
 */
public class RequirementAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(RequirementAgent.class);

    /**
     * Below this, the workflow parks for a human rather than generating.
     *
     * <p>A policy constant rather than a model judgement: if the model chose its
     * own bar for proceeding, "am I confident enough?" would be answered by the
     * same process that produced the confidence, and the check would be
     * decorative.
     */
    public static final double CONFIDENCE_THRESHOLD = 0.60;

    private static final String SYSTEM_PROMPT = """
            You are a requirements analyst on a software engineering platform.

            Interpret the requirement and decide whether it is specific enough to \
            implement without guessing. Identifying genuine ambiguity is more valuable \
            than appearing decisive: downstream agents will write and apply real code \
            against your interpretation, and a confident guess produces work nobody can \
            review.

            Respond with JSON only, no prose and no code fences:
            {
              "clarity": "CLEAR" | "AMBIGUOUS",
              "confidence": <0.0-1.0>,
              "normalized": "<the requirement restated as a precise engineering problem, or empty if ambiguous>",
              "openQuestions": ["<question a human must answer>"],
              "assumptions": ["<assumption you are making, if any>"],
              "acceptanceCriteria": ["<observable, checkable criterion>"],
              "impactedAreas": ["<module or layer affected>"]
            }

            Report low confidence when the requirement could reasonably mean several \
            different features, when it names an outcome without a measure, or when it \
            depends on context you were not given.
            """;

    private final ModelProvider provider;
    private final ObjectMapper mapper = new ObjectMapper();

    public RequirementAgent(ModelProvider provider) {
        this.provider = provider;
    }

    @Override
    public AgentType type() {
        return AgentType.REQUIREMENT;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        ModelResponse response = provider.complete(ModelRequest.of(
                AgentType.REQUIREMENT,
                execution.workflowId(),
                execution.taskId(),
                execution.effectiveRequirement(),
                SYSTEM_PROMPT,
                buildPrompt(execution)));

        if (response.refused()) {
            return AgentOutcome.safeStop(
                    "The model declined to analyse this requirement; halting rather than proceeding blind.");
        }

        JsonNode parsed = parse(response.text());

        double confidence = parsed.path("confidence").asDouble(0.0);
        String clarity = parsed.path("clarity").asText("AMBIGUOUS");
        List<String> questions = readArray(parsed, "openQuestions");

        boolean ambiguous = "AMBIGUOUS".equalsIgnoreCase(clarity) || confidence < CONFIDENCE_THRESHOLD;

        if (ambiguous) {
            log.info("Requirement parked for clarification on workflow {} (confidence {}, {} questions)",
                    execution.workflowId(), confidence, questions.size());
            return AgentOutcome.needsClarification(
                    "Requirement is not specific enough to implement (confidence %.2f, threshold %.2f). No code generated."
                            .formatted(confidence, CONFIDENCE_THRESHOLD),
                    questions.isEmpty()
                            ? List.of("Please restate the requirement with a specific, checkable outcome.")
                            : questions);
        }

        String normalized = parsed.path("normalized").asText("");
        if (normalized.isBlank()) {
            // Claiming clarity while producing no normalized statement is
            // self-contradictory; treat it as ambiguity rather than trusting the label.
            return AgentOutcome.needsClarification(
                    "The requirement was reported as clear but no normalized statement was produced.",
                    questions.isEmpty() ? List.of("Please restate the requirement.") : questions);
        }

        Map<String, String> artifacts = new LinkedHashMap<>();
        artifacts.put(ContextKeys.REQUIREMENT_NORMALIZED, normalized);
        artifacts.put(ContextKeys.REQUIREMENT_CRITERIA, joinLines(readArray(parsed, "acceptanceCriteria")));
        artifacts.put(ContextKeys.REQUIREMENT_ASSUMPTIONS, joinLines(readArray(parsed, "assumptions")));
        artifacts.put(ContextKeys.REQUIREMENT_CONFIDENCE, String.valueOf(confidence));

        return AgentOutcome.carryOn(
                "Requirement understood with confidence %.2f: %s".formatted(confidence, normalized),
                artifacts);
    }

    private String buildPrompt(AgentExecution execution) {
        return """
                Requirement:
                %s
                """.formatted(execution.effectiveRequirement());
    }

    private JsonNode parse(String text) {
        try {
            return mapper.readTree(extractJson(text));
        } catch (Exception e) {
            throw new AgentOutputException(
                    "requirement agent returned output that is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Pulls the JSON object out of a response that may be wrapped in prose or
     * fences. Being tolerant here is reasonable — the alternative is failing a
     * whole workflow over a stray markdown fence — but the extracted text is
     * still parsed strictly, so malformed JSON is never guessed at.
     */
    static String extractJson(String text) {
        String trimmed = text.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AgentOutputException("no JSON object found in model output");
        }
        return trimmed.substring(start, end + 1);
    }

    private List<String> readArray(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            array.forEach(element -> {
                String value = element.asText("").strip();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            });
        }
        return values;
    }

    private String joinLines(List<String> values) {
        return String.join("\n", values);
    }
}
