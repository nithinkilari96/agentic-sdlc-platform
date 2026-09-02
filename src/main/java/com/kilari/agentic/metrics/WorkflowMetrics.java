package com.kilari.agentic.metrics;

import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.orchestration.WorkflowState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The reliability metrics the platform is judged on.
 *
 * <p>Each of the five named measures maps to a specific instrument here, rather
 * than to a general claim that the system exports telemetry:
 *
 * <ul>
 *   <li><b>Success rate</b> — {@code workflow.outcome} counter, tagged by terminal state.
 *       Safe-stops are counted separately from failures, because a system that
 *       correctly refused to proceed has not malfunctioned and folding the two
 *       together would make good behaviour look like breakage.</li>
 *   <li><b>Retry frequency</b> — {@code task.retry} counter, tagged by agent, so it
 *       is visible <em>which</em> stage is unreliable rather than only that
 *       something is.</li>
 *   <li><b>Rollback frequency</b> — {@code workspace.rollback} counter.</li>
 *   <li><b>MTTR</b> — {@code workflow.mttr} timer, measuring from the first
 *       validation failure to the run reaching a good terminal state. Only
 *       recorded for runs that actually recovered; averaging in runs that never
 *       broke would report a number that improves as fewer things go wrong.</li>
 *   <li><b>End-to-end latency</b> — {@code workflow.duration} timer.</li>
 * </ul>
 */
public class WorkflowMetrics {

    private final MeterRegistry registry;

    public WorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordWorkflowStarted() {
        Counter.builder("workflow.started")
                .description("Workflow runs started")
                .register(registry)
                .increment();
    }

    /** Terminal outcome, end-to-end latency, and MTTR when the run recovered. */
    public void recordWorkflowOutcome(WorkflowRun run) {
        WorkflowState state = run.state();
        if (!state.isTerminal()) {
            return;
        }

        Counter.builder("workflow.outcome")
                .description("Workflow terminal outcomes")
                .tag("state", state.name())
                .tag("succeeded", String.valueOf(state == WorkflowState.COMPLETED))
                .register(registry)
                .increment();

        Timer.builder("workflow.duration")
                .description("End-to-end workflow latency")
                .tag("state", state.name())
                .register(registry)
                .record(run.elapsed());

        run.firstFailureAt().ifPresent(failedAt -> {
            if (state == WorkflowState.COMPLETED) {
                Timer.builder("workflow.mttr")
                        .description("Time from first validation failure to successful completion")
                        .register(registry)
                        .record(Duration.between(failedAt, run.finishedAt().orElseThrow()));
            }
        });
    }

    public void recordTaskSuccess(AgentType agentType) {
        Counter.builder("task.outcome")
                .tag("agent", agentType.name())
                .tag("result", "success")
                .register(registry)
                .increment();
    }

    public void recordTaskExhausted(AgentType agentType) {
        Counter.builder("task.outcome")
                .tag("agent", agentType.name())
                .tag("result", "exhausted")
                .register(registry)
                .increment();
    }

    public void recordRetry(AgentType agentType) {
        Counter.builder("task.retry")
                .description("Task attempts beyond the first")
                .tag("agent", agentType.name())
                .register(registry)
                .increment();
    }

    public void recordTaskDuration(AgentType agentType, long nanos, boolean succeeded) {
        Timer.builder("task.duration")
                .tag("agent", agentType.name())
                .tag("succeeded", String.valueOf(succeeded))
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordRollback() {
        Counter.builder("workspace.rollback")
                .description("Verified workspace rollbacks performed")
                .register(registry)
                .increment();
    }

    public void recordValidationFailure() {
        Counter.builder("validation.failure")
                .description("Build or test validations that failed")
                .register(registry)
                .increment();
    }

    public void recordClarificationRequested() {
        Counter.builder("workflow.clarification")
                .description("Runs parked for human clarification instead of guessing")
                .register(registry)
                .increment();
    }

    public void recordSafeStop() {
        Counter.builder("workflow.safeStop")
                .description("Runs halted by a guardrail rather than an error")
                .register(registry)
                .increment();
    }

    public void recordApproval(boolean granted) {
        Counter.builder("workflow.approval")
                .tag("granted", String.valueOf(granted))
                .register(registry)
                .increment();
    }
}
