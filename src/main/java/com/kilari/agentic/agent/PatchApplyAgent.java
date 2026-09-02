package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.tools.PatchApplier;
import com.kilari.agentic.tools.PathPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the accumulated proposals to the workspace.
 *
 * <p>No model involved. This agent exists as a graph node because applying a
 * patch is a step with dependencies, a state, a retry budget and an audit
 * record like any other — but the work itself is deterministic, and there is
 * nothing here for a model to decide.
 *
 * <p>A repair patch takes precedence over the original implementation for any
 * file both touch. That ordering is deliberate: repairs are generated from
 * evidence about what actually failed, so where the two disagree, the repair is
 * the more informed proposal.
 */
public class PatchApplyAgent implements Agent {

    @Override
    public AgentType type() {
        return AgentType.PATCH_APPLY;
    }

    @Override
    public AgentOutcome execute(AgentExecution execution) {
        // Bound to this run's workspace rather than shared: each workflow gets its
        // own directory, and a policy rooted anywhere broader would treat a
        // sibling run's workspace as a legitimate write target.
        PatchApplier applier = new PatchApplier(new PathPolicy(execution.workspace()));

        List<FileChange> changes = collectChanges(execution);

        if (changes.isEmpty()) {
            throw new AgentOutputException(
                    "no file changes were proposed by any upstream agent; nothing to apply");
        }

        // Capture current hashes so concurrent modification is detected rather
        // than silently overwritten. On a first application the files do not yet
        // exist, and the applier treats an absent expectation as unlocked.
        Map<String, String> expectedHashes = new LinkedHashMap<>();
        for (FileChange change : changes) {
            if (change.operation() != FileChange.Operation.CREATE) {
                applier.currentHash(change.path())
                        .ifPresent(hash -> expectedHashes.put(change.path(), hash));
            }
        }

        PatchApplier.PatchResult result = applier.apply(changes, expectedHashes);

        return AgentOutcome.carryOn(
                "Applied patch to workspace: " + result.summary(),
                Map.of(ContextKeys.PATCH_APPLIED_SUMMARY, result.summary()));
    }

    /**
     * Merges the proposals in precedence order, last writer winning per path.
     */
    private List<FileChange> collectChanges(AgentExecution execution) {
        Map<String, FileChange> byPath = new LinkedHashMap<>();

        for (String key : List.of(
                ContextKeys.PATCH_IMPLEMENTATION,
                ContextKeys.PATCH_TESTS,
                ContextKeys.PATCH_DOCUMENTATION,
                ContextKeys.PATCH_REPAIR)) {
            execution.context().content(key).ifPresent(rendered ->
                    PatchEnvelope.parse(rendered).forEach(change -> byPath.put(change.path(), change)));
        }

        return new ArrayList<>(byPath.values());
    }
}
