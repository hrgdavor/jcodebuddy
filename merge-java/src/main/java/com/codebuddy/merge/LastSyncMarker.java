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
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.ObjectId;

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
 * <h2>The commit field must be an object id</h2>
 *
 * <p>{@link #upstreamCommit()} is the one field that reaches the repository as a
 * revision expression, and a revision expression is not a record of the past: a
 * branch name, a tag or {@code HEAD} means something different tomorrow than it means
 * today. A marker is only a fact about this branch's history if the value in it
 * cannot move, so a marker naming anything but a full object id is refused by
 * {@link #requireCommitId(Path)} rather than resolved - see
 * {@link SyncMarkerException} for what a movable name would have cost.
 *
 * <p>Parsing stays permissive and validation is separate, on purpose. This type is a
 * record of a file's contents, and one is legitimately constructed and compared in
 * contexts that have no repository and no real commit (a unit test of
 * {@link #differsFrom}); the refusal belongs where the value would acquire the power
 * to choose a base.
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

    /**
     * The shape of a commit id: 40 hex characters for SHA-1, 64 for SHA-256.
     *
     * <p>Nothing shorter. An abbreviated id is legal to git and would usually resolve,
     * but it is not what this module writes, it can be ambiguous, and shortening is the
     * beginning of the same slope that ends with a branch name. A marker is read once
     * per run, so being exact costs nothing and removes a class of doubt.
     */
    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");

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
     * True when a value is a full commit id rather than a name that can move.
     *
     * <p>The distinction is the marker's whole safety property: {@code eecaadf...} can
     * only ever mean the commit it names, while {@code upstream} means whatever that
     * branch happens to point at when it is read.
     */
    public static boolean isCommitId(String value) {
        return value != null && COMMIT_ID.matcher(value).matches();
    }

    /**
     * This marker's commit as an object id, or an error saying why it is not one.
     *
     * <p>The only way to get a usable base out of a marker, so that failing to check is
     * not the easy path. A caller that has a repository should use this rather than
     * {@link #upstreamCommit()}, which is the raw field.
     *
     * <p>Deliberately does not consult the repository: "is this an object id" is a
     * question about the file's format, and this type owns the format. "Is it a commit
     * this repository can read" is a question about a repository, and belongs to the
     * caller that has one.
     *
     * @param historyRoot the branch's history directory, used both to locate the file
     *                    for an accurate message and to name it for the reader
     * @throws SyncMarkerException when the field is not a full commit id
     */
    public ObjectId requireCommitId(Path historyRoot) {
        if (!isCommitId(upstreamCommit)) {
            throw new SyncMarkerException("the sync marker at " + pathFor(historyRoot)
                + " records the base as '" + upstreamCommit + "', which is not a commit id."
                + " A branch name, a tag or a revision expression means whatever it points at"
                + " when it is read, and a base that has moved up to the upstream makes a"
                + " conflicting merge look clean - so this is refused rather than resolved."
                + " Write the full 40-character commit id there, or delete the marker to start"
                + " again from a merge base.");
        }
        try {
            return ObjectId.fromString(upstreamCommit);
        } catch (RuntimeException e) {
            // A 64-character id in a SHA-1 repository lands here.
            throw new SyncMarkerException("the sync marker at " + pathFor(historyRoot)
                + " records the base as '" + upstreamCommit + "', which this repository"
                + " cannot use as an object id (" + e.getMessage() + "). Write the id this"
                + " repository uses, or delete the marker to start again from a merge base.");
        }
    }

    /**
     * Read a branch's marker, if one has been recorded.
     *
     * <p>An absent file is absence; a file that is present but names no commit at all is
     * also absence, because a blank field names nothing and there is no wrong answer to
     * fall into - see {@link #parse(String)}. A file that names something
     * <em>unusable</em> is not absence, and is refused by {@link #requireCommitId(Path)}
     * rather than reported as "never synced".
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
     * <p>This reads a file, and only that. A marker with no commit field, or a blank one,
     * is absent: a missing marker means "first sync", which the caller can handle, and
     * guessing a commit from a malformed file would be worse than not knowing.
     *
     * <p>A commit field holding something that is <em>not</em> a commit id is parsed
     * through unchanged rather than rejected here, because rejecting it is a decision
     * about using it and this method never uses anything. The refusal happens in
     * {@link #requireCommitId(Path)}, which is the only supported way to turn a marker
     * into a base.
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
