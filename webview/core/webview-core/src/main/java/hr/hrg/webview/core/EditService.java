package hr.hrg.webview.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;

/**
 * The ONE place a page's bytes become a write, exactly as {@link Navigator} is the one place a request becomes
 * a host call.
 *
 * <p>The rules it enforces, each of which is a decision in the plan rather than a nicety:
 *
 * <ol>
 *   <li><b>Same path jail as navigation</b> — the project root, symlinks resolved, no {@code ..} escape.</li>
 *   <li><b>A stale digest is a refusal, never a merge.</b> The page must send the digest of what it read; if
 *       the file has changed, the answer is {@code STALE} plus the current digest so the page can re-read. The
 *       one thing this never does is apply an edit to content the reader has not seen.</li>
 *   <li><b>A write is atomic</b> — a temporary file in the same directory, then a move. A page that is
 *       interrupted, or a process that dies, leaves either the old file or the new one, never half of each.</li>
 *   <li><b>The file's endings survive</b> ({@link EditableText}).</li>
 *   <li><b>Every applied write is checkpointed</b>, so {@code undo} restores the exact previous bytes.</li>
 *   <li><b>Writes are rate-limited by the same limiter as navigation</b>, because a page that can spin is a
 *       page that can rewrite a file in a loop.</li>
 * </ol>
 *
 * <p>{@code dryRun} is the default flow (plan Q1): {@link #propose} computes the diff and changes nothing, and
 * {@link #apply} writes only when the request says so. The asymmetry is deliberate — a page has to ask twice.
 */
public final class EditService {

    /** What happened, in enough detail for a transport to answer with the right status. */
    public enum Reason {
        /** The edit was applied, or would be under {@code dryRun}. */
        OK,
        /** The edit described exactly what is already there; nothing was written. */
        NO_CHANGE,
        /** The file changed since the page read it. Refused; the current digest is in the outcome. */
        STALE,
        /** The edits do not address this file: a bad position, an overlap, or no edits at all. */
        INVALID_EDIT,
        /** The path was empty or not a path. */
        INVALID_PATH,
        /** The path resolves outside the project. */
        OUTSIDE_PROJECT,
        /** Nothing is there. */
        NOT_FOUND,
        /** It is there and cannot be read, or is a directory. */
        NOT_READABLE,
        /** Too many requests in the window. */
        RATE_LIMITED,
        /** Nothing recorded to undo for this file. */
        NOTHING_TO_UNDO,
        /** Nothing recorded to redo for this file. */
        NOTHING_TO_REDO
    }

    /**
     * One answer.
     *
     * @param digest the digest of the content the operation leaves behind — what was written, what would be
     *               written under {@code dryRun}, or what is on disk now when the request was refused as
     *               {@code STALE}; the caller can render it and chain the next edit without a re-read
     * @param unifiedDiff the change, for the reader to accept; empty when the operation was not a proposal
     */
    public record Outcome(Reason reason, String filePath, String digest, boolean applied, String unifiedDiff,
                          String detail) {

        public boolean succeeded() {
            return reason == Reason.OK || reason == Reason.NO_CHANGE;
        }

        /** True when a write actually happened. */
        public boolean wrote() {
            return applied && reason == Reason.OK;
        }

        public static Outcome refused(Reason reason, String filePath, String detail) {
            return new Outcome(reason, filePath == null ? "" : filePath, "", false, "", detail);
        }
    }

    private final PathResolver resolver;
    private final RateLimiter rateLimiter;
    private final CheckpointStore checkpoints;

    public EditService(String projectRoot) {
        this(projectRoot, new CheckpointStore(),
                new RateLimiter(Navigator.RATE_LIMIT_COUNT, Navigator.RATE_LIMIT_WINDOW_MS, Clock.SYSTEM));
    }

    public EditService(String projectRoot, CheckpointStore checkpoints, RateLimiter rateLimiter) {
        this.resolver = PathResolver.forProject(projectRoot);
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
    }

    /** The checkpoint history, so a caller can report whether undo is available. */
    public CheckpointStore checkpoints() {
        return checkpoints;
    }

    /** Proposes: computes the diff and the resulting digest, and writes nothing. */
    public Outcome propose(EditRequest request) {
        return run(new EditRequest(request.filePath(), request.expectedDigest(), request.edits(), true));
    }

    /** Applies what the request says; {@code dryRun} still means "change nothing". */
    public Outcome apply(EditRequest request) {
        return run(request);
    }

    /** Restores the bytes recorded before the last write to this file. */
    public Outcome undo(String filePath) {
        return restore(filePath, false);
    }

    /** Reapplies what the last undo replaced. */
    public Outcome redo(String filePath) {
        return restore(filePath, true);
    }

