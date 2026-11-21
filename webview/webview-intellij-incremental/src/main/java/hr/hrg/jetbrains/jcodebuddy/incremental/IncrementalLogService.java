package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Project-scoped log buffer shared by the build service, the <b>JCodeBuddy Build</b> tool window, the live
 * tool socket and the project's own build log. Every line carries a full date/time and is appended to
 * <code>.jcodebuddy/cache/incremental-build/build.log</code>.
 *
 * <p>Every line also carries a sequence number that only increases, even across {@link #clear()} and the
 * eviction of the oldest lines. That is what a consumer polls with: an index into the list would shift when
 * the buffer trims, so the API is "everything after sequence N", and a cleared log simply answers nothing
 * until new lines arrive.</p>
 */
@Service(Service.Level.PROJECT)
public final class IncrementalLogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_LINES = 5_000;

    /** One retained line and the sequence number a consumer uses as its cursor. */
    public record LogLine(long sequence, String text) {
    }

    private final List<LogLine> lines = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    /** Subscribers that want the structured line, so a stream can carry the sequence as well as the text. */
    private final List<Consumer<LogLine>> lineListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Path file;
    private final Object fileLock = new Object();
    /** Serialises {@link #add} with {@link #subscribe}, so a subscriber cannot miss a line between the two. */
    private final Object lineLock = new Object();

    public IncrementalLogService(Project project) {
        String base = project.getBasePath();
        this.file = base == null
                ? Path.of("jcodebuddy-incremental-build.log")
                : Path.of(base, ".jcodebuddy", "cache", "incremental-build", "build.log");
        ensureDirectory();
    }

    public Path file() {
        return file;
    }

    public void add(String message) {
        long seq = sequence.incrementAndGet();
        String line = TIME.format(LocalDateTime.now()) + "  " + message;
        LogLine entry = new LogLine(seq, line);
        synchronized (lineLock) {
            lines.add(entry);
            if (lines.size() > MAX_LINES) {
                lines.remove(0);
            }
            append(line);
            for (Consumer<String> listener : listeners) {
                listener.accept(line);
            }
            for (Consumer<LogLine> listener : lineListeners) {
                listener.accept(entry);
            }
        }
    }

    /**
     * Streams lines: replays the retained backlog after {@code since}, then calls {@code sink} for every new
     * line, and returns the handle that stops it.
     *
     * <p>The replay and the registration happen under the same lock {@link #add} takes, so a line added
     * between "what has this consumer seen" and "give it the live ones" cannot fall into the gap. {@code sink}
     * runs while that lock is held and must therefore only enqueue — the tool socket's sink does.</p>
     *
     * @param since the last sequence the consumer already has; pass {@link #lastSequence()} for "from now"
     */
    public AutoCloseable subscribe(long since, Consumer<LogLine> sink) {
        synchronized (lineLock) {
            for (LogLine line : afterLocked(since, MAX_LINES)) {
                sink.accept(line);
            }
            lineListeners.add(sink);
        }
        return () -> lineListeners.remove(sink);
    }

    /** Every retained line, oldest first. */
    public List<String> snapshot() {
        List<String> text = new ArrayList<>(lines.size());
        for (LogLine line : lines) {
            text.add(line.text());
        }
        return List.copyOf(text);
    }

    /** The most recently assigned sequence number, or {@code 0} before the first line. */
    public long lastSequence() {
        return sequence.get();
    }

    /** How many lines are retained right now. */
    public int size() {
        return lines.size();
    }

    /**
     * The retained lines after {@code since}, oldest first, at most {@code limit} of them.
     *
     * @param since the last sequence a consumer saw; {@code 0} means "from the oldest retained line"
     */
    public List<LogLine> after(long since, int limit) {
        return afterLocked(since, limit);
    }

    private List<LogLine> afterLocked(long since, int limit) {
        List<LogLine> found = new ArrayList<>();
        for (LogLine line : lines) {
            if (line.sequence() <= since) {
                continue;
            }
            found.add(line);
            if (found.size() >= limit) {
                break;
            }
        }
        return List.copyOf(found);
    }

    /** The newest {@code limit} lines, oldest first; what a consumer with no cursor asks for. */
    public List<LogLine> tail(int limit) {
        int from = Math.max(0, lines.size() - Math.max(0, limit));
        return List.copyOf(lines.subList(from, lines.size()));
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    public void clear() {
        lines.clear();
        try {
            Files.writeString(file, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // Best effort; clearing the in-memory pane still works.
        }
    }

    public void ensureDirectory() {
        Path parent = file.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException ignored) {
            // The build log is diagnostic; it must not break the build.
        }
    }

    private void append(String line) {
        ensureDirectory();
        synchronized (fileLock) {
            try {
                Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // The tool window remains available if the project output is read-only.
            }
        }
    }

    public static IncrementalLogService getInstance(Project project) {
        return project.getService(IncrementalLogService.class);
    }
}
