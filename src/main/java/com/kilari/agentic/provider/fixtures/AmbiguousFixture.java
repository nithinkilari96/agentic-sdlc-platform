package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelProviderException;
import com.kilari.agentic.provider.ModelRequest;

import java.util.Locale;

/**
 * Ambiguous scenario: "improve analytics".
 *
 * <p>The requirement is under-specified, and the correct behaviour is to stop.
 * A weaker system would infer a plausible meaning and generate confidently
 * against a guess — producing work that looks finished and is unreviewable,
 * because nobody can say whether it did the right thing.
 *
 * <p>The same fixture serves the run before and after clarification, which is
 * what makes the re-planning demonstrable: the identical requirement text yields
 * a different plan once the human's answer is part of the context.
 */
public class AmbiguousFixture implements ScenarioFixture {

    /** Marker the orchestrator appends to the prompt once a human has answered. */
    public static final String CLARIFICATION_MARKER = "clarification:";

    @Override
    public boolean matches(String requirement) {
        return requirement.contains("analytics");
    }

    @Override
    public String respond(ModelRequest request) {
        boolean clarified = request.requirement()
                .toLowerCase(Locale.ROOT)
                .contains(CLARIFICATION_MARKER);

        return switch (request.agentType()) {
            case REQUIREMENT -> clarified ? REQUIREMENT_CLARIFIED : REQUIREMENT_AMBIGUOUS;
            case REPOSITORY_ANALYSIS -> requireClarified(clarified, REPOSITORY_ANALYSIS);
            case ARCHITECTURE -> requireClarified(clarified, ARCHITECTURE);
            case IMPLEMENTATION -> requireClarified(clarified, IMPLEMENTATION);
            case TEST -> requireClarified(clarified, TESTS);
            case DOCUMENTATION -> requireClarified(clarified, DOCUMENTATION);
            default -> throw new ModelProviderException(
                    "no ambiguous-scenario fixture for agent " + request.agentType());
        };
    }

    /**
     * Guards the fixture against being asked to generate before clarification.
     *
     * <p>If this ever throws, the orchestrator let a downstream agent run while
     * the workflow was supposed to be parked — which is the exact failure the
     * scenario exists to prove cannot happen.
     */
    private String requireClarified(boolean clarified, String response) {
        if (!clarified) {
            throw new ModelProviderException(
                    "generation was attempted on an unclarified ambiguous requirement — "
                            + "the workflow should have parked at AWAITING_CLARIFICATION");
        }
        return response;
    }

    private static final String REQUIREMENT_AMBIGUOUS = """
            {
              "clarity": "AMBIGUOUS",
              "confidence": 0.31,
              "normalized": "",
              "openQuestions": [
                "Which dimension should analytics break down by — time, geography, referrer, or device?",
                "Is this about collecting data the service does not yet capture, or presenting data it already has?",
                "Should historical clicks be backfilled, or does the new breakdown start from deployment?",
                "Is per-link granularity sufficient, or is an account-level roll-up needed?"
              ],
              "assumptions": [],
              "acceptanceCriteria": [],
              "impactedAreas": [],
              "reasoning": "The service currently records a single click counter per mapping. 'Improve' could mean at least four different features with different data models and different API surfaces, and choosing wrong means writing the wrong schema — expensive to reverse once links are live. The cost of asking is one round trip; the cost of guessing is a migration."
            }
            """;

    private static final String REQUIREMENT_CLARIFIED = """
            {
              "clarity": "CLEAR",
              "confidence": 0.91,
              "normalized": "Record a per-country breakdown of clicks for each short link, and expose it through a new analytics endpoint alongside the existing total.",
              "openQuestions": [],
              "assumptions": [
                "Country is supplied by the edge/CDN as a request header; the service does not perform IP geolocation itself.",
                "Clicks recorded before this change have no country and are reported under 'UNKNOWN' rather than being discarded."
              ],
              "acceptanceCriteria": [
                "Resolving a link records a click against the caller's country",
                "A new endpoint returns per-country counts for a link",
                "A click with no country header is counted as UNKNOWN rather than dropped",
                "The existing total click count and redirect behaviour are unchanged"
              ],
              "impactedAreas": ["api layer", "new analytics component"]
            }
            """;

