package com.kilari.agentic.scenarios;

import com.kilari.agentic.agent.ContextKeys;
import com.kilari.agentic.orchestration.DecisionRecord;
import com.kilari.agentic.orchestration.TaskState;
import com.kilari.agentic.orchestration.WorkflowPlanner;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.orchestration.WorkflowState;
import com.kilari.agentic.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three scenarios the assignment requires, executed end to end against the
 * real engine, real workspaces and real Gradle builds.
 *
 * <p>These are the tests that demonstrate the system rather than a unit of it.
 * Each one asserts on orchestration behaviour — what the graph did, what the
 * lineage recorded, what state the run reached — not merely that a method
 * returned without throwing.
 */
@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:scenarios;DB_CLOSE_DELAY=-1",
        "agentic.workspaces.root=build/test-workspaces"
})
class ScenarioIT {

    @Autowired
    private WorkflowService workflows;

    @Test
    @DisplayName("Greenfield: builds the service from an empty repository and stops for approval")
    void greenfield_scenario() {
        WorkflowRun run = workflows.start(
                "Build a URL shortener service with create, resolve and stats APIs, "
                        + "click analytics and reliability controls.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);

        assertThat(run.state())
                .as("a validated change must wait for a human, never self-approve")
                .isEqualTo(WorkflowState.AWAITING_APPROVAL);

        // Every stage ran, and the parallel branches both completed before the join.
        assertThat(taskState(run, WorkflowPlanner.TASK_REQUIREMENT)).isEqualTo(TaskState.SUCCEEDED);
        assertThat(taskState(run, WorkflowPlanner.TASK_TESTS)).isEqualTo(TaskState.SUCCEEDED);
        assertThat(taskState(run, WorkflowPlanner.TASK_DOCUMENTATION)).isEqualTo(TaskState.SUCCEEDED);
        assertThat(taskState(run, WorkflowPlanner.TASK_PATCH_APPLY)).isEqualTo(TaskState.SUCCEEDED);
        assertThat(taskState(run, WorkflowPlanner.TASK_VALIDATE)).isEqualTo(TaskState.SUCCEEDED);

        // The build actually passed, rather than the workflow merely reaching the end.
        assertThat(run.context().content(ContextKeys.VALIDATION_RESULT))
                .hasValueSatisfying(result -> assertThat(result).startsWith("PASSED"));

        // The generated service exists on disk.
        assertThat(run.workspace().resolve(
                "src/main/java/com/example/shortener/service/ShortenerService.java")).exists();

        // Approval is a second party's decision, and completes the run.
        workflows.approve(run.workflowId(), "approver@example.com", "looks correct");
        assertThat(run.state()).isEqualTo(WorkflowState.COMPLETED);
        assertThat(actors(run)).contains(DecisionRecord.Actor.HUMAN);
    }

    @Test
    @DisplayName("Brownfield: modifies an existing codebase after reading it")
    void brownfield_scenario() {
        WorkflowRun run = workflows.start(
                "Add per-client rate limiting to link creation so one caller cannot exhaust the service.",
                true, WorkflowPlanner.PlanShape.FULL_DELIVERY);

        assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

        // The repository was read before anything was written - that is what makes
        // this brownfield rather than generation that happens to land in a used
        // directory.
        assertThat(taskState(run, WorkflowPlanner.TASK_REPO_ANALYSIS)).isEqualTo(TaskState.SUCCEEDED);
        assertThat(run.context().content(ContextKeys.REPOSITORY_ANALYSIS))
                .hasValueSatisfying(analysis ->
                        assertThat(analysis).contains("ShortenerController"));

        // A pre-existing file was modified, not just new ones added.
        assertThat(run.context().content(ContextKeys.PATCH_APPLIED_SUMMARY))
                .hasValueSatisfying(summary -> assertThat(summary).contains("modified"));

        assertThat(run.workspace().resolve(
                "src/main/java/com/example/shortener/service/RateLimiter.java")).exists();
        assertThat(run.context().content(ContextKeys.VALIDATION_RESULT))
                .hasValueSatisfying(result -> assertThat(result).startsWith("PASSED"));
    }

    @Test
    @DisplayName("Ambiguous: parks without generating, then re-plans once a human answers")
    void ambiguous_scenario() {
        WorkflowRun run = workflows.start("Improve analytics", true, WorkflowPlanner.PlanShape.AMBIGUITY_PROBE);

        // Stopped rather than guessed.
        assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_CLARIFICATION);
        assertThat(run.context().content(ContextKeys.REQUIREMENT_QUESTIONS))
                .hasValueSatisfying(questions -> assertThat(questions).isNotBlank());

        // Critically: nothing was generated and nothing was written.
        assertThat(run.context().get(ContextKeys.PATCH_IMPLEMENTATION)).isEmpty();
        assertThat(run.workspace().resolve(
                "src/main/java/com/example/shortener/analytics/GeoClickAnalytics.java"))
                .doesNotExist();

        int tasksBefore = run.graph().nodes().size();
        int revisionBefore = run.context().revision();

        workflows.clarify(run.workflowId(),
                "Break clicks down by country, using the X-Client-Country header, "
                        + "exposed on a new per-link analytics endpoint.");

        // The graph genuinely changed shape - this is re-planning, not a retry.
        assertThat(run.graph().nodes().size())
                .as("clarification should expand the plan, not re-run the same one")
                .isGreaterThan(tasksBefore);
        assertThat(run.context().revision())
                .as("new information must bump the revision so earlier artifacts are stale")
                .isGreaterThan(revisionBefore);

        assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);
        assertThat(run.workspace().resolve(
                "src/main/java/com/example/shortener/analytics/GeoClickAnalytics.java")).exists();

        // The lineage shows a human in the loop at the point it mattered.
        assertThat(decisionTypes(run))
                .contains(DecisionRecord.DecisionType.CLARIFICATION_REQUESTED)
                .contains(DecisionRecord.DecisionType.CLARIFICATION_RECEIVED)
                .contains(DecisionRecord.DecisionType.PLAN_REVISED);
    }

    @Test
    @DisplayName("Rejection rolls the workspace back rather than leaving refused code behind")
    void rejected_change_is_rolled_back() {
        WorkflowRun run = workflows.start(
                "Build a URL shortener service with create, resolve and stats APIs.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);
        assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

        workflows.reject(run.workflowId(), "approver@example.com", "not the approach we want");

        assertThat(run.state()).isEqualTo(WorkflowState.FAILED);
        assertThat(run.rollbackCount()).isGreaterThan(0);
        assertThat(decisionTypes(run))
                .contains(DecisionRecord.DecisionType.APPROVAL_REJECTED)
                .contains(DecisionRecord.DecisionType.ROLLBACK_PERFORMED);

        // The refused code is gone: the snapshot predates the patch, so the
        // workspace is back to where it started.
        assertThat(run.workspace().resolve(
                "src/main/java/com/example/shortener/service/ShortenerService.java"))
                .doesNotExist();
    }

    private TaskState taskState(WorkflowRun run, String taskId) {
        return run.graph().node(taskId).orElseThrow(
                () -> new AssertionError("no such task: " + taskId)).state();
    }

    private List<DecisionRecord.DecisionType> decisionTypes(WorkflowRun run) {
        return run.context().lineage().stream().map(DecisionRecord::type).toList();
    }

    private List<DecisionRecord.Actor> actors(WorkflowRun run) {
        return run.context().lineage().stream().map(DecisionRecord::actor).toList();
    }
}
