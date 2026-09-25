package hr.hrg.webview.core;

import java.util.List;

/**
 * One request from a page to change a file: the bytes it read, the edits it proposes, and whether this is a
 * rehearsal.
 *
 * <p>{@code expectedDigest} is not optional and cannot be blank. A write without it would be a merge, and
 * merging is the thing this contract refuses to do: if the file changed since the page read it, the only safe
 * answers are "look again" ({@code 409}) or "here is the rewrite you asked for" — never "I applied your edit to
 * something you have not seen".
 *
 * <p>{@code dryRun} is the default flow, not a debugging extra (plan Q1: a proposal is shown as a diff and
 * written only when the reader accepts it). {@code true} means "tell me what would happen and change nothing".
 */
public record EditRequest(String filePath, String expectedDigest, List<TextEdit> edits, boolean dryRun) {

    public EditRequest {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("an edit request must name a file");
        }
        if (expectedDigest == null || expectedDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "an edit request must carry the digest of the content the page read");
        }
        edits = edits == null ? List.of() : List.copyOf(edits);
    }

    /** A rehearsal, which is what a page should send first. */
    public static EditRequest proposal(String filePath, String expectedDigest, List<TextEdit> edits) {
        return new EditRequest(filePath, expectedDigest, edits, true);
    }

    /** The same request with the rehearsal turned off, which is what the reader's "apply" does. */
    public EditRequest accepted() {
        return new EditRequest(filePath, expectedDigest, edits, false);
    }
}
