package com.kilari.agentic.orchestration;

import java.time.Instant;
import java.util.Objects;

/**
 * A typed output produced by one agent and consumed by later ones.
 *
 * <p>Artifacts carry the revision they were produced at. That is what makes
 * staleness detectable: when an upstream artifact is replaced at a higher
 * revision, every downstream artifact still sitting at the old revision is known
 * to have been derived from assumptions that have since changed.
 */
public record Artifact(
        String key,
        AgentType producedBy,
        String taskId,
        int revision,
        String content,
        Instant producedAt) {

    public Artifact {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(producedBy, "producedBy");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(producedAt, "producedAt");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
    }

    public static Artifact of(String key, AgentType producedBy, String taskId, int revision, String content) {
        return new Artifact(key, producedBy, taskId, revision, content, Timestamps.now());
    }

    /** True when this artifact was derived from an older view of the world. */
    public boolean isStaleAt(int currentRevision) {
        return revision < currentRevision;
    }
}
