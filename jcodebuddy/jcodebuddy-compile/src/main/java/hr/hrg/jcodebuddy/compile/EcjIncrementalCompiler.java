// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.compile;

import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A standalone ECJ incremental compiler for file-change triggered recompiles.
 *
 * <p>This is a benchmark and dev-time helper, not a build tool and not a watcher.
 * It knows nothing about where the changed files came from: the caller passes the
 * files that changed, and the compiler compiles exactly those. Watching is
 * <b>fs-meta's</b> job — the daemon reports batches of changed paths and a caller
 * feeds them here.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   EcjIncrementalCompiler
 *   ├── CachingNameEnvironment   (one instance; index() runs once)
 *   │     ├── jarTypeCache      (JAR class bytes, loaded once at index())
 *   │     ├── jdkTypeCache      (JDK class bytes, loaded on demand, cached)
 *   │     └── packageSet        (JAR packages + ModuleLayer.boot() packages)
 *   ├── ClassFileWriter          (one instance, reset() and reused each compile)
 *   └── CompilerOptions          (built once, reused)
 *
 *   per compile():
 *     new Compiler(nameEnvironment, ...)   ← lightweight; fresh ECJ state
 *     compiler.compile(units)              ← parses the changed .java files, resolves
 *                                            types through the caches, writes .class
 * </pre>
 *
 * <h2>What makes it incremental</h2>
 * <ul>
 *   <li>{@link #index()} reads the dependency JARs once; no compile re-scans a JAR.</li>
 *   <li>A fresh ECJ {@link Compiler} is created per call, so no type binding from the
 *       previous round leaks into the next one; the name environment (the expensive
 *       part) is reused.</li>
 *   <li>The {@link ClassFileWriter} is reused and reset, not recreated.</li>
 *   <li>Types compiled by an earlier call are resolved from the bin directory, so a
 *       change to one file does not require the files that depend on it.</li>
 * </ul>
 *
 * <h2>Timing</h2>
 * {@link #lastCompileNanos()} is the wall-clock duration of the last
 * {@link #compile(Set)} call, measured around source reading and ECJ's compile, so a
 * benchmark can quote the compile cost without the one-time index cost.
 * {@link #lastIndexNanos()} is the duration of the last {@link #index()} call.
 *
 * <h2>Lifecycle</h2>
 * Call {@link #index()} once before the first compile, or let the first
 * {@code compile()} do it. {@code compile()} never re-indexes.
 *
 * <h2>Thread safety</h2>
 * Not thread-safe. Use from the single thread that owns the compiler.
 */
public final class EcjIncrementalCompiler {

    /** Source roots the changed files' packages are derived from. */
    private final List<Path> sourceRoots;

    /** Output directory for {@code .class} files. */
    private final Path binDir;

    private final CachingNameEnvironment nameEnvironment;
    private final ClassFileWriter classFileWriter;
    private final CompilerOptions options;
    private final IErrorHandlingPolicy errorPolicy;
    private final IProblemFactory problemFactory;

    private long lastCompileNanos;
    private long lastIndexNanos;
    private List<String> lastErrors = List.of();

    /**
     * @param sourceRoot the source root the changed files live under
     * @param binDir     the directory to write {@code .class} files to
     * @param classpath  the effective classpath (JARs and directories), typically
     *                   {@code java.class.path} plus extra entries; see
     *                   {@link #buildClasspath(Path, List)}
     */
    public EcjIncrementalCompiler(Path sourceRoot, Path binDir, String classpath) {
        this(List.of(Objects.requireNonNull(sourceRoot, "sourceRoot")), binDir, classpath, new SharedIndex());
    }

    /**
     * Creates a compiler whose source files may come from several source roots feeding one module output
     * (for example {@code src/main/java} and {@code src/generated/java}). A per-root compiler cannot compile
     * types that references cross those roots in both directions; one shared batch can.
     */
    public EcjIncrementalCompiler(List<Path> sourceRoots, Path binDir, String classpath) {
        this(sourceRoots, binDir, classpath, new SharedIndex());
    }

    public EcjIncrementalCompiler(List<Path> sourceRoots, Path binDir, String classpath, SharedIndex shared) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("at least one source root is required");
        }
        this.sourceRoots = sourceRoots.stream()
                .map(root -> Objects.requireNonNull(root, "sourceRoot"))
                .map(root -> root.toAbsolutePath().normalize())
                .sorted(java.util.Comparator.comparingInt(Path::getNameCount).reversed())
                .toList();
        this.binDir = Objects.requireNonNull(binDir, "binDir").toAbsolutePath().normalize();
        this.nameEnvironment = new CachingNameEnvironment(classpath, this.binDir,
                Objects.requireNonNull(shared, "shared").shared);
        this.classFileWriter = new ClassFileWriter(this.binDir);
        this.options = buildOptions();
        this.errorPolicy = DefaultErrorHandlingPolicies.proceedWithAllProblems();
        this.problemFactory = new DefaultProblemFactory(Locale.getDefault());
    }

    private Path rootFor(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path root : sourceRoots) {
            if (normalized.startsWith(root)) {
                return root;
            }
        }
        return null;
    }

    /** An index that several compilers can share; create one and pass it to their constructors. */
    public static final class SharedIndex {
        private final CachingNameEnvironment.SharedIndex shared = new CachingNameEnvironment.SharedIndex();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Pre-indexes the dependency JARs and the JDK packages. Idempotent: the first
     * call does the work and every later call returns immediately.
     *
     * <p>This is the one-time cost the incremental design pays up front. It is safe
     * to call it after the application has started, and it is also called by the
     * first {@link #compile(Set)} if the caller did not call it explicitly.
     */
    public void index() {
        long startedAt = System.nanoTime();
        try {
            nameEnvironment.index();
        } finally {
            lastIndexNanos = System.nanoTime() - startedAt;
        }
    }

    // ── Compile ───────────────────────────────────────────────────────────────

    /**
     * Compiles one changed file immediately, before the rest of an fs-meta batch is known.
     *
     * <p>This is the START_BATCH fast path: it resolves against the class files already in
     * {@code binDir}, so it can produce a class for the first changed unit while the remaining
     * CHANGE_ENTRY frames are still arriving. The caller must still compile the complete changed
     * set at END_BATCH when more than one file changed, because this call cannot see a sibling
     * type that is also being changed in the same batch.</p>
     */
    public boolean compileOne(Path changedFile) {
        return compile(Set.of(Objects.requireNonNull(changedFile, "changedFile")));
    }

    /**
     * Starts the internal ECJ phase split for one file: {@code beginToCompile} parses the source and builds its
     * type bindings immediately, while {@link #completeEarly(EarlyCompilation)} runs ECJ's resolution/cross-check
     * and codegen later. This is for a one-file fs-meta batch: it overlaps the parse with the rest of the batch
     * window, not a replacement for the changed-set compile when several files changed.
     *
     * <p>This is deliberately opt-in and isolated in {@link PhaseCompiler}: the methods are ECJ internals, not a
     * stable public API. If a future ECJ changes or removes them, only this adapter needs a fork or a fallback;
     * the normal {@link #compile(Set)} path is independent of them.</p>
     *
     * @return a handle for {@link #completeEarly(EarlyCompilation)}, or {@code null} when the early phase could
     * not start; {@link #lastErrors()} then explains why and the caller should fall back to {@link #compile(Set)}
     */
    public EarlyCompilation beginEarly(Path changedFile) {
        Objects.requireNonNull(changedFile, "changedFile");
        long startedAt = System.nanoTime();
        try {
            classFileWriter.reset();
            if (!nameEnvironment.isIndexed()) {
                index();
            }
            SourceUnit unit = new SourceUnit(changedFile, rootFor(changedFile));
            nameEnvironment.setSourceUnits(new SourceUnit[]{unit});
            PhaseCompiler compiler = new PhaseCompiler(nameEnvironment, errorPolicy, options,
                    classFileWriter, problemFactory);
            compiler.begin(new ICompilationUnit[]{unit});
            lastErrors = List.of();
            return new EarlyCompilation(compiler, unit, changedFile, startedAt);
        } catch (Exception e) {
            lastErrors = List.of("ECJ early compile failed: " + messageOf(e));
            nameEnvironment.cleanup();
            return null;
        }
    }

    /**
     * Completes an {@link #beginEarly(Path)} compilation: ECJ resolves types, reports cross-check problems and
     * writes the class file. The handle is single-use.
     */
    public boolean completeEarly(EarlyCompilation early) {
        Objects.requireNonNull(early, "early");
        if (early.completed) {
            throw new IllegalStateException("this early compilation was already completed");
        }
        early.completed = true;
        try {
            early.compiler.finish();
            List<String> problems = new ArrayList<>(classFileWriter.errors());
            lastErrors = List.copyOf(problems);
            return !classFileWriter.hasErrors() && problems.isEmpty();
        } catch (RuntimeException e) {
            lastErrors = List.of("ECJ early completion failed: " + messageOf(e));
            return false;
        } finally {
            lastCompileNanos = System.nanoTime() - early.startedAt;
            nameEnvironment.cleanup();
        }
    }

    /**
     * One opt-in early compilation. It owns a dedicated ECJ {@link Compiler}; completing it is the only operation
     * that writes class files.
     */
    public static final class EarlyCompilation {

        private final PhaseCompiler compiler;
        @SuppressWarnings("unused")
        private final SourceUnit unit;
        private final Path file;
        private final long startedAt;
        private boolean completed;

        private EarlyCompilation(PhaseCompiler compiler, SourceUnit unit, Path file, long startedAt) {
            this.compiler = compiler;
            this.unit = unit;
            this.file = file;
            this.startedAt = startedAt;
        }

        /** The source this early compilation belongs to. */
        public Path file() {
            return file;
        }
    }

    /**
     * The one place that depends on ECJ's protected phase methods. A future ECJ that removes them breaks this
     * adapter at compile time, which is the intended fork boundary: the rest of the compiler does not change.
     */
    private static final class PhaseCompiler extends Compiler {

        PhaseCompiler(CachingNameEnvironment nameEnvironment, IErrorHandlingPolicy policy, CompilerOptions options,
                      ClassFileWriter requestor, IProblemFactory problemFactory) {
            super(nameEnvironment, policy, options, requestor, problemFactory);
        }

        void begin(ICompilationUnit[] units) {
            beginToCompile(units);
        }

        void finish() {
            processCompiledUnits(0, false);
        }
    }

    /**
     * Incrementally compiles the given set of changed source files.
     *
     * <p>Exactly the files passed are compiled. A file that is not under the source
     * root, is not a {@code .java} file, or cannot be read is reported as a failure
     * with a diagnostic rather than an exception; so is an ECJ internal error.
     *
     * @param changedFiles the {@code .java} files to compile
     * @return {@code true} when every file compiled without an error
     * @throws NullPointerException if {@code changedFiles} is {@code null}
     */
    public boolean compile(Set<Path> changedFiles) {
        return compile(changedFiles, true);
    }

    /**
     * Compiles the changed files and optionally rewrites byte-identical class files.
     *
     * @param changedFiles   the {@code .java} files to compile
     * @param writeIdentical {@code false} to leave an existing class file untouched when the newly produced
     *                       bytes are identical; {@code true} for a forced rebuild
     * @return {@code true} when every file compiled without an error
     */
    public boolean compile(Set<Path> changedFiles, boolean writeIdentical) {
        Objects.requireNonNull(changedFiles, "changedFiles");
        long startedAt = System.nanoTime();
        try {
            classFileWriter.reset();
            classFileWriter.setWriteIdentical(writeIdentical);
            if (changedFiles.isEmpty()) {
                lastErrors = List.of();
                return true;
            }

            // A caller that did not index explicitly still gets the index exactly once.
            // It runs before the compile timer starts, so lastCompileNanos() never
            // carries the one-time index cost even on this lazy path.
            if (!nameEnvironment.isIndexed()) {
                index();
                startedAt = System.nanoTime();
            }

            List<String> problems = new ArrayList<>();
            // ECJ resolves a batch as one unit, but a Set's iteration order is unspecified. Sorting keeps
            // diagnostics and any order-sensitive ECJ behaviour deterministic; it is not a substitute for
            // ordering separate source roots, which is the caller's multi-root scheduling job.
            List<Path> ordered = changedFiles.stream()
                    .map(file -> file.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
            SourceUnit[] units = new SourceUnit[ordered.size()];
            int next = 0;
            for (Path file : ordered) {
                try {
                    Path root = rootFor(file);
                    if (root == null) {
                        problems.add(file + "  source file is not under the source root");
                        continue;
                    }
                    units[next++] = new SourceUnit(file, root);
                } catch (Exception e) {
                    // Reading a source file, or deriving its package, is caller input:
                    // report it, never let it escape as an exception.
                    problems.add(file + "  " + messageOf(e));
                }
            }
            if (!problems.isEmpty()) {
                lastErrors = List.copyOf(problems);
                return false;
            }

            nameEnvironment.setSourceUnits(units);

            // Fresh compiler per call: the lookup environment and type bindings are
            // per-compilation state. The name environment is the expensive half and it
            // is reused, so this constructor is cheap.
            Compiler compiler = new Compiler(nameEnvironment, errorPolicy, options,
                    classFileWriter, problemFactory);
            // Compiler.compile() calls nameEnvironment.cleanup() in a finally block.
            compiler.compile(units);

            problems.addAll(classFileWriter.errors());
            lastErrors = List.copyOf(problems);
            return !classFileWriter.hasErrors() && problems.isEmpty();
        } catch (RuntimeException e) {
            // A compile error is a problem, not an exception: convert ECJ's internal
            // failures to a diagnostic so a caller can report and continue.
            lastErrors = List.of("ECJ compilation failed: " + messageOf(e));
            return false;
        } finally {
            lastCompileNanos = System.nanoTime() - startedAt;
        }
    }

    // ── Results ───────────────────────────────────────────────────────────────

    /**
     * Wall-clock nanoseconds of the last {@link #compile(Set)} call, excluding the
     * one-time {@link #index()} cost. {@code 0} before the first compile.
     *
     * <p>Measured around reading the source files and ECJ's compile, which is the
     * duration a benchmark wants to attribute to "recompile the changed files".
     */
    public long lastCompileNanos() {
        return lastCompileNanos;
    }

    /** Nanoseconds of the last {@link #index()} call ({@code 0} before the first). */
    public long lastIndexNanos() {
        return lastIndexNanos;
    }

    /**
     * Diagnostics of the last {@link #compile(Set)} call: empty on success, one entry
     * per error otherwise. Each entry is {@code "&lt;file&gt;:&lt;line&gt;  &lt;message&gt;"}
     * for an ECJ problem, or a read/write failure message. The returned list is an
     * immutable snapshot and never {@code null}.
     */
    public List<String> lastErrors() {
        return lastErrors;
    }

    /** Approximate heap use of the shared dependency/JDK index and hot class cache. */
    public CompilerMemory memoryStats() {
        return nameEnvironment.memoryStats();
    }

    // ── Options ───────────────────────────────────────────────────────────────

    /**
     * Builds the ECJ options used for every compile. They are tuned for the
     * incremental case:
     * <ul>
     *   <li>Java 25 source, compliance and target — the level this reactor and the IntelliJ plugin compile at.</li>
     *   <li>Warnings disabled, since each one costs an extra analysis pass and this
     *       compiler's contract is "did it compile", not "is it tidy".</li>
     *   <li>Annotation processing and doc-comment parsing disabled
     *       ({@code -proc:none} and no Javadoc work).</li>
     *   <li>Line numbers, source file and local variable attributes kept, so compiled
     *       classes remain debuggable.</li>
     * </ul>
     */
    private static CompilerOptions buildOptions() {
        Map<String, String> settings = new HashMap<>();

        settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_25);
        settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_25);
        settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_25);
        settings.put(CompilerOptions.OPTION_Encoding, "UTF-8");

        // Annotation processing: the equivalent of -proc:none.
        settings.put(CompilerOptions.OPTION_Process_Annotations, CompilerOptions.DISABLED);

        // Debug attributes (kept for IDE/debugger integration).
        settings.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE);
        settings.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE);
        settings.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE);

        // Null analysis.
        settings.put(CompilerOptions.OPTION_ReportNullReference, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportPotentialNullReference, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportRedundantNullCheck, CompilerOptions.IGNORE);

        // Unused-code warnings.
        settings.put(CompilerOptions.OPTION_ReportUnusedImport, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportUnusedLocal, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportUnusedPrivateMember, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportUnusedDeclaredThrownException, CompilerOptions.IGNORE);

        // Dead code.
        settings.put(CompilerOptions.OPTION_ReportDeadCode, CompilerOptions.IGNORE);

        // Generics / raw types.
        settings.put(CompilerOptions.OPTION_ReportUncheckedTypeOperation, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportRawTypeReference, CompilerOptions.IGNORE);

        // Deprecation.
        settings.put(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE);

        // Style warnings.
        settings.put(CompilerOptions.OPTION_ReportUnnecessaryElse, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportFieldHiding, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportLocalVariableHiding, CompilerOptions.IGNORE);

        // Javadoc parsing overhead.
        settings.put(CompilerOptions.OPTION_DocCommentSupport, CompilerOptions.DISABLED);
        settings.put(CompilerOptions.OPTION_ReportMissingJavadocComments, CompilerOptions.IGNORE);
        settings.put(CompilerOptions.OPTION_ReportMissingJavadocTags, CompilerOptions.IGNORE);

        // Suppress whatever warning is left.
        settings.put(CompilerOptions.OPTION_SuppressWarnings, CompilerOptions.ENABLED);

        return new CompilerOptions(settings);
    }

    // ── Classpath helper ──────────────────────────────────────────────────────

    /**
     * Builds a classpath string that puts {@code binDir} first (so types compiled by
     * an earlier run resolve from the project before any dependency JAR), then the
     * current {@code java.class.path}, then the extra entries.
     *
     * <p>Empty entries are skipped: an empty classpath element means "the current
     * directory" to some tools, which is never what a compiler's classpath should
     * silently pick up.
     *
     * @param binDir         the compiler output directory
     * @param extraClasspath additional entries, or {@code null}
     */
    public static String buildClasspath(Path binDir, List<String> extraClasspath) {
        Objects.requireNonNull(binDir, "binDir");
        StringBuilder classpath = new StringBuilder(binDir.toAbsolutePath().toString());
        String runtimeClasspath = System.getProperty("java.class.path", "");
        if (!runtimeClasspath.isEmpty()) {
            classpath.append(File.pathSeparatorChar).append(runtimeClasspath);
        }
        if (extraClasspath != null) {
            for (String entry : extraClasspath) {
                if (entry == null || entry.isEmpty()) {
                    continue;
                }
                classpath.append(File.pathSeparatorChar).append(entry);
            }
        }
        return classpath.toString();
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.toString() : message;
    }
}
