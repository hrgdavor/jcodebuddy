package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Project-scoped log buffer shared by the build service, IntelliJ's {@code idea.log} (through the service)
 * and the <b>JCodeBuddy Build</b> tool window.
 */
@Service(Service.Level.PROJECT)
public final class IncrementalLogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LINES = 5_000;

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public void add(String message) {
        String line = TIME.format(LocalTime.now()) + "  " + message;
        lines.add(line);
        if (lines.size() > MAX_LINES) {
            lines.remove(0);
        }
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
    }

    public static IncrementalLogService getInstance(Project project) {
        return project.getService(IncrementalLogService.class);
    }
}
