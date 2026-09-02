package com.kilari.agentic.persistence;

import com.kilari.agentic.agent.ContextKeys;
import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.orchestration.DecisionRecord;
import com.kilari.agentic.orchestration.RecoverySupport;
import com.kilari.agentic.orchestration.TaskNode;
import com.kilari.agentic.orchestration.TaskState;
import com.kilari.agentic.orchestration.WorkflowGraph;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves workflow state actually survives losing the process.
 *
 * <p>The distinction being tested is between an audit trail and a recovery
 * mechanism. Recording what a run did is enough to explain a crash afterwards;
 * it is not enough to continue. These tests drop every in-memory reference to a
 * run and rebuild it purely from storage, which is what a restarted pod does.
 */
@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:recovery;DB_CLOSE_DELAY=-1",
        "agentic.workspaces.root=build/test-workspaces-recovery"
})
class CrashRecoveryIT {

    @Autowired
    private WorkflowService workflows;

    @Autowired
    private WorkflowStore store;

    @Test
    @DisplayName("a run parked for approval is fully reconstructable from storage alone")
    void parked_run_survives_process_loss() {
        WorkflowRun original = workflows.start(
                "Build a URL shortener service with create, resolve and stats APIs.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);

        assertThat(original.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);
        int originalLineageSize = original.context().lineage().size();
        int originalRevision = original.context().revision();

        // Simulate the process dying: nothing but the database survives.
        WorkflowRun recovered = store.load(original.workflowId()).orElseThrow(
                () -> new AssertionError("workflow was not persisted"));

        assertThat(recovered).isNotSameAs(original);
        assertThat(recovered.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);
        assertThat(recovered.requirement()).isEqualTo(original.requirement());
        assertThat(recovered.context().revision()).isEqualTo(originalRevision);

        // The graph came back with every node's individual state intact - not just
        // a list of task names, but where each one got to.
        assertThat(recovered.graph().nodes()).hasSameSizeAs(original.graph().nodes());
        original.graph().nodes().forEach(node ->
                assertThat(recovered.graph().node(node.id()).orElseThrow().state())
                        .as("state of task %s", node.id())
                        .isEqualTo(node.state()));

        // Cross-stage context survived, so a resumed run reasons from what earlier
        // agents established rather than starting over.
        assertThat(recovered.context().content(ContextKeys.REQUIREMENT_NORMALIZED))
                .isEqualTo(original.context().content(ContextKeys.REQUIREMENT_NORMALIZED));
        assertThat(recovered.context().content(ContextKeys.VALIDATION_RESULT))
                .hasValueSatisfying(result -> assertThat(result).startsWith("PASSED"));

        // The audit lineage is intact and still attributes each decision.
        assertThat(recovered.context().lineage()).hasSize(originalLineageSize);
        assertThat(recovered.context().lineage())
                .extracting(DecisionRecord::actor)
                .contains(DecisionRecord.Actor.ORCHESTRATOR, DecisionRecord.Actor.AGENT);
    }

    @Test
    @DisplayName("a recovered run can still be approved, so recovery restores capability not just data")
    void recovered_run_remains_actionable() {
        WorkflowRun original = workflows.start(
                "Build a URL shortener service with create and resolve APIs.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);
        assertThat(original.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

        WorkflowRun recovered = store.load(original.workflowId()).orElseThrow();

        // Approving the reconstructed aggregate must work exactly as it would
        // have before the crash. Data that cannot be acted on is not recovery.
        workflows.approve(recovered.workflowId(), "approver@example.com", "verified after restart");

        WorkflowRun after = store.load(original.workflowId()).orElseThrow();
        assertThat(after.state()).isEqualTo(WorkflowState.COMPLETED);
        assertThat(after.approver()).contains("approver@example.com");
        assertThat(after.context().lineage())
                .extracting(DecisionRecord::type)
                .contains(DecisionRecord.DecisionType.APPROVAL_GRANTED);
    }

    @Test
    @DisplayName("a task that was RUNNING at crash time is re-run rather than assumed complete")
    void interrupted_task_is_reset_for_safe_re_execution() {
        // A run whose patch-apply was in flight when the process died. Its side
        // effects are unknown - it may have written some files and not others.
        WorkflowGraph graph = new WorkflowGraph(List.of(
                new TaskNode("requirement", AgentType.REQUIREMENT, Set.of(), 2, 0),
                new TaskNode("patch-apply", AgentType.PATCH_APPLY, Set.of("requirement"), 2, 0)));

        // Task state is deliberately not publicly mutable — the engine owns
        // transitions. RecoverySupport is the sanctioned way to place a node in a
        // given state, which is exactly what reconstructing a crash requires.
        RecoverySupport.restoreNode(graph.node("requirement").orElseThrow(),
                TaskState.SUCCEEDED, 1, null, Instant.now(), Instant.now());
        RecoverySupport.restoreNode(graph.node("patch-apply").orElseThrow(),
                TaskState.RUNNING, 1, null, Instant.now(), null);

        assertThat(graph.node("patch-apply").orElseThrow().state()).isEqualTo(TaskState.RUNNING);

        WorkflowRun crashed = new WorkflowRun("wf-crash-test", "some requirement", graph,
                Path.of("build/test-workspaces-recovery/wf-crash-test"));
        crashed.transitionTo(WorkflowState.RUNNING, "mid-flight");
        store.checkpoint(crashed);

        WorkflowRun recovered = store.load("wf-crash-test").orElseThrow();

        assertThat(recovered.graph().node("requirement").orElseThrow().state())
                .as("completed work stays completed")
                .isEqualTo(TaskState.SUCCEEDED);
        assertThat(recovered.graph().node("patch-apply").orElseThrow().state())
                .as("an interrupted mutating task must be re-run from a verified snapshot, "
                        + "never assumed to have finished")
                .isEqualTo(TaskState.PENDING);

        // And it is genuinely schedulable again, not merely relabelled.
        assertThat(recovered.graph().readyTasks())
                .extracting(TaskNode::id)
                .containsExactly("patch-apply");
    }

    @Test
    @DisplayName("execution counters and timing survive the restart, not just the graph")
    void counters_and_timing_are_restored() {
        WorkflowGraph graph = new WorkflowGraph(List.of(
                new TaskNode("requirement", AgentType.REQUIREMENT, Set.of(), 2, 0)));
        WorkflowRun original = new WorkflowRun("wf-counters", "some requirement", graph,
                Path.of("build/test-workspaces-recovery/wf-counters"));

        original.nextRepairRound();
        original.recordRollback();
        original.recordRetry();
        original.recordRetry();
        original.recordFailureMoment();
        original.transitionTo(WorkflowState.RUNNING, "mid-flight");
        store.checkpoint(original);

        WorkflowRun recovered = store.load("wf-counters").orElseThrow();

        // The repair budget is the important one. If it resets, a run that keeps
        // crashing gets a fresh allowance every restart and the bound stops
        // bounding anything.
        assertThat(recovered.repairRounds())
                .as("repair budget must not reset across a restart")
                .isEqualTo(original.repairRounds());
        assertThat(recovered.rollbackCount()).isEqualTo(original.rollbackCount());
        assertThat(recovered.retryCount()).isEqualTo(original.retryCount());

        // Timing drives end-to-end latency and MTTR. Restarting must not make a
        // long-running workflow look like it just began.
        assertThat(recovered.startedAt())
                .as("start time must survive, or latency is measured from the restart")
                .isEqualTo(original.startedAt());
        assertThat(recovered.firstFailureAt())
                .as("MTTR is measured from the first failure; losing it loses the metric")
                .isEqualTo(original.firstFailureAt());
    }

    @Test
    @DisplayName("interrupted runs are discoverable at startup; parked ones are left for their human")
    void resumable_runs_are_identified() {
        WorkflowRun parked = workflows.start(
                "Build a URL shortener service with stats APIs.", false, WorkflowPlanner.PlanShape.FULL_DELIVERY);
        assertThat(parked.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

        assertThat(store.findResumable())
                .as("a run waiting on a human is still resumable state, not finished")
                .contains(parked.workflowId());

        workflows.approve(parked.workflowId(), "approver@example.com", "done");

        assertThat(store.findResumable())
                .as("a completed run must never be picked up again by recovery")
                .doesNotContain(parked.workflowId());
    }
}
