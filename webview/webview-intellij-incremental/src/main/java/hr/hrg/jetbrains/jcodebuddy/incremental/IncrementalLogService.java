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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Project-scoped log buffer shared by the build service, the <b>JCodeBuddy Build</b> tool window and the
 * project's own build log. Every line carries a full date/time and is appended to
 * <code>.jcodebuddy/cache/incremental-build/build.log</code>.
 */
@Service(Service.Level.PROJECT)
public final class IncrementalLogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int MAX_LINES = 5_000;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final Path file;
    private final Object fileLock = new Object();

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
        String line = TIME.format(LocalDateTime.now()) + "  " + message;
        lines.add(line);
        if (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
        append(line);
        for (Consumer<String> listener : listeners) {
            listener.accept(line);
        }
    }

    public List<String> snapshot() {
        return List.copyOf(lines);
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
