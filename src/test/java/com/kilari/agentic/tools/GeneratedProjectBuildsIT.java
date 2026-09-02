package com.kilari.agentic.tools;

import com.kilari.agentic.agent.FileChange;
import com.kilari.agentic.agent.PatchEnvelope;
import com.kilari.agentic.provider.fixtures.GreenfieldFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the platform actually produces a working project, rather than
 * plausible-looking text.
 *
 * <p>This runs a real Gradle build in a temporary workspace, so it is slow and
 * tagged accordingly. It is also the test that matters most: everything else
 * verifies that the orchestrator behaves correctly, while this one verifies that
 * what the orchestrator produces compiles and passes its own tests.
 */
@Tag("integration")
class GeneratedProjectBuildsIT {

    @Test
    @DisplayName("the generated URL shortener compiles and its tests pass")
    void generated_project_builds_green(@TempDir Path tempDir) {
        Path workspacesRoot = tempDir.resolve("workspaces");
        WorkspaceFactory factory = new WorkspaceFactory(workspacesRoot, Path.of("."));
        Path workspace = factory.create("greenfield-build-check");

        List<FileChange> changes = new ArrayList<>();
        changes.addAll(PatchEnvelope.parse(GreenfieldFixture.IMPLEMENTATION));
        changes.addAll(PatchEnvelope.parse(GreenfieldFixture.tests()));

        PatchApplier applier = new PatchApplier(new PathPolicy(workspace));
        PatchApplier.PatchResult result = applier.apply(changes);

        assertThat(result.created()).isNotEmpty();
        assertThat(workspace.resolve("build.gradle.kts")).exists();
        assertThat(workspace.resolve(
                "src/main/java/com/example/shortener/service/ShortenerService.java")).exists();

        BuildValidator.ValidationResult validation = new BuildValidator().validate(workspace);

        assertThat(validation.passed())
                .as("generated project failed to build:%n%s", validation.output())
                .isTrue();
    }

    @Test
    @DisplayName("the workspace gets a working build wrapper the agent cannot author")
    void workspace_receives_an_executable_wrapper(@TempDir Path tempDir) {
        WorkspaceFactory factory = new WorkspaceFactory(tempDir.resolve("ws"), Path.of("."));
        Path workspace = factory.create("wrapper-check");

        assertThat(workspace.resolve("gradlew")).exists();
        assertThat(Files.isExecutable(workspace.resolve("gradlew"))).isTrue();
        assertThat(workspace.resolve("gradle/wrapper/gradle-wrapper.jar")).exists();
    }
}
