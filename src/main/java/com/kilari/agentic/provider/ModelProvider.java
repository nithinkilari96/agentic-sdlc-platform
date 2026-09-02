package com.kilari.agentic.provider;

/**
 * The one seam through which the platform talks to a language model.
 *
 * <p>Everything above this interface — the graph, the gates, the tool layer, the
 * approval flow — is deterministic Java that behaves identically whichever
 * implementation is wired in. That is the point: orchestration correctness can
 * be tested exhaustively without paying for, or depending on, model
 * nondeterminism, and the model can be swapped without touching control flow.
 */
public interface ModelProvider {

    /** Short identifier recorded in the decision lineage, so every generated artifact names its source. */
    String name();

    /** True when responses come from a real model rather than a fixture. */
    boolean isLive();

    ModelResponse complete(ModelRequest request);
}
