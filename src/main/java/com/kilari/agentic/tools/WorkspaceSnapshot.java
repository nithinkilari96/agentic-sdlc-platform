package com.kilari.agentic.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.kilari.agentic.agent.FileChange;

/**
 * An immutable copy of the workspace contents, taken before a mutating step.
 *
 * <p>Rollback is only a real control if it can be shown to have worked. This
 * captures a content hash per file alongside the content itself, so restoring
 * can verify afterwards that every file matches the snapshot — a rollback that
 * silently half-succeeded is worse than none, because the run would continue on
 * a workspace nobody has an accurate description of.
 */
public class WorkspaceSnapshot {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSnapshot.class);

    /** Directories never worth snapshotting; build output is regenerated anyway. */
    private static final List<String> EXCLUDED_DIRS = List.of(".git", "build", ".gradle", "out");

    private final String snapshotId;
    private final Path root;
    private final Map<String, String> contents;
    private final Map<String, String> hashes;
    private final Instant takenAt;

    private WorkspaceSnapshot(String snapshotId, Path root,
                             Map<String, String> contents, Map<String, String> hashes) {
        this.snapshotId = snapshotId;
        this.root = root;
        this.contents = Map.copyOf(contents);
        this.hashes = Map.copyOf(hashes);
        this.takenAt = Instant.now();
    }

    public static WorkspaceSnapshot capture(String snapshotId, Path root) {
        Map<String, String> contents = new HashMap<>();
        Map<String, String> hashes = new HashMap<>();

        if (!Files.exists(root)) {
            return new WorkspaceSnapshot(snapshotId, root, contents, hashes);
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return EXCLUDED_DIRS.contains(name)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = root.relativize(file).toString();
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    contents.put(relative, content);
                    hashes.put(relative, FileChange.sha256(content));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // A file we cannot read is a file we cannot restore. Recording it
                    // as absent would make a later rollback quietly delete it.
                    throw new UncheckedIOException(
                            new IOException("cannot snapshot unreadable file " + file, exc));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to snapshot workspace " + root, e);
        }

        log.debug("Captured snapshot {} of {} files under {}", snapshotId, contents.size(), root);
        return new WorkspaceSnapshot(snapshotId, root, contents, hashes);
    }

    /**
     * Restores the workspace to this snapshot and verifies the result.
     *
     * @return the outcome, including which files were restored and removed
     * @throws RollbackVerificationException if the restored tree does not match
     */
    public RollbackResult restore() {
        List<String> restored = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        try {
            // Remove anything that did not exist when the snapshot was taken.
            if (Files.exists(root)) {
                List<Path> currentFiles = new ArrayList<>();
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        return EXCLUDED_DIRS.contains(name)
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        currentFiles.add(file);
                        return FileVisitResult.CONTINUE;
                    }
                });

                for (Path file : currentFiles) {
                    String relative = root.relativize(file).toString();
                    if (!contents.containsKey(relative)) {
                        Files.delete(file);
                        removed.add(relative);
                    }
                }
            }

            // Restore every file the snapshot knows about.
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                Path target = root.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                restored.add(entry.getKey());
            }

        } catch (IOException e) {
            throw new UncheckedIOException("rollback failed for snapshot " + snapshotId, e);
        }

        verify();
        log.info("Rollback to snapshot {} verified: {} files restored, {} removed",
                snapshotId, restored.size(), removed.size());
        return new RollbackResult(snapshotId, restored, removed, true);
    }

    /**
     * Re-hashes every file and compares against the snapshot.
     *
     * <p>This is the difference between claiming a rollback and having one.
     */
    public void verify() {
        List<String> mismatches = new ArrayList<>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            Path file = root.resolve(entry.getKey());
            try {
                if (!Files.exists(file)) {
                    mismatches.add(entry.getKey() + " (missing after restore)");
                    continue;
                }
                String actual = FileChange.sha256(Files.readString(file, StandardCharsets.UTF_8));
                if (!actual.equals(entry.getValue())) {
                    mismatches.add(entry.getKey() + " (content hash mismatch)");
                }
            } catch (IOException e) {
                mismatches.add(entry.getKey() + " (unreadable: " + e.getMessage() + ")");
            }
        }

        if (!mismatches.isEmpty()) {
            throw new RollbackVerificationException(
                    "rollback verification failed for snapshot %s: %s"
                            .formatted(snapshotId, String.join(", ", mismatches)));
        }
    }

    public String snapshotId() {
        return snapshotId;
    }

    public int fileCount() {
        return contents.size();
    }

    public Instant takenAt() {
        return takenAt;
    }

    public record RollbackResult(String snapshotId, List<String> restored,
                                 List<String> removed, boolean verified) {
    }

    public static class RollbackVerificationException extends RuntimeException {
        public RollbackVerificationException(String message) {
            super(message);
        }
    }
}
