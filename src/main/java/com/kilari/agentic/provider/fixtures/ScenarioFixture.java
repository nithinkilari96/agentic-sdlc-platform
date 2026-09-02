package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelRequest;

/**
 * A fixed set of agent responses covering one assessment scenario.
 *
 * <p>Fixtures are selected by matching the requirement text, so the same
 * workflow code path drives all three scenarios — the scenario is data, not a
 * branch in the orchestrator.
 */
public interface ScenarioFixture {

    /** Whether this fixture covers the given (lower-cased) requirement text. */
    boolean matches(String lowerCasedRequirement);

    /** The canned response for the requesting agent. */
    String respond(ModelRequest request);
}
