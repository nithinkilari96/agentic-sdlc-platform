package com.kilari.agentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * The persisted form of a workflow run.
 *
 * <p>One row per workflow, overwritten on each checkpoint, holding everything
 * needed to reconstruct the aggregate: the graph with each node's state, the
 * context artifacts, the human decisions received, and the counters the metrics
 * are derived from. The audit lineage lives separately in
 * {@link DecisionRecordEntity} because it is append-only — history must not be
 * rewritten by a later checkpoint.
 *
 * <p>The {@code @Version} column guards against two nodes checkpointing the same
 * run concurrently. Today the engine runs single-process so it should never
 * trigger; it is here because the moment this is deployed on more than one
 * replica, a silent last-write-wins would corrupt state in a way that is very
 * hard to diagnose after the fact.
 */
@Entity
@Table(name = "workflow_checkpoint")
public class WorkflowCheckpointEntity {

    @Id
    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Lob
    @Column(name = "requirement", nullable = false)
    private String requirement;

    @Lob
    @Column(name = "clarification")
    private String clarification;

    @Column(name = "approver", length = 128)
    private String approver;

    @Lob
    @Column(name = "terminal_reason")
    private String terminalReason;

    @Column(name = "workspace_path", length = 1024)
    private String workspacePath;

    @Column(name = "context_revision", nullable = false)
    private int contextRevision;

    /** Serialised node states: id, agent type, dependencies, state, attempts. */
    @Lob
    @Column(name = "graph_json", nullable = false)
    private String graphJson;

    /** Serialised context artifacts. */
    @Lob
    @Column(name = "artifacts_json", nullable = false)
    private String artifactsJson;

    @Column(name = "repair_rounds", nullable = false)
    private int repairRounds;

    @Column(name = "rollback_count", nullable = false)
    private int rollbackCount;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "first_failure_at")
    private Instant firstFailureAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "checkpointed_at", nullable = false)
    private Instant checkpointedAt;

    @Version
    @Column(name = "version")
    private Long version;

    protected WorkflowCheckpointEntity() {
        // for JPA
    }

    public WorkflowCheckpointEntity(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getRequirement() {
        return requirement;
    }

    public void setRequirement(String requirement) {
        this.requirement = requirement;
    }

    public String getClarification() {
        return clarification;
    }

    public void setClarification(String clarification) {
        this.clarification = clarification;
    }

    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    public String getTerminalReason() {
        return terminalReason;
    }

    public void setTerminalReason(String terminalReason) {
        this.terminalReason = terminalReason;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public int getContextRevision() {
        return contextRevision;
    }

    public void setContextRevision(int contextRevision) {
        this.contextRevision = contextRevision;
    }

    public String getGraphJson() {
        return graphJson;
    }

    public void setGraphJson(String graphJson) {
        this.graphJson = graphJson;
    }

    public String getArtifactsJson() {
        return artifactsJson;
    }

    public void setArtifactsJson(String artifactsJson) {
        this.artifactsJson = artifactsJson;
    }

    public int getRepairRounds() {
        return repairRounds;
    }

    public void setRepairRounds(int repairRounds) {
        this.repairRounds = repairRounds;
    }

    public int getRollbackCount() {
        return rollbackCount;
    }

    public void setRollbackCount(int rollbackCount) {
        this.rollbackCount = rollbackCount;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFirstFailureAt() {
        return firstFailureAt;
    }

    public void setFirstFailureAt(Instant firstFailureAt) {
        this.firstFailureAt = firstFailureAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCheckpointedAt() {
        return checkpointedAt;
    }

    public void setCheckpointedAt(Instant checkpointedAt) {
        this.checkpointedAt = checkpointedAt;
    }
}
