package com.kilari.agentic.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Live provider backed by the Anthropic Messages API.
 *
 * <p>Deliberately thin. It performs one model call and reports what came back —
 * it holds no retry policy, no fallback logic and no notion of workflow state,
 * because those are orchestration concerns that belong to the deterministic
 * layer and must behave identically regardless of which provider is active.
 */
public class ClaudeModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeModelProvider.class);

    /** Default model for every agent. Overridable via configuration. */
    public static final String DEFAULT_MODEL = "claude-opus-5";

    private final AnthropicClient client;
    private final String modelId;

    public ClaudeModelProvider(String apiKey, String modelId) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.modelId = modelId == null || modelId.isBlank() ? DEFAULT_MODEL : modelId;
    }

    @Override
    public String name() {
        return "anthropic:" + modelId;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(request.maxTokens())
                .system(request.systemPrompt())
                .addUserMessage(request.userPrompt())
                // Adaptive thinking: the model decides how much reasoning each agent
                // step warrants, rather than us guessing a fixed budget per agent.
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.HIGH)
                        .build())
                .build();

        Instant start = Instant.now();
        try {
            Message response = client.messages().create(params);
            Duration latency = Duration.between(start, Instant.now());

            boolean refused = response.stopReason()
                    .map(reason -> "refusal".equalsIgnoreCase(reason.toString()))
                    .orElse(false);

            String text = response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            if (refused) {
                // Surfaced rather than swallowed: a refusal is a legitimate outcome
                // the orchestrator must be able to safe-stop on, not an exception.
                log.warn("Model declined request for agent {} on workflow {}",
                        request.agentType(), request.workflowId());
            }

            return new ModelResponse(text, name(), modelId, latency,
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    refused);

        } catch (AnthropicServiceException e) {
            Duration latency = Duration.between(start, Instant.now());
            log.error("Model call failed for agent {} after {}ms: {}",
                    request.agentType(), latency.toMillis(), e.getMessage());
            throw new ModelProviderException(
                    "Anthropic call failed for %s: %s".formatted(request.agentType(), e.getMessage()), e);
        }
    }
}
