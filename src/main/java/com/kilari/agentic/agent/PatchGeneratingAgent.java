package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.provider.ModelProvider;
import com.kilari.agentic.provider.ModelRequest;
import com.kilari.agentic.provider.ModelResponse;
import com.kilari.agentic.tools.PathPolicy;

import java.util.List;
import java.util.Map;

/**
 * Shared behaviour for the agents that produce code, tests, documentation or
 * repairs.
 *
 * <p>All four differ only in what they are asked for and where their output is
 * stored; the mechanics — prompt the model, parse the envelope, check the
 * proposal against policy — are identical, and duplicating them four times would
 * mean four places for the policy check to be forgotten.
 *
 * <p>Note what happens at the end of {@link #execute}: the patch is validated
 * against {@link PathPolicy} but <em>not</em> applied. Generation and mutation
 * are separate tasks in the graph, so a proposal can be inspected, superseded by
 * a re-plan, or discarded without anything having touched the filesystem.
 */
public class PatchGeneratingAgent implements Agent {

    private final AgentType type;
    private final String contextKey;
    private final String systemPrompt;
    private final ModelProvider provider;

    public PatchGeneratingAgent(AgentType type, String contextKey, String systemPrompt,
                                ModelProvider provider) {
        this.type = type;
        this.contextKey = contextKey;
        this.systemPrompt = systemPrompt;
        this.provider = provider;
    }

    @Override
    public AgentType type() {
        return type;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        ModelResponse response = provider.complete(ModelRequest.of(
                type,
                execution.workflowId(),
                execution.taskId(),
                execution.effectiveRequirement(),
                systemPrompt,
                buildPrompt(execution)));

        if (response.refused()) {
            return AgentOutcome.safeStop(
                    "The model declined to generate %s output.".formatted(type));
        }

        List<FileChange> changes = PatchEnvelope.parse(response.text());
        if (changes.isEmpty()) {
            throw new AgentOutputException(
                    "%s agent produced no parseable file changes".formatted(type));
        }

        // Checked now rather than at apply time so a policy violation is
        // attributed to the agent that proposed it, and so a rejected proposal
        // never reaches the component that writes to disk. Rooted at this run's
        // own workspace — a policy scoped any wider would accept a path pointing
        // into a sibling run.
        new PathPolicy(execution.workspace()).validatePatch(changes);

        return AgentOutcome.carryOn(
                "%s proposed %d file change(s): %s".formatted(
                        type,
                        changes.size(),
                        changes.stream().map(FileChange::path).limit(6).toList()),
                Map.of(contextKey, PatchEnvelope.render(changes)));
    }

    private String buildPrompt(AgentExecution execution) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Requirement:\n").append(execution.effectiveRequirement()).append("\n\n");

        execution.context().content(ContextKeys.REQUIREMENT_NORMALIZED)
                .ifPresent(v -> prompt.append("Normalized requirement:\n").append(v).append("\n\n"));
        execution.context().content(ContextKeys.REQUIREMENT_CRITERIA)
                .ifPresent(v -> prompt.append("Acceptance criteria:\n").append(v).append("\n\n"));
        execution.context().content(ContextKeys.REPOSITORY_ANALYSIS)
                .ifPresent(v -> prompt.append("Repository analysis:\n").append(v).append("\n\n"));
        execution.context().content(ContextKeys.ARCHITECTURE_DESIGN)
                .ifPresent(v -> prompt.append("Architecture:\n").append(v).append("\n\n"));

        if (type == AgentType.TEST) {
            execution.context().content(ContextKeys.PATCH_IMPLEMENTATION)
                    .ifPresent(v -> prompt.append("Implementation to test:\n").append(v).append("\n\n"));
        }

        if (type == AgentType.REPAIR) {
            // The repair agent's whole value is that it works from evidence rather
            // than from a second guess at the original requirement.
            execution.context().content(ContextKeys.VALIDATION_FAILURE)
                    .ifPresent(v -> prompt.append("Build/test failure to fix:\n").append(v).append("\n\n"));
            execution.context().content(ContextKeys.PATCH_IMPLEMENTATION)
                    .ifPresent(v -> prompt.append("Current implementation:\n").append(v).append("\n\n"));
        }

        return prompt.toString();
    }

    // ---- prompts -----------------------------------------------------------

    public static final String IMPLEMENTATION_PROMPT = """
            You are a senior engineer implementing a requirement.

            Write production-quality code: clear names, no dead abstraction, and \
            comments only where the reasoning is not evident from the code itself. \
            Follow the conventions described in the repository analysis rather than \
            imposing your own.

            Emit every file using exactly this envelope, and nothing else — no prose, \
            no markdown fences, no explanation before or after:

            <<<FILE path=relative/path/File.java op=CREATE>>>
            ...complete file content...
            <<<END>>>

            Use op=CREATE for a new file, op=MODIFY to replace an existing one \
            (emit the complete file, not a diff), op=DELETE to remove one.

            Paths must be relative to the repository root. Do not write build wrapper \
            files (gradlew, gradle/wrapper/*) — they are managed by the platform and \
            such a change will be rejected.
            """;

    public static final String TEST_PROMPT = """
            You are a senior engineer writing tests for a change that has just been implemented.

            Test observable behaviour and the edges that matter: boundary conditions, \
            invalid input, concurrency where relevant, and each acceptance criterion. \
            A test that restates the implementation line by line proves nothing.

            Name tests as sentences describing the behaviour being asserted.

            Emit every file using exactly this envelope, and nothing else:

            <<<FILE path=src/test/java/... op=CREATE>>>
            ...complete file content...
            <<<END>>>
            """;

    public static final String DOCUMENTATION_PROMPT = """
            You are documenting a change for the engineers who will maintain it.

            Explain what it does, how to use it, and — most importantly — why the \
            design is the way it is, including what was rejected. State limitations \
            honestly; a documented gap is a known risk, an undocumented one is a \
            surprise in production.

            Emit every file using exactly this envelope, and nothing else:

            <<<FILE path=docs/....md op=CREATE>>>
            ...complete file content...
            <<<END>>>
            """;

    public static final String REPAIR_PROMPT = """
            You are fixing a change that failed its build or tests.

            You are given the failure output and the current implementation. Diagnose \
            the actual cause from the evidence rather than rewriting broadly in the \
            hope that something works — an unfocused rewrite destroys the parts that \
            were already correct and makes the next failure harder to read.

            Emit only the files that need to change, using exactly this envelope and \
            nothing else:

            <<<FILE path=relative/path/File.java op=MODIFY>>>
            ...complete corrected file content...
            <<<END>>>
            """;

    public static final String ARCHITECTURE_PROMPT = """
            You are designing the approach for a requirement before it is implemented.

            Record the decisions that constrain the implementation, and for each one \
            state what you rejected and why. A decision without a rejected alternative \
            is not a decision, it is a default.

            Respond with JSON only, no prose and no code fences:
            {
              "approach": "<the shape of the change and why it fits this codebase>",
              "decisions": [{"decision": "...", "rationale": "...", "alternativesRejected": "..."}],
              "risks": [{"risk": "...", "mitigation": "..."}],
              "plannedFiles": ["<path this change is expected to touch>"]
            }
            """;
}
