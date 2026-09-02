package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.tools.BuildValidator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles the workspace and runs its tests.
 *
 * <p>This is the step that turns generated text into evidence. Everything before
 * it is a claim; the exit code is the first fact in the workflow, and it is what
 * a human approver is ultimately shown.
 *
 * <p>A failing build is returned as a {@code VALIDATION_FAILED} outcome rather
 * than thrown. The distinction matters: an exception means the platform
 * malfunctioned, while a failed build means the platform worked correctly and
 * the generated code is wrong — which is the input the repair path needs.
 */
public class BuildValidationAgent implements Agent {

    private final BuildValidator validator;

    public BuildValidationAgent(BuildValidator validator) {
        this.validator = validator;
    }

    @Override
    public AgentType type() {
        return AgentType.BUILD_VALIDATION;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        BuildValidator.ValidationResult result = validator.validate(execution.workspace());

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("exitCode", String.valueOf(result.exitCode()));
        evidence.put("durationMs", String.valueOf(result.elapsed().toMillis()));
        evidence.put("outputTruncated", String.valueOf(result.outputTruncated()));

        if (result.passed()) {
            Map<String, String> artifacts = Map.of(
                    ContextKeys.VALIDATION_RESULT,
                    "PASSED in %dms".formatted(result.elapsed().toMillis()));
            return new AgentOutcome(
                    AgentOutcome.Signal.CONTINUE,
                    "Build and tests passed in %dms".formatted(result.elapsed().toMillis()),
                    artifacts,
                    java.util.List.of(),
                    evidence);
        }

        evidence.put("failureSummary", result.failureSummary());

        return new AgentOutcome(
                AgentOutcome.Signal.VALIDATION_FAILED,
                "Build failed (exit %d)".formatted(result.exitCode()),
                Map.of(
                        ContextKeys.VALIDATION_RESULT, "FAILED",
                        ContextKeys.VALIDATION_FAILURE, result.failureSummary()),
                java.util.List.of(),
                evidence);
    }
}
