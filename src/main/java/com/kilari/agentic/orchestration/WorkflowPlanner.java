package com.kilari.agentic.orchestration;

import java.util.List;
import java.util.Set;

/**
 * Builds the task graph for a run, and rebuilds parts of it when the world changes.
 *
 * <p>The plan is deterministic Java, not a model output. Letting the model emit
 * its own DAG sounds more autonomous but removes the property that makes the
 * system governable: if the graph is generated, then the gates, the retry
 * budgets and the approval checkpoints are generated too, and a prompt injected
 * upstream could produce a plan with no approval step in it at all. The model
 * decides <em>what the change should be</em>; the platform decides
 * <em>what steps are allowed to happen</em>.
 */
public final class WorkflowPlanner {

    public static final String TASK_REQUIREMENT = "requirement";
    public static final String TASK_REPO_ANALYSIS = "repository-analysis";
    public static final String TASK_ARCHITECTURE = "architecture";
    public static final String TASK_IMPLEMENTATION = "implementation";
    public static final String TASK_TESTS = "tests";
    public static final String TASK_DOCUMENTATION = "documentation";
    public static final String TASK_PATCH_APPLY = "patch-apply";
    public static final String TASK_VALIDATE = "validate";
    public static final String TASK_RELEASE = "release-readiness";

    private static final int DEFAULT_ATTEMPTS = 2;

    private WorkflowPlanner() {
    }

    /**
     * The standard SDLC plan.
     *
     * <p>Note the shape rather than the contents. Tests and documentation both
     * depend only on the implementation and on nothing from each other, so they
     * are free to run concurrently. {@code patch-apply} depends on both, making
     * it a synchronisation point that cannot start until both branches have
     * finished. Neither fact is written down as an ordering instruction — both
     * fall out of the declared dependencies.
     */
    public static WorkflowGraph initialPlan() {
        return new WorkflowGraph(List.of(
                node(TASK_REQUIREMENT, AgentType.REQUIREMENT),
                node(TASK_REPO_ANALYSIS, AgentType.REPOSITORY_ANALYSIS, TASK_REQUIREMENT),
                node(TASK_ARCHITECTURE, AgentType.ARCHITECTURE, TASK_REPO_ANALYSIS),
                node(TASK_IMPLEMENTATION, AgentType.IMPLEMENTATION, TASK_ARCHITECTURE),

                // Parallel branches - no dependency between them.
                node(TASK_TESTS, AgentType.TEST, TASK_IMPLEMENTATION),
                node(TASK_DOCUMENTATION, AgentType.DOCUMENTATION, TASK_IMPLEMENTATION),

                // Join: waits for both branches.
                node(TASK_PATCH_APPLY, AgentType.PATCH_APPLY, TASK_TESTS, TASK_DOCUMENTATION),

                node(TASK_VALIDATE, AgentType.BUILD_VALIDATION, TASK_PATCH_APPLY),
                node(TASK_RELEASE, AgentType.RELEASE_READINESS, TASK_VALIDATE)));
    }

    /**
     * Reshapes the graph after a validation failure.
     *
     * <p>This is re-planning rather than retrying. A retry would run the same
     * failed node again against unchanged inputs and get the same answer. Instead
     * the remaining plan is replaced with a different shape: a repair task that
     * consumes the failure evidence, then a fresh apply and a fresh validation,
     * with the approval gate re-attached at the end.
     *
     * <p>The failed validation node is superseded rather than deleted, so the
     * lineage still shows that it ran and what it found.
     */
    public static WorkflowGraph.PlanRevision repairRevision(int round, String previousValidateTaskId) {
        String repair = "repair-" + round;
        String apply = "patch-apply-" + round;
        String validate = "validate-" + round;
        String release = "release-readiness-" + round;

        return new WorkflowGraph.PlanRevision(
                List.of(
                        node(repair, AgentType.REPAIR),
                        node(apply, AgentType.PATCH_APPLY, repair),
                        node(validate, AgentType.BUILD_VALIDATION, apply),
                        node(release, AgentType.RELEASE_READINESS, validate)),
                List.of(previousValidateTaskId, releaseTaskFor(round - 1)),
                "validation failed; inserting a repair round driven by the failure evidence");
    }

    /** The release node belonging to a given repair round; round 0 is the initial plan. */
    public static String releaseTaskFor(int round) {
        return round <= 0 ? TASK_RELEASE : "release-readiness-" + round;
    }

    /**
     * Reshapes the graph after a human clarifies an ambiguous requirement.
     *
     * <p>The requirement node is left as it is — it ran, and its finding that the
     * requirement was ambiguous is a true part of the history. What changes is
     * everything after it: a fresh requirement pass now has the clarification in
     * context, and the rest of the plan hangs off that.
     */
    public static WorkflowGraph.PlanRevision clarificationRevision() {
        String requirement = "requirement-clarified";

        return new WorkflowGraph.PlanRevision(
                List.of(
                        node(requirement, AgentType.REQUIREMENT),
                        node(TASK_REPO_ANALYSIS, AgentType.REPOSITORY_ANALYSIS, requirement),
                        node(TASK_ARCHITECTURE, AgentType.ARCHITECTURE, TASK_REPO_ANALYSIS),
                        node(TASK_IMPLEMENTATION, AgentType.IMPLEMENTATION, TASK_ARCHITECTURE),
                        node(TASK_TESTS, AgentType.TEST, TASK_IMPLEMENTATION),
                        node(TASK_DOCUMENTATION, AgentType.DOCUMENTATION, TASK_IMPLEMENTATION),
                        node(TASK_PATCH_APPLY, AgentType.PATCH_APPLY, TASK_TESTS, TASK_DOCUMENTATION),
                        node(TASK_VALIDATE, AgentType.BUILD_VALIDATION, TASK_PATCH_APPLY),
                        node(TASK_RELEASE, AgentType.RELEASE_READINESS, TASK_VALIDATE)),
                List.of(),
                "human clarification received; expanding the plan into full delivery");
    }

    /** The graph an ambiguous run starts with: understand the requirement, and stop there. */
    public static WorkflowGraph ambiguityProbePlan() {
        return new WorkflowGraph(List.of(node(TASK_REQUIREMENT, AgentType.REQUIREMENT)));
    }

    private static TaskNode node(String id, AgentType type, String... dependsOn) {
        return new TaskNode(id, type, Set.of(dependsOn), DEFAULT_ATTEMPTS, 0);
    }
}
