package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelProviderException;
import com.kilari.agentic.provider.ModelRequest;

/**
 * Brownfield scenario: add per-client rate limiting to the existing shortener.
 *
 * <p>The interesting part is that this scenario modifies code it did not write.
 * The repository-analysis response reflects real structure — the same structure
 * {@link GreenfieldFixture} produces, since that output is what seeds this
 * scenario's workspace.
 *
 * <p>Modifications replace whole files rather than applying diffs. Fuzzy patch
 * application against model-authored hunks fails in ways that are hard to detect
 * and easy to half-apply; a whole-file write guarded by an optimistic lock on
 * the file's prior hash either lands completely or is refused.
 */
public class BrownfieldFixture implements ScenarioFixture {

    @Override
    public boolean matches(String requirement) {
        return requirement.contains("rate limit") || requirement.contains("rate-limit");
    }

    @Override
    public String respond(ModelRequest request) {
        return switch (request.agentType()) {
            case REQUIREMENT -> REQUIREMENT;
            case REPOSITORY_ANALYSIS -> REPOSITORY_ANALYSIS;
            case ARCHITECTURE -> ARCHITECTURE;
            case IMPLEMENTATION -> IMPLEMENTATION;
            case TEST -> TESTS;
            case DOCUMENTATION -> DOCUMENTATION;
            case REPAIR -> throw new ModelProviderException(
                    "brownfield fixture generates a build-clean patch; no repair response is defined");
            default -> throw new ModelProviderException(
                    "no brownfield fixture for agent " + request.agentType());
        };
    }

    private static final String REQUIREMENT = """
            {
              "clarity": "CLEAR",
              "confidence": 0.88,
              "normalized": "Limit how many short links a single client may create per minute, rejecting excess requests with HTTP 429, so one caller cannot exhaust the keyspace or the service's memory.",
              "openQuestions": [],
              "assumptions": [
                "Client identity is the caller's IP address, which is what the service can observe without an authentication scheme.",
                "Only creation is limited. Resolution is the read path and rate limiting it would degrade legitimate traffic to popular links."
              ],
              "acceptanceCriteria": [
                "A client within its quota can create links as before",
                "A client exceeding the quota receives 429 rather than a new code",
                "The limit refills over time so a blocked client recovers without intervention",
                "Existing shortening, resolution and stats behaviour is unchanged"
              ],
              "impactedAreas": ["api layer", "service layer"]
            }
            """;

    private static final String REPOSITORY_ANALYSIS = """
            {
              "summary": "Spring Boot service, package-by-feature under com.example.shortener with api, service, domain and infra layers. Dependencies point inward: api depends on service, service depends on the UrlRepository interface. Constructor injection throughout, records for value types, no Lombok. Creation enters through ShortenerController.shorten, which is the single choke point for limiting link creation.",
              "impactedModules": [
                "api/ShortenerController.java - the only entry point for link creation",
                "service/ - new RateLimiter belongs here, alongside UrlValidator, since both reject requests before work is done"
              ],
              "conventions": [
                "Guard classes live in service and throw a nested checked-style RuntimeException",
                "Controller translates domain exceptions to status codes via @ExceptionHandler",
                "Tests are plain JUnit against constructed objects, not @SpringBootTest"
              ],
              "integrationPoints": [
                "UrlValidator is the existing precedent for a request-rejecting collaborator; RateLimiter follows the same shape so the controller's error handling stays uniform"
              ]
            }
            """;

    private static final String ARCHITECTURE = """
            {
              "approach": "Add a token-bucket RateLimiter in the service package and consult it at the start of ShortenerController.shorten. Following UrlValidator's existing shape means the new failure mode is handled by the same @ExceptionHandler mechanism the controller already uses, rather than introducing a second error-translation style.",
              "decisions": [
                {
                  "decision": "Token bucket rather than a fixed window counter",
                  "rationale": "A fixed window lets a client send its whole quota at the end of one window and again at the start of the next, delivering double the intended rate across the boundary. A bucket refills continuously, so the limit holds at every instant.",
                  "alternativesRejected": "Sliding window log - accurate but stores a timestamp per request, which is unbounded memory for exactly the abusive client the limit is meant to contain."
                },
                {
                  "decision": "Limit creation only, not resolution",
                  "rationale": "Resolution is the read path. Limiting it would throttle legitimate traffic to a popular link, which is success rather than abuse.",
                  "alternativesRejected": "Global limiting - simpler, but one heavy client would deny service to everyone."
                },
                {
                  "decision": "Refill computed lazily on access rather than by a scheduled task",
                  "rationale": "No background thread to fail, and the bucket is correct whenever it is read. Same reasoning the codebase already applies to TTL expiry.",
                  "alternativesRejected": "Scheduled refill - a second moving part whose failure silently disables the limit."
                }
              ],
              "risks": [
                {"risk": "Per-IP buckets grow without bound as distinct clients arrive", "mitigation": "Buckets are evicted once fully refilled and idle, so only active clients occupy memory."},
                {"risk": "Clients behind a shared NAT share a quota", "mitigation": "Documented; a real deployment would key on an authenticated principal instead."}
              ],
              "plannedFiles": [
                "src/main/java/com/example/shortener/service/RateLimiter.java",
                "src/main/java/com/example/shortener/api/ShortenerController.java"
              ]
            }
            """;

