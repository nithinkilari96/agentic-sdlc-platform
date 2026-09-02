package com.kilari.agentic.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes the project's build inside a workspace and turns the result into
 * structured evidence.
 *
 * <p>This is the platform's only executable capability, and it is deliberately
 * not a general command runner. The model never supplies a command, arguments,
 * or a working directory — it cannot, because none of those are parameters of
 * this class. The command is fixed at construction, the directory is the
 * workspace, and the result is data. That is the whole difference between an
 * agent that can validate its work and an agent that can run anything it likes.
 */
public class BuildValidator {

    private static final Logger log = LoggerFactory.getLogger(BuildValidator.class);

    /** Retained output lines per stream. Enough to diagnose, bounded enough to prompt with. */
    private static final int MAX_OUTPUT_LINES = 400;

    /**
     * Environment variables stripped before the build runs. A generated build
     * script executes arbitrary code by design, so it must not inherit the
     * platform's credentials — a malicious or merely careless build file should
     * find nothing worth exfiltrating.
     */
    private static final List<String> CREDENTIAL_PREFIXES = List.of(
            "ANTHROPIC", "OPENAI", "AWS", "GITHUB", "GH_", "GOOGLE", "AZURE",
            "TOKEN", "SECRET", "PASSWORD", "PASSWD", "APIKEY", "API_KEY", "CREDENTIAL");

    private final List<String> command;
    private final Duration timeout;

    public BuildValidator() {
        this(List.of("./gradlew", "test", "--console=plain", "--no-daemon"), Duration.ofMinutes(10));
    }

    public BuildValidator(List<String> command, Duration timeout) {
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    public ValidationResult validate(Path workspace) {
        if (!Files.exists(workspace)) {
            throw new IllegalArgumentException("workspace does not exist: " + workspace);
        }

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);

        stripCredentials(builder.environment());

        Instant start = Instant.now();
        Process process = null;
        try {
            process = builder.start();

            Deque<String> output = new ArrayDeque<>();
            long totalLines = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalLines++;
                    output.addLast(line);
                    // Keep a bounded tail. A runaway build must not exhaust heap,
                    // and the end of the log is where failures are described.
                    if (output.size() > MAX_OUTPUT_LINES) {
                        output.removeFirst();
                    }
                }
            }

            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            Duration elapsed = Duration.between(start, Instant.now());

            if (!finished) {
                process.destroyForcibly();
                log.warn("Build timed out after {} in {}", timeout, workspace);
                return new ValidationResult(false, -1, elapsed,
                        String.join("\n", output),
                        totalLines > MAX_OUTPUT_LINES,
                        "build exceeded the %s timeout and was terminated".formatted(timeout));
            }

            int exitCode = process.exitValue();
            String log_ = String.join("\n", output);
            boolean passed = exitCode == 0;

            log.info("Build {} in {}ms (exit {})", passed ? "passed" : "failed",
                    elapsed.toMillis(), exitCode);

            return new ValidationResult(passed, exitCode, elapsed, log_,
                    totalLines > MAX_OUTPUT_LINES,
                    passed ? null : summariseFailure(log_, exitCode));

        } catch (IOException e) {
            Duration elapsed = Duration.between(start, Instant.now());
            return new ValidationResult(false, -1, elapsed, "", false,
                    "could not start build process: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("interrupted while waiting for build", e);
        }
    }

    /**
     * Removes anything credential-shaped from the environment the build inherits.
     *
     * <p>Package-private and static so it can be tested directly against a
     * synthetic environment. Verifying this through a real subprocess would mean
     * injecting a fake secret into the JVM's own environment, which is not
     * possible from inside the test — and a security control that cannot be
     * tested tends to become a security control that does not work.
     */
    static void stripCredentials(Map<String, String> environment) {
        environment.keySet().removeIf(key -> {
            String upper = key.toUpperCase(Locale.ROOT);
            return CREDENTIAL_PREFIXES.stream().anyMatch(upper::contains);
        });
    }

    /**
     * Extracts the part of the log worth feeding to a repair agent.
     *
     * <p>Sending a whole build log wastes context and buries the signal. The
     * compiler and test-failure lines are what the repair actually needs.
     */
    private String summariseFailure(String buildLog, int exitCode) {
        List<String> interesting = buildLog.lines()
                .filter(line -> line.contains("error:")
                        || line.contains("FAILED")
                        || line.contains("Test >")
                        || line.contains("Caused by:")
                        || line.contains("cannot find symbol")
                        || line.contains("expected:")
                        || line.contains("but was:")
                        || line.startsWith("* What went wrong"))
                .limit(40)
                .toList();

        if (interesting.isEmpty()) {
            return "build failed with exit code %d; no recognisable compiler or test diagnostics"
                    .formatted(exitCode);
        }
        return String.join("\n", interesting);
    }

    /** The build outcome, as evidence rather than a boolean. */
    public record ValidationResult(
            boolean passed,
            int exitCode,
            Duration elapsed,
            String output,
            boolean outputTruncated,
            String failureSummary) {

        public String evidence() {
            return passed
                    ? "build passed in %dms".formatted(elapsed.toMillis())
                    : failureSummary;
        }
    }
}
