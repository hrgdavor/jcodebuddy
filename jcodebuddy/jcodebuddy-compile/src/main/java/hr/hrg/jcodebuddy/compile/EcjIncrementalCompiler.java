// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.compile;

import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
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

    /** Source root the changed files' packages are derived from. */
    private final Path sourceRoot;

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
        this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        this.binDir = Objects.requireNonNull(binDir, "binDir").toAbsolutePath().normalize();
        this.nameEnvironment = new CachingNameEnvironment(classpath, this.binDir);
        this.classFileWriter = new ClassFileWriter(this.binDir);
        this.options = buildOptions();
        this.errorPolicy = DefaultErrorHandlingPolicies.proceedWithAllProblems();
        this.problemFactory = new DefaultProblemFactory(Locale.getDefault());
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
        Objects.requireNonNull(changedFiles, "changedFiles");
        long startedAt = System.nanoTime();
        try {
            classFileWriter.reset();
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
            SourceUnit[] units = new SourceUnit[changedFiles.size()];
            int next = 0;
            for (Path file : changedFiles) {
                try {
                    units[next++] = new SourceUnit(file, sourceRoot);
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

    // ── Options ───────────────────────────────────────────────────────────────

    /**
     * Builds the ECJ options used for every compile. They are tuned for the
     * incremental case:
     * <ul>
     *   <li>Java 21 source, compliance and target — the level this compiler is
     *       expected to emit; a project that needs a newer level changes this method.</li>
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

        settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_21);
        settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_21);
        settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_21);
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
