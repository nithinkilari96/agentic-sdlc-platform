package com.kilari.agentic.agent;

/**
 * Raised when a model produced output the platform cannot safely act on.
 *
 * <p>Treated as a normal task failure rather than a special case: it consumes an
 * attempt from the retry budget, and if the budget runs out the workflow fails
 * with the malformed output recorded as evidence. Guessing at unparseable output
 * is exactly how an agent ends up applying something nobody intended.
 */
public class AgentOutputException extends RuntimeException {

    public AgentOutputException(String message) {
        super(message);
    }

    public AgentOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