    private static final String REPOSITORY_ANALYSIS = """
            {
              "summary": "Click counting today is a single AtomicLong on UrlMapping, incremented in ShortenerService.resolve. Country is not available at that layer — the service takes a short code and knows nothing about the HTTP request. The controller does have the request, which makes it the correct place to observe geography.",
              "impactedModules": [
                "api/ShortenerController.java - has access to request headers, so it records the geographic dimension",
                "analytics/ - new package for the per-country store, keeping the counting concern out of the domain model"
              ],
              "conventions": [
                "Concurrent maps for shared mutable state, matching InMemoryUrlRepository",
                "Records for response DTOs, declared as nested types on the controller",
                "Plain JUnit tests against constructed objects"
              ],
              "integrationPoints": [
                "UrlMapping.clicks() remains the authoritative total; the per-country store is additive so existing behaviour is untouched"
              ]
            }
            """;

    private static final String ARCHITECTURE = """
            {
              "approach": "Add a GeoClickAnalytics component holding per-code, per-country counters, and record into it from the controller's resolve path where request headers are visible. The existing total on UrlMapping is left alone, so the change is purely additive and cannot regress current behaviour.",
              "decisions": [
                {
                  "decision": "Record geography in the api layer rather than threading a country parameter into ShortenerService",
                  "rationale": "The service's job is code resolution and it has no business knowing about HTTP. Passing a country into it would leak a transport concern through the domain boundary for no gain.",
                  "alternativesRejected": "Adding a country argument to resolve() - changes an existing signature and couples the service to request metadata."
                },
                {
                  "decision": "Trust the edge-provided country header rather than geolocating IPs in-process",
                  "rationale": "IP geolocation needs a database that must be kept current, and every CDN already supplies this header. The tradeoff is that the header is only trustworthy when a proxy sets it, which is documented.",
                  "alternativesRejected": "Bundled GeoIP database - adds a dependency and a data-freshness obligation to a prototype."
                },
                {
                  "decision": "Missing country counted as UNKNOWN rather than dropped",
                  "rationale": "Silently discarding clicks would make the per-country totals disagree with the headline count, and a reviewer comparing them would have no way to tell whether that gap is a bug.",
                  "alternativesRejected": "Discarding - produces numbers that do not reconcile."
                }
              ],
              "risks": [
                {"risk": "Unbounded growth as distinct codes accumulate counters", "mitigation": "Counters are per existing link; the map is bounded by link count, which the rate limiter already constrains."},
                {"risk": "Header is client-controlled without a trusted proxy", "mitigation": "Documented as a deployment requirement; analytics is advisory data, not an access-control input."}
              ],
              "plannedFiles": [
                "src/main/java/com/example/shortener/analytics/GeoClickAnalytics.java",
                "src/main/java/com/example/shortener/api/ShortenerController.java"
              ]
            }
            """;

