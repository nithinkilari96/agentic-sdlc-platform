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
     * <p>The validation node that reported the failure stays SUCCEEDED and is not
     * superseded. That is deliberate rather than an oversight: the validation
     * <em>task</em> did its job — it ran the build and returned a truthful
     * answer. What failed is the change, not the task. Its finding is real
     * history and the lineage records it.
     *
     * <p>What must be removed is the approval gate hanging off it. Left in place,
     * a human would be asked to approve a change whose build failed. Superseding
     * it is therefore the load-bearing part of this revision.
     */
    public static WorkflowGraph.PlanRevision repairRevision(int round) {
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
                List.of(releaseTaskFor(round - 1)),
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

    /**
     * The plan for work that improves tests and documentation without changing
     * production code.
     *
     * <p>Note what is absent: there is no architecture step and no implementation
     * step. Running them anyway would force agents to invent a design and a code
     * change for a requirement that asks for neither, and the resulting empty or
     * fabricated patch would be noise the approver has to see through.
     *
     * <p>The rest of the machinery is unchanged. The patch still goes through the
     * same policy checks, the build still has to pass, and a human still approves
     * — a test-only change is not a lower-risk change, it is a smaller one.
     */
    public static WorkflowGraph testsAndDocsPlan() {
        return new WorkflowGraph(List.of(
                node(TASK_REQUIREMENT, AgentType.REQUIREMENT),
                node(TASK_REPO_ANALYSIS, AgentType.REPOSITORY_ANALYSIS, TASK_REQUIREMENT),

                // Parallel, as in the full plan - neither depends on the other.
                node(TASK_TESTS, AgentType.TEST, TASK_REPO_ANALYSIS),
                node(TASK_DOCUMENTATION, AgentType.DOCUMENTATION, TASK_REPO_ANALYSIS),

                node(TASK_PATCH_APPLY, AgentType.PATCH_APPLY, TASK_TESTS, TASK_DOCUMENTATION),
                node(TASK_VALIDATE, AgentType.BUILD_VALIDATION, TASK_PATCH_APPLY),
                node(TASK_RELEASE, AgentType.RELEASE_READINESS, TASK_VALIDATE)));
    }

    /**
     * The shapes a run can start with.
     *
     * <p>Chosen by the operator rather than inferred from the requirement text.
     * Inferring it would mean a keyword in a requirement could silently decide
     * whether the implementation step happens — which is exactly the kind of
     * control-flow decision the platform deliberately keeps away from anything
     * the model or a prompt can influence.
     */
    public enum PlanShape {

        /** The full SDLC plan: understand, design, implement, test, document, validate. */
        FULL_DELIVERY,

        /** Understand the requirement only, expecting it to be too vague to act on. */
        AMBIGUITY_PROBE,

        /** Improve tests and documentation without touching production code. */
        TESTS_AND_DOCS;

        public WorkflowGraph toGraph() {
            return switch (this) {
                case FULL_DELIVERY -> initialPlan();
                case AMBIGUITY_PROBE -> ambiguityProbePlan();
                case TESTS_AND_DOCS -> testsAndDocsPlan();
            };
        }
    }

    private static TaskNode node(String id, AgentType type, String... dependsOn) {
        return new TaskNode(id, type, Set.of(dependsOn), DEFAULT_ATTEMPTS, 0);
    }
}
