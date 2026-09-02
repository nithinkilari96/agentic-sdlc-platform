package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelRequest;
import com.kilari.agentic.provider.ModelProviderException;

/**
 * Greenfield scenario: build the URL shortener service from an empty repository.
 *
 * <p>The source held here is also the seed for the brownfield and ambiguous
 * scenarios — those runs start from what this scenario produced, rather than
 * from a separately maintained fake repository that could drift out of sync
 * with it.
 */
public class GreenfieldFixture implements ScenarioFixture {

    @Override
    public boolean matches(String requirement) {
        return requirement.contains("url shortener")
                || (requirement.contains("shorten") && requirement.contains("build"));
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
                    "greenfield fixture generates a build-clean patch; no repair response is defined");
            default -> throw new ModelProviderException(
                    "no greenfield fixture for agent " + request.agentType());
        };
    }

    private static final String REQUIREMENT = """
            {
              "clarity": "CLEAR",
              "confidence": 0.93,
              "normalized": "Build a URL shortener service exposing create, resolve and statistics APIs, with click analytics and reliability controls (URL validation, TTL expiry, collision-safe code generation).",
              "openQuestions": [],
              "assumptions": [
                "Single-node in-memory persistence is acceptable for the prototype; the repository is expressed as an interface so a durable implementation can be substituted without touching the service layer.",
                "Short codes are opaque and non-guessable rather than sequential, to avoid enumeration of other users' links."
              ],
              "acceptanceCriteria": [
                "POST /api/v1/urls returns a short code for a valid http(s) URL",
                "GET /{code} redirects to the original URL and records a click",
                "GET /api/v1/urls/{code}/stats returns the click count and creation time",
                "A URL that is not http or https is rejected with 400",
                "An expired mapping resolves to 410 rather than redirecting",
                "Short code generation retries on collision instead of overwriting an existing mapping"
              ],
              "impactedAreas": ["greenfield - no existing modules"]
            }
            """;

    private static final String REPOSITORY_ANALYSIS = """
            {
              "summary": "Empty repository. No existing conventions to honour, so the design sets them: package-by-feature under com.example.shortener, constructor injection, records for immutable data, and an interface at the persistence boundary.",
              "impactedModules": [],
              "conventions": [
                "Java 25, Spring Boot 4.1.1, Gradle Kotlin DSL",
                "Records for value types; no Lombok",
                "Constructor injection only - no field injection"
              ],
              "integrationPoints": []
            }
            """;

    private static final String ARCHITECTURE = """
            {
              "approach": "Three layers with one direction of dependency: api -> service -> domain, plus an infra package holding the repository implementation. The service depends on the UrlRepository interface, never the in-memory class, so swapping in Redis or Postgres later is an infra-only change.",
              "decisions": [
                {
                  "decision": "Random base62 codes rather than a sequential counter encoded to base62",
                  "rationale": "Sequential codes let anyone enumerate every link in the system by counting up. Random codes cost a collision check, which is a cheap map lookup.",
                  "alternativesRejected": "Hashing the URL - makes the mapping deterministic, so the same URL always yields the same code and one user can discover whether another has already shortened a given link."
                },
                {
                  "decision": "Click counting via an AtomicLong per mapping",
                  "rationale": "Resolution is the hot path and must not block. An atomic increment keeps it lock-free.",
                  "alternativesRejected": "Synchronised counter - serialises every redirect through one lock."
                },
                {
                  "decision": "TTL enforced at read time rather than by a sweeper thread",
                  "rationale": "A background sweeper is a second moving part that can fail silently. Checking expiry on read means an expired link is never served even if cleanup has not run.",
                  "alternativesRejected": "Scheduled eviction - correctness would depend on the scheduler being alive."
                }
              ],
              "risks": [
                {"risk": "In-memory storage loses all mappings on restart", "mitigation": "Repository interface allows a durable implementation; documented as a prototype limitation."},
                {"risk": "Unbounded map growth", "mitigation": "TTL support caps lifetime; eviction of expired entries on read keeps the map from growing without limit for expiring links."}
              ],
              "plannedFiles": [
                "settings.gradle.kts", "build.gradle.kts",
                "src/main/java/com/example/shortener/ShortenerApplication.java",
                "src/main/java/com/example/shortener/domain/UrlMapping.java",
                "src/main/java/com/example/shortener/domain/ShortCodeGenerator.java",
                "src/main/java/com/example/shortener/domain/UrlRepository.java",
                "src/main/java/com/example/shortener/infra/InMemoryUrlRepository.java",
                "src/main/java/com/example/shortener/service/UrlValidator.java",
                "src/main/java/com/example/shortener/service/ShortenerService.java",
                "src/main/java/com/example/shortener/api/ShortenerController.java"
              ]
            }
            """;

    /**
     * The implementation patch. This is the canonical shortener source: the
     * workspace seeder replays these same changes to construct the starting
     * repository for the brownfield and ambiguous scenarios.
     */
    public static final String IMPLEMENTATION = """
            <<<FILE path=settings.gradle.kts op=CREATE>>>
            rootProject.name = "url-shortener"
            <<<END>>>
            <<<FILE path=build.gradle.kts op=CREATE>>>
            plugins {
                java
                id("org.springframework.boot") version "4.1.1"
                id("io.spring.dependency-management") version "1.1.7"
            }

            group = "com.example"
            version = "0.1.0"

            java {
                toolchain {
                    languageVersion = JavaLanguageVersion.of(25)
                }
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                testImplementation("org.springframework.boot:spring-boot-starter-test")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }

            tasks.withType<Test> {
                useJUnitPlatform()
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/ShortenerApplication.java op=CREATE>>>
            package com.example.shortener;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class ShortenerApplication {
                public static void main(String[] args) {
                    SpringApplication.run(ShortenerApplication.class, args);
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/domain/UrlMapping.java op=CREATE>>>
            package com.example.shortener.domain;

            import java.time.Instant;
            import java.util.Objects;
            import java.util.Optional;
            import java.util.concurrent.atomic.AtomicLong;

            /**
             * A single short-code to long-URL mapping.
             *
             * <p>Not a record: the click counter is mutable state that has to be
             * incremented on the resolution hot path without locking.
             */
            public final class UrlMapping {

                private final String shortCode;
                private final String longUrl;
                private final Instant createdAt;
                private final Instant expiresAt;
                private final AtomicLong clicks = new AtomicLong();

                public UrlMapping(String shortCode, String longUrl, Instant createdAt, Instant expiresAt) {
                    this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
                    this.longUrl = Objects.requireNonNull(longUrl, "longUrl");
                    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
                    this.expiresAt = expiresAt;
                }

                public String shortCode() {
                    return shortCode;
                }

                public String longUrl() {
                    return longUrl;
                }

                public Instant createdAt() {
                    return createdAt;
                }

                public Optional<Instant> expiresAt() {
                    return Optional.ofNullable(expiresAt);
                }

                public long clicks() {
                    return clicks.get();
                }

                public long recordClick() {
                    return clicks.incrementAndGet();
                }

                public boolean isExpiredAt(Instant now) {
                    return expiresAt != null && !now.isBefore(expiresAt);
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/domain/ShortCodeGenerator.java op=CREATE>>>
            package com.example.shortener.domain;

            import java.security.SecureRandom;

            /**
             * Generates opaque short codes.
             *
             * <p>Codes are random rather than a counter encoded to base62. A
             * sequential scheme lets anyone walk the entire keyspace by counting up,
             * which would expose every link in the system; the cost of randomness is
             * a collision check the caller performs against the repository.
             */
            public class ShortCodeGenerator {

                private static final String ALPHABET =
                        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

                public static final int DEFAULT_LENGTH = 7;

                private final SecureRandom random = new SecureRandom();
                private final int length;

                public ShortCodeGenerator() {
                    this(DEFAULT_LENGTH);
                }

                public ShortCodeGenerator(int length) {
                    if (length < 4) {
                        throw new IllegalArgumentException("short code length must be at least 4");
                    }
                    this.length = length;
                }

                public String generate() {
                    StringBuilder sb = new StringBuilder(length);
                    for (int i = 0; i < length; i++) {
                        sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                    }
                    return sb.toString();
                }

                public int length() {
                    return length;
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/domain/UrlRepository.java op=CREATE>>>
            package com.example.shortener.domain;

            import java.util.Optional;

            /**
             * Persistence boundary.
             *
             * <p>The service depends on this interface and never on a concrete
             * implementation, so replacing in-memory storage with Redis or Postgres
             * is a change confined to the infra package.
             */
            public interface UrlRepository {

                /**
                 * Stores the mapping only if the code is unused.
                 *
                 * @return true if stored, false if the code was already taken
                 */
                boolean saveIfAbsent(UrlMapping mapping);

                Optional<UrlMapping> findByCode(String shortCode);

                void deleteByCode(String shortCode);

                long count();
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/infra/InMemoryUrlRepository.java op=CREATE>>>
            package com.example.shortener.infra;

            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.domain.UrlRepository;
            import org.springframework.stereotype.Repository;

            import java.util.Map;
            import java.util.Optional;
            import java.util.concurrent.ConcurrentHashMap;

            /**
             * In-memory repository backed by a concurrent map.
             *
             * <p>putIfAbsent is what makes collision handling correct under
             * concurrency: two threads generating the same code cannot both believe
             * they won, because only one insert succeeds.
             */
            @Repository
            public class InMemoryUrlRepository implements UrlRepository {

                private final Map<String, UrlMapping> mappings = new ConcurrentHashMap<>();

                @Override
                public boolean saveIfAbsent(UrlMapping mapping) {
                    return mappings.putIfAbsent(mapping.shortCode(), mapping) == null;
                }

                @Override
                public Optional<UrlMapping> findByCode(String shortCode) {
                    return Optional.ofNullable(mappings.get(shortCode));
                }

                @Override
                public void deleteByCode(String shortCode) {
                    mappings.remove(shortCode);
                }

                @Override
                public long count() {
                    return mappings.size();
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/service/UrlValidator.java op=CREATE>>>
            package com.example.shortener.service;

            import org.springframework.stereotype.Component;

            import java.net.URI;
            import java.net.URISyntaxException;
            import java.util.Locale;
            import java.util.Set;

            /**
             * Rejects URLs the service must not shorten.
             *
             * <p>An open redirector is a real vulnerability: without a scheme
             * allow-list, a shortener will happily mint a link to javascript: or
             * file: and lend it the credibility of its own domain.
             */
            @Component
            public class UrlValidator {

                private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
                private static final int MAX_URL_LENGTH = 2048;

                public void validate(String url) {
                    if (url == null || url.isBlank()) {
                        throw new InvalidUrlException("URL must not be empty");
                    }
                    if (url.length() > MAX_URL_LENGTH) {
                        throw new InvalidUrlException(
                                "URL exceeds maximum length of " + MAX_URL_LENGTH + " characters");
                    }

                    URI uri;
                    try {
                        uri = new URI(url);
                    } catch (URISyntaxException e) {
                        throw new InvalidUrlException("URL is not well formed: " + e.getReason());
                    }

                    String scheme = uri.getScheme();
                    if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                        throw new InvalidUrlException(
                                "only http and https URLs may be shortened, got: " + scheme);
                    }
                    if (uri.getHost() == null || uri.getHost().isBlank()) {
                        throw new InvalidUrlException("URL must include a host");
                    }
                }

                public static class InvalidUrlException extends RuntimeException {
                    public InvalidUrlException(String message) {
                        super(message);
                    }
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/service/ShortenerService.java op=CREATE>>>
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
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/ShortenerConfiguration.java op=CREATE>>>
            package com.example.shortener;

            import com.example.shortener.domain.ShortCodeGenerator;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            public class ShortenerConfiguration {

                @Bean
                public ShortCodeGenerator shortCodeGenerator() {
                    return new ShortCodeGenerator();
                }
            }
            <<<END>>>
            <<<FILE path=src/main/java/com/example/shortener/api/ShortenerController.java op=CREATE>>>
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

    /** Exposed so the build-verification test can prove the generated project is green. */
    public static String tests() {
        return TESTS;
    }

    private static final String TESTS = """
            <<<FILE path=src/test/java/com/example/shortener/service/ShortenerServiceTest.java op=CREATE>>>
            package com.example.shortener.service;

            import com.example.shortener.domain.ShortCodeGenerator;
            import com.example.shortener.domain.UrlMapping;
            import com.example.shortener.infra.InMemoryUrlRepository;
            import org.junit.jupiter.api.BeforeEach;
            import org.junit.jupiter.api.Test;

            import java.time.Duration;
            import java.util.Optional;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            class ShortenerServiceTest {

                private ShortenerService service;

                @BeforeEach
                void setUp() {
                    service = new ShortenerService(
                            new InMemoryUrlRepository(),
                            new ShortCodeGenerator(),
                            new UrlValidator());
                }

                @Test
                void shortening_a_valid_url_returns_a_resolvable_code() {
                    UrlMapping mapping = service.shorten("https://example.com/some/page", null);

                    assertThat(mapping.shortCode()).hasSize(ShortCodeGenerator.DEFAULT_LENGTH);
                    assertThat(service.resolve(mapping.shortCode()))
                            .map(UrlMapping::longUrl)
                            .contains("https://example.com/some/page");
                }

                @Test
                void each_shortening_gets_a_distinct_code() {
                    UrlMapping first = service.shorten("https://example.com/a", null);
                    UrlMapping second = service.shorten("https://example.com/a", null);

                    assertThat(first.shortCode()).isNotEqualTo(second.shortCode());
                }

                @Test
                void resolving_records_a_click_but_reading_stats_does_not() {
                    UrlMapping mapping = service.shorten("https://example.com", null);

                    service.resolve(mapping.shortCode());
                    service.resolve(mapping.shortCode());
                    service.stats(mapping.shortCode());

                    assertThat(service.stats(mapping.shortCode()))
                            .map(UrlMapping::clicks)
                            .contains(2L);
                }

                @Test
                void an_unknown_code_resolves_to_empty() {
                    assertThat(service.resolve("nosuch")).isEmpty();
                }

                @Test
                void a_non_http_url_is_rejected() {
                    assertThatThrownBy(() -> service.shorten("javascript:alert(1)", null))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class)
                            .hasMessageContaining("only http and https");
                }

                @Test
                void a_url_without_a_host_is_rejected() {
                    assertThatThrownBy(() -> service.shorten("https:///nohost", null))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class);
                }

                @Test
                void an_expired_mapping_is_gone_rather_than_redirected() throws Exception {
                    UrlMapping mapping = service.shorten("https://example.com", Duration.ofMillis(20));
                    Thread.sleep(40);

                    assertThatThrownBy(() -> service.resolve(mapping.shortCode()))
                            .isInstanceOf(ShortenerService.MappingExpiredException.class);
                }

                @Test
                void an_expired_mapping_is_not_reported_in_stats() throws Exception {
                    UrlMapping mapping = service.shorten("https://example.com", Duration.ofMillis(20));
                    Thread.sleep(40);

                    assertThat(service.stats(mapping.shortCode())).isEmpty();
                }
            }
            <<<END>>>
            <<<FILE path=src/test/java/com/example/shortener/domain/ShortCodeGeneratorTest.java op=CREATE>>>
            package com.example.shortener.domain;

            import org.junit.jupiter.api.Test;

            import java.util.HashSet;
            import java.util.Set;

            import static org.assertj.core.api.Assertions.assertThat;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            class ShortCodeGeneratorTest {

                @Test
                void generates_codes_of_the_configured_length() {
                    ShortCodeGenerator generator = new ShortCodeGenerator(10);
                    assertThat(generator.generate()).hasSize(10);
                }

                @Test
                void generates_codes_from_the_base62_alphabet_only() {
                    ShortCodeGenerator generator = new ShortCodeGenerator();
                    assertThat(generator.generate()).matches("[0-9a-zA-Z]+");
                }

                @Test
                void collisions_are_rare_enough_to_be_practical() {
                    ShortCodeGenerator generator = new ShortCodeGenerator();
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < 10_000; i++) {
                        seen.add(generator.generate());
                    }
                    assertThat(seen).hasSize(10_000);
                }

                @Test
                void rejects_a_length_short_enough_to_be_guessable() {
                    assertThatThrownBy(() -> new ShortCodeGenerator(3))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
            <<<END>>>
            """;

    private static final String DOCUMENTATION = """
            <<<FILE path=README.md op=CREATE>>>
            # URL Shortener

            Shortens http(s) URLs to opaque codes, resolves them with a redirect, and
            reports click counts.

            ## API

            | Method | Path | Purpose |
            |---|---|---|
            | `POST` | `/api/v1/urls` | Create a short code. Body: `{"url": "...", "ttlSeconds": 3600}` (`ttlSeconds` optional) |
            | `GET` | `/{shortCode}` | Redirect (302) to the original URL and record a click |
            | `GET` | `/api/v1/urls/{shortCode}/stats` | Click count and creation time |

            Responses: `201` on create, `302` on resolve, `400` for a URL that is not
            http(s) or is malformed, `404` for an unknown code, `410` for an expired one.

            ## Running

            ```
            ./gradlew bootRun
            ```

            ## Design notes

            Short codes are random base62 rather than a sequential counter. Sequential
            codes can be enumerated, which would expose every link in the system.

            Expiry is checked when a code is read, not by a background sweeper. A
            sweeper is a second moving part that can fail silently; checking on read
            means an expired link is never served regardless of cleanup state.

            `UrlRepository` is an interface with an in-memory implementation. Storage
            is the only layer that has to change to make mappings durable.

            ## Limitations

            Mappings live in memory and are lost on restart. There is no authentication,
            so anyone who can reach the service can create links. Click counts are
            per-process and would need consolidating behind a load balancer.
            <<<END>>>
            """;
}
