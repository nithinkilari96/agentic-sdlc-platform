package com.kilari.agentic.tools;

import com.kilari.agentic.agent.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only component in the platform that writes agent-authored content to disk.
 *
 * <p>Concentrating filesystem mutation in one place is what makes the security
 * story checkable. There is exactly one method to audit, one policy to satisfy,
 * and one snapshot taken before anything changes — rather than a dozen call
 * sites each deciding for themselves whether a path looks safe.
 */
public class PatchApplier {

    private static final Logger log = LoggerFactory.getLogger(PatchApplier.class);

    private final PathPolicy policy;

    public PatchApplier(PathPolicy policy) {
        this.policy = policy;
    }

    /**
     * Applies a patch atomically with respect to policy: the whole patch is
     * validated before a single byte is written, so a rejected change cannot
     * leave a half-applied workspace behind.
     *
     * @param expectedBaseHashes optimistic-lock expectations, keyed by path. If a
     *                           file's current content does not hash to the value
     *                           recorded when the agent read it, someone else has
     *                           changed it and the patch is refused rather than
     *                           blindly overwriting concurrent work.
     */
    public PatchResult apply(List<FileChange> changes, Map<String, String> expectedBaseHashes) {
        policy.validatePatch(changes);
        verifyOptimisticLocks(changes, expectedBaseHashes);

        Map<String, String> writtenHashes = new LinkedHashMap<>();
        List<String> created = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> deleted = new ArrayList<>();

        try {
            for (FileChange change : changes) {
                Path target = policy.resolve(change);

                switch (change.operation()) {
                    case CREATE, MODIFY -> {
                        boolean existed = Files.exists(target);
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, change.content(), StandardCharsets.UTF_8);
                        writtenHashes.put(change.path(), change.contentHash());
                        if (existed) {
                            modified.add(change.path());
                        } else {
                            created.add(change.path());
                        }
                    }
                    case DELETE -> {
                        if (Files.deleteIfExists(target)) {
                            deleted.add(change.path());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed applying patch", e);
        }

        verifyWrites(writtenHashes);

        log.info("Applied patch: {} created, {} modified, {} deleted",
                created.size(), modified.size(), deleted.size());
        return new PatchResult(created, modified, deleted, writtenHashes);
    }

    public PatchResult apply(List<FileChange> changes) {
        return apply(changes, Map.of());
    }

    /**
     * Confirms each file still holds the content the agent based its change on.
     *
     * <p>Without this, a repair agent working from a stale read would silently
     * clobber whatever the previous attempt wrote.
     */
    private void verifyOptimisticLocks(List<FileChange> changes, Map<String, String> expectedBaseHashes) {
        if (expectedBaseHashes.isEmpty()) {
            return;
        }
        for (FileChange change : changes) {
            String expected = expectedBaseHashes.get(change.path());
            if (expected == null) {
                continue;
            }
            Optional<String> actual = currentHash(change.path());
            if (actual.isEmpty()) {
                throw new OptimisticLockException(
                        "expected %s to exist with hash %s, but it is absent"
                                .formatted(change.path(), abbreviate(expected)));
            }
            if (!actual.get().equals(expected)) {
                throw new OptimisticLockException(
                        "%s changed since the agent read it (expected %s, found %s)"
                                .formatted(change.path(), abbreviate(expected), abbreviate(actual.get())));
            }
        }
    }

    /** Re-reads what was just written and confirms it matches the proposal. */
    private void verifyWrites(Map<String, String> writtenHashes) {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : writtenHashes.entrySet()) {
            Optional<String> actual = currentHash(entry.getKey());
            if (actual.isEmpty() || !actual.get().equals(entry.getValue())) {
                mismatches.add(entry.getKey());
            }
        }
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException(
                    "post-write verification failed for: " + String.join(", ", mismatches));
        }
    }

    public Optional<String> currentHash(String relativePath) {
        Path file = policy.workspaceRoot().resolve(relativePath);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(FileChange.sha256(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    public record PatchResult(List<String> created, List<String> modified,
                              List<String> deleted, Map<String, String> writtenHashes) {

        public int totalChanges() {
            return created.size() + modified.size() + deleted.size();
        }

        public String summary() {
            return "%d created, %d modified, %d deleted"
                    .formatted(created.size(), modified.size(), deleted.size());
        }
    }

    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }
}
