// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run.ecj;

import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Drop-in incremental compiler that replaces {@code BatchCompiler} for
 * file-change triggered recompiles.
 *
 * <h2>Architecture</h2>
 * <pre>
 *   IncrementalCompiler
 *   ├── CachingNameEnvironment  (one instance, lives forever)
 *   │     ├── jarTypeCache      (all JAR bytes, loaded once at index())
 *   │     ├── jdkTypeCache      (JDK class bytes, loaded on demand, cached)
 *   │     └── packageSet        (JARs + ModuleLayer.boot() packages)
 *   ├── ClassFileWriter         (one instance, reset()/reused each compile)
 *   └── CompilerOptions         (built once, reused)
 *
 *   Per compile():
 *     new Compiler(nameEnv, ...)   ← lightweight; resets LookupEnvironment
 *     compiler.compile(units)      ← parses changed .java files, resolves
 *                                    types via nameEnv (mostly HashMap), writes .class
 * </pre>
 *
 * <h2>Performance model</h2>
 * <ul>
 *   <li>{@code index()} — one-time cost ~200–500 ms (depends on JAR count)</li>
 *   <li>{@code compile(k files)} — expected ~5–15 ms for 1–3 changed files
 *       on a warm JVM, vs ~30–80 ms with {@code BatchCompiler}</li>
 *   <li>Savings come from eliminating JAR re-scanning: {@code findType} calls
 *       hit a {@code HashMap} instead of iterating {@code JarFile} entries</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread-safe — must be called from the single-threaded reload executor.
 */
public final class IncrementalCompiler {
    private static final Logger log = LoggerFactory.getLogger(IncrementalCompiler.class);

    private final Path                  srcDir;
    private final CachingNameEnvironment nameEnv;
    private final ClassFileWriter        requestor;
    private final CompilerOptions        options;
    private final IErrorHandlingPolicy   errorPolicy;
    private final IProblemFactory        problemFactory;

