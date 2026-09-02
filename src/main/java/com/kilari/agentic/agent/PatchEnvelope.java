package com.kilari.agentic.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the file-change envelope that code-producing agents emit.
 *
 * <p>The wire format is deliberately not JSON. Source files embedded in JSON
 * strings have to escape every quote, backslash and newline, which is both
 * token-expensive and a reliable source of malformed output. A line-delimited
 * envelope lets the content pass through byte-for-byte:
 *
 * <pre>
 * &lt;&lt;&lt;FILE path=src/main/java/Foo.java op=CREATE&gt;&gt;&gt;
 * ...file content, verbatim...
 * &lt;&lt;&lt;END&gt;&gt;&gt;
 * </pre>
 *
 * <p>Parsing is strict. Anything the model emits outside a well-formed envelope
 * — commentary, apologies, half-written markers — is discarded rather than
 * guessed at, and an unterminated envelope is an error. A patch we cannot parse
 * unambiguously is one we must not apply.
 */
public final class PatchEnvelope {

    private static final Pattern HEADER = Pattern.compile(
            "^<<<FILE\\s+path=(?<path>\\S+)\\s+op=(?<op>CREATE|MODIFY|DELETE)>>>$");

    private static final String TERMINATOR = "<<<END>>>";

    private PatchEnvelope() {
    }

    public static List<FileChange> parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return List.of();
        }

        List<FileChange> changes = new ArrayList<>();
        String[] lines = modelOutput.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            Matcher header = HEADER.matcher(lines[i].strip());
            if (!header.matches()) {
                i++;
                continue;
            }

            String path = header.group("path");
            FileChange.Operation op = FileChange.Operation.valueOf(header.group("op"));

            StringBuilder content = new StringBuilder();
            int j = i + 1;
            boolean terminated = false;
            while (j < lines.length) {
                if (lines[j].strip().equals(TERMINATOR)) {
                    terminated = true;
                    break;
                }
                content.append(lines[j]).append('\n');
                j++;
            }

            if (!terminated) {
                throw new MalformedPatchException(
                        "unterminated file envelope for path %s — refusing to apply a truncated patch"
                                .formatted(path));
            }

            changes.add(new FileChange(
                    path,
                    op,
                    op == FileChange.Operation.DELETE ? null : content.toString()));
            i = j + 1;
        }

        return List.copyOf(changes);
    }

    /** Renders changes back into the envelope format, used when building repair prompts. */
    public static String render(List<FileChange> changes) {
        StringBuilder sb = new StringBuilder();
        for (FileChange change : changes) {
            sb.append("<<<FILE path=").append(change.path())
                    .append(" op=").append(change.operation()).append(">>>\n");
            if (change.content() != null) {
                sb.append(change.content());
                if (!change.content().endsWith("\n")) {
                    sb.append('\n');
                }
            }
            sb.append(TERMINATOR).append('\n');
        }
        return sb.toString();
    }

    public static class MalformedPatchException extends RuntimeException {
        public MalformedPatchException(String message) {
            super(message);
        }
    }
}
