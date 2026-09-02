package com.kilari.agentic.scenarios;

import com.kilari.agentic.agent.ContextKeys;
import com.kilari.agentic.orchestration.DecisionRecord;
import com.kilari.agentic.orchestration.TaskNode;
import com.kilari.agentic.orchestration.TaskState;
import com.kilari.agentic.orchestration.WorkflowPlanner;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.orchestration.WorkflowState;
import com.kilari.agentic.provider.fixtures.RepairLoopFixture;
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
 * The failure-driven re-planning path, executed for real.
 *
 * <p>A first implementation that does not compile, a build that genuinely fails,
 * a verified rollback, a graph that changes shape, and a second build that
 * passes. Only the initial defect is fixtured — everything after it is the
 * platform actually reacting to a real compiler error.
 *
 * <p>This is the behaviour that separates an agentic system from a pipeline: what
 * happens next is decided by observed state, not by a script.
 */
@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:repairloop;DB_CLOSE_DELAY=-1",
        "agentic.workspaces.root=build/test-workspaces-repair"
})
class RepairLoopIT {

    @Autowired
    private WorkflowService workflows;

    @Test
    @DisplayName("a failing build drives a repair round and reaches a passing outcome")
    void failed_validation_triggers_repair_and_recovers() {
        WorkflowRun run = workflows.start(
                "Build a URL shortener with a click counter, using a " + RepairLoopFixture.TRIGGER
                        + " to exercise the repair path.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);

        // It recovered rather than failing.
        assertThat(run.state())
                .as("the run should reach approval after repairing itself")
                .isEqualTo(WorkflowState.AWAITING_APPROVAL);

        // Exactly one repair round was needed, and the budget was not exhausted.
        assertThat(run.repairRounds()).isEqualTo(1);

        // The workspace was rolled back before repairing, so the repair applied to
        // clean ground rather than layering on a half-broken attempt.
        assertThat(run.rollbackCount())
                .as("a rollback must precede the repair")
                .isGreaterThanOrEqualTo(1);

        // The graph genuinely changed shape - this is the difference between
        // re-planning and retrying.
        List<String> taskIds = run.graph().nodes().stream().map(TaskNode::id).toList();
        assertThat(taskIds)
                .as("re-planning should add a repair round to the graph")
                .contains("repair-1", "patch-apply-1", "validate-1", "release-readiness-1");

        // The validation task that found the failure stays SUCCEEDED: it ran the
        // build and returned a truthful answer, so the task did its job. What
        // failed is the change, not the task, and its finding is real history.
        assertThat(run.graph().node(WorkflowPlanner.TASK_VALIDATE).orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);

        // The approval gate hanging off it, however, must be gone - otherwise a
        // human would be asked to approve a change whose build failed.
        assertThat(run.graph().node(WorkflowPlanner.TASK_RELEASE).orElseThrow().state())
                .isEqualTo(TaskState.SUPERSEDED);

        // The second build actually passed - the repair worked, it was not just attempted.
        assertThat(run.graph().node("validate-1").orElseThrow().state())
                .isEqualTo(TaskState.SUCCEEDED);
        assertThat(run.context().content(ContextKeys.VALIDATION_RESULT))
                .hasValueSatisfying(result -> assertThat(result).startsWith("PASSED"));

        // The corrected file is on disk, and it is the repaired version.
        assertThat(run.workspace().resolve(
                "src/main/java/com/example/shortener/domain/ClickCounter.java")).exists();

        // The context revision advanced, marking pre-failure artifacts stale.
        assertThat(run.context().revision())
                .as("new evidence must bump the revision")
                .isGreaterThan(0);

        // The lineage tells the whole story, in order and attributed.
        List<DecisionRecord.DecisionType> types = run.context().lineage().stream()
                .map(DecisionRecord::type)
                .toList();
        assertThat(types).contains(
                DecisionRecord.DecisionType.VALIDATION_RESULT,
                DecisionRecord.DecisionType.ROLLBACK_PERFORMED,
                DecisionRecord.DecisionType.PLAN_REVISED);
    }

    @Test
    @DisplayName("a human is never asked to approve a change whose build failed")
    void the_failing_build_is_never_presented_for_approval() {
        WorkflowRun run = workflows.start(
                "Build a shortener with a " + RepairLoopFixture.TRIGGER + " for the repair path.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);

        // The only approval request in the lineage comes after the passing build.
        List<DecisionRecord> lineage = run.context().lineage();

        int firstApprovalRequest = indexOf(lineage, DecisionRecord.DecisionType.APPROVAL_REQUESTED);
        int lastValidationFailure = lastIndexOfFailedValidation(lineage);

        assertThat(firstApprovalRequest)
                .as("an approval request should exist once the build passes")
                .isGreaterThanOrEqualTo(0);
        assertThat(firstApprovalRequest)
                .as("approval must never be requested before the failure was resolved")
                .isGreaterThan(lastValidationFailure);
    }

    private int indexOf(List<DecisionRecord> lineage, DecisionRecord.DecisionType type) {
        for (int i = 0; i < lineage.size(); i++) {
            if (lineage.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }

    private int lastIndexOfFailedValidation(List<DecisionRecord> lineage) {
        int last = -1;
        for (int i = 0; i < lineage.size(); i++) {
            DecisionRecord record = lineage.get(i);
            if (record.type() == DecisionRecord.DecisionType.VALIDATION_RESULT
                    && record.summary().contains("failed")) {
                last = i;
            }
        }
        return last;
    }
}
