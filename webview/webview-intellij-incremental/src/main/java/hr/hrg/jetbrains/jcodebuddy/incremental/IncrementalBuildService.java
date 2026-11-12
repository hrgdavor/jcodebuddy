package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
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
import java.util.Comparator;
import java.util.HashMap;
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

    private static final Logger LOG = Logger.getInstance(IncrementalBuildService.class);
    private static final Pattern DIAGNOSTIC = Pattern.compile("^(.+):(\\d+)\\s{2}(.*)$");
    private static final int INITIAL_COMPILE_CHUNK =
            Integer.getInteger("jcodebuddy.intellij.initialCompileChunk", 32);

    private final Project project;
    private final IncrementalDiagnosticsService diagnostics;
    private final IncrementalLogService logService;
    private final JcodeBuddySettings settings;
    private final Object lifecycle = new Object();
    private volatile ExecutorService worker;

    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Path projectRoot;
    private volatile Path daemonRoot;
    private volatile Path daemonSocket;
    private volatile List<SourceRoot> sourceRoots = List.of();
    private volatile Map<Path, EcjIncrementalCompiler> compilers = Map.of();
    private volatile FsMetaDaemonWatch watch;
    private final Set<Path> failedFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile long lastAppliedBatch = -1;
    private volatile String sourceSignature = "";
    private volatile String classpathSignature = "";
    private volatile BatchStateStore batchState;
    private volatile DiagnosticStateStore diagnosticState;
    private volatile CompiledHashStore hashStore;
    private volatile Map<Path, String> compiledHashes = Map.of();
    private volatile boolean fullCompileRequired;

    public IncrementalBuildService(@NotNull Project project) {
        this.project = project;
        this.diagnostics = project.getService(IncrementalDiagnosticsService.class);
        this.logService = project.getService(IncrementalLogService.class);
        this.settings = project.getService(JcodeBuddySettings.class);
    }

    /** Starts the loop once per project when the project-level setting is enabled. */
    public void start() {
        if (!settings.isEnabled()) {
            log("Start requested but the project setting is disabled");
            return;
        }
        synchronized (lifecycle) {
            if (!started.compareAndSet(false, true)) {
                log("Start requested but the incremental build is already running");
                return;
            }
            String base = project.getBasePath();
            if (base == null) {
                started.set(false);
                notify("Project has no base directory; incremental build was not started.", NotificationType.WARNING);
                return;
            }
            projectRoot = Path.of(base).toAbsolutePath().normalize();
            log("Starting incremental build for " + projectRoot);
            Runtime runtime = Runtime.getRuntime();
            log("IDE heap: max=" + (runtime.maxMemory() / (1024 * 1024)) + "MB, used="
                    + ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)) + "MB");
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
            DiagnosticStateStore state = diagnosticState;
            if (state != null) {
                state.clear();
            }
        }
    }

    /** Compiles every source root again, on the worker thread. */
    public void rebuild() {
        if (!settings.isEnabled()) {
            notify("Enable JCodeBuddy Incremental Build for this project before rebuilding.", NotificationType.WARNING);
            return;
        }
        start();
        ExecutorService current = worker;
        if (current == null) {
            return;
        }
        current.submit(() -> {
            if (!started.get()) {
                return;
            }
            log("Manual full rebuild requested");
            boolean ok = compileAll();
            if (ok) {
                fullCompileRequired = false;
                long head = daemonSocket == null ? -1 : queryHead(daemonSocket);
                FsMetaDaemonWatch currentWatch = watch;
                saveMarker(head >= 0 ? head
                        : currentWatch == null ? -1 : currentWatch.lastAppliedBatch());
            } else {
                fullCompileRequired = true;
            }
        });
    }

    /** Drops the current fs-meta subscription and attempts daemon discovery again. */
    public void reconnectFsMeta() {
        if (!settings.isEnabled()) {
            notify("Enable JCodeBuddy Incremental Build for this project before reconnecting.", NotificationType.WARNING);
            return;
        }
        start();
        ExecutorService current = worker;
        if (current == null) {
            return;
        }
        current.submit(() -> {
            if (!started.get()) {
                return;
            }
            log("Manual fs-meta reconnect requested");
            stopWatch();
            startWatch();
        });
    }

    private void stopWatch() {
        FsMetaDaemonWatch current = watch;
        watch = null;
        if (current != null) {
            current.stop();
        }
    }

    private void stop() {
        synchronized (lifecycle) {
            if (!started.compareAndSet(true, false)) {
                return;
            }
            log("Stopping incremental build");
            stopWatch();
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
                log("No Java source roots found; incremental build is idle");
                return;
            }
            log("Discovered " + sourceRoots.size() + " Java source root(s) across modules");
            compilers = createCompilers(sourceRoots);
            sourceSignature = signature(sourceRoots) + "|" + classpathSignature;
            batchState = new BatchStateStore(projectRoot);
            diagnosticState = new DiagnosticStateStore(projectRoot);
            hashStore = new CompiledHashStore(projectRoot);
            CompiledHashStore.State hashes = hashStore.load();
            compiledHashes = new LinkedHashMap<>(hashes.hashes());
            List<Diagnostic> restored = diagnosticState.load();
            diagnostics.replaceAll(restored);
            if (!restored.isEmpty()) {
                log("Restored " + restored.size() + " diagnostic(s) from " + diagnosticState.file());
                refreshHighlighting();
            }

            FsMetaDaemonDiscovery.Attach attach = discover();
            long head = -1;
            if (attach != null) {
                daemonRoot = attach.location().root();
                daemonSocket = attach.location().socket();
                head = queryHead(attach.location().socket());
            }

            // The explicit Rebuild action is the only path that calls compileAll(). A missing hash state is
            // the unavoidable first bootstrap; it is still driven file-by-file through the hash path.
            if (compiledHashes.isEmpty()) {
                log("No compiled-hash state; performing the initial bootstrap hash scan");
                boolean ok = compileFromHashes("bootstrap");
                if (!ok) {
                    fullCompileRequired = true;
                }
            } else {
                compileFromHashes("startup");
            }
            if (attach != null) {
                if (failedFiles.isEmpty()) {
                    saveMarker(head);
                }
                startWatch(attach, head >= 0 ? head : FsMetaClient.LATEST);
            }
        } catch (OutOfMemoryError memory) {
            LOG.error("JCodeBuddy incremental build ran out of IDE heap", memory);
            notify("JCodeBuddy incremental build ran out of IDE heap. Increase Help | Change Memory Settings "
                    + "and reopen the project; the build log shows the project/module source roots. "
                    + "The compiler shares one dependency index, so this is the IDE heap limit, not a per-module cache.",
                    NotificationType.ERROR);
        } catch (Throwable failed) {
            LOG.error("JCodeBuddy incremental build failed to start", failed);
            notify("JCodeBuddy incremental build failed to start: " + failed, NotificationType.ERROR);
        }
    }

    private FsMetaDaemonDiscovery.Attach discover() {
        try {
            FsMetaDaemonDiscovery.Attach attach = FsMetaDaemonDiscovery.discoverAncestor(projectRoot);
            if (attach == null || !attach.attached()) {
                notify("No fs-meta daemon serves this project. Start `fsmeta daemon start` and "
                        + "`fsmeta root add " + projectRoot + "`, then use Reconnect fs-meta. "
                        + "The initial full compile will run, but changes are not being watched.", NotificationType.WARNING);
                return null;
            }
            return attach;
        } catch (RuntimeException failed) {
            LOG.error("Cannot find the fs-meta daemon", failed);
            notify("Cannot find the fs-meta daemon: " + failed, NotificationType.WARNING);
            return null;
        }
    }

    private long queryHead(Path socket) {
        try (FsMetaClient client = FsMetaClient.connect(socket, FsMetaClient.LATEST)) {
            return client.serverGeneration();
        } catch (Exception failed) {
            log("Could not read fs-meta head: " + failed);
            return -1;
        }
    }

    private String signature(List<SourceRoot> roots) {
        StringBuilder signature = new StringBuilder();
        for (SourceRoot root : roots) {
            signature.append(root.module().getName()).append('|')
                    .append(root.root()).append('|')
                    .append(root.output()).append('|')
                    .append(root.test()).append('\n');
        }
        return Integer.toHexString(signature.toString().hashCode()) + ":" + signature.length();
    }

    /** A marker is only trusted when the compiler outputs have not been cleaned away. */

    private void saveMarker(long batch) {
        if (batch < 0 || batchState == null || daemonRoot == null) {
            return;
        }
        try {
            batchState.save(batch, daemonRoot.toString(), sourceSignature);
            lastAppliedBatch = batch;
            log("Saved fs-meta batch marker " + batch + " -> " + batchState.file());
        } catch (IOException failed) {
            LOG.warn("Cannot save fs-meta batch marker " + batchState.file(), failed);
            log("Cannot save fs-meta batch marker: " + failed);
        }
    }

    /**
     * Compares current source hashes with the last successfully compiled set. This is the automatic
     * compile path; it deliberately never calls {@link #compileAll()}.
     */
    private boolean compileFromHashes(String reason) {
        Map<Path, String> current = hashSources(sourceRoots);
        Set<Path> changed = new LinkedHashSet<>();
        Set<Path> deleted = new LinkedHashSet<>();
        for (Map.Entry<Path, String> entry : current.entrySet()) {
            String previous = compiledHashes.get(entry.getKey());
            SourceRoot root = rootFor(entry.getKey());
            if (!entry.getValue().equals(previous) || (root != null && primaryClassMissing(root, entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (Path previous : compiledHashes.keySet()) {
            if (!current.containsKey(previous)) {
                deleted.add(previous);
            }
        }
        if (changed.isEmpty() && deleted.isEmpty()) {
            log("No source hash changes (" + reason + ")");
            return true;
        }
        log("Hash-driven compile (" + reason + "): " + changed.size() + " changed, "
                + deleted.size() + " deleted");
        return compileChanged(changed, deleted);
    }

    private boolean compileChanged(Set<Path> changed, Set<Path> deleted) {
        Map<Path, Set<Path>> byRoot = new LinkedHashMap<>();
        for (Path file : changed) {
            SourceRoot root = rootFor(file);
            if (root != null) {
                byRoot.computeIfAbsent(root.root(), ignored -> new LinkedHashSet<>()).add(file);
            }
        }
        for (Path file : deleted) {
            SourceRoot root = rootFor(file);
            compiledHashes.remove(file);
            diagnostics.removeFile(file);
            if (root != null) {
                deleteCompiledClasses(root, file);
            }
        }
        boolean success = true;
        for (SourceRoot root : sourceRoots) {
            Set<Path> files = byRoot.get(root.root());
            EcjIncrementalCompiler compiler = compilers.get(root.root());
            if (files == null || files.isEmpty()) {
                continue;
            }
            if (compiler == null) {
                success = false;
                failedFiles.addAll(files);
                continue;
            }
            log("Compiling [" + root.module().getName() + "] " + files.size() + " hash-changed file(s)");
            List<Path> pending = new ArrayList<>(files);
            Set<Path> failed = new LinkedHashSet<>();
            int chunkIndex = 0;
            for (int start = 0; start < pending.size(); start += INITIAL_COMPILE_CHUNK) {
                int end = Math.min(start + INITIAL_COMPILE_CHUNK, pending.size());
                Set<Path> chunk = new LinkedHashSet<>(pending.subList(start, end));
                chunkIndex++;
                log("Compiling [" + root.module().getName() + "] hash chunk " + chunkIndex + " "
                        + chunk.size() + " file(s)");
                if (!compiler.compile(chunk)) {
                    failed.addAll(chunk);
                }
            }
            boolean rootOk = true;
            if (!failed.isEmpty()) {
                log("Retrying [" + root.module().getName() + "] " + failed.size()
                        + " hash-changed file(s)");
                rootOk = compiler.compile(failed);
            }
            if (rootOk) {
                rememberHashes(files);
                failedFiles.removeAll(files);
            } else {
                failedFiles.addAll(files);
            }
            success &= rootOk;
            publish(root, compiler);
        }
        saveHashes();
        refreshHighlighting();
        return success;
    }

    private Map<Path, String> hashSources(List<SourceRoot> roots) {
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (SourceRoot root : roots) {
            for (Path file : javaFiles(root.root())) {
                try {
                    hashes.put(file, CompiledHashStore.hash(file));
                } catch (IOException failed) {
                    log("Cannot hash " + file + ": " + failed);
                }
            }
        }
        return hashes;
    }

    private void rememberHashes(Set<Path> files) {
        for (Path file : files) {
            try {
                compiledHashes.put(file, CompiledHashStore.hash(file));
            } catch (IOException failed) {
                compiledHashes.remove(file);
            }
        }
    }

    private void saveHashes() {
        if (hashStore == null) {
            return;
        }
        try {
            hashStore.save(sourceSignature, Map.copyOf(compiledHashes));
        } catch (IOException failed) {
            LOG.warn("Cannot save compiled-hash state " + hashStore.file(), failed);
            log("Cannot save compiled-hash state: " + failed);
        }
    }

    private static boolean primaryClassMissing(SourceRoot root, Path source) {
        String relative = root.root().relativize(source).toString().replace('\\', '/');
        if (!relative.endsWith(".java")) {
            return false;
        }
        String className = relative.substring(0, relative.length() - 5) + ".class";
        return !Files.isRegularFile(root.output().resolve(className.replace('/', File.separatorChar)));
    }

    private static void deleteCompiledClasses(SourceRoot root, Path source) {
        String relative = root.root().relativize(source).toString().replace('\\', '/');
        if (!relative.endsWith(".java")) {
            return;
        }
        String base = relative.substring(0, relative.length() - 5);
        Path primary = root.output().resolve((base + ".class").replace('/', File.separatorChar));
        try {
            Files.deleteIfExists(primary);
            Path parent = primary.getParent();
            String prefix = primary.getFileName().toString();
            String simple = prefix.substring(0, prefix.length() - ".class".length()) + "$";
            if (parent != null && Files.isDirectory(parent)) {
                try (Stream<Path> stream = Files.list(parent)) {
                    stream.filter(path -> path.getFileName().toString().startsWith(simple))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // Best effort; the source hash is still removed.
                                }
                            });
                }
            }
        } catch (IOException ignored) {
            // Best effort; a stale class is not a correctness input for the next source hash check.
        }
    }

    private List<SourceRoot> collectSourceRoots() {
        List<SourceRoot> roots = new ArrayList<>();
        for (Module module : modulesInDependencyOrder()) {
            List<SourceRoot> moduleRoots = new ArrayList<>();
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
                    SourceRoot source = new SourceRoot(module, Path.of(root.getPath()).toAbsolutePath().normalize(),
                            output, test);
                    moduleRoots.add(source);
                    log("Source root [" + module.getName() + "] " + source.root()
                            + (test ? " (test)" : "") + " -> " + output);
                }
            }
            moduleRoots.sort(Comparator.comparing(SourceRoot::test));
            roots.addAll(moduleRoots);
        }
        return List.copyOf(roots);
    }

    /**
     * Topological module order: a dependency is visited before the module that needs its output. This is
     * what makes an initial multi-module compile work when IntelliJ has no previous class output.
     */
    private List<Module> modulesInDependencyOrder() {
        List<Module> ordered = new ArrayList<>();
        Map<Module, Boolean> visiting = new HashMap<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            visit(module, visiting, ordered);
        }
        return ordered;
    }

    private void visit(Module module, Map<Module, Boolean> visiting, List<Module> ordered) {
        Boolean state = visiting.get(module);
        if (state != null) {
            return; // already ordered, or a cycle; a cycle cannot improve on one pass
        }
        visiting.put(module, Boolean.TRUE);
        for (Module dependency : ModuleRootManager.getInstance(module).getDependencies()) {
            visit(dependency, visiting, ordered);
        }
        ordered.add(module);
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
        Map<Module, List<SourceRoot>> byModule = new LinkedHashMap<>();
        for (SourceRoot root : roots) {
            byModule.computeIfAbsent(root.module(), ignored -> new ArrayList<>()).add(root);
        }

        // One union classpath and one shared JAR/JDK index for the whole project. Without the shared
        // index every source root keeps its own copy of every dependency class, which is what made the
        // IntelliJ heap run out in a large multi-module project.
        Set<String> classpath = new LinkedHashSet<>();
        for (Module module : byModule.keySet()) {
            Set<String> moduleClasspath = classpathFor(module);
            logClasspath(module, moduleClasspath);
            classpath.addAll(moduleClasspath);
        }
        for (SourceRoot root : roots) {
            classpath.add(root.output().toString());
        }
        List<String> extra = new ArrayList<>(classpath);
        classpathSignature = Integer.toHexString(extra.hashCode()) + ":" + extra.size();
        EcjIncrementalCompiler.SharedIndex shared = new EcjIncrementalCompiler.SharedIndex();
        Map<Path, EcjIncrementalCompiler> created = new LinkedHashMap<>();
        boolean indexed = false;
        for (SourceRoot root : roots) {
            String rootClasspath = EcjIncrementalCompiler.buildClasspath(root.output(), extra);
            EcjIncrementalCompiler compiler = new EcjIncrementalCompiler(
                    root.root(), root.output(), rootClasspath, shared);
            if (!indexed) {
                compiler.index();
                indexed = true;
                log("Indexed shared dependency/JDK cache: " + compiler.memoryStats().summary());
            }
            created.put(root.root(), compiler);
        }
        return Map.copyOf(created);
    }

    private Set<String> classpathFor(Module module) {
        Set<String> entries = new LinkedHashSet<>();
        OrderEnumerator.orderEntries(module).recursively().forEach(entry -> {
            for (VirtualFile file : entry.getFiles(OrderRootType.CLASSES)) {
                entries.add(physicalPath(file));
            }
            return true;
        });
        return entries;
    }

    /**
     * IntelliJ represents a JAR root as {@code .../foo.jar!/}; the compiler and {@link java.io.File}
     * need the physical {@code .../foo.jar} path. Passing the virtual path made every Maven JAR look
     * missing and kept it out of the ECJ index.
     */
    private static String physicalPath(VirtualFile file) {
        String path = file.getPath();
        int jarRoot = path.indexOf("!/");
        return jarRoot >= 0 ? path.substring(0, jarRoot) : path;
    }

    /**
     * Logs every classpath entry, not just the count. This is the first thing to inspect when a Maven
     * dependency appears unresolvable: it shows whether the entry was present, whether it is a JAR or a
     * directory, and whether it exists on disk.
     */
    private void logClasspath(Module module, Set<String> entries) {
        int jars = 0;
        int directories = 0;
        int missing = 0;
        log("Classpath [" + module.getName() + "] " + entries.size() + " entries:");
        for (String entry : entries) {
            Path path;
            try {
                path = Path.of(entry);
            } catch (RuntimeException malformed) {
                missing++;
                log("  MISSING (invalid path) " + entry);
                continue;
            }
            String kind;
            if (Files.isDirectory(path)) {
                kind = "DIR";
                directories++;
            } else if (Files.isRegularFile(path)) {
                kind = "JAR";
                jars++;
            } else {
                kind = "MISSING";
                missing++;
            }
            log("  " + kind + " " + entry);
        }
        log("Classpath summary [" + module.getName() + "]: jars=" + jars
                + ", dirs=" + directories + ", missing=" + missing);
    }

    private boolean compileAll() {
        log("Full compile: " + sourceRoots.size() + " source root(s), chunk=" + INITIAL_COMPILE_CHUNK);
        logCompilerMemory("before full compile");
        boolean success = true;
        for (SourceRoot root : sourceRoots) {
            EcjIncrementalCompiler compiler = compilers.get(root.root());
            if (compiler == null) {
                continue;
            }
            Set<Path> files = javaFiles(root.root());
            List<Path> pending = new ArrayList<>(files);
            Set<Path> failed = new LinkedHashSet<>();
            if (pending.isEmpty()) {
                success &= compiler.compile(Set.of());
                publish(root, compiler);
                continue;
            }
            int chunkIndex = 0;
            for (int start = 0; start < pending.size(); start += INITIAL_COMPILE_CHUNK) {
                int end = Math.min(start + INITIAL_COMPILE_CHUNK, pending.size());
                Set<Path> chunk = new LinkedHashSet<>(pending.subList(start, end));
                chunkIndex++;
                log("Compiling [" + root.module().getName() + "] chunk " + chunkIndex + " "
                        + chunk.size() + " file(s) from " + root.root());
                if (!compiler.compile(chunk)) {
                    failed.addAll(chunk);
                }
            }
            // A chunk may reference a type that a later chunk writes. Retry the failed files once, after
            // every chunk has had a chance to produce its class files. Real errors fail again and are
            // published from this final pass.
            boolean rootOk = true;
            if (!failed.isEmpty()) {
                log("Retrying [" + root.module().getName() + "] " + failed.size()
                        + " file(s) after the initial chunk pass");
                rootOk = compiler.compile(failed);
            }
            if (rootOk) {
                rememberHashes(files);
            } else {
                failedFiles.addAll(failed);
            }
            publish(root, compiler);
            success &= rootOk;
        }
        saveHashes();
        logCompilerMemory("after full compile");
        if (success) {
            failedFiles.clear();
            fullCompileRequired = false;
        }
        refreshHighlighting();
        return success;
    }

    private void logCompilerMemory(String phase) {
        EcjIncrementalCompiler compiler = compilers.values().stream().findFirst().orElse(null);
        if (compiler == null) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        log("Compiler memory " + phase + ": " + compiler.memoryStats().summary()
                + "; IDE heap used=" + ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                + "MB/" + (runtime.maxMemory() / (1024 * 1024)) + "MB");
    }

    private void startWatch() {
        FsMetaDaemonDiscovery.Attach attach = discover();
        if (attach == null) {
            return;
        }
        startWatch(attach, lastAppliedBatch >= 0 ? lastAppliedBatch : FsMetaClient.LATEST);
    }

    private void startWatch(FsMetaDaemonDiscovery.Attach attach, long fromBatch) {
        stopWatch();
        if (!started.get() || attach == null || !attach.attached()) {
            return;
        }
        daemonRoot = attach.location().root();
        daemonSocket = attach.location().socket();
        PathFilter filter = filterFor(daemonRoot);
        log("fs-meta daemon root: " + daemonRoot);
        log("fs-meta socket: " + attach.location().socket());
        log("fs-meta filter: " + filter);
        log("Subscribing to fs-meta from batch " + fromBatch);
        watch = new FsMetaDaemonWatch(filter, attach.location().socket(), fromBatch,
                batch -> worker.submit(() -> applyBatch(batch)));
        try {
            watch.start();
            log("Subscribed to fs-meta at generation " + watch.lastAppliedBatch());
        } catch (IOException failed) {
            LOG.error("Cannot start the fs-meta subscription", failed);
            notify("Cannot start the fs-meta subscription: " + failed, NotificationType.WARNING);
        }
    }

    private void applyBatch(WatchBatch batch) {
        if (fullCompileRequired) {
            log("Recovering from a failed bootstrap using source hashes (no full rebuild)");
            boolean ok = compileFromHashes("bootstrap recovery");
            if (ok) {
                fullCompileRequired = false;
            }
            return;
        }
        boolean ok = apply(batch);
        if (ok && failedFiles.isEmpty()) {
            FsMetaDaemonWatch current = watch;
            if (current != null) {
                saveMarker(current.lastAppliedBatch());
            }
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

    private boolean apply(WatchBatch batch) {
        log("Batch: changed=" + batch.changed() + " deleted=" + batch.deleted()
                + (batch.overflow() ? " overflow" : ""));
        if (batch.overflow()) {
            return compileFromHashes("fs-meta overflow");
        }
        Map<Path, Set<Path>> changedByRoot = new LinkedHashMap<>();
        int ignoredUnchanged = 0;
        for (String name : batch.changed()) {
            Path file = daemonRoot.resolve(name).toAbsolutePath().normalize();
            SourceRoot root = rootFor(file);
            if (root == null || !file.toString().endsWith(".java")) {
                continue;
            }
            if (!Files.isRegularFile(file)) {
                log("fs-meta reported a missing changed file (treating as deleted): " + file);
                removeHashAndClasses(root, file);
                continue;
            }
            String currentHash;
            try {
                currentHash = CompiledHashStore.hash(file);
            } catch (IOException failed) {
                log("Cannot hash changed file " + file + ": " + failed);
                currentHash = null;
            }
            String previousHash = compiledHashes.get(file);
            if (currentHash != null && currentHash.equals(previousHash)
                    && !primaryClassMissing(root, file)) {
                ignoredUnchanged++;
                log("fs-meta false positive: " + file + " has the same hash as the compiled generation; ignored");
                continue;
            }
            changedByRoot.computeIfAbsent(root.root(), ignored -> new LinkedHashSet<>()).add(file);
        }
        for (String name : batch.deleted()) {
            Path file = daemonRoot.resolve(name).toAbsolutePath().normalize();
            SourceRoot root = rootFor(file);
            if (root != null) {
                if (Files.isRegularFile(file)) {
                    log("fs-meta false deletion: " + file + " still exists; ignored");
                    continue;
                }
                removeHashAndClasses(root, file);
            }
        }
        if (ignoredUnchanged > 0) {
            log("Ignored " + ignoredUnchanged + " fs-meta change(s) whose content hash is unchanged");
        }
        failedFiles.removeIf(file -> !Files.isRegularFile(file));
        for (Path failed : failedFiles) {
            SourceRoot root = rootFor(failed);
            if (root != null) {
                changedByRoot.computeIfAbsent(root.root(), ignored -> new LinkedHashSet<>()).add(failed);
            }
        }
        if (changedByRoot.isEmpty()) {
            log("Batch had no Java source changes");
            return true;
        }
        boolean success = true;
        for (SourceRoot root : sourceRoots) {
            Set<Path> changed = changedByRoot.get(root.root());
            EcjIncrementalCompiler compiler = compilers.get(root.root());
            if (changed == null) {
                continue;
            }
            if (compiler == null) {
                log("No compiler for changed root " + root.root());
                success = false;
                continue;
            }
            log("Compiling [" + root.module().getName() + "] " + changed.size() + " changed file(s)");
            boolean rootOk = compiler.compile(changed);
            if (rootOk) {
                rememberHashes(changed);
                failedFiles.removeAll(changed);
            } else {
                failedFiles.addAll(changed);
            }
            success &= rootOk;
            publish(root, compiler);
        }
        saveHashes();
        logCompilerMemory("after batch compile");
        refreshHighlighting();
        return success;
    }

    private void removeHashAndClasses(SourceRoot root, Path file) {
        compiledHashes.remove(file);
        diagnostics.removeFile(file);
        deleteCompiledClasses(root, file);
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
        if (diagnosticState != null) {
            try {
                diagnosticState.save(diagnostics.snapshot());
            } catch (IOException failed) {
                LOG.warn("Cannot persist diagnostics " + diagnosticState.file(), failed);
                log("Cannot persist diagnostics: " + failed);
            }
        }
        if (parsed.isEmpty()) {
            log("Compile OK [" + root.module().getName() + "] " + root.root());
        } else {
            log("Compile FAILED [" + root.module().getName() + "] " + parsed.size()
                    + " diagnostic(s); first: " + parsed.getFirst().message());
        }
    }

    private void refreshHighlighting() {
        ApplicationManager.getApplication().invokeLater(
                () -> DaemonCodeAnalyzer.getInstance(project).restart());
    }

    private void log(String message) {
        LOG.info(message);
        logService.add(message);
    }

    private void notify(String message, NotificationType type) {
        log(type + ": " + message);
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

    private record SourceRoot(Module module, Path root, Path output, boolean test) {
    }
}
