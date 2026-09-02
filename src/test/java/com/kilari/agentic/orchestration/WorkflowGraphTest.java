package com.kilari.agentic.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowGraphTest {

    private static TaskNode node(String id, String... deps) {
        return new TaskNode(id, AgentType.IMPLEMENTATION, Set.of(deps), 3, 0);
    }

    @Nested
    @DisplayName("structural validation")
    class StructuralValidation {

        @Test
        void rejects_a_dependency_on_a_task_that_does_not_exist() {
            assertThatThrownBy(() -> new WorkflowGraph(List.of(node("b", "a"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("depends on unknown task a");
        }

        @Test
        void rejects_a_cycle() {
            // a -> b -> c -> a
            List<TaskNode> cyclic = List.of(node("a", "c"), node("b", "a"), node("c", "b"));
            assertThatThrownBy(() -> new WorkflowGraph(cyclic))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        void rejects_a_self_dependency() {
            assertThatThrownBy(() -> node("a", "a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot depend on itself");
        }

        @Test
        void rejects_duplicate_task_ids() {
            assertThatThrownBy(() -> new WorkflowGraph(List.of(node("a"), node("a"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate task id");
        }
    }

    @Nested
    @DisplayName("readiness and parallelism")
    class Readiness {

        @Test
        void only_the_roots_are_ready_initially() {
            WorkflowGraph graph = new WorkflowGraph(List.of(
                    node("requirement"),
                    node("implementation", "requirement")));

            assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("requirement");
        }

        @Test
        void independent_branches_become_ready_together_so_they_can_run_in_parallel() {
            WorkflowGraph graph = new WorkflowGraph(List.of(
                    node("implementation"),
                    node("tests", "implementation"),
                    node("docs", "implementation")));

            graph.node("implementation").orElseThrow().markRunning();
            graph.node("implementation").orElseThrow().markSucceeded();

            assertThat(graph.readyTasks())
                    .extracting(TaskNode::id)
                    .containsExactlyInAnyOrder("tests", "docs");
        }

        @Test
        void a_join_waits_for_every_incoming_branch() {
            WorkflowGraph graph = new WorkflowGraph(List.of(
                    node("implementation"),
                    node("tests", "implementation"),
                    node("docs", "implementation"),
                    node("patch", "tests", "docs")));

            succeed(graph, "implementation");
            succeed(graph, "tests");

            // docs has not finished, so the synchronisation point stays blocked
            assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("docs");

            succeed(graph, "docs");
            assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("patch");
        }
    }

    @Nested
    @DisplayName("failure handling")
    class FailureHandling {

        @Test
        void a_failed_task_stays_eligible_while_it_has_retries_left() {
            WorkflowGraph graph = new WorkflowGraph(List.of(node("a")));
            TaskNode a = graph.node("a").orElseThrow();

            a.markRunning();
            a.markFailed("build failed");

            assertThat(a.state()).isEqualTo(TaskState.FAILED);
            assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("a");
        }

        @Test
        void a_task_is_exhausted_once_the_retry_budget_is_spent() {
            WorkflowGraph graph = new WorkflowGraph(List.of(
                    new TaskNode("a", AgentType.IMPLEMENTATION, Set.of(), 2, 0)));
            TaskNode a = graph.node("a").orElseThrow();

            a.markRunning();
            a.markFailed("attempt 1");
            assertThat(a.state()).isEqualTo(TaskState.FAILED);

            a.markRunning();
            a.markFailed("attempt 2");

            assertThat(a.state()).isEqualTo(TaskState.EXHAUSTED);
            assertThat(graph.readyTasks()).isEmpty();
        }

        @Test
        void terminal_failure_blocks_everything_downstream_transitively() {
            WorkflowGraph graph = new WorkflowGraph(List.of(
                    new TaskNode("a", AgentType.IMPLEMENTATION, Set.of(), 1, 0),
                    node("b", "a"),
                    node("c", "b")));

            TaskNode a = graph.node("a").orElseThrow();
            a.markRunning();
            a.markFailed("unrecoverable");

            List<TaskNode> blocked = graph.propagateBlocked();

            assertThat(blocked).extracting(TaskNode::id).containsExactlyInAnyOrder("b", "c");
            assertThat(graph.node("c").orElseThrow().state()).isEqualTo(TaskState.BLOCKED);
        }
    }

    @Nested
    @DisplayName("dynamic re-planning")
    class Replanning {

        @Test
        void a_revision_can_replace_pending_work_with_a_different_shape() {
            // Original plan: a -> b -> d
            WorkflowGraph graph = new WorkflowGraph(List.of(
                    node("a"), node("b", "a"), node("d", "b")));
            succeed(graph, "a");

            // New information arrives: b is wrong, the work splits into x and y.
            WorkflowGraph.PlanDelta delta = graph.applyRevision(new WorkflowGraph.PlanRevision(
                    List.of(node("x", "a"), node("y", "x")),
                    List.of("b", "d"),
                    "clarification changed the approach"));

            assertThat(delta.added()).containsExactly("x", "y");
            assertThat(delta.superseded()).containsExactly("b", "d");
            assertThat(graph.node("b").orElseThrow().state()).isEqualTo(TaskState.SUPERSEDED);
            assertThat(graph.readyTasks()).extracting(TaskNode::id).containsExactly("x");
        }

        @Test
        void completed_work_is_never_superseded_because_its_side_effects_already_happened() {
            WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b", "a")));
            succeed(graph, "a");

            WorkflowGraph.PlanDelta delta = graph.applyRevision(
                    new WorkflowGraph.PlanRevision(List.of(), List.of("a"), "try to undo history"));

            assertThat(delta.superseded()).isEmpty();
            assertThat(graph.node("a").orElseThrow().state()).isEqualTo(TaskState.SUCCEEDED);
        }

        @Test
        void a_running_task_cannot_be_superseded_out_from_under_the_executor() {
            WorkflowGraph graph = new WorkflowGraph(List.of(node("a")));
            graph.node("a").orElseThrow().markRunning();

            assertThatThrownBy(() -> graph.applyRevision(
                    new WorkflowGraph.PlanRevision(List.of(), List.of("a"), "mid-flight change")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("while it is running");
        }

        @Test
        void a_revision_that_would_create_a_cycle_is_rejected() {
            WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b", "a")));

            // A planner that emits two mutually dependent nodes must not be able to
            // deadlock the executor: the cycle is caught when the revision is applied.
            assertThatThrownBy(() -> graph.applyRevision(new WorkflowGraph.PlanRevision(
                    List.of(node("c", "d"), node("d", "c")), List.of(), "bad plan")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cycle");
        }
    }

    private static void succeed(WorkflowGraph graph, String id) {
        TaskNode node = graph.node(id).orElseThrow();
        node.markRunning();
        node.markSucceeded();
    }
}