    static final String IMPLEMENTATION = """
            <<<FILE path=src/main/java/com/example/shortener/analytics/GeoClickAnalytics.java op=CREATE>>>
            package com.example.shortener.analytics;

            import org.springframework.stereotype.Component;

            import java.util.Comparator;
            import java.util.LinkedHashMap;
            import java.util.Locale;
            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;
            import java.util.concurrent.atomic.LongAdder;

            /**
             * Per-country click counts for each short link.
             *
             * <p>Additive to the existing total on UrlMapping rather than replacing it,
             * so this cannot regress the headline count that callers already depend on.
             *
             * <p>LongAdder rather than AtomicLong: clicks on a popular link arrive
             * concurrently, and LongAdder trades a slightly more expensive read for
             * much cheaper contended writes — the right way round for a counter written
             * on every redirect and read only when someone asks for a report.
             */
            @Component
            public class GeoClickAnalytics {

                /** Used when the edge supplied no country, so totals still reconcile. */
                public static final String UNKNOWN_COUNTRY = "UNKNOWN";

                private final Map<String, Map<String, LongAdder>> clicksByCode = new ConcurrentHashMap<>();

                public void recordClick(String shortCode, String countryCode) {
                    String country = normalise(countryCode);
                    clicksByCode
                            .computeIfAbsent(shortCode, code -> new ConcurrentHashMap<>())
                            .computeIfAbsent(country, c -> new LongAdder())
                            .increment();
                }

                /**
                 * Counts for one link, ordered most-clicked first so the response is
                 * useful without the caller having to sort it.
                 */
                public Map<String, Long> countsFor(String shortCode) {
                    Map<String, LongAdder> counts = clicksByCode.get(shortCode);
                    if (counts == null) {
                        return Map.of();
                    }
                    return counts.entrySet().stream()
                            .sorted(Map.Entry.<String, LongAdder>comparingByValue(
                                            Comparator.comparingLong(LongAdder::sum))
                                    .reversed())
                            .collect(LinkedHashMap::new,
                                    (map, entry) -> map.put(entry.getKey(), entry.getValue().sum()),
                                    LinkedHashMap::putAll);
                }

                public long totalFor(String shortCode) {
                    Map<String, LongAdder> counts = clicksByCode.get(shortCode);
                    if (counts == null) {
                        return 0L;
                    }
                    return counts.values().stream().mapToLong(LongAdder::sum).sum();
                }

                private String normalise(String countryCode) {
                    if (countryCode == null || countryCode.isBlank()) {
                        return UNKNOWN_COUNTRY;
                    }
                    String trimmed = countryCode.trim().toUpperCase(Locale.ROOT);
                    // Anything that is not a two-letter code is treated as absent rather
                    // than stored, so a malformed header cannot pollute the dimension
                    // with arbitrary client-supplied strings.
                    return trimmed.matches("[A-Z]{2}") ? trimmed : UNKNOWN_COUNTRY;
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/api/ShortenerController.java op=MODIFY>>>
            package com.example.shortener.api;

            import com.example.shortener.analytics.GeoClickAnalytics;
            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.service.ShortenerService;
            import com.example.shortener.service.UrlValidator;
            import jakarta.servlet.http.HttpServletRequest;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.ExceptionHandler;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;

            import java.net.URI;
            import java.time.Duration;
            import java.time.Instant;
            import java.util.Map;

            @RestController
            public class ShortenerController {

                /** Country supplied by the CDN/edge. Meaningful only behind a trusted proxy. */
                private static final String COUNTRY_HEADER = "X-Client-Country";

                private final ShortenerService service;
                private final GeoClickAnalytics geoAnalytics;

                public ShortenerController(ShortenerService service, GeoClickAnalytics geoAnalytics) {
                    this.service = service;
                    this.geoAnalytics = geoAnalytics;
                }

                @PostMapping("/api/v1/urls")
                public ResponseEntity<ShortenResponse> shorten(@RequestBody ShortenRequest request) {
                    Duration ttl = request.ttlSeconds() == null
                            ? null
                            : Duration.ofSeconds(request.ttlSeconds());
                    UrlMapping mapping = service.shorten(request.url(), ttl);
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(new ShortenResponse(
                                    mapping.shortCode(),
                                    mapping.longUrl(),
                                    mapping.createdAt(),
                                    mapping.expiresAt().orElse(null)));
                }

                @GetMapping("/{shortCode}")
                public ResponseEntity<Void> resolve(@PathVariable String shortCode,
                                                    HttpServletRequest httpRequest) {
                    return service.resolve(shortCode)
                            .map(mapping -> {
                                // Recorded here rather than in the service: geography is a
                                // transport detail the domain layer has no reason to know.
                                geoAnalytics.recordClick(shortCode,
                                        httpRequest.getHeader(COUNTRY_HEADER));
                                return ResponseEntity.status(HttpStatus.FOUND)
                                        .location(URI.create(mapping.longUrl()))
                                        .<Void>build();
                            })
                            .orElseGet(() -> ResponseEntity.notFound().build());
                }

                @GetMapping("/api/v1/urls/{shortCode}/stats")
                public ResponseEntity<StatsResponse> stats(@PathVariable String shortCode) {
                    return service.stats(shortCode)
                            .map(mapping -> ResponseEntity.ok(new StatsResponse(
                                    mapping.shortCode(),
                                    mapping.longUrl(),
                                    mapping.clicks(),
                                    mapping.createdAt())))
                            .orElseGet(() -> ResponseEntity.notFound().build());
                }

                @GetMapping("/api/v1/urls/{shortCode}/analytics/countries")
                public ResponseEntity<CountryAnalyticsResponse> countryAnalytics(
                        @PathVariable String shortCode) {
                    if (service.stats(shortCode).isEmpty()) {
                        return ResponseEntity.notFound().build();
                    }
                    return ResponseEntity.ok(new CountryAnalyticsResponse(
                            shortCode,
                            geoAnalytics.totalFor(shortCode),
                            geoAnalytics.countsFor(shortCode)));
                }

                @ExceptionHandler(UrlValidator.InvalidUrlException.class)
                public ResponseEntity<Map<String, String>> handleInvalidUrl(
                        UrlValidator.InvalidUrlException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }

                @ExceptionHandler(ShortenerService.MappingExpiredException.class)
                public ResponseEntity<Map<String, String>> handleExpired(
                        ShortenerService.MappingExpiredException e) {
                    return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", e.getMessage()));
                }

                public record ShortenRequest(String url, Long ttlSeconds) {
                }

                public record ShortenResponse(String shortCode, String longUrl,
                                              Instant createdAt, Instant expiresAt) {
                }

                public record StatsResponse(String shortCode, String longUrl,
                                            long clicks, Instant createdAt) {
                }

                public record CountryAnalyticsResponse(String shortCode, long totalClicks,
                                                       Map<String, Long> clicksByCountry) {
                }
            }
            <<<END>>>
            """;

