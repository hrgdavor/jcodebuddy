// {@link com.codebuddy.merge.LastSyncMarker} Records the upstream commit last merged into a branch.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The upstream commit that was current the last time a branch was brought up to
 * date — the module's {@code lastSynced}, and the only reference point that can
 * answer "what has the upstream done since I last looked".
 *
 * <h2>Why this is recorded and not derived</h2>
 *
 * <p>Git's merge base looks like the same thing and is not. The merge base is
 * <em>derived</em> from the commit graph, so a rebase silently changes it; it is also
 * recomputed from whatever the two tips happen to be, which is a property of the
 * graph rather than of this branch's history. A sync marker is a fact about this
 * branch — "I last saw the upstream at commit X" — and survives operations that
 * rewrite the graph.
 *
 * <p>See {@code docs/WHAT_IS_BASE.md} for why treating the two as interchangeable
 * produces conflicts that do not exist: an older base makes the upstream look as
 * though it re-added everything the branch already merged, and makes the branch look
 * as though it deleted it.
 *
 * <h2>Storage</h2>
 *
 * <p>One small file per branch under
 * {@code .jcodebuddy/merge-history/<branch>/last-sync}, holding a commit id and an
 * optional timestamp and description. It sits beside the decision history because it
 * is part of the same story: the decisions are what this branch concluded, the marker
 * is what it had seen when it concluded them.
 *
 * @param upstreamCommit the upstream commit id that was last merged in
 * @param upstreamRef    the ref it was reached through, e.g. {@code origin/main}
 * @param recordedAt     when the marker was written, as an ISO-8601 instant
 * @param note           an optional human note, e.g. "sync #3"
 */
public record LastSyncMarker(String upstreamCommit, String upstreamRef,
                             String recordedAt, String note) {

    /** File name used within a branch's history directory. */
    public static final String FILE_NAME = "last-sync";

    public LastSyncMarker {
        Objects.requireNonNull(upstreamCommit, "upstreamCommit");
        upstreamRef = upstreamRef == null ? "unknown" : upstreamRef;
        recordedAt = recordedAt == null ? "" : recordedAt;
        note = note == null ? "" : note;
    }

    /**
     * A marker for an upstream commit, stamped now.
     */
    public static LastSyncMarker of(String upstreamCommit, String upstreamRef) {
        return of(upstreamCommit, upstreamRef, "");
    }

    /**
     * A marker for an upstream commit, stamped now, with a note about how it arose.
     */
    public static LastSyncMarker of(String upstreamCommit, String upstreamRef, String note) {
        return new LastSyncMarker(upstreamCommit, upstreamRef,
            java.time.Instant.now().toString(), note);
    }

    /**
     * True when the given upstream commit is <b>not</b> the one this marker recorded.
     *
     * <p>Named for what it can actually establish. Ordering the two would need the
     * commit graph, which this marker deliberately does not carry - and a marker that
     * could be ordered would not survive a rebase. So this reports <em>difference</em>:
     * "the upstream is not where I left it", which is the signal a caller needs.
     */
    public boolean differsFrom(String currentUpstreamCommit) {
        return currentUpstreamCommit != null && !upstreamCommit.equals(currentUpstreamCommit);
    }

    /**
     * Where a branch's marker lives.
     */
    public static Path pathFor(Path historyRoot) {
        return historyRoot.resolve(FILE_NAME);
    }

    /**
     * Read a branch's marker, if one has been recorded.
     */
    public static Optional<LastSyncMarker> read(Path historyRoot) {
        Path path = pathFor(historyRoot);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the sync marker at " + path, e);
        }
    }

    /**
     * Write a branch's marker, creating the directory if needed.
     */
    public void write(Path historyRoot) {
        Path path = pathFor(historyRoot);
        try {
            Files.createDirectories(historyRoot);
            Path temp = historyRoot.resolve(FILE_NAME + ".tmp");
            Files.writeString(temp, render(), StandardCharsets.UTF_8);
            Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the sync marker at " + path, e);
        }
    }

    /**
     * Serialise as one {@code key = value} per line.
     *
     * <p>Deliberately not JSON: this is a two-field file that a human may well
     * inspect or edit during a merge, and the format should be obvious at a glance.
     */
    String render() {
        return "upstreamCommit = " + upstreamCommit + "\n"
            + "upstreamRef = " + upstreamRef + "\n"
            + "recordedAt = " + recordedAt + "\n"
            + "note = " + note + "\n";
    }

    /**
     * Parse the file written by {@link #render()}.
     *
     * <p>An unreadable or incomplete marker is treated as absent rather than as an
     * error: a missing marker means "first sync", which the caller can handle, and
     * guessing a commit from a malformed file would be worse than not knowing.
     */
    static Optional<LastSyncMarker> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String commit = null;
        String ref = "unknown";
        String recordedAt = "";
        String note = "";

        for (String line : text.split("\n")) {
            int separator = line.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            switch (key) {
                case "upstreamCommit" -> commit = value;
                case "upstreamRef" -> ref = value;
                case "recordedAt" -> recordedAt = value;
                case "note" -> note = value;
                default -> {
                    // Unknown keys are ignored so a newer writer does not break an
                    // older reader.
                }
            }
        }

        if (commit == null || commit.isBlank() || "null".equals(commit)) {
            return Optional.empty();
        }
        return Optional.of(new LastSyncMarker(commit, ref, recordedAt, note));
    }

    /**
     * A short description for a merge log.
     */
    public String describe() {
        return shortCommit() + " (" + upstreamRef + ")"
            + (recordedAt.isBlank() ? "" : " recorded " + recordedAt)
            + (note.isBlank() ? "" : " - " + note);
    }

    /**
     * The commit id abbreviated for display.
     */
    public String shortCommit() {
        return upstreamCommit.length() <= 12 ? upstreamCommit : upstreamCommit.substring(0, 12);
    }

    @Override
    public String toString() {
        return "LastSyncMarker{" + describe() + '}';
    }
}
