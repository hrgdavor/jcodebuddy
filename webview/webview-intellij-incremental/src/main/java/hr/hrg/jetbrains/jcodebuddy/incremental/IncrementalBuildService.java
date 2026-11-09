package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.vfs.VirtualFile;
import hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler;
import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonWatch;
import hr.hrg.jcodebuddy.watch.PathFilter;
import hr.hrg.jcodebuddy.watch.WatchBatch;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The project-level incremental build loop.
 *
 * <p>On project open this service:</p>
 * <ol>
 *   <li>collects the Java source roots and module compiler-output directories from the IntelliJ project;</li>
 *   <li>creates one {@link EcjIncrementalCompiler} per source root into the module's existing compiler output;</li>
 *   <li>compiles the source roots once, then subscribes to the project's fs-meta daemon;</li>
 *   <li>compiles exactly the changed files from each batch into that same output;</li>
 *   <li>publishes diagnostics in memory and restarts IntelliJ highlighting.</li>
 * </ol>
 *
 * <p>There is no JSON report, no intermediate diagnostics file and no IntelliJ build invocation. IntelliJ's
 * auto-build must be disabled by the user; this service is the writer of the compiler output in that setup.</p>
 */
@Service(Service.Level.PROJECT)
public final class IncrementalBuildService implements Disposable {

    private static final Pattern DIAGNOSTIC = Pattern.compile("^(.+):(\\d+)\\s{2}(.*)$");

    private final Project project;
    private final IncrementalDiagnosticsService diagnostics;
    private final JcodeBuddySettings settings;
    private final Object lifecycle = new Object();
    private volatile ExecutorService worker;

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Path projectRoot;
    private volatile Path daemonRoot;
    private volatile List<SourceRoot> sourceRoots = List.of();
    private volatile Map<Path, EcjIncrementalCompiler> compilers = Map.of();
    private volatile FsMetaDaemonWatch watch;

    public IncrementalBuildService(@NotNull Project project) {
        this.project = project;
        this.diagnostics = project.getService(IncrementalDiagnosticsService.class);
        this.settings = project.getService(JcodeBuddySettings.class);
    }

