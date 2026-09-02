package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.provider.ModelProvider;
import com.kilari.agentic.provider.ModelRequest;
import com.kilari.agentic.provider.ModelResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Grounds brownfield work in the code that actually exists.
 *
 * <p>Without this step an agent modifying an existing service writes whatever
 * its training suggests is idiomatic, which is how you end up with a change that
 * compiles, passes review at a glance, and quietly contradicts every convention
 * around it. Reading the repository first is what makes the difference between
 * generating code and modifying a codebase.
 *
 * <p>The repository digest is assembled by deterministic code, not requested
 * from the model. The model receives what the platform chose to show it — it
 * cannot ask to read an arbitrary path.
 */
public class RepositoryAnalysisAgent implements Agent {

    /** Enough for the model to infer conventions without flooding the context. */
    private static final int MAX_FILES_IN_DIGEST = 40;
    private static final int MAX_BYTES_PER_FILE = 4_000;
    /**
     * Excluded from the digest. Alongside build output, this skips the gradle
     * wrapper: the platform installs it into every workspace, so including it
     * would make a genuinely empty greenfield repository look like an existing
     * codebase — and the agent would describe conventions that are the
     * platform's, not the project's.
     */
    private static final List<String> SKIPPED_DIRS =
            List.of(".git", "build", ".gradle", "out", "gradle");

    private static final String SYSTEM_PROMPT = """
            You are a software architect examining an existing codebase before it is modified.

            Identify the structure, conventions and integration points that a change \
            must respect. Describe what is actually there, not what good practice would \
            suggest should be there.

            Respond with JSON only, no prose and no code fences:
            {
              "summary": "<how the codebase is organised and how data flows through it>",
              "impactedModules": ["<path or component> - <why this change touches it>"],
              "conventions": ["<convention the change must follow>"],
              "integrationPoints": ["<existing seam the change should use>"]
            }
            """;

    private final ModelProvider provider;

    public RepositoryAnalysisAgent(ModelProvider provider) {
        this.provider = provider;
    }

    @Override
    public AgentType type() {
        return AgentType.REPOSITORY_ANALYSIS;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        String digest = buildDigest(execution.workspace());

        String prompt = """
                Requirement:
                %s

                Normalized requirement:
                %s

                Existing repository contents:
                %s
                """.formatted(
                execution.effectiveRequirement(),
                execution.context().content(ContextKeys.REQUIREMENT_NORMALIZED).orElse("(not available)"),
                digest);

        ModelResponse response = provider.complete(ModelRequest.of(
                AgentType.REPOSITORY_ANALYSIS,
                execution.workflowId(),
                execution.taskId(),
                execution.effectiveRequirement(),
                SYSTEM_PROMPT,
                prompt));

        if (response.refused()) {
            return AgentOutcome.safeStop("The model declined to analyse the repository.");
        }

        // Validated as JSON so a malformed response fails here rather than
        // producing a downstream prompt containing an error message.
        String json = RequirementAgent.extractJson(response.text());

        return AgentOutcome.carryOn(
                "Analysed repository (%s)".formatted(
                        digest.isBlank() ? "empty - greenfield" : "existing codebase"),
                Map.of(ContextKeys.REPOSITORY_ANALYSIS, json));
    }

    /**
     * Assembles a bounded description of the repository.
     *
     * <p>Returns empty for a greenfield run, which is itself the signal that
     * there are no existing conventions to honour.
     */
    String buildDigest(Path workspace) {
        if (workspace == null || !Files.exists(workspace)) {
            return "";
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(workspace)) {
            files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        Path relative = workspace.relativize(path);
                        return SKIPPED_DIRS.stream().noneMatch(skip ->
                                relative.startsWith(skip) || relative.toString().contains("/" + skip + "/"));
                    })
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".kts")
                                || name.endsWith(".md") || name.endsWith(".properties")
                                || name.endsWith(".yml");
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_FILES_IN_DIGEST)
                    .toList();
        } catch (IOException e) {
            return "(repository could not be read: " + e.getMessage() + ")";
        }

        if (files.isEmpty()) {
            return "";
        }

        List<String> sections = new ArrayList<>();
        for (Path file : files) {
            String relative = workspace.relativize(file).toString();
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                sections.add("--- %s ---%n(unreadable)".formatted(relative));
                continue;
            }
            boolean truncated = content.length() > MAX_BYTES_PER_FILE;
            if (truncated) {
                content = content.substring(0, MAX_BYTES_PER_FILE);
            }
            sections.add("--- %s ---%n%s%s".formatted(
                    relative, content, truncated ? "\n... (truncated)" : ""));
        }

        return String.join("\n\n", sections);
    }
}
