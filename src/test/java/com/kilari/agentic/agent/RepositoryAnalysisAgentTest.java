package com.kilari.agentic.agent;

import com.kilari.agentic.tools.WorkspaceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryAnalysisAgentTest {

    private final RepositoryAnalysisAgent agent = new RepositoryAnalysisAgent(null);

    @Test
    @DisplayName("a freshly created workspace reads as empty despite the installed build wrapper")
    void wrapper_files_do_not_make_a_greenfield_workspace_look_occupied(@TempDir Path tempDir) {
        // The platform installs gradlew and gradle/wrapper/* into every workspace.
        // gradle-wrapper.properties would otherwise be picked up by the digest,
        // and the agent would report an existing codebase with conventions that
        // belong to the platform rather than to the project.
        WorkspaceFactory factory = new WorkspaceFactory(tempDir.resolve("ws"), Path.of("."));
        Path workspace = factory.create("greenfield-digest-check");

        assertThat(workspace.resolve("gradle/wrapper/gradle-wrapper.properties")).exists();
        assertThat(agent.buildDigest(workspace))
                .as("an empty repository must read as empty")
                .isEmpty();
    }

    @Test
    @DisplayName("real project files are included in the digest")
    void project_sources_are_included(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("src/main/java/com/example"));
        Files.writeString(workspace.resolve("src/main/java/com/example/Thing.java"),
                "package com.example;\npublic class Thing {}\n", StandardCharsets.UTF_8);

        String digest = agent.buildDigest(workspace);

        assertThat(digest)
                .contains("src/main/java/com/example/Thing.java")
                .contains("public class Thing {}");
    }

    @Test
    @DisplayName("build output is excluded so the agent reasons about sources, not artifacts")
    void build_output_is_excluded(@TempDir Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("build/classes"));
        Files.writeString(workspace.resolve("build/classes/Generated.java"),
                "class Generated {}", StandardCharsets.UTF_8);

        assertThat(agent.buildDigest(workspace)).isEmpty();
    }
}
