package com.kilari.agentic.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Durable storage for workspace snapshots.
 *
 * <p>Without this, rollback works only for as long as the process lives. A crash
 * after a patch was applied would leave the next process with a modified
 * workspace and no record of what it looked like before — and worse, re-running
 * the interrupted task would capture the <em>already-modified</em> tree as its
 * new baseline. A later rollback would then faithfully restore the broken state,
 * which is a more dangerous failure than having no rollback at all, because it
 * reports success.
 *
 * <p>Snapshots are stored outside the workspace they describe. Keeping them
 * inside would mean a restore walks its own backup, and the first rollback would
 * delete the evidence needed for the second.
 */
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private static final String MANIFEST = "manifest.txt";
    private static final String FILES_DIR = "files";

    private final Path root;

    public SnapshotStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Writes a snapshot to disk so it outlives the process that captured it. */
    public void save(String workflowId, String taskId, WorkspaceSnapshot snapshot) {
        Path dir = directoryFor(workflowId, taskId);
        try {
            deleteRecursively(dir);
            Files.createDirectories(dir.resolve(FILES_DIR));

            StringBuilder manifest = new StringBuilder();
            for (Map.Entry<String, byte[]> entry : snapshot.contents().entrySet()) {
                Path target = dir.resolve(FILES_DIR).resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
                manifest.append(WorkspaceSnapshot.hash(entry.getValue()))
                        .append(' ')
                        .append(entry.getKey())
                        .append('\n');
            }

            // Manifest last: a directory without one is an incomplete write, and
            // load() treats it as absent rather than restoring a partial snapshot.
            Files.writeString(dir.resolve(MANIFEST), manifest.toString(), StandardCharsets.UTF_8);

            log.debug("Persisted snapshot {}/{} ({} files)", workflowId, taskId,
                    snapshot.contents().size());
        } catch (IOException e) {
            throw new UncheckedIOException("could not persist snapshot " + dir, e);
        }
    }

    /**
     * Reads a snapshot back, or empty if none was fully written.
     *
     * @param workspace the workspace the snapshot describes, which the restored
     *                  snapshot will write back to
     */
    public Optional<WorkspaceSnapshot> load(String workflowId, String taskId, Path workspace) {
        Path dir = directoryFor(workflowId, taskId);
        Path manifest = dir.resolve(MANIFEST);

        if (!Files.exists(manifest)) {
            return Optional.empty();
        }

        try {
            Map<String, byte[]> contents = new LinkedHashMap<>();
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int split = line.indexOf(' ');
                String expectedHash = line.substring(0, split);
                String relative = line.substring(split + 1);

                byte[] content = Files.readAllBytes(dir.resolve(FILES_DIR).resolve(relative));
                if (!WorkspaceSnapshot.hash(content).equals(expectedHash)) {
                    // The backup itself is corrupt. Restoring from it would produce a
                    // workspace that matches nothing, so refuse rather than guess.
                    throw new IllegalStateException(
                            "persisted snapshot %s/%s is corrupt at %s"
                                    .formatted(workflowId, taskId, relative));
                }
                contents.put(relative, content);
            }

            log.info("Recovered snapshot {}/{} from disk ({} files)",
                    workflowId, taskId, contents.size());
            return Optional.of(WorkspaceSnapshot.fromContents(taskId, workspace, contents));

        } catch (IOException e) {
            throw new UncheckedIOException("could not read snapshot " + dir, e);
        }
    }

    /** Removes every snapshot for a workflow once it has reached a terminal state. */
    public void deleteAll(String workflowId) {
        deleteRecursively(root.resolve(workflowId));
    }

    private Path directoryFor(String workflowId, String taskId) {
        return root.resolve(workflowId).resolve(taskId);
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not clear snapshot directory " + path, e);
        }
    }

    public Path root() {
        return root;
    }
}