    static final String IMPLEMENTATION = """
            <<<FILE path=src/main/java/com/example/shortener/service/RateLimiter.java op=CREATE>>>
            package com.example.shortener.service;

            import org.springframework.stereotype.Component;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            /**
             * Per-client token bucket limiting how fast short links may be created.
             *
             * <p>A bucket rather than a fixed window: a window counter allows a client
             * to spend its whole quota at the end of one window and again at the start
             * of the next, delivering twice the intended rate across the boundary. A
             * bucket refills continuously, so the limit holds at every instant.
             *
             * <p>Refill is computed when a bucket is read rather than by a scheduled
             * task, matching how the service already handles TTL expiry. There is no
             * background thread whose failure would silently disable the limit.
             */
            @Component
            public class RateLimiter {

                private static final int DEFAULT_CAPACITY = 20;
                private static final Duration DEFAULT_REFILL_PERIOD = Duration.ofMinutes(1);

                private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
                private final int capacity;
                private final Duration refillPeriod;

                public RateLimiter() {
                    this(DEFAULT_CAPACITY, DEFAULT_REFILL_PERIOD);
                }

                public RateLimiter(int capacity, Duration refillPeriod) {
                    if (capacity < 1) {
                        throw new IllegalArgumentException("capacity must be at least 1");
                    }
                    this.capacity = capacity;
                    this.refillPeriod = refillPeriod;
                }

                /**
                 * Consumes one token for the client, or rejects.
                 *
                 * @throws RateLimitExceededException if the client has no tokens left
                 */
                public void checkAndConsume(String clientId) {
                    String key = clientId == null || clientId.isBlank() ? "unknown" : clientId;
                    Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));

                    synchronized (bucket) {
                        bucket.refill(capacity, refillPeriod);
                        if (!bucket.tryConsume()) {
                            throw new RateLimitExceededException(
                                    "rate limit of " + capacity + " creations per "
                                            + refillPeriod.toSeconds() + "s exceeded");
                        }
                    }

                    evictIdleBuckets();
                }

                /**
                 * Drops buckets that are full and untouched, so the map holds only
                 * clients that are actually active. Without this, every distinct IP
                 * ever seen would occupy memory permanently - which is precisely the
                 * exhaustion the limiter exists to prevent.
                 */
                private void evictIdleBuckets() {
                    if (buckets.size() < 1024) {
                        return;
                    }
                    Instant cutoff = Instant.now().minus(refillPeriod.multipliedBy(2));
                    buckets.entrySet().removeIf(entry -> {
                        Bucket bucket = entry.getValue();
                        synchronized (bucket) {
                            return bucket.tokens >= capacity && bucket.lastRefill.isBefore(cutoff);
                        }
                    });
                }

                public int capacity() {
                    return capacity;
                }

                private static final class Bucket {
                    private double tokens;
                    private Instant lastRefill;

                    Bucket(int initialTokens) {
                        this.tokens = initialTokens;
                        this.lastRefill = Instant.now();
                    }

                    void refill(int capacity, Duration refillPeriod) {
                        Instant now = Instant.now();
                        long elapsedMillis = Duration.between(lastRefill, now).toMillis();
                        if (elapsedMillis <= 0) {
                            return;
                        }
                        double refillRate = (double) capacity / refillPeriod.toMillis();
                        tokens = Math.min(capacity, tokens + elapsedMillis * refillRate);
                        lastRefill = now;
                    }

                    boolean tryConsume() {
                        if (tokens < 1.0) {
                            return false;
                        }
                        tokens -= 1.0;
                        return true;
                    }
                }

                public static class RateLimitExceededException extends RuntimeException {
                    public RateLimitExceededException(String message) {
                        super(message);
                    }
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/api/ShortenerController.java op=MODIFY>>>
            package com.example.shortener.api;

            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.service.RateLimiter;
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

                private final ShortenerService service;
                private final RateLimiter rateLimiter;

                public ShortenerController(ShortenerService service, RateLimiter rateLimiter) {
                    this.service = service;
                    this.rateLimiter = rateLimiter;
                }

                @PostMapping("/api/v1/urls")
                public ResponseEntity<ShortenResponse> shorten(@RequestBody ShortenRequest request,
                                                               HttpServletRequest httpRequest) {
                    // Checked before any work is done, so a client over its quota costs
                    // the service a map lookup rather than a code allocation.
                    rateLimiter.checkAndConsume(clientId(httpRequest));

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
                public ResponseEntity<Void> resolve(@PathVariable String shortCode) {
                    return service.resolve(shortCode)
                            .map(mapping -> ResponseEntity.status(HttpStatus.FOUND)
                                    .location(URI.create(mapping.longUrl()))
                                    .<Void>build())
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

                private String clientId(HttpServletRequest request) {
                    // X-Forwarded-For is only meaningful behind a proxy that sets it;
                    // it is client-controlled otherwise, so it is a convenience for
                    // deployment rather than an identity to trust.
                    String forwarded = request.getHeader("X-Forwarded-For");
                    if (forwarded != null && !forwarded.isBlank()) {
                        return forwarded.split(",")[0].trim();
                    }
                    return request.getRemoteAddr();
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

                @ExceptionHandler(RateLimiter.RateLimitExceededException.class)
                public ResponseEntity<Map<String, String>> handleRateLimited(
                        RateLimiter.RateLimitExceededException e) {
                    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                            .body(Map.of("error", e.getMessage()));
                }

                public record ShortenRequest(String url, Long ttlSeconds) {
                }

                public record ShortenResponse(String shortCode, String longUrl,
                                              Instant createdAt, Instant expiresAt) {
                }

                public record StatsResponse(String shortCode, String longUrl,
                                            long clicks, Instant createdAt) {
                }
            }
            <<<END>>>
            """;

