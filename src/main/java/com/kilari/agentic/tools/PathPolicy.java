package com.kilari.agentic.tools;

import com.kilari.agentic.agent.FileChange;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a proposed file change is allowed to touch the filesystem.
 *
 * <p>Every check here assumes the proposal is untrusted. It was produced by a
 * language model from a prompt that may itself contain attacker-controlled text
 * — a requirement description, a repository file, a build error — so a path like
 * {@code ../../../.ssh/authorized_keys} is a case to be rejected by
 * construction, not an unlikely accident.
 *
 * <p>The policy is an allow-list. Anything not explicitly permitted is denied,
 * which means a capability the platform gains later has to be granted
 * deliberately rather than inherited by omission.
 */
public class PathPolicy {

    /** File types an engineering agent has any legitimate reason to write. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java", ".kts", ".kt", ".gradle", ".xml", ".yml", ".yaml",
            ".properties", ".md", ".json", ".txt", ".sql");

    /**
     * Paths that are refused even inside the workspace. Build scripts and
     * wrappers are executable surface: a patch that rewrites gradlew or drops a
     * malicious plugin into settings.gradle.kts turns the validation step into
     * arbitrary code execution.
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "gradlew", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradle/wrapper/gradle-wrapper.jar");

    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final int MAX_FILES_PER_PATCH = 60;

    private final Path workspaceRoot;

    public PathPolicy(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * Validates an entire patch before any of it is applied.
     *
     * <p>All-or-nothing on purpose: applying the safe half of a patch and
     * rejecting the rest would leave the workspace in a state no agent intended
     * and no snapshot describes.
     */
    public void validatePatch(List<FileChange> changes) {
        if (changes.isEmpty()) {
            throw new PolicyViolationException("patch contains no file changes");
        }
        if (changes.size() > MAX_FILES_PER_PATCH) {
            throw new PolicyViolationException(
                    "patch touches %d files, exceeding the limit of %d"
                            .formatted(changes.size(), MAX_FILES_PER_PATCH));
        }

        Set<String> seen = new HashSet<>();
        for (FileChange change : changes) {
            if (!seen.add(change.path())) {
                // Two changes to one path make the final content order-dependent,
                // and the applier's recorded hash would describe only one of them.
                throw new PolicyViolationException(
                        "patch contains duplicate changes for path: " + change.path());
            }
            validateChange(change);
        }
    }

    public void validateChange(FileChange change) {
        String rawPath = change.path();

        if (rawPath.isBlank()) {
            throw new PolicyViolationException("empty path");
        }
        if (rawPath.startsWith("/") || rawPath.startsWith("~")) {
            throw new PolicyViolationException("absolute paths are not permitted: " + rawPath);
        }
        if (rawPath.contains("\0")) {
            throw new PolicyViolationException("path contains a null byte: " + rawPath);
        }
        if (PROTECTED_PATHS.contains(rawPath)) {
            throw new PolicyViolationException(
                    "refusing to modify build wrapper or its configuration: " + rawPath);
        }

        // Resolve then re-check containment. Checking for ".." textually is not
        // enough — symlinks and encoded variants slip past string matching, while
        // a normalized absolute path either sits under the root or it does not.
        Path resolved = workspaceRoot.resolve(rawPath).normalize().toAbsolutePath();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new PolicyViolationException(
                    "path escapes the workspace: %s resolves outside %s"
                            .formatted(rawPath, workspaceRoot));
        }
        if (resolved.equals(workspaceRoot)) {
            throw new PolicyViolationException("cannot write to the workspace root itself");
        }

        String lower = rawPath.toLowerCase(Locale.ROOT);
        boolean extensionAllowed = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!extensionAllowed) {
            throw new PolicyViolationException(
                    "file type not permitted for agent-authored changes: " + rawPath);
        }

        if (change.sizeBytes() > MAX_FILE_BYTES) {
            throw new PolicyViolationException(
                    "file %s is %d bytes, exceeding the %d byte limit"
                            .formatted(rawPath, change.sizeBytes(), MAX_FILE_BYTES));
        }
    }

    /** Resolves a validated change to its absolute path. */
    public Path resolve(FileChange change) {
        validateChange(change);
        return workspaceRoot.resolve(change.path()).normalize().toAbsolutePath();
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public static class PolicyViolationException extends RuntimeException {
        public PolicyViolationException(String message) {
            super(message);
        }
    }
}
