package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelProviderException;
import com.kilari.agentic.provider.ModelRequest;

/**
 * Exercises the repair loop: a first implementation that genuinely does not
 * compile, and a repair that fixes it.
 *
 * <p>The failure is seeded deliberately, and that needs saying plainly. A
 * deterministic provider cannot spontaneously produce a bug, so the only way to
 * demonstrate failure-driven re-planning without depending on a live model
 * having a bad day is to fixture a first attempt that fails. What is <em>not</em>
 * faked is anything downstream: the compiler really rejects the code, the build
 * really fails, the workspace is really rolled back to its verified snapshot,
 * the graph really is re-planned, and the second build really passes.
 *
 * <p>The seeded error is a missing semicolon rather than something exotic —
 * a realistic first-draft mistake, and one javac reports unambiguously, so the
 * failure summary handed to the repair agent is the kind of evidence a real
 * failure would produce.
 */
public class RepairLoopFixture implements ScenarioFixture {

    /** Explicit marker so this never captures an ordinary requirement. */
    public static final String TRIGGER = "seeded compile failure";

    @Override
    public boolean matches(String requirement) {
        return requirement.contains(TRIGGER);
    }

    @Override
    public String respond(ModelRequest request) {
        return switch (request.agentType()) {
            case REQUIREMENT -> REQUIREMENT;
            case REPOSITORY_ANALYSIS -> REPOSITORY_ANALYSIS;
            case ARCHITECTURE -> ARCHITECTURE;
            // The full working service, plus one file that does not compile.
            case IMPLEMENTATION -> GreenfieldFixture.IMPLEMENTATION + BROKEN_FILE;
            case TEST -> GreenfieldFixture.tests();
            case DOCUMENTATION -> DOCUMENTATION;
            case REPAIR -> REPAIRED_FILE;
            default -> throw new ModelProviderException(
                    "no repair-loop fixture for agent " + request.agentType());
        };
    }

    private static final String REQUIREMENT = """
            {
              "clarity": "CLEAR",
              "confidence": 0.90,
              "normalized": "Build the URL shortener service including a click counter component. Used to exercise the repair loop with a seeded compile failure.",
              "openQuestions": [],
              "assumptions": ["The first implementation attempt contains a compile error, seeded to exercise failure-driven re-planning."],
              "acceptanceCriteria": [
                "The service compiles and its tests pass after repair",
                "The failing build is not presented to a human for approval"
              ],
              "impactedAreas": ["greenfield"]
            }
            """;

    private static final String REPOSITORY_ANALYSIS = """
            {
              "summary": "Empty repository; conventions are set by this change.",
              "impactedModules": [],
              "conventions": ["Java 25, Spring Boot 4.1.1, Gradle Kotlin DSL"],
              "integrationPoints": []
            }
            """;

    private static final String ARCHITECTURE = """
            {
              "approach": "Standard layered service plus a ClickCounter component.",
              "decisions": [
                {
                  "decision": "Counter kept separate from UrlMapping",
                  "rationale": "Isolates the counting concern so it can later move to a shared store without touching the mapping model.",
                  "alternativesRejected": "Folding it into UrlMapping - couples per-link state to the counting strategy."
                }
              ],
              "risks": [],
              "plannedFiles": ["src/main/java/com/example/shortener/domain/ClickCounter.java"]
            }
            """;

    /** Missing semicolon after the assignment — javac rejects this outright. */
    private static final String BROKEN_FILE = """
            <<<FILE path=src/main/java/com/example/shortener/domain/ClickCounter.java op=CREATE>>>
            package com.example.shortener.domain;

            import java.util.concurrent.atomic.AtomicLong;

            /** Tracks click totals for a short code. */
            public class ClickCounter {

                private final AtomicLong count = new AtomicLong();

                public long increment() {
                    long updated = count.incrementAndGet()
                    return updated;
                }

                public long current() {
                    return count.get();
                }
            }
            <<<END>>>
            """;

    private static final String REPAIRED_FILE = """
            <<<FILE path=src/main/java/com/example/shortener/domain/ClickCounter.java op=MODIFY>>>
            package com.example.shortener.domain;

            import java.util.concurrent.atomic.AtomicLong;

            /** Tracks click totals for a short code. */
            public class ClickCounter {

                private final AtomicLong count = new AtomicLong();

                public long increment() {
                    long updated = count.incrementAndGet();
                    return updated;
                }

                public long current() {
                    return count.get();
                }
            }
            <<<END>>>
            """;

    private static final String DOCUMENTATION = """
            <<<FILE path=docs/click-counter.md op=CREATE>>>
            # Click counter

            Tracks click totals per short code using an atomic counter, so the
            resolution path stays lock-free.

            Kept separate from `UrlMapping` so the counting strategy can move to a
            shared store later without changing the mapping model.
            <<<END>>>
            """;
}
