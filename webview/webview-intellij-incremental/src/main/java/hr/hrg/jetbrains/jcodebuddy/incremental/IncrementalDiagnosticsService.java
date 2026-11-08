package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory diagnostic channel between the incremental compiler and the editor annotator.
 *
 * <p>This service is deliberately small and non-persistent: a compile replaces the diagnostics for the
 * compiled source roots, and the annotator reads the current map. No file is written, so there is no stale
 * JSON report and no file-watcher round trip for editor squiggles.</p>
 */
@Service(Service.Level.PROJECT)
public final class IncrementalDiagnosticsService {

    private final Map<Path, List<Diagnostic>> byFile = new ConcurrentHashMap<>();

    /**
     * Replaces all diagnostics produced by one source root. A successful compile calls this with an empty
     * collection, which removes the previous errors for that root.
     */
    public void replaceRoot(Path sourceRoot, Collection<Diagnostic> diagnostics) {
        Path root = normalize(sourceRoot);
        byFile.keySet().removeIf(file -> file.startsWith(root));
        for (Diagnostic diagnostic : diagnostics) {
            Path file = normalize(diagnostic.file());
            byFile.computeIfAbsent(file, ignored -> new ArrayList<>()).add(diagnostic);
        }
    }

    /** Diagnostics for one editor file; an empty list means the file is clean. */
    public List<Diagnostic> forFile(String path) {
        List<Diagnostic> found = byFile.get(normalize(Path.of(path)));
        return found == null ? List.of() : List.copyOf(found);
    }

    /** Removes diagnostics for a file that no longer exists or was deleted in a batch. */
    public void removeFile(Path file) {
        byFile.remove(normalize(file));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** The project service has no resources to dispose; the map goes with the project. */
    public void clear() {
        byFile.clear();
    }

    /** Kept for the service lifecycle; the project owns this instance. */
    public static IncrementalDiagnosticsService getInstance(Project project) {
        return project.getService(IncrementalDiagnosticsService.class);
    }
}
