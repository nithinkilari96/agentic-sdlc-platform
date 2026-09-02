package com.kilari.agentic.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rollback has to survive losing the process, not just losing a task.
 *
 * <p>An in-memory-only snapshot means a crash after a patch was applied leaves
 * the next process with a modified workspace and no record of the original. The
 * dangerous part is not the missing rollback — it is that re-running the
 * interrupted task would capture the already-modified tree as its baseline, so a
 * later rollback would faithfully restore the broken state and report success.
 */
class SnapshotDurabilityTest {

    @Test
    @DisplayName("a snapshot taken before a crash can be restored by a different process")
    void snapshot_outlives_the_process_that_took_it(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path source = workspace.resolve("Service.java");
        Files.writeString(source, "class Service { /* original */ }", StandardCharsets.UTF_8);

        SnapshotStore store = new SnapshotStore(tempDir.resolve("snapshots"));
        store.save("wf-1", "patch-apply", WorkspaceSnapshot.capture("patch-apply", workspace));

        // The patch lands, then the process dies. Everything in memory is gone.
        Files.writeString(source, "class Service { /* half-applied garbage */ }",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("Leftover.java"), "class Leftover {}",
                StandardCharsets.UTF_8);

        // A new process, holding nothing but the workspace path and the store.
        SnapshotStore afterRestart = new SnapshotStore(tempDir.resolve("snapshots"));
        WorkspaceSnapshot recovered =
                afterRestart.load("wf-1", "patch-apply", workspace).orElseThrow();

        recovered.restore();

        assertThat(Files.readString(source)).isEqualTo("class Service { /* original */ }");
        assertThat(workspace.resolve("Leftover.java"))
                .as("files created by the interrupted patch must be removed")
                .doesNotExist();
    }

    @Test
    @DisplayName("binary content survives the round trip to disk")
    void binary_content_round_trips(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        byte[] binary = {0x00, (byte) 0xFF, (byte) 0xC3, 0x28, 0x7F, (byte) 0x80};
        Files.write(workspace.resolve("artifact.bin"), binary);

        SnapshotStore store = new SnapshotStore(tempDir.resolve("snapshots"));
        store.save("wf-2", "patch-apply", WorkspaceSnapshot.capture("patch-apply", workspace));

        Files.write(workspace.resolve("artifact.bin"), new byte[]{0x01});
        store.load("wf-2", "patch-apply", workspace).orElseThrow().restore();

        assertThat(Files.readAllBytes(workspace.resolve("artifact.bin"))).isEqualTo(binary);
    }

    @Test
    @DisplayName("a snapshot whose write was interrupted is treated as absent, not partial")
    void an_incomplete_snapshot_is_not_offered(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("A.java"), "original", StandardCharsets.UTF_8);

        Path snapshotRoot = tempDir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(snapshotRoot);
        store.save("wf-3", "patch-apply", WorkspaceSnapshot.capture("patch-apply", workspace));

        // The manifest is written last, so losing it is what an interrupted write
        // looks like. Restoring from the files alone would restore an unknown subset.
        Files.delete(snapshotRoot.resolve("wf-3").resolve("patch-apply").resolve("manifest.txt"));

        assertThat(store.load("wf-3", "patch-apply", workspace))
                .as("a partially written snapshot must not be used as a restore point")
                .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("a corrupted snapshot is refused rather than restored")
    void a_corrupt_snapshot_is_refused(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("A.java"), "original", StandardCharsets.UTF_8);

        Path snapshotRoot = tempDir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(snapshotRoot);
        store.save("wf-4", "patch-apply", WorkspaceSnapshot.capture("patch-apply", workspace));

        // Disk rot, or a careless hand. Either way the backup no longer matches its
        // manifest, and restoring from it would produce a state matching nothing.
        Files.writeString(
                snapshotRoot.resolve("wf-4").resolve("patch-apply").resolve("files").resolve("A.java"),
                "tampered", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> store.load("wf-4", "patch-apply", workspace))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    @DisplayName("snapshots are stored outside the workspace they describe")
    void snapshots_do_not_live_inside_the_workspace(@TempDir Path tempDir) throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("A.java"), "original", StandardCharsets.UTF_8);

        SnapshotStore store = new SnapshotStore(tempDir.resolve("snapshots"));
        store.save("wf-5", "patch-apply", WorkspaceSnapshot.capture("patch-apply", workspace));

        // If the snapshot lived inside the workspace, a restore would walk its own
        // backup and the first rollback would delete what the second one needs.
        assertThat(store.root().startsWith(workspace)).isFalse();
        try (var entries = Files.list(workspace)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .containsExactly("A.java");
        }
    }
}
