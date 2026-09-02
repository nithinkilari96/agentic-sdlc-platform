package com.kilari.agentic.provider.fixtures;

import com.kilari.agentic.provider.ModelProviderException;
import com.kilari.agentic.provider.ModelRequest;

/**
 * Test and documentation improvement: no production code changes.
 *
 * <p>Runs on the {@code TESTS_AND_DOCS} plan, which has no architecture or
 * implementation node. That is the interesting part of this scenario — asking
 * an implementation agent to contribute to a requirement that explicitly does
 * not change production code would force it to invent something, and the
 * approver would then have to work out which parts of the diff were actually
 * asked for.
 *
 * <p>Everything else is unchanged: the patch passes the same policy checks, the
 * build still has to go green, and a human still approves. A test-only change is
 * smaller, not safer.
 */
public class TestAndDocsFixture implements ScenarioFixture {

    @Override
    public boolean matches(String requirement) {
        return requirement.contains("test coverage")
                || requirement.contains("document the api")
                || requirement.contains("document the error");
    }

    @Override
    public String respond(ModelRequest request) {
        return switch (request.agentType()) {
            case REQUIREMENT -> REQUIREMENT;
            case REPOSITORY_ANALYSIS -> REPOSITORY_ANALYSIS;
            case TEST -> TESTS;
            case DOCUMENTATION -> DOCUMENTATION;
            // Reaching either of these means the wrong plan was selected: this
            // requirement changes no production code, so there is nothing to design
            // or implement. Failing loudly beats fabricating a change.
            case ARCHITECTURE, IMPLEMENTATION -> throw new ModelProviderException(
                    "a test-and-documentation requirement has no design or implementation step; "
                            + "the TESTS_AND_DOCS plan should have been selected");
            default -> throw new ModelProviderException(
                    "no test-and-docs fixture for agent " + request.agentType());
        };
    }

    private static final String REQUIREMENT = """
            {
              "clarity": "CLEAR",
              "confidence": 0.89,
              "normalized": "Add test coverage for URL validation edge cases that are currently unexercised, and document the API's error responses so callers know what each status means.",
              "openQuestions": [],
              "assumptions": [
                "No production behaviour changes; this records and verifies what the service already does.",
                "If a test reveals the current behaviour is wrong, that is a separate defect to raise rather than to silently fix here."
              ],
              "acceptanceCriteria": [
                "Validation edge cases have explicit tests: userinfo in the URL, oversized input, missing scheme, whitespace",
                "Every error status the API can return is documented with its cause",
                "The build stays green and no production file is modified"
              ],
              "impactedAreas": ["test sources", "documentation"]
            }
            """;

    private static final String REPOSITORY_ANALYSIS = """
            {
              "summary": "UrlValidator enforces a scheme allow-list, a length cap and a host requirement, but the existing ShortenerServiceTest covers only the non-http rejection and the missing-host case. Uncovered: URLs carrying userinfo, input over the length cap, a bare string with no scheme, and whitespace-only input. The controller maps three exception types to 400, 404 and 410, none of which are documented for callers.",
              "impactedModules": [
                "src/test/java/com/example/shortener/service/ - where validation tests belong, alongside the existing service tests",
                "docs/ - no error reference exists yet"
              ],
              "conventions": [
                "Plain JUnit against constructed objects, no Spring context",
                "Test names are sentences describing the behaviour asserted",
                "AssertJ for assertions"
              ],
              "integrationPoints": [
                "UrlValidator.InvalidUrlException is the single failure type these tests assert on"
              ]
            }
            """;

