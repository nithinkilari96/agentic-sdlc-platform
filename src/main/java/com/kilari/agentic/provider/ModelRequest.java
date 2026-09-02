package com.kilari.agentic.provider;

import com.kilari.agentic.orchestration.AgentType;

import java.util.Objects;

/**
 * A single model call.
 *
 * <p>Carries the calling agent and workflow so the provider can attribute cost
 * and latency per agent.
 *
 * <p>{@code requirement} is kept separate from {@code userPrompt} rather than
 * being folded into it. The prompt accumulates context — repository digests,
 * prior artifacts, build output — and anything inspecting the request to decide
 * what it is about would otherwise be reading the codebase's own text as if it
 * were the user's intent. Keeping the requirement addressable makes that
 * distinction explicit for the deterministic provider, and for cost attribution
 * and log correlation in general.
 */
public record ModelRequest(
        AgentType agentType,
        String workflowId,
        String taskId,
        String requirement,
        String systemPrompt,
        String userPrompt,
        long maxTokens) {

    public ModelRequest {
        Objects.requireNonNull(agentType, "agentType");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public static ModelRequest of(AgentType agentType, String workflowId, String taskId,
                                  String requirement, String systemPrompt, String userPrompt) {
        return new ModelRequest(agentType, workflowId, taskId, requirement,
                systemPrompt, userPrompt, 16_000L);
    }
}
