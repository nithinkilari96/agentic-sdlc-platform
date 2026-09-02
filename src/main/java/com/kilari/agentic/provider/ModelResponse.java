package com.kilari.agentic.provider;

import java.time.Duration;
import java.util.Objects;

/**
 * The result of one model call, including the accounting the metrics layer needs.
 */
public record ModelResponse(
        String text,
        String providerName,
        String modelId,
        Duration latency,
        long inputTokens,
        long outputTokens,
        boolean refused) {

    public ModelResponse {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(providerName, "providerName");
        Objects.requireNonNull(latency, "latency");
    }

    public static ModelResponse of(String text, String providerName, String modelId, Duration latency) {
        return new ModelResponse(text, providerName, modelId, latency, 0L, 0L, false);
    }
}
