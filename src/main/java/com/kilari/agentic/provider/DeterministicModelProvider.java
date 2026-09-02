package com.kilari.agentic.provider;

import com.kilari.agentic.provider.fixtures.AmbiguousFixture;
import com.kilari.agentic.provider.fixtures.BrownfieldFixture;
import com.kilari.agentic.provider.fixtures.GreenfieldFixture;
import com.kilari.agentic.provider.fixtures.ScenarioFixture;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * A bounded, repeatable test double for the model — <em>not</em> evidence of
 * model reasoning.
 *
 * <p>This exists so two different things can be verified independently. The
 * orchestration layer has a great deal of behaviour worth testing exhaustively:
 * dependency ordering, gate evaluation, retry budgets, rollback verification,
 * approval routing, crash recovery. None of that should be validated against a
 * nondeterministic model, because a failing test would leave you unable to tell
 * whether the orchestrator broke or the model simply answered differently.
 *
 * <p>So the fixtures below return fixed, well-formed agent outputs, which makes
 * the whole platform runnable end-to-end in CI with no credentials. Real
 * open-ended generation is the {@link ClaudeModelProvider}, reached through the
 * identical interface. Anything the deterministic provider can do, the live
 * provider can do — the orchestrator cannot tell them apart.
 */
public class DeterministicModelProvider implements ModelProvider {

    /**
     * Order is significant: specific fixtures are tried before general ones.
     *
     * <p>The ambiguous fixture matches on "analytics", which also appears in the
     * greenfield requirement ("...click analytics and reliability features").
     * Matching it first would classify a perfectly specific requirement as too
     * vague to act on. The two concrete scenarios are therefore checked first,
     * and the ambiguous fixture is the fallback — which mirrors how the real
     * system behaves, since ambiguity is what remains when nothing specific fits.
     */
    private final List<ScenarioFixture> fixtures = List.of(
            new GreenfieldFixture(),
            new BrownfieldFixture(),
            new AmbiguousFixture());

    @Override
    public String name() {
        return "deterministic-fixture";
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        // Matched against the requirement alone. The full prompt accumulates the
        // repository digest, and the seeded shortener's own files mention "url
        // shortener" - matching on that would classify a brownfield change as a
        // greenfield build.
        String requirement = request.requirement().toLowerCase(Locale.ROOT);

        ScenarioFixture fixture = fixtures.stream()
                .filter(f -> f.matches(requirement))
                .findFirst()
                .orElseThrow(() -> new ModelProviderException(
                        """
                        No deterministic fixture matches this requirement.

                        The deterministic provider only covers the three assessment scenarios. \
                        For an open-ended requirement, run with a real provider by setting \
                        ANTHROPIC_API_KEY. Requirement was: %s"""
                                .formatted(truncate(request.userPrompt()))));

        String response = fixture.respond(request);

        // A fixture returns instantly; reporting a realistic latency keeps the
        // metrics layer exercised rather than always measuring zero.
        return new ModelResponse(response, name(), "fixture", Duration.ofMillis(120),
                estimateTokens(request.systemPrompt()) + estimateTokens(request.userPrompt()),
                estimateTokens(response),
                false);
    }

    /** Rough token estimate for fixture accounting; the live provider reports real usage. */
    private static long estimateTokens(String text) {
        return text == null ? 0 : Math.round(text.length() / 3.7);
    }

    private static String truncate(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160) + "…";
    }
}
