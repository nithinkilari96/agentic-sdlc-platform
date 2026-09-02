package com.kilari.agentic.provider;

import com.kilari.agentic.orchestration.AgentType;

import java.util.Objects;

/**
 * A single model call.
 *
 * <p>Carries the calling agent and workflow so the provider can attribute cost
 * and latency per agent, and so the deterministic provider can select the right
 * fixture without the caller knowing which implementation it is talking to.
 */
public record ModelRequest(
        AgentType agentType,
        String workflowId,
        String taskId,
        String systemPrompt,
        String userPrompt,
        long maxTokens) {

    public ModelRequest {
        Objects.requireNonNull(agentType, "agentType");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
    }

    public static ModelRequest of(AgentType agentType, String workflowId, String taskId,
                                  String systemPrompt, String userPrompt) {
        return new ModelRequest(agentType, workflowId, taskId, systemPrompt, userPrompt, 16_000L);
    }
}
