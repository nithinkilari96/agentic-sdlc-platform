package com.kilari.agentic.governance;

import java.util.Locale;

/**
 * Separation of duties between the person who runs a workflow and the person who
 * approves its output.
 *
 * <p>Header-based and deliberately simple — this is a prototype boundary, not an
 * authentication system, and pretending otherwise would be worse than being
 * explicit about it. What it does establish is that the two capabilities are
 * distinct in the design: an operator cannot approve, so the approval gate
 * requires a second party by construction rather than by convention.
 *
 * <p>A production deployment replaces the header check with the identity provider
 * and keeps the same distinction.
 */
public enum Role {

    /** May start workflows and answer clarification requests. */
    OPERATOR,

    /** May approve or reject a validated change. Cannot start runs. */
    APPROVER;

    public static Role parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ForbiddenException("no role supplied; set the X-Role header");
        }
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("unknown role: " + value);
        }
    }

    public static void require(String suppliedRole, Role required) {
        Role actual = parse(suppliedRole);
        if (actual != required) {
            throw new ForbiddenException(
                    "this action requires the %s role, but the caller is %s".formatted(required, actual));
        }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }
}