    static final String TESTS = """
            <<<FILE path=src/test/java/com/example/shortener/service/RateLimiterTest.java op=CREATE>>>
            package com.example.shortener.service;

            import org.junit.jupiter.api.Test;

            import java.time.Duration;

            import static org.assertj.core.api.Assertions.assertThatCode;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            class RateLimiterTest {

                @Test
                void a_client_within_its_quota_is_allowed() {
                    RateLimiter limiter = new RateLimiter(5, Duration.ofMinutes(1));

                    assertThatCode(() -> {
                        for (int i = 0; i < 5; i++) {
                            limiter.checkAndConsume("10.0.0.1");
                        }
                    }).doesNotThrowAnyException();
                }

                @Test
                void a_client_over_its_quota_is_rejected() {
                    RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1));
                    for (int i = 0; i < 3; i++) {
                        limiter.checkAndConsume("10.0.0.2");
                    }

                    assertThatThrownBy(() -> limiter.checkAndConsume("10.0.0.2"))
                            .isInstanceOf(RateLimiter.RateLimitExceededException.class)
                            .hasMessageContaining("rate limit");
                }

                @Test
                void clients_are_limited_independently() {
                    RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
                    limiter.checkAndConsume("10.0.0.3");

                    assertThatCode(() -> limiter.checkAndConsume("10.0.0.4"))
                            .doesNotThrowAnyException();
                }

                @Test
                void a_blocked_client_recovers_as_the_bucket_refills() throws Exception {
                    RateLimiter limiter = new RateLimiter(2, Duration.ofMillis(200));
                    limiter.checkAndConsume("10.0.0.5");
                    limiter.checkAndConsume("10.0.0.5");

                    assertThatThrownBy(() -> limiter.checkAndConsume("10.0.0.5"))
                            .isInstanceOf(RateLimiter.RateLimitExceededException.class);

                    Thread.sleep(250);

                    assertThatCode(() -> limiter.checkAndConsume("10.0.0.5"))
                            .doesNotThrowAnyException();
                }

                @Test
                void a_missing_client_id_still_gets_a_bucket_rather_than_bypassing_the_limit() {
                    RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1));
                    limiter.checkAndConsume(null);

                    assertThatThrownBy(() -> limiter.checkAndConsume(null))
                            .isInstanceOf(RateLimiter.RateLimitExceededException.class);
                }
            }
            <<<END>>>
            """;

    private static final String DOCUMENTATION = """
            <<<FILE path=docs/rate-limiting.md op=CREATE>>>
            # Rate limiting

            Link creation is limited per client to stop one caller exhausting the
            keyspace or the service's memory. Resolution is not limited — that is the
            read path, and throttling it would penalise popular links.

            ## Behaviour

            - Default quota: 20 creations per minute per client
            - Over quota: `429 Too Many Requests`
            - Recovery is automatic as the bucket refills; no intervention needed

            ## Why a token bucket

            A fixed-window counter lets a client spend its full quota at the end of one
            window and again at the start of the next, delivering double the intended
            rate across the boundary. A bucket refills continuously, so the limit holds
            at every instant.

            Refill is computed when a bucket is read rather than on a timer. There is no
            background thread whose failure would silently disable the limit — the same
            reasoning the service already applies to TTL expiry.

            ## Client identity

            Clients are keyed by IP, taken from `X-Forwarded-For` when present and
            falling back to the socket address. `X-Forwarded-For` is client-controlled
            unless a trusted proxy overwrites it, so this is a deployment convenience
            rather than an identity to rely on. A deployment with authentication should
            key on the authenticated principal instead.

            ## Limitations

            Buckets are per-process, so a client's effective quota multiplies by the
            number of instances behind a load balancer. Clients sharing a NAT share a
            quota.
            <<<END>>>
            """;
}