    private Outcome run(EditRequest request) {
        if (!rateLimiter.tryAcquire()) {
            return Outcome.refused(Reason.RATE_LIMITED, request.filePath(),
                    rateLimiter.limit() + " requests per " + (rateLimiter.windowMillis() / 1000) + "s");
        }
        PathResolution resolution = resolver.resolve(request.filePath());
        if (resolution == null) {
            return Outcome.refused(Reason.INVALID_PATH, request.filePath(), "not a usable path");
        }
        if (resolution.escaped()) {
            return Outcome.refused(Reason.OUTSIDE_PROJECT, resolution.absolute(), "outside the project");
        }
        Path file = Path.of(resolution.absolute());
        if (!Files.exists(file)) {
            return Outcome.refused(Reason.NOT_FOUND, resolution.absolute(), "nothing is there");
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Outcome.refused(Reason.NOT_READABLE, resolution.absolute(),
                    "not a readable regular file");
        }

        byte[] before;
        try {
            before = Files.readAllBytes(file);
        } catch (IOException e) {
            return Outcome.refused(Reason.NOT_READABLE, resolution.absolute(), e.getMessage());
        }
        String currentDigest = SourceDigest.of(before);
        if (!SourceDigest.isWellFormed(request.expectedDigest())) {
            return new Outcome(Reason.INVALID_EDIT, resolution.absolute(), currentDigest, false, "",
                    "expectedDigest must look like " + SourceDigest.PREFIX + "<64 hex chars>");
        }
        if (!currentDigest.equalsIgnoreCase(request.expectedDigest())) {
            // The refusal that makes the API safe: never apply to content the caller has not seen.
            return new Outcome(Reason.STALE, resolution.absolute(), currentDigest, false, "",
                    "the file changed since it was read; re-read it and propose again");
        }

        EditableText text = EditableText.of(before);
        String edited;
        try {
            edited = text.apply(request.edits());
        } catch (IllegalArgumentException e) {
            return new Outcome(Reason.INVALID_EDIT, resolution.absolute(), currentDigest, false, "", e.getMessage());
        }
        byte[] after = text.render(edited);
        String afterDigest = SourceDigest.of(after);
        String diff = UnifiedDiff.between(request.filePath(), text.renderedOriginal(), text.renderedAfter(edited));

        if (Arrays.equals(before, after)) {
            return new Outcome(Reason.NO_CHANGE, resolution.absolute(), currentDigest, false, diff,
                    "the edit describes what is already there");
        }
        if (request.dryRun()) {
            return new Outcome(Reason.OK, resolution.absolute(), afterDigest, false, diff,
                    "dryRun: nothing was written");
        }
        try {
            writeAtomically(file, after);
        } catch (IOException e) {
            return new Outcome(Reason.NOT_READABLE, resolution.absolute(), currentDigest, false, diff,
                    "could not write: " + e.getMessage());
        }
        checkpoints.recordBefore(file, before, afterDigest);
        return new Outcome(Reason.OK, resolution.absolute(), afterDigest, true, diff, "");
    }

    private Outcome restore(String filePath, boolean redo) {
        if (!rateLimiter.tryAcquire()) {
            return Outcome.refused(Reason.RATE_LIMITED, filePath,
                    rateLimiter.limit() + " requests per " + (rateLimiter.windowMillis() / 1000) + "s");
        }
        PathResolution resolution = resolver.resolve(filePath);
        if (resolution == null) {
            return Outcome.refused(Reason.INVALID_PATH, filePath, "not a usable path");
        }
        if (resolution.escaped()) {
            return Outcome.refused(Reason.OUTSIDE_PROJECT, resolution.absolute(), "outside the project");
        }
        Path file = Path.of(resolution.absolute());
        if (!Files.isRegularFile(file)) {
            return Outcome.refused(Reason.NOT_FOUND, resolution.absolute(), "nothing is there");
        }
        byte[] current;
        try {
            current = Files.readAllBytes(file);
        } catch (IOException e) {
            return Outcome.refused(Reason.NOT_READABLE, resolution.absolute(), e.getMessage());
        }
        String currentDigest = SourceDigest.of(current);
        // An undo is a write, so it obeys the same rule as any other: it must not clobber content this host did
        // not put there. If the file no longer matches what the checkpoint was taken against, somebody edited
        // it in the meantime and restoring the old bytes would silently discard that edit.
        String expected = redo ? checkpoints.pendingRedoDigest(file) : checkpoints.pendingUndoDigest(file);
        if (expected != null && !expected.equals(currentDigest)) {
            return new Outcome(Reason.STALE, resolution.absolute(), currentDigest, false, "",
                    (redo ? "redo" : "undo") + " refused: the file changed since this host wrote it, so the "
                            + "restore would discard that change. Re-read it and decide what you want");
        }
        byte[] restored = redo ? checkpoints.redo(file, current) : checkpoints.undo(file, current);
        if (restored == null) {
            return new Outcome(redo ? Reason.NOTHING_TO_REDO : Reason.NOTHING_TO_UNDO, resolution.absolute(),
                    currentDigest, false, "",
                    redo ? "the last action was not an undo" : "this host has no checkpoint for that file");
        }
        String diff = UnifiedDiff.between(filePath, new String(current, java.nio.charset.StandardCharsets.UTF_8),
                new String(restored, java.nio.charset.StandardCharsets.UTF_8));
        try {
            writeAtomically(file, restored);
        } catch (IOException e) {
            return new Outcome(Reason.NOT_READABLE, resolution.absolute(), SourceDigest.of(current), false, diff,
                    "could not write: " + e.getMessage());
        }
        return new Outcome(Reason.OK, resolution.absolute(), SourceDigest.of(restored), true, diff,
                redo ? "redo applied" : "undo applied");
    }

    /**
     * A temporary file in the same directory, then a move. Same directory matters: a move across volumes is a
     * copy plus a delete and is not atomic, which is the property being bought here.
     */
    private static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Path temporary = Files.createTempFile(directory, ".webviewd-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Documented fallback: still a replace, just not promised to be atomic by the filesystem.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