    static final String TESTS = """
            <<<FILE path=src/test/java/com/example/shortener/service/UrlValidatorTest.java op=CREATE>>>
            package com.example.shortener.service;

            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;

            import static org.assertj.core.api.Assertions.assertThatCode;
            import static org.assertj.core.api.Assertions.assertThatThrownBy;

            /**
             * Edge cases for URL validation that the service tests did not reach.
             *
             * <p>These record current behaviour rather than propose new behaviour. Where
             * a case looked wrong it is noted rather than silently changed - altering
             * behaviour under cover of a test-coverage task would hide a real decision.
             */
            class UrlValidatorTest {

                private final UrlValidator validator = new UrlValidator();

                @ParameterizedTest
                @ValueSource(strings = {
                        "https://example.com",
                        "http://example.com/path?query=1",
                        "https://sub.example.co.uk:8443/deep/path"
                })
                void accepts_well_formed_http_and_https_urls(String url) {
                    assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
                }

                @ParameterizedTest
                @ValueSource(strings = {
                        "javascript:alert(1)",
                        "file:///etc/passwd",
                        "ftp://example.com/file",
                        "data:text/html;base64,PHNjcmlwdD4="
                })
                void rejects_schemes_that_are_not_http_or_https(String url) {
                    // An open redirector is a real vulnerability: without the allow-list
                    // the service would lend its own domain's credibility to these.
                    assertThatThrownBy(() -> validator.validate(url))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class)
                            .hasMessageContaining("only http and https");
                }

                @Test
                void rejects_a_url_with_no_scheme_at_all() {
                    assertThatThrownBy(() -> validator.validate("example.com/path"))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class);
                }

                @Test
                void rejects_an_empty_url() {
                    assertThatThrownBy(() -> validator.validate(""))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class)
                            .hasMessageContaining("must not be empty");
                }

                @Test
                void rejects_a_whitespace_only_url() {
                    assertThatThrownBy(() -> validator.validate("   "))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class)
                            .hasMessageContaining("must not be empty");
                }

                @Test
                void rejects_a_null_url() {
                    assertThatThrownBy(() -> validator.validate(null))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class);
                }

                @Test
                void rejects_a_url_longer_than_the_cap() {
                    String tooLong = "https://example.com/" + "a".repeat(2100);

                    assertThatThrownBy(() -> validator.validate(tooLong))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class)
                            .hasMessageContaining("maximum length");
                }

                @Test
                void accepts_a_url_carrying_userinfo() {
                    // Recording current behaviour, not endorsing it. A URL of the form
                    // https://trusted.example@evil.example/ reads as trusted.example to a
                    // human and resolves to evil.example, which is a known phishing shape.
                    // Changing this is a behaviour decision that belongs in its own task.
                    assertThatCode(() -> validator.validate("https://user:pass@example.com"))
                            .doesNotThrowAnyException();
                }

                @Test
                void rejects_a_url_that_is_not_well_formed() {
                    assertThatThrownBy(() -> validator.validate("https://exa mple.com"))
                            .isInstanceOf(UrlValidator.InvalidUrlException.class);
                }
            }
            <<<END>>>
            """;

    private static final String DOCUMENTATION = """
            <<<FILE path=docs/api-errors.md op=CREATE>>>
            # API error responses

            Every non-success status the service returns, what causes it, and what a
            caller should do about it.

            | Status | Endpoint | Cause | Caller action |
            |---|---|---|---|
            | `400 Bad Request` | `POST /api/v1/urls` | URL is empty, malformed, over 2048 characters, missing a host, or uses a scheme other than http/https | Fix the URL and retry |
            | `404 Not Found` | `GET /{shortCode}` | No mapping exists for that code | Do not retry; the code is wrong or was never issued |
            | `404 Not Found` | `GET /api/v1/urls/{code}/stats` | No mapping, or the mapping has expired | Do not retry |
            | `410 Gone` | `GET /{shortCode}` | The mapping existed but its TTL has passed | Do not retry; create a new link |

            ## Why 404 and 410 are different

            `404` means the code was never valid. `410` means it was valid and has
            expired. The distinction matters to callers deciding whether to retry or to
            surface "this link has expired" to a user — collapsing both into `404` would
            make that impossible.

            Note the asymmetry: resolving an expired code returns `410`, while asking
            for its stats returns `404`. Stats filters expired mappings out rather than
            reporting them as gone. This is existing behaviour, recorded here because a
            caller reading both endpoints would otherwise find it surprising.

            ## Scheme restrictions

            Only `http` and `https` may be shortened. `javascript:`, `data:` and `file:`
            are rejected because a shortener that accepts them becomes a way to lend the
            service's own domain credibility to a hostile payload.

            ## Known gap

            URLs carrying userinfo — `https://trusted.example@evil.example/` — are
            currently accepted. That form reads as `trusted.example` to a person and
            resolves to `evil.example`, which is a recognised phishing shape. It is
            documented here rather than changed, because altering it is a behaviour
            decision that belongs in its own task with its own approval.
            <<<END>>>
            """;
}
