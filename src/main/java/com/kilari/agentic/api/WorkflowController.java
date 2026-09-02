package com.kilari.agentic.api;

import com.kilari.agentic.agent.ContextKeys;
import com.kilari.agentic.governance.Role;
import com.kilari.agentic.orchestration.DecisionRecord;
import com.kilari.agentic.orchestration.TaskNode;
import com.kilari.agentic.orchestration.WorkflowPlanner;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.service.WorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operator and approver surface.
 *
 * <p>Separation of duties is enforced here rather than assumed: starting a run
 * and approving its output require different roles. An operator who could also
 * approve their own run would make the approval gate ceremonial — the whole
 * point is that a second party looks at the evidence.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WorkflowSummary> start(@RequestBody StartRequest request,
                                                 @RequestHeader(value = "X-Role", defaultValue = "OPERATOR")
                                                 String role) {
        Role.require(role, Role.OPERATOR);

        WorkflowRun run = service.start(
                request.requirement(),
                Boolean.TRUE.equals(request.brownfield()),
                request.planShape());

        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowSummary.from(run));
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<WorkflowSummary> status(@PathVariable String workflowId) {
        return service.find(workflowId)
                .map(run -> ResponseEntity.ok(WorkflowSummary.from(run)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<String>> list() {
        return ResponseEntity.ok(service.listWorkflows());
    }

    /** The full decision lineage — who decided what, at which context revision. */
    @GetMapping("/{workflowId}/lineage")
    public ResponseEntity<List<LineageEntry>> lineage(@PathVariable String workflowId) {
        return service.find(workflowId)
                .map(run -> ResponseEntity.ok(run.context().lineage().stream()
                        .map(LineageEntry::from)
                        .toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The current graph, including superseded nodes, so re-planning is visible. */
    @GetMapping("/{workflowId}/graph")
    public ResponseEntity<List<TaskSummary>> graph(@PathVariable String workflowId) {
        return service.find(workflowId)
                .map(run -> ResponseEntity.ok(run.graph().nodes().stream()
                        .map(TaskSummary::from)
                        .toList()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{workflowId}/clarify")
    public ResponseEntity<WorkflowSummary> clarify(@PathVariable String workflowId,
                                                   @RequestBody ClarifyRequest request,
                                                   @RequestHeader(value = "X-Role", defaultValue = "OPERATOR")
                                                   String role) {
        Role.require(role, Role.OPERATOR);
        service.clarify(workflowId, request.clarification());
        return status(workflowId);
    }

    @PostMapping("/{workflowId}/approve")
    public ResponseEntity<WorkflowSummary> approve(@PathVariable String workflowId,
                                                   @RequestBody DecisionRequest request,
                                                   @RequestHeader("X-Role") String role,
                                                   @RequestHeader("X-User") String user) {
        Role.require(role, Role.APPROVER);
        service.approve(workflowId, user, request.comment());
        return status(workflowId);
    }

    @PostMapping("/{workflowId}/reject")
    public ResponseEntity<WorkflowSummary> reject(@PathVariable String workflowId,
                                                  @RequestBody DecisionRequest request,
                                                  @RequestHeader("X-Role") String role,
                                                  @RequestHeader("X-User") String user) {
        Role.require(role, Role.APPROVER);
        service.reject(workflowId, user, request.comment());
        return status(workflowId);
    }

    @ExceptionHandler(Role.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(Role.ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    // ---- payloads ----------------------------------------------------------

    public record StartRequest(String requirement, Boolean brownfield,
                               Boolean expectAmbiguity, Boolean testsAndDocsOnly) {

        /**
         * Resolves the requested flags to a plan shape.
         *
         * <p>Rejects asking for two shapes at once rather than silently preferring
         * one: the caller has expressed something contradictory, and picking a
         * winner would hide that until they wondered why half their plan was
         * missing.
         */
        WorkflowPlanner.PlanShape planShape() {
            boolean ambiguous = Boolean.TRUE.equals(expectAmbiguity);
            boolean testsAndDocs = Boolean.TRUE.equals(testsAndDocsOnly);

            if (ambiguous && testsAndDocs) {
                throw new IllegalArgumentException(
                        "expectAmbiguity and testsAndDocsOnly select different plans; choose one");
            }
            if (ambiguous) {
                return WorkflowPlanner.PlanShape.AMBIGUITY_PROBE;
            }
            if (testsAndDocs) {
                return WorkflowPlanner.PlanShape.TESTS_AND_DOCS;
            }
            return WorkflowPlanner.PlanShape.FULL_DELIVERY;
        }
    }

    public record ClarifyRequest(String clarification) {
    }

    public record DecisionRequest(String comment) {
    }

    public record WorkflowSummary(
            String workflowId,
            String state,
            String requirement,
            int contextRevision,
            int taskCount,
            int repairRounds,
            int rollbacks,
            int retries,
            long elapsedMs,
            List<String> openQuestions,
            String evidence,
            String terminalReason) {

        static WorkflowSummary from(WorkflowRun run) {
            return new WorkflowSummary(
                    run.workflowId(),
                    run.state().name(),
                    run.requirement(),
                    run.context().revision(),
                    run.graph().nodes().size(),
                    run.repairRounds(),
                    run.rollbackCount(),
                    run.retryCount(),
                    run.elapsed().toMillis(),
                    run.context().content(ContextKeys.REQUIREMENT_QUESTIONS)
                            .map(text -> List.of(text.split("\n")))
                            .orElse(List.of()),
                    run.context().content(ContextKeys.RELEASE_EVIDENCE).orElse(null),
                    run.terminalReason().orElse(null));
        }
    }

    public record TaskSummary(String id, String agent, String state, int attempts,
                              List<String> dependsOn, String lastFailure) {

        static TaskSummary from(TaskNode node) {
            return new TaskSummary(
                    node.id(),
                    node.agentType().name(),
                    node.state().name(),
                    node.attempts(),
                    List.copyOf(node.dependsOn()),
                    node.lastFailureReason());
        }
    }

    public record LineageEntry(String taskId, String actor, String type, int revision,
                               String summary, Instant at) {

        static LineageEntry from(DecisionRecord record) {
            return new LineageEntry(
                    record.taskId(),
                    record.actor().name(),
                    record.type().name(),
                    record.contextRevision(),
                    record.summary(),
                    record.recordedAt());
        }
    }
}
