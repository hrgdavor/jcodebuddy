package hr.hrg.webview.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * undo history is a leak in a process that serves pages for hours. The limit is per file, so one busy file
 * cannot evict another's history.
 *
 * <p><b>It can outlive the process.</b> {@link #persistent} writes every state to a directory as it is taken,
 * so a page that reloads the browser — or a host that restarts — still has its {@code /undo}. That matters
 * because the alternative is a reader who is told "nothing to undo" about a change they can see in their
 * editor's history. An in-memory store stays the default: a caller that has nowhere to write (a test, a
 * throwaway host) should not be forced to have one.
 */
public final class CheckpointStore {

    /** How many previous states one file keeps. */
    public static final int DEFAULT_LIMIT = 20;

    /** The two kinds of remembered state, and the last path segment of their files. */
    private static final String UNDO = "undo";
    private static final String REDO = "redo";

    private final int limit;
    private final Path directory;
    private final Map<String, Deque<Checkpoint>> undoable = new HashMap<>();
    private final Map<String, Deque<Checkpoint>> redoable = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();

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
        this(limitPerFile, null);
    }

    private CheckpointStore(int limitPerFile, Path directory) {
        if (limitPerFile < 1) {
            throw new IllegalArgumentException("a checkpoint store must keep at least one state");
        }
        this.limit = limitPerFile;
        this.directory = directory;
    }

    /**
     * A store that also writes each state under {@code directory}, so the history survives this process.
     *
     * <p>A failure to write is not a failure to remember: the in-memory state is authoritative for this run, and
     * a journal that cannot be written is reported rather than allowed to break an edit the reader already made.
     */
    public static CheckpointStore persistent(Path directory, int limitPerFile) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create the checkpoint directory " + directory, e);
        }
        return new CheckpointStore(limitPerFile, directory);
    }

    /** Where this store journals to, or null when it is memory only. */
    public Path directory() {
        return directory;
    }

    /** Records the bytes that were on disk before a write, and invalidates any pending redo. */
    public synchronized void recordBefore(Path file, byte[] before, String digestAfter) {
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> stack = undoable.computeIfAbsent(key, k -> new ArrayDeque<>());
        stack.push(new Checkpoint(before.clone(), digestAfter));
        while (stack.size() > limit) {
            stack.removeLast();
        }
        redoable.remove(key);
        if (journal() != null) {
            journal().deleteAll(key, REDO);
            journal().append(key, UNDO, before, digestAfter);
            journal().trim(key, UNDO, limit);
        }
    }

    /**
     * The bytes to restore for an undo, or null when there is nothing to undo.
     *
     * @param current what the file holds now, remembered so that a redo can return to it
     */
    public synchronized byte[] undo(Path file, byte[] current) {
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> stack = undoable.get(key);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Checkpoint checkpoint = stack.pop();
        Deque<Checkpoint> redos = redoable.computeIfAbsent(key, k -> new ArrayDeque<>());
        // The redo is safe while the file still looks like what this undo is about to leave behind.
        String restoredDigest = SourceDigest.of(checkpoint.bytes());
        redos.push(new Checkpoint(current.clone(), restoredDigest));
        while (redos.size() > limit) {
            redos.removeLast();
        }
        if (journal() != null) {
            journal().deleteNewest(key, UNDO);
            journal().append(key, REDO, current, restoredDigest);
            journal().trim(key, REDO, limit);
        }
        return checkpoint.bytes();
    }

    /** The bytes to restore for a redo, or null when the last action was not an undo. */
    public synchronized byte[] redo(Path file, byte[] current) {
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> redos = redoable.get(key);
        if (redos == null || redos.isEmpty()) {
            return null;
        }
        Checkpoint checkpoint = redos.pop();
        Deque<Checkpoint> stack = undoable.computeIfAbsent(key, k -> new ArrayDeque<>());
        String restoredDigest = SourceDigest.of(checkpoint.bytes());
        stack.push(new Checkpoint(current.clone(), restoredDigest));
        while (stack.size() > limit) {
            stack.removeLast();
        }
        if (journal() != null) {
            journal().deleteNewest(key, REDO);
            journal().append(key, UNDO, current, restoredDigest);
            journal().trim(key, UNDO, limit);
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
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> stack = undoable.get(key);
        return stack == null || stack.isEmpty() ? null : stack.peek().expectedCurrent();
    }

    public synchronized String pendingRedoDigest(Path file) {
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> stack = redoable.get(key);
        return stack == null || stack.isEmpty() ? null : stack.peek().expectedCurrent();
    }

    /** How many undos are available for a file, for a caller that wants to offer the verb or not. */
    public synchronized int undoDepth(Path file) {
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> stack = undoable.get(key);
        return stack == null ? 0 : stack.size();
    }

    public synchronized int redoDepth(Path file) {
        String key = keyOf(file);
        load(key);
        Deque<Checkpoint> stack = redoable.get(key);
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

    /**
     * Reads a file's journal once, on first use.
     *
     * <p>Lazy rather than eager so that starting a host does not walk a directory of every file ever edited, and
     * once because re-reading would resurrect an entry this process has already consumed.
     */
    private void load(String key) {
        if (!loaded.add(key)) {
            return;
        }
        Journal journal = journal();
        if (journal == null) {
            return;
        }
        Deque<Checkpoint> undos = new ArrayDeque<>();
        for (Journal.Entry entry : journal.read(key)) {
            if (UNDO.equals(entry.kind())) {
                undos.push(entry.checkpoint());
            }
        }
        Deque<Checkpoint> redos = new ArrayDeque<>();
        for (Journal.Entry entry : journal.read(key)) {
            if (REDO.equals(entry.kind())) {
                redos.push(entry.checkpoint());
            }
        }
        if (!undos.isEmpty()) {
            undoable.put(key, undos);
        }
        if (!redos.isEmpty()) {
            redoable.put(key, redos);
        }
    }

    private Journal journal() {
        return directory == null ? null : new Journal(directory);
    }

    /**
     * The on-disk half: one directory per file, one small file per state.
     *
     * <p>One file per state rather than one rewritten journal, because a rewrite is the one operation that can
     * lose the whole history to an interruption; appending a file and deleting a file are each atomic enough for
     * a history that is a convenience rather than a contract.
     *
     * <p>The format is a short text header, a blank line, then the raw bytes — so the bytes are stored verbatim
     * and a human can see what a checkpoint is without a tool.
     */
    private static final class Journal {

        private static final byte[] SEPARATOR = "\n\n".getBytes(StandardCharsets.UTF_8);

        private final Path directory;

        Journal(Path directory) {
            this.directory = directory;
        }

        record Entry(int sequence, String kind, Checkpoint checkpoint) {
        }

        Path dirFor(String key) {
            // A hash of the absolute path, not the path: a checkpoint directory must not be a second place where
            // a project's structure is written down, and a file name has to be legal on every platform.
            String hash = SourceDigest.ofText(key).substring(SourceDigest.PREFIX.length());
            return directory.resolve(hash.substring(0, 16));
        }

        void append(String key, String kind, byte[] bytes, String expectedCurrent) {
            try {
                Path dir = dirFor(key);
                Files.createDirectories(dir);
                List<Entry> existing = list(dir, kind);
                int next = existing.isEmpty() ? 1 : existing.get(existing.size() - 1).sequence() + 1;
                // No trailing newline on the header: with one, the first "\n\n" in the file would be the
                // header's own end plus the separator's first byte, and the payload would come back one byte
                // longer than it went in. (Found by the round-trip test, which is why it compares lengths.)
                String header = "path=" + key + "\nkind=" + kind + "\nexpectedCurrent=" + expectedCurrent;
                Path target = dir.resolve(String.format("%04d-%s.entry", next, kind));
                Path temporary = Files.createTempFile(dir, ".entry-", ".tmp");
                Files.write(temporary, concat(header.getBytes(StandardCharsets.UTF_8), SEPARATOR, bytes));
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Remembered in memory for this run regardless: a journal that cannot be written must not break
                // an edit the reader has already accepted.
                System.err.println("checkpoint journal: could not record a state: " + e.getMessage());
            }
        }

        List<Entry> read(String key) {
            Path dir = dirFor(key);
            List<Entry> entries = new ArrayList<>();
            for (String kind : List.of(UNDO, REDO)) {
                entries.addAll(list(dir, kind));
            }
            entries.sort(java.util.Comparator.comparingInt(Entry::sequence)
                    .thenComparing(entry -> UNDO.equals(entry.kind()) ? 0 : 1));
            return entries;
        }

        private List<Entry> list(Path dir, String kind) {
            List<Entry> entries = new ArrayList<>();
            if (!Files.isDirectory(dir)) {
                return entries;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*-" + kind + ".entry")) {
                for (Path file : stream) {
                    String name = file.getFileName().toString();
                    int sequence;
                    try {
                        sequence = Integer.parseInt(name.substring(0, name.indexOf('-')));
                    } catch (RuntimeException e) {
                        continue;
                    }
                    byte[] raw = Files.readAllBytes(file);
                    int split = indexOf(raw, SEPARATOR);
                    if (split < 0) {
                        continue;
                    }
                    String header = new String(raw, 0, split, StandardCharsets.UTF_8);
                    String expectedCurrent = "";
                    for (String line : header.split("\n")) {
                        if (line.startsWith("expectedCurrent=")) {
                            expectedCurrent = line.substring("expectedCurrent=".length());
                        }
                    }
                    byte[] payload = java.util.Arrays.copyOfRange(raw, split + SEPARATOR.length, raw.length);
                    entries.add(new Entry(sequence, kind, new Checkpoint(payload, expectedCurrent)));
                }
            } catch (IOException e) {
                System.err.println("checkpoint journal: could not read " + dir + ": " + e.getMessage());
            }
            entries.sort(java.util.Comparator.comparingInt(Entry::sequence));
            return entries;
        }

        void deleteNewest(String key, String kind) {
            List<Entry> entries = list(dirFor(key), kind);
            if (entries.isEmpty()) {
                return;
            }
            delete(dirFor(key), entries.get(entries.size() - 1));
        }

        void deleteAll(String key, String kind) {
            for (Entry entry : list(dirFor(key), kind)) {
                delete(dirFor(key), entry);
            }
        }

        /** Keeps at most {@code keep} states of one kind, dropping the oldest. */
        void trim(String key, String kind, int keep) {
            List<Entry> entries = list(dirFor(key), kind);
            for (int i = 0; i < entries.size() - keep; i++) {
                delete(dirFor(key), entries.get(i));
            }
        }

        private void delete(Path dir, Entry entry) {
            try {
                Files.deleteIfExists(dir.resolve(String.format("%04d-%s.entry", entry.sequence(), entry.kind())));
            } catch (IOException e) {
                System.err.println("checkpoint journal: could not delete a state: " + e.getMessage());
            }
        }

        private static byte[] concat(byte[] header, byte[] separator, byte[] payload) {
            byte[] all = new byte[header.length + separator.length + payload.length];
            System.arraycopy(header, 0, all, 0, header.length);
            System.arraycopy(separator, 0, all, header.length, separator.length);
            System.arraycopy(payload, 0, all, header.length + separator.length, payload.length);
            return all;
        }

        private static int indexOf(byte[] haystack, byte[] needle) {
            outer:
            for (int i = 0; i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }
}
