package com.kilari.agentic.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    /**
     * Directories not worth snapshotting. Build output is regenerated anyway, and
     * the gradle wrapper is platform-managed — {@link PathPolicy} forbids agents
     * from writing it, so it cannot change and does not need restoring.
     */
    private static final List<String> EXCLUDED_DIRS =
            List.of(".git", "build", ".gradle", "out", "gradle");

    private final String snapshotId;
    private final Path root;
    private final Map<String, byte[]> contents;
    private final Map<String, String> hashes;
    private final Instant takenAt;

    private WorkspaceSnapshot(String snapshotId, Path root,
                             Map<String, byte[]> contents, Map<String, String> hashes) {
        this.snapshotId = snapshotId;
        this.root = root;
        this.contents = Map.copyOf(contents);
        this.hashes = Map.copyOf(hashes);
        this.takenAt = Instant.now();
    }

    public static WorkspaceSnapshot capture(String snapshotId, Path root) {
        // Bytes rather than text: a workspace legitimately contains binaries, and
        // reading one as UTF-8 both corrupts it and throws. A snapshot that cannot
        // represent every file is not a restore point.
        Map<String, byte[]> contents = new HashMap<>();
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
                    byte[] content = Files.readAllBytes(file);
                    contents.put(relative, content);
                    hashes.put(relative, sha256(content));
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
            for (Map.Entry<String, byte[]> entry : contents.entrySet()) {
                Path target = root.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
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
                String actual = sha256(Files.readAllBytes(file));
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

    /** The captured files, for {@link SnapshotStore} to persist. */
    Map<String, byte[]> contents() {
        return contents;
    }

    /** Rebuilds a snapshot read back from disk. */
    static WorkspaceSnapshot fromContents(String snapshotId, Path root, Map<String, byte[]> contents) {
        Map<String, String> hashes = new HashMap<>();
        contents.forEach((path, content) -> hashes.put(path, sha256(content)));
        return new WorkspaceSnapshot(snapshotId, root, contents, hashes);
    }

    /** Exposed so the store can verify a persisted snapshot has not rotted. */
    static String hash(byte[] content) {
        return sha256(content);
    }

    /** Content hash over raw bytes, so binaries hash as reliably as source files. */
    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
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
