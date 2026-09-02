package com.kilari.agentic.governance;

import com.kilari.agentic.agent.ContextKeys;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Governance behaviour that only shows up under concurrency or on the paths a
 * happy-path test never reaches.
 */
@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:governance;DB_CLOSE_DELAY=-1",
        "agentic.workspaces.root=build/test-workspaces-governance"
})
class GovernanceIT {

    @Autowired
    private WorkflowService workflows;

    @Test
    @DisplayName("only one of several simultaneous decisions is applied")
    void concurrent_decisions_do_not_both_take_effect() throws Exception {
        WorkflowRun run = workflows.start(
                "Build a URL shortener service with create and resolve APIs.",
                false, WorkflowPlanner.PlanShape.FULL_DELIVERY);
        assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        // Four approvers and four rejecters arriving at the same instant. Without
        // serialisation, several can pass the state check before any of them
        // transitions the run - and a reject landing after an approve would roll
        // back a change that was already accepted.
        List<Callable<Void>> decisions = List.of(
                decision(() -> workflows.approve(run.workflowId(), "a@example.com", "ok"), succeeded, refused),
                decision(() -> workflows.reject(run.workflowId(), "b@example.com", "no"), succeeded, refused),
                decision(() -> workflows.approve(run.workflowId(), "c@example.com", "ok"), succeeded, refused),
                decision(() -> workflows.reject(run.workflowId(), "d@example.com", "no"), succeeded, refused),
                decision(() -> workflows.approve(run.workflowId(), "e@example.com", "ok"), succeeded, refused),
                decision(() -> workflows.reject(run.workflowId(), "f@example.com", "no"), succeeded, refused));

        try (ExecutorService executor = Executors.newFixedThreadPool(decisions.size())) {
            for (Future<Void> future : executor.invokeAll(decisions)) {
                future.get();
            }
        }

        assertThat(succeeded.get())
                .as("exactly one decision may take effect")
                .isEqualTo(1);
        assertThat(refused.get()).isEqualTo(decisions.size() - 1);

        // And the run landed in a terminal state consistent with a single decision.
        assertThat(run.state().isTerminal()).isTrue();
        assertThat(run.state())
                .isIn(WorkflowState.COMPLETED, WorkflowState.FAILED);
    }

    @Test
    @DisplayName("ambiguity is caught on the full plan too, without being told to expect it")
    void ambiguity_does_not_depend_on_the_caller_knowing_in_advance() {
        // The AMBIGUITY_PROBE shape is an optimisation - it avoids planning nine
        // tasks for a requirement that will not survive the first one. It is not
        // how ambiguity is detected, and a caller who does not use it gets the
        // same protection.
        WorkflowRun run = workflows.start(
                "Improve analytics", true, WorkflowPlanner.PlanShape.FULL_DELIVERY);

        assertThat(run.state())
                .as("the requirement agent parks the run regardless of which plan it started with")
                .isEqualTo(WorkflowState.AWAITING_CLARIFICATION);

        // Nothing downstream ran, even though the full plan contained those nodes.
        assertThat(run.context().get(ContextKeys.PATCH_IMPLEMENTATION)).isEmpty();
        assertThat(run.context().content(ContextKeys.REQUIREMENT_QUESTIONS))
                .hasValueSatisfying(questions -> assertThat(questions).isNotBlank());
    }

    private Callable<Void> decision(Runnable action, AtomicInteger succeeded, AtomicInteger refused) {
        return () -> {
            try {
                action.run();
                succeeded.incrementAndGet();
            } catch (IllegalStateException expected) {
                // The run was already decided by whoever got the lock first.
                refused.incrementAndGet();
            }
            return null;
        };
    }
}