    static final String TESTS = """
            <<<FILE path=src/test/java/com/example/shortener/analytics/GeoClickAnalyticsTest.java op=CREATE>>>
            package com.example.shortener.analytics;

            import org.junit.jupiter.api.Test;

            import java.util.List;
            import java.util.Map;

            import static org.assertj.core.api.Assertions.assertThat;

            class GeoClickAnalyticsTest {

                @Test
                void counts_clicks_per_country() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();
                    analytics.recordClick("abc1234", "US");
                    analytics.recordClick("abc1234", "US");
                    analytics.recordClick("abc1234", "IN");

                    assertThat(analytics.countsFor("abc1234"))
                            .containsEntry("US", 2L)
                            .containsEntry("IN", 1L);
                }

                @Test
                void a_missing_country_is_counted_as_unknown_rather_than_dropped() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();
                    analytics.recordClick("abc1234", null);
                    analytics.recordClick("abc1234", "  ");

                    assertThat(analytics.countsFor("abc1234"))
                            .containsEntry(GeoClickAnalytics.UNKNOWN_COUNTRY, 2L);
                    assertThat(analytics.totalFor("abc1234")).isEqualTo(2L);
                }

                @Test
                void a_malformed_country_header_cannot_pollute_the_dimension() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();
                    analytics.recordClick("abc1234", "not-a-country-code");
                    analytics.recordClick("abc1234", "USA");

                    assertThat(analytics.countsFor("abc1234"))
                            .containsOnlyKeys(GeoClickAnalytics.UNKNOWN_COUNTRY);
                }

                @Test
                void country_codes_are_normalised_to_upper_case() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();
                    analytics.recordClick("abc1234", "gb");
                    analytics.recordClick("abc1234", "GB");

                    assertThat(analytics.countsFor("abc1234")).containsEntry("GB", 2L);
                }

                @Test
                void results_are_ordered_most_clicked_first() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();
                    analytics.recordClick("abc1234", "FR");
                    for (int i = 0; i < 3; i++) {
                        analytics.recordClick("abc1234", "DE");
                    }
                    analytics.recordClick("abc1234", "IT");
                    analytics.recordClick("abc1234", "IT");

                    assertThat(List.copyOf(analytics.countsFor("abc1234").keySet()))
                            .containsExactly("DE", "IT", "FR");
                }

                @Test
                void an_unseen_code_reports_no_clicks_rather_than_failing() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();

                    assertThat(analytics.countsFor("never-seen")).isEqualTo(Map.of());
                    assertThat(analytics.totalFor("never-seen")).isZero();
                }

                @Test
                void per_country_counts_reconcile_with_the_total() {
                    GeoClickAnalytics analytics = new GeoClickAnalytics();
                    analytics.recordClick("abc1234", "US");
                    analytics.recordClick("abc1234", "IN");
                    analytics.recordClick("abc1234", null);

                    long summed = analytics.countsFor("abc1234").values().stream()
                            .mapToLong(Long::longValue).sum();

                    assertThat(summed).isEqualTo(analytics.totalFor("abc1234")).isEqualTo(3L);
                }
            }
            <<<END>>>
            """;

    private static final String DOCUMENTATION = """
            <<<FILE path=docs/geo-analytics.md op=CREATE>>>
            # Per-country click analytics

            Each redirect is counted against the caller's country, in addition to the
            existing total click count.

            ## API

            `GET /api/v1/urls/{shortCode}/analytics/countries`

            ```json
            {
              "shortCode": "aB3xY9z",
              "totalClicks": 143,
              "clicksByCountry": { "US": 82, "IN": 41, "GB": 15, "UNKNOWN": 5 }
            }
            ```

            Counts are ordered most-clicked first. `404` if the link does not exist or
            has expired.

            ## Where country comes from

            The `X-Client-Country` request header, which CDNs and edge proxies populate.
            The service does not geolocate IP addresses itself — that would require a
            database with an ongoing freshness obligation, for data the edge already has.

            The header is only trustworthy behind a proxy that overwrites it. Treat these
            counts as advisory reporting, never as an input to an access-control
            decision.

            ## Reconciliation

            A click with no country header, or a header that is not a two-letter code, is
            counted as `UNKNOWN` rather than discarded. This keeps the per-country counts
            summing to the total — if they did not reconcile, a reader would have no way
            to tell an intentional gap from a lost-write bug.

            Clicks recorded before this feature shipped have no country data and are not
            backfilled, so `totalClicks` here may lag the link's lifetime total in
            `/stats` for links created earlier.
            <<<END>>>
            """;
}
