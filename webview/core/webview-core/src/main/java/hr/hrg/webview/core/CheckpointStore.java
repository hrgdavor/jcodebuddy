package hr.hrg.webview.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The previous bytes of every file this host has written, so {@code /undo} can put them back exactly.
 *
 * <p>"Exactly" is the requirement, not "equivalently": the plan's gate is a byte comparison, and a
 * reconstruction from a diff would fail it the moment the file's encoding, line endings or trailing newline
 * were involved. So a checkpoint holds the bytes.
 *
 * <p>Two stacks per file, not one: undoing then redoing must land on the same bytes, which means an undo has
 * to remember what it replaced. A redo stack that is not empty is also what makes the next *new* write
 * invalidate it — the usual editor semantics, and the honest ones: a redo of a change made against content that
 * has since been written again would be a merge.
 *
 * <p>Bounded per file ({@value #DEFAULT_LIMIT} states). Beyond that the oldest is dropped, because an unbounded
 * undo history is a memory leak in a process that serves pages for hours. The limit is per file, so one busy
 * file cannot evict another's history.
 *
 * <p>In-memory on purpose for this phase: the undo stack belongs to the running host, and a page that wants to
 * undo an edit it made a second ago is talking to that host. Surviving a restart is a Phase 3 follow-up.
 */
public final class CheckpointStore {

    /** How many previous states one file keeps. */
    public static final int DEFAULT_LIMIT = 20;

    private final int limit;
    private final Map<String, Deque<Checkpoint>> undoable = new HashMap<>();
    private final Map<String, Deque<Checkpoint>> redoable = new HashMap<>();

    /**
     * One remembered state.
     *
     * <p>{@code bytes} is what to restore; {@code expectedCurrent} is the digest the file must have for this
     * checkpoint to be safe to apply — "the file still looks the way the action left it". For an undo that is
     * the state the write produced; for a redo it is the state the undo restored. If the file does not match,
     * somebody else changed it since, and applying the checkpoint would silently discard their work — the exact
     * thing this contract refuses to do anywhere else.
     */
    public record Checkpoint(byte[] bytes, String expectedCurrent) {
    }

    public CheckpointStore() {
        this(DEFAULT_LIMIT);
    }

    public CheckpointStore(int limitPerFile) {
        if (limitPerFile < 1) {
            throw new IllegalArgumentException("a checkpoint store must keep at least one state");
        }
        this.limit = limitPerFile;
    }

    /** Records the bytes that were on disk before a write, and invalidates any pending redo. */
    public synchronized void recordBefore(Path file, byte[] before, String digestAfter) {
        String key = keyOf(file);
        Deque<Checkpoint> stack = undoable.computeIfAbsent(key, k -> new ArrayDeque<>());
        stack.push(new Checkpoint(before.clone(), digestAfter));
        while (stack.size() > limit) {
            stack.removeLast();
        }
        redoable.remove(key);
    }

    /**
     * The bytes to restore for an undo, or null when there is nothing to undo.
     *
     * @param current what the file holds now, remembered so that a redo can return to it
     */
    public synchronized byte[] undo(Path file, byte[] current) {
        String key = keyOf(file);
        Deque<Checkpoint> stack = undoable.get(key);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Checkpoint checkpoint = stack.pop();
        Deque<Checkpoint> redos = redoable.computeIfAbsent(key, k -> new ArrayDeque<>());
        // The redo is safe while the file still looks like what this undo is about to leave behind.
        redos.push(new Checkpoint(current.clone(), SourceDigest.of(checkpoint.bytes())));
        while (redos.size() > limit) {
            redos.removeLast();
        }
        return checkpoint.bytes();
    }

    /** The bytes to restore for a redo, or null when the last action was not an undo. */
    public synchronized byte[] redo(Path file, byte[] current) {
        String key = keyOf(file);
        Deque<Checkpoint> redos = redoable.get(key);
        if (redos == null || redos.isEmpty()) {
            return null;
        }
        Checkpoint checkpoint = redos.pop();
        Deque<Checkpoint> stack = undoable.computeIfAbsent(key, k -> new ArrayDeque<>());
        stack.push(new Checkpoint(current.clone(), SourceDigest.of(checkpoint.bytes())));
        while (stack.size() > limit) {
            stack.removeLast();
        }
        return checkpoint.bytes();
    }

    /**
     * The digest the file must have for the next undo to be safe, or null when there is no undo to make.
     *
     * <p>Asked before an undo rather than after: a caller that finds the file changed can refuse and say so,
     * instead of restoring bytes that would throw away somebody else's edit.
     */
    public synchronized String pendingUndoDigest(Path file) {
        Deque<Checkpoint> stack = undoable.get(keyOf(file));
        return stack == null || stack.isEmpty() ? null : stack.peek().expectedCurrent();
    }

    public synchronized String pendingRedoDigest(Path file) {
        Deque<Checkpoint> stack = redoable.get(keyOf(file));
        return stack == null || stack.isEmpty() ? null : stack.peek().expectedCurrent();
    }

    /** How many undos are available for a file, for a caller that wants to offer the verb or not. */
    public synchronized int undoDepth(Path file) {
        Deque<Checkpoint> stack = undoable.get(keyOf(file));
        return stack == null ? 0 : stack.size();
    }

    public synchronized int redoDepth(Path file) {
        Deque<Checkpoint> stack = redoable.get(keyOf(file));
        return stack == null ? 0 : stack.size();
    }

    /** The count a test or a health document can compare against {@link #DEFAULT_LIMIT}. */
    public int limit() {
        return limit;
    }

    private static String keyOf(Path file) {
        // Normalised so that the same file addressed two ways shares one history: a resolve('..') or a
        // different drive-letter case must not create a second, empty stack.
        return file.toAbsolutePath().normalize().toString();
    }
}
