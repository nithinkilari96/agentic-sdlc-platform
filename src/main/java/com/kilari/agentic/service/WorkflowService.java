package com.kilari.agentic.service;

import com.kilari.agentic.agent.FileChange;
import com.kilari.agentic.agent.PatchEnvelope;
import com.kilari.agentic.metrics.WorkflowMetrics;
import com.kilari.agentic.orchestration.DecisionRecord;
import com.kilari.agentic.orchestration.WorkflowEngine;
import com.kilari.agentic.orchestration.WorkflowGraph;
import com.kilari.agentic.orchestration.WorkflowPlanner;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.orchestration.WorkflowState;
import com.kilari.agentic.persistence.WorkflowStore;
import com.kilari.agentic.provider.fixtures.GreenfieldFixture;
import com.kilari.agentic.tools.PatchApplier;
import com.kilari.agentic.tools.PathPolicy;
import com.kilari.agentic.tools.WorkspaceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Application-level entry point for running workflows.
 *
 * <p>Owns the things a run needs but the engine should not know about: workspace
 * creation, seeding a starting codebase for brownfield scenarios, and keeping
 * live runs addressable while they are parked waiting for a human.
 */
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowEngine engine;
    private final WorkflowStore store;
    private final WorkspaceFactory workspaces;
    private final WorkflowMetrics metrics;

    /**
     * Runs currently in memory.
     *
     * <p>A cache, not the source of truth — every run is also checkpointed, so a
     * process restart loses nothing but the convenience of not reading from the
     * database.
     */
    private final Map<String, WorkflowRun> active = new ConcurrentHashMap<>();

    /**
     * One lock per workflow, guarding human decisions.
     *
     * <p>The engine checks a run's state and then transitions it. Without a lock
     * those are two steps, so two approvers clicking at once — or an approve
     * racing a reject — can both pass the check and both act. The second one
     * would either double-transition a completed run or roll back a change that
     * was just approved.
     *
     * <p>Per workflow rather than global: decisions on unrelated runs have no
     * reason to queue behind each other. This is correct within one process;
     * across replicas it would need a lease in the database, which is the same
     * gap as distributed workflow ownership generally.
     */
    private final Map<String, ReentrantLock> decisionLocks = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowEngine engine, WorkflowStore store,
                           WorkspaceFactory workspaces, WorkflowMetrics metrics) {
        this.engine = engine;
        this.store = store;
        this.workspaces = workspaces;
        this.metrics = metrics;
    }

    /**
     * Starts a run and drives it until it settles or parks.
     *
     * @param seedExistingService when true the workspace is pre-populated with the
     *                            shortener service, making this a brownfield run
     *                            against real existing code
     * @param shape               which graph the run starts with. Chosen by the
     *                            caller rather than inferred from the requirement
     *                            text, so a keyword can never silently decide
     *                            whether the implementation step happens
     */
    public WorkflowRun start(String requirement, boolean seedExistingService,
                             WorkflowPlanner.PlanShape shape) {
        String workflowId = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        Path workspace = workspaces.create(workflowId);

        if (seedExistingService) {
            seedExistingCodebase(workspace);
        }

        WorkflowGraph graph = shape.toGraph();

        WorkflowRun run = new WorkflowRun(workflowId, requirement, graph, workspace);
        active.put(workflowId, run);
        metrics.recordWorkflowStarted();

        log.info("Starting workflow {} ({}, {} tasks, {})", workflowId, shape,
                graph.nodes().size(), seedExistingService ? "brownfield" : "greenfield");

        engine.drive(run);
        return run;
    }

    /**
     * Materialises the existing shortener into the workspace.
     *
     * <p>The seed is the greenfield scenario's own output rather than a separately
     * maintained copy. A hand-written fake would drift, and then the brownfield
     * run would be modifying a codebase that the platform never actually produces.
     */
    private void seedExistingCodebase(Path workspace) {
        List<FileChange> seed = new ArrayList<>();
        seed.addAll(PatchEnvelope.parse(GreenfieldFixture.IMPLEMENTATION));
        seed.addAll(PatchEnvelope.parse(GreenfieldFixture.tests()));

        new PatchApplier(new PathPolicy(workspace)).apply(seed);
        log.info("Seeded workspace {} with {} files of existing service code",
                workspace, seed.size());
    }

    public void clarify(String workflowId, String clarification) {
        withDecisionLock(workflowId, run -> engine.resumeWithClarification(run, clarification));
    }

    public void approve(String workflowId, String approver, String comment) {
        withDecisionLock(workflowId, run -> engine.approve(run, approver, comment));
    }

    public void reject(String workflowId, String approver, String reason) {
        withDecisionLock(workflowId, run -> engine.reject(run, approver, reason));
    }

    /**
     * Serialises human decisions on one workflow.
     *
     * <p>The state check inside the engine happens under this lock, so a second
     * caller arriving concurrently finds the run already transitioned and is
     * rejected by the same guard that would have rejected it a second later.
     * The loser gets a clear conflict rather than a silent double action.
     */
    private void withDecisionLock(String workflowId, Consumer<WorkflowRun> action) {
        ReentrantLock lock = decisionLocks.computeIfAbsent(workflowId, id -> new ReentrantLock());
        lock.lock();
        try {
            action.accept(require(workflowId));
        } finally {
            lock.unlock();
        }
    }

    public Optional<WorkflowRun> find(String workflowId) {
        WorkflowRun cached = active.get(workflowId);
        if (cached != null) {
            return Optional.of(cached);
        }
        // Falls through to storage, which is what makes a run survivable across a
        // restart: the caller does not need to know whether it is cached.
        Optional<WorkflowRun> loaded = store.load(workflowId);
        loaded.ifPresent(run -> active.put(workflowId, run));
        return loaded;
    }

    public List<String> listWorkflows() {
        return store.findAll();
    }

    private WorkflowRun require(String workflowId) {
        return find(workflowId).orElseThrow(() ->
                new IllegalArgumentException("no such workflow: " + workflowId));
    }

    /**
     * Recovers runs that were in flight when the process last stopped.
     *
     * <p>Parked runs are rehydrated but not driven — they are waiting on a person,
     * and a restart is not an answer. Runs that were mid-execution are driven
     * again from their last checkpoint.
     */
    public int recoverInterruptedRuns() {
        List<String> resumable = store.findResumable();
        int resumed = 0;

        for (String workflowId : resumable) {
            Optional<WorkflowRun> loaded = store.load(workflowId);
            if (loaded.isEmpty()) {
                continue;
            }
            WorkflowRun run = loaded.get();
            active.put(workflowId, run);

            if (run.state().isAwaitingHuman()) {
                log.info("Recovered workflow {} still awaiting a human decision ({})",
                        workflowId, run.state());
                continue;
            }

            log.info("Resuming interrupted workflow {} from state {}", workflowId, run.state());
            run.context().record(DecisionRecord.of(
                    workflowId, null,
                    DecisionRecord.Actor.ORCHESTRATOR,
                    DecisionRecord.DecisionType.RECOVERY_RESUMED,
                    run.context().revision(),
                    "Resumed after process restart from last checkpoint"));

            run.transitionTo(WorkflowState.RUNNING, "resumed after restart");
            engine.drive(run);
            resumed++;
        }

        if (!resumable.isEmpty()) {
            log.info("Recovery complete: {} of {} interrupted runs resumed",
                    resumed, resumable.size());
        }
        return resumed;
    }

    /** Waits for a run to reach a terminal or parked state. Used by tests and the demo. */
    public WorkflowRun awaitSettled(String workflowId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            WorkflowRun run = require(workflowId);
            if (run.state().isTerminal() || run.state().isAwaitingHuman()) {
                return run;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting workflow " + workflowId, e);
            }
        }
        throw new IllegalStateException(
                "workflow %s did not settle within %s".formatted(workflowId, timeout));
    }
}
