package hr.hrg.webview.webviewd;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/events}: server-sent events for files changing under the project, so a page can re-render
 * instead of polling ({@link hr.hrg.webview.core.EditorHost} has no opinion here — this is host work, not
 * editor work).
 *
 * <p>Frames are produced by {@link #awaitFrame(long)} rather than inside the HTTP handler, so the interesting
 * part — which changes become which frame — is testable without a socket or a sleeping test.
 *
 * <p>Noise control is explicit: the directories a project generates (`.git`, `.jcodebuddy`, `node_modules`,
 * `target`, `build`, `.gradle`) are not watched, because a page cares that its sources changed, not that a
 * build wrote a hundred class files. Watching is recursive by walking the tree at construction, with a cap, and
 * directories created later are registered as they appear — otherwise a page would stop seeing new files the
 * moment the first one was added.
 *
 * <p>One frame per poll, not one per event: a save that touches a dozen files is one thing that happened, and a
 * page re-renders once.
 */
final class ProjectEventStream implements AutoCloseable {

    /** Directories a page never wants noise from. */
    private static final Set<String> IGNORED = Set.of(
            ".git", ".jcodebuddy", ".gradle", "node_modules", "target", "build", "out", ".idea");

    /** How many directories are registered; beyond this a project is too large for a watcher to be honest. */
    static final int MAX_DIRECTORIES = 2000;

    private final Path project;
    private final WatchService watchService;
    private final Map<WatchKey, Path> watched = new HashMap<>();
    private boolean truncated;

    private ProjectEventStream(Path project, WatchService watchService) {
        this.project = project;
        this.watchService = watchService;
    }

    static ProjectEventStream of(Path project) throws IOException {
        WatchService service = FileSystems.getDefault().newWatchService();
        ProjectEventStream stream = new ProjectEventStream(project, service);
        stream.registerTree(project);
        return stream;
    }

    private void registerTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && IGNORED.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (watched.size() >= MAX_DIRECTORIES) {
                    truncated = true;
                    return FileVisitResult.TERMINATE;
                }
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path directory) throws IOException {
        WatchKey key = directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        watched.put(key, directory);
    }

    /** True when the project had more directories than {@link #MAX_DIRECTORIES} and watching is partial. */
    boolean truncated() {
        return truncated;
    }

    int watchedDirectoryCount() {
        return watched.size();
    }

    /**
     * The next frame to send: a {@code change} event naming the paths that moved, or a comment line when the
     * timeout passes with nothing to report. Never null — a stream that produced nothing would look closed.
     */
    String awaitFrame(long timeoutMillis) throws InterruptedException {
        WatchKey key = watchService.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (key == null) {
            return ": keep-alive\n\n";
        }
        Set<String> changed = new LinkedHashSet<>();
        List<Path> newDirectories = new ArrayList<>();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                changed.add("*");
                continue;
            }
            Path directory = watched.get(key);
            if (directory == null) {
                continue;
            }
            Path updated = directory.resolve(String.valueOf(event.context()));
            if (IGNORED.contains(updated.getFileName().toString())) {
                continue;
            }
            if (Files.isDirectory(updated)) {
                // Directories are registered but never reported. Windows reports a child's change against the
                // parent's *entry* as well (its timestamp moved), so forwarding directories would tell a page
                // that "src" changed when the thing that changed was "src/A.java" - and the page would have
                // nothing to re-render. A new directory is registered below so files inside it are seen.
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    newDirectories.add(updated);
                }
                continue;
            }
            changed.add(relative(updated));
        }
        for (Path directory : newDirectories) {
            try {
                registerTree(directory);
            } catch (IOException ignored) {
                // Losing a subtree is worth a missing event, not a broken stream.
            }
        }
        boolean valid = key.reset();
        if (!valid) {
            watched.remove(key);
        }
        if (changed.isEmpty()) {
            return ": keep-alive\n\n";
        }
        return "event: change\ndata: " + frameData(changed) + "\n\n";
    }

    /** The page sees project-relative, forward-slashed paths: the same spelling it links with. */
    private String relative(Path file) {
        String path = file.toAbsolutePath().normalize().toString();
        String root = project.toAbsolutePath().normalize().toString();
        String relative = path.startsWith(root) ? path.substring(root.length()) : path;
        return relative.replace('\\', '/').replaceFirst("^/+", "");
    }

    private static String frameData(Set<String> paths) {
        StringBuilder json = new StringBuilder("{\"paths\":[");
        boolean first = true;
        for (String path : paths) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(path.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append("]}").toString();
    }

    @Override
    public void close() {
        try {
            watchService.close();
        } catch (IOException ignored) {
            // Closing a watcher cannot fail in a way a caller can act on.
        }
        watched.clear();
    }
}
