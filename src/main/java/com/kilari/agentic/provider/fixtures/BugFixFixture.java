package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelProviderException;
import com.kilari.agentic.provider.ModelRequest;

/**
 * Brownfield bug fix: a non-positive TTL produces a link that can never resolve.
 *
 * <p>The defect is genuine, not staged for the scenario. The service computes
 * {@code expiresAt = now.plus(ttl)} without checking the sign, so
 * {@code ttlSeconds: 0} yields an expiry equal to creation time, and a negative
 * value yields one in the past. Either way the API answers {@code 201 Created}
 * and every subsequent resolution returns {@code 410 Gone} — the caller is told
 * their link was created, and it never worked.
 *
 * <p>It is a good bug for this scenario precisely because it is quiet: nothing
 * throws, no log records an error, and the failure only shows up as a link that
 * mysteriously does not work. Finding it requires reasoning about the code
 * rather than reading a stack trace.
 */
public class BugFixFixture implements ScenarioFixture {

    @Override
    public boolean matches(String requirement) {
        return requirement.contains("ttl") || requirement.contains("time-to-live")
                || requirement.contains("time to live");
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
                    "bug-fix fixture generates a build-clean patch; no repair response is defined");
            default -> throw new ModelProviderException(
                    "no bug-fix fixture for agent " + request.agentType());
        };
    }

    private static final String REQUIREMENT = """
            {
              "clarity": "CLEAR",
              "confidence": 0.94,
              "normalized": "Reject a zero or negative time-to-live when creating a short link, instead of returning 201 for a link that is already expired and can never be resolved.",
              "openQuestions": [],
              "assumptions": [
                "A zero TTL is a caller error rather than a request for a permanent link; permanence is expressed by omitting ttlSeconds entirely, which the API already supports.",
                "Existing links created with a bad TTL are already unresolvable and are not retroactively repaired."
              ],
              "acceptanceCriteria": [
                "Creating a link with ttlSeconds of 0 is rejected with 400 rather than 201",
                "Creating a link with a negative ttlSeconds is rejected with 400",
                "Omitting ttlSeconds still creates a link that never expires",
                "A positive ttlSeconds behaves exactly as before"
              ],
              "impactedAreas": ["service layer", "api error mapping"]
            }
            """;

    private static final String REPOSITORY_ANALYSIS = """
            {
              "summary": "ShortenerService.shorten computes expiresAt as now.plus(timeToLive) with no check on the sign of the duration. UrlMapping.isExpiredAt then evaluates !now.isBefore(expiresAt), which is true when the two are equal. A zero TTL therefore produces a mapping that is expired at the instant it is stored. The controller converts a caller-supplied ttlSeconds directly into a Duration, so the bad value passes through untouched.",
              "impactedModules": [
                "service/ShortenerService.java - shorten() is where the unchecked duration becomes an expiry instant",
                "api/ShortenerController.java - needs to map the new rejection to 400, matching how it already handles InvalidUrlException"
              ],
              "conventions": [
                "Guard failures are nested RuntimeExceptions on the class that raises them",
                "The controller maps each domain exception to a status via @ExceptionHandler",
                "Validation happens before any state is allocated"
              ],
              "integrationPoints": [
                "UrlValidator is the existing precedent for rejecting bad input at the service boundary; this fix follows the same shape so the controller's error handling stays uniform"
              ]
            }
            """;

    private static final String ARCHITECTURE = """
            {
              "approach": "Reject a non-positive TTL in ShortenerService.shorten, before a short code is allocated, and map it to 400 in the controller alongside the existing invalid-URL handler.",
              "decisions": [
                {
                  "decision": "Reject rather than silently treat a zero TTL as no expiry",
                  "rationale": "Coercing bad input into a plausible meaning hides the caller's mistake. They asked for something contradictory, and the API already has a way to express a permanent link - omitting ttlSeconds.",
                  "alternativesRejected": "Treating ttl<=0 as null - the caller never learns their request was wrong, and two different inputs quietly produce the same result."
                },
                {
                  "decision": "Validate in the service, not the controller",
                  "rationale": "The invariant belongs to the domain operation, not to one transport. A future caller arriving through a different entry point gets the same guarantee.",
                  "alternativesRejected": "Bean validation on the request record - only protects the HTTP path and duplicates the rule if another caller appears."
                },
                {
                  "decision": "Validate before allocating a short code",
                  "rationale": "Matches the existing ordering, where UrlValidator runs first. A rejected request should cost a comparison, not a keyspace allocation.",
                  "alternativesRejected": "Checking after creation - wastes a code and briefly stores an unusable mapping."
                }
              ],
              "risks": [
                {"risk": "A client currently sending ttlSeconds=0 starts receiving 400", "mitigation": "Those links never resolved, so no working behaviour changes; documented as the intended correction."}
              ],
              "plannedFiles": [
                "src/main/java/com/example/shortener/service/ShortenerService.java",
                "src/main/java/com/example/shortener/api/ShortenerController.java"
              ]
            }
            """;

    static final String IMPLEMENTATION = """
            <<<FILE path=src/main/java/com/example/shortener/service/ShortenerService.java op=MODIFY>>>
            package com.example.shortener.service;

            import com.example.shortener.domain.ShortCodeGenerator;
            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.domain.UrlRepository;
            import org.springframework.stereotype.Service;

            import java.time.Duration;
            import java.time.Instant;
            import java.util.Optional;

            /**
             * Core shortening and resolution logic.
             */
            @Service
            public class ShortenerService {

                /**
                 * Bounded so a pathological run of collisions fails loudly instead of
                 * spinning forever. At seven base62 characters this is unreachable in
                 * practice; if it ever trips, the keyspace is genuinely exhausted and
                 * silently retrying would only hide that.
                 */
                private static final int MAX_COLLISION_RETRIES = 5;

                private final UrlRepository repository;
                private final ShortCodeGenerator codeGenerator;
                private final UrlValidator validator;

                public ShortenerService(UrlRepository repository,
                                        ShortCodeGenerator codeGenerator,
                                        UrlValidator validator) {
                    this.repository = repository;
                    this.codeGenerator = codeGenerator;
                    this.validator = validator;
                }

                public UrlMapping shorten(String longUrl, Duration timeToLive) {
                    validator.validate(longUrl);
                    validateTimeToLive(timeToLive);

                    Instant now = Instant.now();
                    Instant expiresAt = timeToLive == null ? null : now.plus(timeToLive);

                    for (int attempt = 0; attempt < MAX_COLLISION_RETRIES; attempt++) {
                        UrlMapping mapping =
                                new UrlMapping(codeGenerator.generate(), longUrl, now, expiresAt);
                        if (repository.saveIfAbsent(mapping)) {
                            return mapping;
                        }
                    }
                    throw new CodeGenerationException(
                            "could not allocate an unused short code after "
                                    + MAX_COLLISION_RETRIES + " attempts");
                }

                /**
                 * Rejects a time-to-live that cannot describe a usable link.
                 *
                 * <p>Expiry is evaluated as !now.isBefore(expiresAt), so a zero duration
                 * makes a mapping expired at the instant it is created and a negative one
                 * makes it expired before that. Previously both were accepted, and the
                 * caller received 201 for a link that answered 410 on every resolution.
                 *
                 * <p>Rejected rather than coerced to "no expiry": the caller asked for
                 * something contradictory, and the API already expresses a permanent link
                 * by omitting the field. Quietly reinterpreting the request would mean two
                 * different inputs produce the same result and the mistake is never
                 * surfaced.
                 */
                private void validateTimeToLive(Duration timeToLive) {
                    if (timeToLive == null) {
                        return;
                    }
                    if (timeToLive.isZero() || timeToLive.isNegative()) {
                        throw new InvalidTimeToLiveException(
                                "time-to-live must be positive; omit it entirely for a link that never expires");
                    }
                }

                /**
                 * Resolves a code to its mapping and records the click.
                 *
                 * <p>Expiry is evaluated on read rather than by a background sweeper,
                 * so an expired link is never served even if cleanup has not run. The
                 * expired entry is removed here as well, which keeps the map from
                 * accumulating dead mappings without needing a scheduler at all.
                 */
                public Optional<UrlMapping> resolve(String shortCode) {
                    Optional<UrlMapping> found = repository.findByCode(shortCode);
                    if (found.isEmpty()) {
                        return Optional.empty();
                    }

                    UrlMapping mapping = found.get();
                    if (mapping.isExpiredAt(Instant.now())) {
                        repository.deleteByCode(shortCode);
                        throw new MappingExpiredException(shortCode);
                    }

                    mapping.recordClick();
                    return Optional.of(mapping);
                }

                /** Looks up a mapping without recording a click. */
                public Optional<UrlMapping> stats(String shortCode) {
                    return repository.findByCode(shortCode)
                            .filter(mapping -> !mapping.isExpiredAt(Instant.now()));
                }

                public static class CodeGenerationException extends RuntimeException {
                    public CodeGenerationException(String message) {
                        super(message);
                    }
                }

                public static class MappingExpiredException extends RuntimeException {
                    public MappingExpiredException(String shortCode) {
                        super("short code has expired: " + shortCode);
                    }
                }

                public static class InvalidTimeToLiveException extends RuntimeException {
                    public InvalidTimeToLiveException(String message) {
                        super(message);
                    }
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/api/ShortenerController.java op=MODIFY>>>
            package com.example.shortener.api;

            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.service.ShortenerService;
            import com.example.shortener.service.UrlValidator;
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

                public ShortenerController(ShortenerService service) {
                    this.service = service;
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

                @ExceptionHandler(UrlValidator.InvalidUrlException.class)
                public ResponseEntity<Map<String, String>> handleInvalidUrl(
                        UrlValidator.InvalidUrlException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }

                @ExceptionHandler(ShortenerService.InvalidTimeToLiveException.class)
                public ResponseEntity<Map<String, String>> handleInvalidTtl(
                        ShortenerService.InvalidTimeToLiveException e) {
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
            }
            <<<END>>>
            """;

    static final String TESTS = """
            <<<FILE path=src/test/java/com/example/shortener/service/TimeToLiveValidationTest.java op=CREATE>>>
            package com.example.shortener.service;

            import com.example.shortener.domain.ShortCodeGenerator;
            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.infra.InMemoryUrlRepository;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Test;

            import java.time.Duration;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatCode;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            /**
             * Regression tests for the non-positive TTL defect.
             *
             * <p>The bug was quiet: creation succeeded and only resolution failed, so
             * these assert the rejection happens at creation, where the caller can act
             * on it.
             */
            class TimeToLiveValidationTest {

                private ShortenerService service;

                @BeforeEach
                void setUp() {
                    service = new ShortenerService(
                            new InMemoryUrlRepository(),
                            new ShortCodeGenerator(),
                            new UrlValidator());
                }

                @Test
                void a_zero_ttl_is_rejected_rather_than_creating_an_already_expired_link() {
                    assertThatThrownBy(() -> service.shorten("https://example.com", Duration.ZERO))
                            .isInstanceOf(ShortenerService.InvalidTimeToLiveException.class)
                            .hasMessageContaining("must be positive");
                }

                @Test
                void a_negative_ttl_is_rejected() {
                    assertThatThrownBy(() ->
                            service.shorten("https://example.com", Duration.ofSeconds(-60)))
                            .isInstanceOf(ShortenerService.InvalidTimeToLiveException.class);
                }

                @Test
                void a_rejected_ttl_does_not_consume_a_short_code() {
                    InMemoryUrlRepository repository = new InMemoryUrlRepository();
                    ShortenerService svc = new ShortenerService(
                            repository, new ShortCodeGenerator(), new UrlValidator());

                    assertThatThrownBy(() -> svc.shorten("https://example.com", Duration.ZERO))
                            .isInstanceOf(ShortenerService.InvalidTimeToLiveException.class);

                    assertThat(repository.count())
                            .as("validation must run before a code is allocated")
                            .isZero();
                }

                @Test
                void omitting_the_ttl_still_creates_a_link_that_never_expires() {
                    UrlMapping mapping = service.shorten("https://example.com", null);

                    assertThat(mapping.expiresAt()).isEmpty();
                    assertThat(service.resolve(mapping.shortCode())).isPresent();
                }

                @Test
                void a_positive_ttl_behaves_exactly_as_before() {
                    UrlMapping mapping =
                            service.shorten("https://example.com", Duration.ofMinutes(5));

                    assertThat(mapping.expiresAt()).isPresent();
                    assertThatCode(() -> service.resolve(mapping.shortCode()))
                            .doesNotThrowAnyException();
                }
            }
            <<<END>>>
            """;

    private static final String DOCUMENTATION = """
            <<<FILE path=docs/ttl-validation.md op=CREATE>>>
            # Time-to-live validation

            `ttlSeconds` must be a positive number of seconds. Omit it entirely for a
            link that never expires.

            | Input | Result |
            |---|---|
            | omitted / `null` | Link never expires |
            | positive | Link expires after that many seconds |
            | `0` or negative | `400 Bad Request` |

            ## The defect this fixes

            Expiry is evaluated as `!now.isBefore(expiresAt)`, which is true when the two
            instants are equal. A zero TTL therefore produced a mapping that was expired
            at the moment it was stored, and a negative one produced an expiry in the
            past. Both were accepted: the API returned `201 Created`, and every
            subsequent resolution returned `410 Gone`.

            The failure was quiet — nothing threw, nothing was logged, and the only
            symptom was a link that never worked. Rejecting at creation puts the error
            where the caller can act on it.

            ## Why rejection rather than coercion

            Treating `ttlSeconds: 0` as "no expiry" would have been easy and would have
            made the symptom disappear. It also means two different requests silently
            produce the same result, and the caller never learns that what they asked
            for was contradictory. The API already expresses a permanent link by
            omitting the field, so there is no gap to fill.

            ## Compatibility

            Callers currently sending `0` or a negative value will start receiving `400`.
            No working behaviour changes: the links those requests produced were never
            resolvable. Existing stored mappings are not retroactively repaired — they
            are already expired and are evicted when next read.
            <<<END>>>
            """;
}
