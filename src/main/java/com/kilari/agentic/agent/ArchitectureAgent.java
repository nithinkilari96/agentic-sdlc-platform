package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.provider.ModelProvider;
import com.kilari.agentic.provider.ModelRequest;
import com.kilari.agentic.provider.ModelResponse;

import java.util.Map;

/**
 * Chooses the approach the implementation must follow.
 *
 * <p>Separating design from implementation is what makes the resulting change
 * reviewable. The decisions and rejected alternatives recorded here become part
 * of the reviewable outcome, so an approver sees the reasoning rather than only
 * the diff — and can disagree with the approach before arguing about the code.
 */
public class ArchitectureAgent implements Agent {

    private final ModelProvider provider;

    public ArchitectureAgent(ModelProvider provider) {
        this.provider = provider;
    }

    @Override
    public AgentType type() {
        return AgentType.ARCHITECTURE;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        String prompt = """
                Requirement:
                %s

                Normalized requirement:
                %s

                Acceptance criteria:
                %s

                Repository analysis:
                %s
                """.formatted(
                execution.effectiveRequirement(),
                execution.context().content(ContextKeys.REQUIREMENT_NORMALIZED).orElse("(not available)"),
                execution.context().content(ContextKeys.REQUIREMENT_CRITERIA).orElse("(none stated)"),
                execution.context().content(ContextKeys.REPOSITORY_ANALYSIS).orElse("(greenfield)"));

        ModelResponse response = provider.complete(ModelRequest.of(
                AgentType.ARCHITECTURE,
                execution.workflowId(),
                execution.taskId(),
                execution.effectiveRequirement(),
                PatchGeneratingAgent.ARCHITECTURE_PROMPT,
                prompt));

        if (response.refused()) {
            return AgentOutcome.safeStop("The model declined to produce a design.");
        }

        String json = RequirementAgent.extractJson(response.text());

        return AgentOutcome.carryOn(
                "Design produced with explicit decisions and rejected alternatives",
                Map.of(ContextKeys.ARCHITECTURE_DESIGN, json));
    }
}
