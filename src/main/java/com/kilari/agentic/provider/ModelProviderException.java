package com.kilari.agentic.provider;

/**
 * Raised when a model call fails in a way the provider cannot resolve.
 *
 * <p>Unchecked on purpose: agents should not be writing catch blocks around
 * model calls. A failed call is evidence the orchestrator acts on — it decides
 * whether the retry budget allows another attempt or whether the task is
 * exhausted — so the exception propagates to the executor by design.
 */
public class ModelProviderException extends RuntimeException {

    public ModelProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModelProviderException(String message) {
        super(message);
    }
}