    /**
     * @param srcDir          source root (passed to {@link SourceUnit})
     * @param binDir          output directory for {@code .class} files
     * @param classpathString effective {@code -cp} string including dependency JARs
     *                        (typically {@code java.class.path} + extra entries)
     */
    public IncrementalCompiler(Path srcDir, Path binDir, String classpathString) {
        this.srcDir         = srcDir;
        this.nameEnv        = new CachingNameEnvironment(classpathString);
        this.nameEnv.binDir = binDir;
        this.requestor      = new ClassFileWriter(binDir);
        this.options        = buildOptions();
        this.errorPolicy    = DefaultErrorHandlingPolicies.proceedWithAllProblems();
        this.problemFactory = new DefaultProblemFactory(Locale.getDefault());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Pre-indexes all JAR class bytes and all JDK module packages.
     *
     * <p>Must be called once before {@link #compile}.  Idempotent.
     * Runs on the reload executor thread (after the startup full compile),
     * so the app has already started and there is no user-visible delay.
     */
    public void index() {
        nameEnv.index();
    }

    // ── Compile ───────────────────────────────────────────────────────────────

    /**
     * Incrementally compiles the given set of changed source files.
     *
     * <p>Key differences from {@code BatchCompiler}:
     * <ul>
     *   <li>No JAR scanning — type bytes come from the in-memory cache.</li>
     *   <li>A fresh {@code Compiler} (ECJ internal) is created per call, but
     *       the expensive {@link CachingNameEnvironment} is reused.</li>
     *   <li>All warnings disabled ({@code CompilerOptions}) to minimise AST
     *       traversal overhead.</li>
     * </ul>
     *
     * @param changedFiles the {@code .java} files to compile
     * @return {@code true} if compilation succeeded (no errors)
     */
    public boolean compile(Set<Path> changedFiles) {
        if (changedFiles.isEmpty()) return true;

        // ── Build source units ────────────────────────────────────────────────
        SourceUnit[] units = new SourceUnit[changedFiles.size()];
        int i = 0;
        for (Path f : changedFiles) {
            try {
                units[i++] = new SourceUnit(f, srcDir);
            } catch (IOException e) {
                log.error("Failed to read source file {}: {}", f, e.getMessage());
                return false;
            }
        }

        // ── Inject cross-unit source resolution ───────────────────────────────
        nameEnv.setSourceUnits(units);
        requestor.reset();

        // ── Create a fresh Compiler (lightweight; nameEnv is the expensive part)
        Compiler compiler = new Compiler(nameEnv, errorPolicy, options, requestor, problemFactory);

        // ── Compile ───────────────────────────────────────────────────────────
        compiler.compile(units);
        // nameEnv.cleanup() is called inside compiler.compile() in a finally block

        // ── Report errors ──────────────────────────────────────────────────────
        if (requestor.hasErrors()) {
            for (String err : requestor.getErrors()) {
                log.error("ECJ: {}", err);
            }
            return false;
        }
        return true;
    }

    // ── Options ───────────────────────────────────────────────────────────────

    /**
     * Builds ECJ {@link CompilerOptions} tuned for minimum compile latency:
     * <ul>
     *   <li>Java 21 source/target/compliance.</li>
     *   <li>All warnings disabled (each warning requires an extra AST pass).</li>
     *   <li>Annotation processing disabled (like {@code -proc:none}).</li>
     *   <li>Doc comment parsing disabled (saves time in the parser).</li>
     * </ul>
     */
    private static CompilerOptions buildOptions() {
        Map<String, String> s = new HashMap<>();

        s.put(CompilerOptions.OPTION_Source,          CompilerOptions.VERSION_21);
        s.put(CompilerOptions.OPTION_Compliance,      CompilerOptions.VERSION_21);
        s.put(CompilerOptions.OPTION_TargetPlatform,  CompilerOptions.VERSION_21);
        s.put(CompilerOptions.OPTION_Encoding,        "UTF-8");

        // Annotation processing (proc:none equivalent)
        s.put(CompilerOptions.OPTION_Process_Annotations, CompilerOptions.DISABLED);

        // Debug attributes (keep for IDE/debugger integration)
        s.put(CompilerOptions.OPTION_LineNumberAttribute,   CompilerOptions.GENERATE);
        s.put(CompilerOptions.OPTION_SourceFileAttribute,   CompilerOptions.GENERATE);
        s.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE);

        // ── Disable all analysis that adds overhead without compile correctness ──

        // Null analysis
        s.put(CompilerOptions.OPTION_ReportNullReference,             CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportPotentialNullReference,    CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportRedundantNullCheck,        CompilerOptions.IGNORE);

        // Unused code warnings
        s.put(CompilerOptions.OPTION_ReportUnusedImport,              CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportUnusedLocal,               CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportUnusedPrivateMember,       CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportUnusedDeclaredThrownException, CompilerOptions.IGNORE);

        // Dead code
        s.put(CompilerOptions.OPTION_ReportDeadCode,                  CompilerOptions.IGNORE);

        // Generics / raw types
        s.put(CompilerOptions.OPTION_ReportUncheckedTypeOperation,    CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportRawTypeReference,          CompilerOptions.IGNORE);

        // Deprecation
        s.put(CompilerOptions.OPTION_ReportDeprecation,               CompilerOptions.IGNORE);

        // Style warnings
        s.put(CompilerOptions.OPTION_ReportUnnecessaryElse,           CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportFieldHiding,               CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportLocalVariableHiding,       CompilerOptions.IGNORE);

        // Javadoc (parsing overhead)
        s.put(CompilerOptions.OPTION_DocCommentSupport,               CompilerOptions.DISABLED);
        s.put(CompilerOptions.OPTION_ReportMissingJavadocComments,    CompilerOptions.IGNORE);
        s.put(CompilerOptions.OPTION_ReportMissingJavadocTags,        CompilerOptions.IGNORE);

        // Suppress all remaining warnings
        s.put(CompilerOptions.OPTION_SuppressWarnings,                CompilerOptions.ENABLED);

        return new CompilerOptions(s);
    }

    /** Builds a classpath string that prepends {@code binDir} for type reuse. */
    public static String buildClasspath(Path binDir, List<String> extraClasspath) {
        String runtimeCp = System.getProperty("java.class.path");
        StringBuilder sb = new StringBuilder();
        sb.append(binDir.toAbsolutePath()).append(File.pathSeparatorChar);
        sb.append(runtimeCp);
        if (extraClasspath != null && !extraClasspath.isEmpty()) {
            sb.append(File.pathSeparatorChar).append(String.join(File.pathSeparator, extraClasspath));
        }
        return sb.toString();
    }
}