    /** Starts the loop once per project when the project-level setting is enabled. */
    public void start() {
        if (!settings.isEnabled()) {
            return;
        }
        synchronized (lifecycle) {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            String base = project.getBasePath();
            if (base == null) {
                started.set(false);
                notify("Project has no base directory; incremental build was not started.", NotificationType.WARNING);
                return;
            }
            projectRoot = Path.of(base).toAbsolutePath().normalize();
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "jcodebuddy-incremental-build");
                thread.setDaemon(true);
                return thread;
            });
            worker.submit(this::initialize);
        }
    }

    /** Applies a changed project-level setting without restarting the IDE. */
    public void applyEnabledState() {
        if (settings.isEnabled()) {
            start();
        } else {
            stop();
        }
    }

    private void stop() {
        synchronized (lifecycle) {
            if (!started.compareAndSet(true, false)) {
                return;
            }
            FsMetaDaemonWatch current = watch;
            watch = null;
            if (current != null) {
                current.stop();
            }
            ExecutorService currentWorker = worker;
            worker = null;
            if (currentWorker != null) {
                currentWorker.shutdownNow();
            }
            diagnostics.clear();
            if (!project.isDisposed()) {
                refreshHighlighting();
            }
        }
    }

    private void initialize() {
        try {
            if (!started.get()) {
                return;
            }
            sourceRoots = collectSourceRoots();
            if (sourceRoots.isEmpty() || !started.get()) {
                return;
            }
            compilers = createCompilers(sourceRoots);
            compileAll();
            if (started.get()) {
                startWatch();
            }
        } catch (Throwable failed) {
            notify("JCodeBuddy incremental build failed to start: " + failed, NotificationType.ERROR);
        }
    }

    private List<SourceRoot> collectSourceRoots() {
        List<SourceRoot> roots = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            CompilerModuleExtension extension = CompilerModuleExtension.getInstance(module);
            VirtualFile mainOutput = extension == null ? null : extension.getCompilerOutputPath();
            VirtualFile testOutput = extension == null ? null : extension.getCompilerOutputPathForTests();
            for (ContentEntry entry : ModuleRootManager.getInstance(module).getContentEntries()) {
                for (SourceFolder folder : entry.getSourceFolders()) {
                    VirtualFile root = folder.getFile();
                    if (root == null || !root.isDirectory()) {
                        continue;
                    }
                    boolean test = folder.isTestSource();
                    Path output = outputFor(module, test ? testOutput : mainOutput, test);
                    roots.add(new SourceRoot(Path.of(root.getPath()).toAbsolutePath().normalize(), output, test));
                }
            }
        }
        return List.copyOf(roots);
    }

    private Path outputFor(Module module, VirtualFile compilerOutput, boolean test) {
        if (compilerOutput != null) {
            return Path.of(compilerOutput.getPath()).toAbsolutePath().normalize();
        }
        return projectRoot.resolve("out")
                .resolve(test ? "test" : "production")
                .resolve(module.getName());
    }

    private Map<Path, EcjIncrementalCompiler> createCompilers(List<SourceRoot> roots) {
        Set<String> entries = new LinkedHashSet<>();
        OrderEnumerator.orderEntries(project).recursively().forEach(entry -> {
            for (VirtualFile file : entry.getFiles(OrderRootType.CLASSES)) {
                entries.add(file.getPath());
            }
            return true;
        });
        for (SourceRoot root : roots) {
            entries.add(root.output().toString());
        }
        List<String> extra = new ArrayList<>(entries);
        Map<Path, EcjIncrementalCompiler> created = new LinkedHashMap<>();
        for (SourceRoot root : roots) {
            String classpath = EcjIncrementalCompiler.buildClasspath(root.output(), extra);
            EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(root.root(), root.output(), classpath);
            compiler.index();
            created.put(root.root(), compiler);
        }
        return Map.copyOf(created);
    }

    private void compileAll() {
        for (SourceRoot root : sourceRoots) {
            EcjIncrementalCompiler compiler = compilers.get(root.root());
            if (compiler == null) {
                continue;
            }
            Set<Path> files = javaFiles(root.root());
            compiler.compile(files);
            publish(root, compiler);
        }
        refreshHighlighting();
    }

    private void startWatch() {
        FsMetaDaemonDiscovery.Attach attach;
        try {
            attach = FsMetaDaemonDiscovery.discoverAncestor(projectRoot);
        } catch (RuntimeException failed) {
            notify("Cannot find the fs-meta daemon: " + failed, NotificationType.WARNING);
            return;
        }
        if (attach == null || !attach.attached()) {
            notify("No fs-meta daemon serves this project. Start `fsmeta daemon start` and "
                    + "`fsmeta root add " + projectRoot + "`, then reopen the project. Initial compile "
                    + "has run, but changes will not be watched.", NotificationType.WARNING);
            return;
        }
        daemonRoot = attach.location().root();
        PathFilter filter = filterFor(daemonRoot);
        watch = new FsMetaDaemonWatch(filter, attach.location().socket(), FsMetaClient.LATEST,
                batch -> worker.submit(() -> apply(batch)));
        try {
            watch.start();
        } catch (IOException failed) {
            notify("Cannot start the fs-meta subscription: " + failed, NotificationType.WARNING);
        }
    }

    private PathFilter filterFor(Path daemonRoot) {
        List<String> includes = new ArrayList<>();
        for (SourceRoot sourceRoot : sourceRoots) {
            if (!sourceRoot.root().startsWith(daemonRoot)) {
                continue;
            }
            String relative = daemonRoot.relativize(sourceRoot.root()).toString().replace('\\', '/');
            includes.add(relative.isEmpty() ? "**" : relative + "/**");
        }
        if (includes.isEmpty()) {
            includes.add("**");
        }
        return PathFilter.of(includes, List.of("**/out/**", "**/target/**", "**/.jcodebuddy/**"));
    }

    private void apply(WatchBatch batch) {
        if (batch.overflow()) {
            compileAll();
            return;
        }
        Map<Path, Set<Path>> changedByRoot = new LinkedHashMap<>();
        for (String name : batch.changed()) {
            Path file = daemonRoot.resolve(name).toAbsolutePath().normalize();
            SourceRoot root = rootFor(file);
            if (root == null || !file.toString().endsWith(".java")) {
                continue;
            }
            if (Files.isRegularFile(file)) {
                changedByRoot.computeIfAbsent(root.root(), ignored -> new LinkedHashSet<>()).add(file);
            } else {
                diagnostics.removeFile(file);
            }
        }
        if (changedByRoot.isEmpty()) {
            return;
        }
        for (Map.Entry<Path, Set<Path>> entry : changedByRoot.entrySet()) {
            SourceRoot root = sourceRoots.stream()
                    .filter(candidate -> candidate.root().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);
            EcjIncrementalCompiler compiler = compilers.get(entry.getKey());
            if (root == null || compiler == null) {
                continue;
            }
            compiler.compile(entry.getValue());
            publish(root, compiler);
        }
        refreshHighlighting();
    }

    private SourceRoot rootFor(Path file) {
        for (SourceRoot root : sourceRoots) {
            if (file.startsWith(root.root())) {
                return root;
            }
        }
        return null;
    }

    private void publish(SourceRoot root, EcjIncrementalCompiler compiler) {
        List<Diagnostic> parsed = new ArrayList<>();
        for (String error : compiler.lastErrors()) {
            Matcher matcher = DIAGNOSTIC.matcher(error);
            if (matcher.matches()) {
                parsed.add(new Diagnostic(
                        Path.of(matcher.group(1)).toAbsolutePath().normalize(),
                        Integer.parseInt(matcher.group(2)),
                        0,
                        matcher.group(3)));
            } else {
                parsed.add(new Diagnostic(root.root(), 0, 0, error));
            }
        }
        diagnostics.replaceRoot(root.root(), parsed);
    }

    private void refreshHighlighting() {
        ApplicationManager.getApplication().invokeLater(
                () -> DaemonCodeAnalyzer.getInstance(project).restart());
    }

    private void notify(String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("JCodeBuddy Incremental Build")
                .createNotification(message, type)
                .notify(project);
    }

    private static Set<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            Set<Path> files = new LinkedHashSet<>();
            stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .forEach(path -> files.add(path.toAbsolutePath().normalize()));
            return files;
        } catch (IOException failed) {
            return Set.of();
        }
    }

    @Override
    public void dispose() {
        stop();
    }

    private record SourceRoot(Path root, Path output, boolean test) {
    }
}
