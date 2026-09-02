package com.kilari.agentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One immutable entry in the audit lineage.
 *
 * <p>Append-only by design: rows are inserted and never updated or deleted. That
 * is the property that makes the trail worth anything — a log that can be
 * rewritten after the fact records what someone later wanted to have happened,
 * not what did.
 *
 * <p>Every row names its actor, so "the agent chose this" and "a human approved
 * this" remain distinguishable when a decision is questioned months later. The
 * context revision is stored too, because the same decision can be correct at
 * one revision and wrong at the next.
 */
@Entity
@Table(name = "decision_record", indexes = {
        @Index(name = "idx_decision_workflow", columnList = "workflow_id, recorded_at")
})
public class DecisionRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "task_id", length = 128)
    private String taskId;

    @Column(name = "actor", nullable = false, length = 32)
    private String actor;

    @Column(name = "decision_type", nullable = false, length = 48)
    private String decisionType;

    @Column(name = "context_revision", nullable = false)
    private int contextRevision;

    @Lob
    @Column(name = "summary", nullable = false)
    private String summary;

    @Lob
    @Column(name = "evidence_json")
    private String evidenceJson;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected DecisionRecordEntity() {
        // for JPA
    }

    public DecisionRecordEntity(String workflowId, String taskId, String actor, String decisionType,
                                int contextRevision, String summary, String evidenceJson,
                                Instant recordedAt) {
        this.workflowId = workflowId;
        this.taskId = taskId;
        this.actor = actor;
        this.decisionType = decisionType;
        this.contextRevision = contextRevision;
        this.summary = summary;
        this.evidenceJson = evidenceJson;
        this.recordedAt = recordedAt;
    }

    public Long getId() {
        return id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getActor() {
        return actor;
    }

    public String getDecisionType() {
        return decisionType;
    }

    public int getContextRevision() {
        return contextRevision;
    }

    public String getSummary() {
        return summary;
    }

    public String getEvidenceJson() {
        return evidenceJson;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
