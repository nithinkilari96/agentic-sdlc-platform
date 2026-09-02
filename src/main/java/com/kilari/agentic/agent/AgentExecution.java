package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.WorkflowContext;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything an agent is permitted to see when it runs.
 *
 * <p>Scoped deliberately. An agent gets the workflow context, its own task
 * identity and the workspace path — not the graph, not the executor, not the
 * other agents. An agent that cannot reach the orchestrator cannot reorder its
 * own dependencies or mark itself complete.
 */
public record AgentExecution(
        String workflowId,
        String taskId,
        String requirement,
        String clarification,
        WorkflowContext context,
        Path workspace,
        int attempt) {

    public AgentExecution {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(context, "context");
    }

    public Optional<String> clarificationText() {
        return Optional.ofNullable(clarification).filter(s -> !s.isBlank());
    }

    /**
     * The requirement as the agent should read it — the original text plus any
     * human clarification, so a resumed run reasons about the complete picture
     * rather than the question that was originally too vague to answer.
     */
    public String effectiveRequirement() {
        return clarificationText()
                .map(c -> requirement + "\n\nClarification: " + c)
                .orElse(requirement);
    }

    public boolean isRetry() {
        return attempt > 1;
    }
}
