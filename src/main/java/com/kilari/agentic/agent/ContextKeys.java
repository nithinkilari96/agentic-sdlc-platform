package com.kilari.agentic.agent;

/**
 * Artifact keys agents use to pass work to each other.
 *
 * <p>Named constants rather than inline strings: the context is the integration
 * surface between agents, and a typo in a key would not fail loudly — it would
 * silently produce an empty read, and a downstream agent would generate against
 * missing information without anything looking wrong.
 */
public final class ContextKeys {

    public static final String REQUIREMENT_NORMALIZED = "requirement.normalized";
    public static final String REQUIREMENT_CRITERIA = "requirement.acceptanceCriteria";
    public static final String REQUIREMENT_ASSUMPTIONS = "requirement.assumptions";
    public static final String REQUIREMENT_CONFIDENCE = "requirement.confidence";
    public static final String REQUIREMENT_QUESTIONS = "requirement.openQuestions";

    public static final String REPOSITORY_ANALYSIS = "repository.analysis";
    public static final String ARCHITECTURE_DESIGN = "architecture.design";
    public static final String ARCHITECTURE_DECISIONS = "architecture.decisions";
    public static final String ARCHITECTURE_RISKS = "architecture.risks";

    public static final String PATCH_IMPLEMENTATION = "patch.implementation";
    public static final String PATCH_TESTS = "patch.tests";
    public static final String PATCH_DOCUMENTATION = "patch.documentation";
    public static final String PATCH_REPAIR = "patch.repair";

    public static final String PATCH_APPLIED_SUMMARY = "patch.appliedSummary";
    public static final String VALIDATION_RESULT = "validation.result";
    public static final String VALIDATION_FAILURE = "validation.failure";
    public static final String RELEASE_EVIDENCE = "release.evidence";

    private ContextKeys() {
    }
}
