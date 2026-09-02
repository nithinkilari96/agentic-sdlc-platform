package com.kilari.agentic.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One proposed change to one file.
 *
 * <p>A proposal, not an action. The model produces these; the controlled tool
 * layer decides whether any of them may touch the filesystem. Keeping the
 * proposal inert until it has been authorized is what stops a generated path
 * like {@code ../../.ssh/authorized_keys} from ever being opened.
 */
public record FileChange(String path, Operation operation, String content) {

    public enum Operation {
        CREATE,
        MODIFY,
        DELETE
    }

    public FileChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(operation, "operation");
        if (operation != Operation.DELETE) {
            Objects.requireNonNull(content, "content required for " + operation);
        }
    }

    /**
     * SHA-256 of the proposed content, used for optimistic locking: the patch
     * applier records what it wrote, and rollback verification re-hashes the file
     * afterwards to prove the restore actually landed.
     */
    public String contentHash() {
        return sha256(content == null ? "" : content);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public long sizeBytes() {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }
}
