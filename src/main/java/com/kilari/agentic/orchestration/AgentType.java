package com.kilari.agentic.orchestration;

/**
 * The kinds of agent work a node can carry.
 *
 * <p>An enum rather than a free-form string because the model does not get to
 * invent new capabilities at runtime: a plan may only wire together agents the
 * platform already knows how to authorize, execute and audit.
 */
public enum AgentType {

    /** Interprets intent, scores ambiguity, normalizes into an engineering problem. */
    REQUIREMENT,

    /** Reads the existing repository to ground brownfield changes in real conventions. */
    REPOSITORY_ANALYSIS,

    /** Chooses the approach and produces the design the implementation must follow. */
    ARCHITECTURE,

    /** Generates the production code change as a structured patch proposal. */
    IMPLEMENTATION,

    /** Generates unit/integration tests for the change. */
    TEST,

    /** Generates supporting documentation for the change. */
    DOCUMENTATION,

    /** Applies the accumulated patch through the controlled tool layer. */
    PATCH_APPLY,

    /** Runs the build/test command in the sandboxed workspace. */
    BUILD_VALIDATION,

    /** Turns validation failure evidence into a revised change. */
    REPAIR,

    /** Assembles the reviewable outcome presented to the approver. */
    RELEASE_READINESS;

    /**
     * Whether this agent proposes changes but performs no side effects itself.
     * Generative agents are the ones the model drives; the others are
     * deterministic operations the platform owns.
     */
    public boolean isGenerative() {
        return switch (this) {
            case REQUIREMENT, REPOSITORY_ANALYSIS, ARCHITECTURE,
                 IMPLEMENTATION, TEST, DOCUMENTATION, REPAIR -> true;
            case PATCH_APPLY, BUILD_VALIDATION, RELEASE_READINESS -> false;
        };
    }

    /**
     * Whether running this agent mutates the workspace. These are the nodes that
     * need snapshots taken beforehand so a rollback has something to restore.
     */
    public boolean mutatesWorkspace() {
        return this == PATCH_APPLY;
    }
}
