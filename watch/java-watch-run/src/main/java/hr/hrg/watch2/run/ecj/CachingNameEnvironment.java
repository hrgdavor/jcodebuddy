// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run.ecj;

import java.util.regex.Pattern;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A persistent {@link INameEnvironment} that caches the binary contents of all
 * classpath JARs in a {@code HashMap<String, byte[]>} after a one-time
 * {@link #index()} call.
 *
 * <h2>Why this matters</h2>
 * ECJ's {@code BatchCompiler} creates a fresh {@code FileSystem} (name environment)
 * on every invocation, which re-scans every JAR on every compile.  For a project
 * with Jackson + SLF4J (≈ 1 700 class files), that takes 25–60 ms even before
 * ECJ parses a single source line.
 * <p>
 * By pre-loading all JAR bytes into a {@code HashMap}, subsequent {@link #findType}
 * calls become simple map lookups, reducing the per-compile overhead to near zero.
 *
 * <h2>Type resolution order</h2>
 * <ol>
 *   <li>{@link #setSourceUnits(SourceUnit[]) Source units} currently being compiled
 *       (for cross-unit symbol resolution).</li>
 *   <li>{@code bin/} directory — previously compiled project classes.</li>
 *   <li>JAR cache — pre-indexed dependency JARs.</li>
 *   <li>Platform / bootstrap classloader — JDK classes (loaded on demand,
 *       cached for subsequent calls).</li>
 * </ol>
 *
 * <h2>Package discovery</h2>
 * {@code isPackage()} checks a {@code HashSet<String>} that is populated from:
 * <ul>
 *   <li>The package components of every class in every indexed JAR.</li>
 *   <li>Every package exposed by the JDK modules in {@code ModuleLayer.boot()}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread-safe by design — to be called only from the single-threaded
 * {@code hot-swap-reload} executor.
 */
public final class CachingNameEnvironment implements INameEnvironment {
    private static final Logger log = LoggerFactory.getLogger(CachingNameEnvironment.class);

    // ── Persistent state (survives across compile cycles) ─────────────────────

    /** Dependency JAR files to index (no JDK modules — those go through classloader). */
    private final List<File> jarFiles;

    /**
     * All class bytes loaded from dependency JARs.
     * Key: internal slash-form name, e.g. {@code "com/fasterxml/jackson/core/JsonParser"}.
     */
    private final Map<String, byte[]> jarTypeCache  = new HashMap<>();

    /**
     * JDK class bytes loaded on-demand from the platform/bootstrap classloaders.
     * Same key format as {@link #jarTypeCache}.
     */
    private final Map<String, byte[]> jdkTypeCache  = new HashMap<>();

    /**
     * All known package paths in slash form.
     * e.g. {@code "com"}, {@code "com/fasterxml"}, {@code "com/fasterxml/jackson"}.
     * Pre-populated from JARs and JDK modules.
     */
    private final Set<String>         packageSet     = new HashSet<>();

    // ── Per-compile state ─────────────────────────────────────────────────────

    /** Source units currently being compiled, keyed by internal type name. */
    private Map<String, SourceUnit>   sourceUnits    = Collections.emptyMap();

    // ── Flags ─────────────────────────────────────────────────────────────────

    private boolean indexed = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param classpathString the effective {@code -cp} string (JARs, directories).
     *                        Typically {@code java.class.path} plus extra entries.
     */
    public CachingNameEnvironment(String classpathString) {
        jarFiles = new ArrayList<>();
        for (String entry : classpathString.split(Pattern.quote(File.pathSeparator))) {
            File f = new File(entry.trim());
            if (f.isFile() && (entry.endsWith(".jar") || entry.endsWith(".zip"))) {
                jarFiles.add(f);
            }
            // Directories (including bin/) are checked dynamically via the filesystem.
        }
    }

    // ── Index (one-time startup cost) ─────────────────────────────────────────

    /**
     * Eagerly loads the binary content of every {@code .class} file in every
     * indexed JAR into {@link #jarTypeCache}, and records all package paths.
     *
     * <p>Also enumerates every package in every JDK module in
     * {@link ModuleLayer#boot()} so that {@link #isPackage} works for JDK types
     * without additional I/O.
     *
     * <p>Call once, after the daemon's startup full compile.  Safe to call from
     * the reload executor thread.
     */
    public void index() {
        if (indexed) return;
        indexed = true;

        long t0 = System.currentTimeMillis();
        int  typeCount = 0;

        // ── 1. Load all class bytes from dependency JARs ──────────────────────
        for (File jar : jarFiles) {
            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || entry.isDirectory()) continue;

                    String typeKey = name.substring(0, name.length() - 6); // strip .class
                    registerPackage(typeKey);
                    try (InputStream is = jf.getInputStream(entry)) {
                        jarTypeCache.put(typeKey, is.readAllBytes());
                        typeCount++;
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to index JAR {}: {}", jar, e.getMessage());
            }
        }

        // ── 2. Open jrt:/ filesystem and eagerly cache all JDK module class bytes ──
        // On Java 9+ the JDK classes live in modules, not on the standard classpath.
        // The platform classloader cannot serve them via getResourceAsStream(), so we
        // use the jrt:/ FileSystem instead.
        openJrtFilesystem();
        indexJrtModules(); // may take ~200-400ms on first run; cached forever after

        // ── 3. Enumerate JDK module packages via ModuleLayer.boot() ─────────────
        // isPackage() must return true for every JDK package. Enumerating modules
        // from the module layer is O(N packages) and needs no I/O.
        int jdkPkgCount = 0;
        for (Module m : ModuleLayer.boot().modules()) {
            for (String pkg : m.getPackages()) {
                packageSet.add(pkg.replace('.', '/'));
                jdkPkgCount++;
            }
        }

        log.info("CachingNameEnvironment: {} dep-types from {} JARs, {} JDK packages from modules in {}ms",
                typeCount, jarFiles.size(), jdkPkgCount, System.currentTimeMillis() - t0);
    }

    // ── Per-compile control ───────────────────────────────────────────────────

    /**
     * Injects the source units for the current compile so they can resolve
     * each other's types (e.g. SampleMain referencing DataProcessor in the
     * same batch).  Must be called before each {@code Compiler.compile()}.
     *
     * @param units the units being compiled, or {@code null} to clear
     */
    public void setSourceUnits(SourceUnit[] units) {
        if (units == null || units.length == 0) {
            sourceUnits = Collections.emptyMap();
        } else {
            sourceUnits = new HashMap<>(units.length * 2);
            for (SourceUnit u : units) sourceUnits.put(u.getInternalTypeName(), u);
        }
    }

    // ── INameEnvironment ──────────────────────────────────────────────────────

    @Override
    public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
        return findByKey(toKey(compoundTypeName));
    }

    @Override
    public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
        return findByKey(toKey(packageName, typeName));
    }

    @Override
    public boolean isPackage(char[][] parentPackageName, char[] packageName) {
        return packageSet.contains(toKey(parentPackageName, packageName));
    }

    /**
     * Called by ECJ after {@code Compiler.compile()} (in a {@code finally} block).
     * We only clear the per-compile source-unit map; the JAR cache and package
     * set are persistent and must NOT be cleared here.
     */
    @Override
    public void cleanup() {
        sourceUnits = Collections.emptyMap();
    }

    // ── Internal resolution ───────────────────────────────────────────────────

    private NameEnvironmentAnswer findByKey(String typeKey) {
        // 1. Source unit currently being compiled (highest priority)
        SourceUnit su = sourceUnits.get(typeKey);
        if (su != null) return new NameEnvironmentAnswer(su, null);

        // 2. Previously compiled class in bin/ (project itself)
        //    Always check the filesystem so we pick up the most recent compile.
        NameEnvironmentAnswer fromBin = lookupInBinDir(typeKey);
        if (fromBin != null) return fromBin;

        // 3. Dependency JAR (pre-indexed in memory)
        byte[] jarBytes = jarTypeCache.get(typeKey);
        if (jarBytes != null) return toAnswer(jarBytes, typeKey);

        // 4. JDK type via platform/bootstrap classloader (cached after first access)
        byte[] jdkCached = jdkTypeCache.get(typeKey);
        if (jdkCached != null) return toAnswer(jdkCached, typeKey);

        byte[] jdkBytes = loadViaClassLoader(typeKey);
        if (jdkBytes != null) {
            jdkTypeCache.put(typeKey, jdkBytes);
            // Register its packages so future isPackage() calls succeed
            registerPackage(typeKey);
            return toAnswer(jdkBytes, typeKey);
        }

        return null; // type not found anywhere
    }

    private NameEnvironmentAnswer lookupInBinDir(String typeKey) {
        // binDir lookup is done by the caller (IncrementalCompiler passes binDir
        // as a classpath directory; see note below).
        // We do it here explicitly for correctness — Compiler uses us as the
        // sole resolution authority.
        //
        // This field is set by IncrementalCompiler after construction.
        if (binDir == null) return null;
        Path classFile = binDir.resolve(typeKey.replace('/', File.separatorChar) + ".class");
        if (!Files.exists(classFile)) return null;
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            ClassFileReader reader = new ClassFileReader(bytes, typeKey.toCharArray(), false);
            return new NameEnvironmentAnswer(reader, null);
        } catch (IOException | ClassFormatException e) {
            log.debug("bin/ read error for {}: {}", typeKey, e.getMessage());
            return null;
        }
    }

    private static NameEnvironmentAnswer toAnswer(byte[] bytes, String typeKey) {
        try {
            ClassFileReader reader = new ClassFileReader(bytes, typeKey.toCharArray(), false);
            return new NameEnvironmentAnswer(reader, null);
        } catch (ClassFormatException e) {
            log.debug("ClassFormatException parsing {}: {}", typeKey, e.getMessage());
            return null;
        }
    }

    private static byte[] loadViaClassLoader(String typeKey) {
        String resource = typeKey + ".class";
        // Standard system-classloader resource lookup (covers app classpath, etc.)
        try (InputStream is = ClassLoader.getSystemResourceAsStream(resource)) {
            if (is != null) return is.readAllBytes();
        } catch (IOException ignored) {}
        return null;
    }

    // ── jrt:/ filesystem for JDK module class bytes (Java 9+) ────────────────

    /** The jrt:/ filesystem, opened once and reused. {@code null} if unavailable. */
    private java.nio.file.FileSystem jrtFs;

    /**
     * Opens the {@code jrt:/} virtual filesystem that exposes all JDK module class
     * bytes on Java 9+.  Called once from {@link #index()}.
     *
     * <p>With the jrt:/ filesystem, any JDK class can be read as a byte array via:
     * <pre>
     *   Files.readAllBytes(jrtFs.getPath("/modules", moduleName, "java/lang/Object.class"))
     * </pre>
     * However, we don't know the module name for an arbitrary type key, so we
     * pre-index the jrt:/ tree during {@link #indexJrtModules()}.
     */
    private void openJrtFilesystem() {
        try {
            jrtFs = java.nio.file.FileSystems.newFileSystem(
                    java.net.URI.create("jrt:/"),
                    Collections.singletonMap("java.home", System.getProperty("java.home")));
        } catch (Exception e) {
            log.warn("Could not open jrt:/ filesystem (JDK class resolution may fail): {}", e.getMessage());
            jrtFs = null;
        }
    }

    /**
     * Eagerly loads all JDK class bytes from the {@code jrt:/} filesystem.
     * Also registers all package paths found.
     *
     * <p>This replaces the unreliable {@code platformClassLoader.getResourceAsStream()}
     * approach which does not work for JDK module classes in Java 9+.
     */
    private void indexJrtModules() {
        if (jrtFs == null) return;
        long t0 = System.currentTimeMillis();
        int count = 0;
        try {
            java.nio.file.Path modules = jrtFs.getPath("/modules");
            // Walk the entire jrt:/ tree - /modules/<moduleName>/<packagePath>/<Type>.class
            try (var stream = Files.walk(modules)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                    String name = p.toString();
                    if (!name.endsWith(".class")) continue;
                    // name: /modules/java.base/java/lang/Object.class
                    // We need the key without leading module component: "java/lang/Object"
                    // Path structure: /modules/<module>/<rest...>
                    java.nio.file.Path rel = modules.relativize(p);
                    int nameCount = rel.getNameCount();
                    if (nameCount < 2) continue; // skip module-info.class at root level
                    // Skip the module name (first segment), build type key from rest
                    java.nio.file.Path typePath = rel.subpath(1, nameCount);
                    String typeKey = typePath.toString().replace('\\', '/');
                    if (typeKey.endsWith(".class")) typeKey = typeKey.substring(0, typeKey.length() - 6);
                    registerPackage(typeKey);
                    byte[] bytes = Files.readAllBytes(p);
                    jdkTypeCache.put(typeKey, bytes);
                    count++;
                }
            }
        } catch (IOException e) {
            log.warn("Error walking jrt:/ filesystem: {}", e.getMessage());
        }
        log.info("CachingNameEnvironment: indexed {} JDK module class bytes from jrt:/ in {}ms",
                count, System.currentTimeMillis() - t0);
    }

    // ── Package registration ──────────────────────────────────────────────────

    /**
     * Registers all prefix packages of a type's internal name.
     * e.g. {@code "com/fasterxml/jackson/core/JsonParser"} registers
     * {@code "com"}, {@code "com/fasterxml"}, {@code "com/fasterxml/jackson"}, …
     */
    private void registerPackage(String typeKey) {
        int slash = typeKey.lastIndexOf('/');
        if (slash <= 0) return;
        String pkg = typeKey.substring(0, slash);
        int i = 0;
        while (i <= pkg.length()) {
            int next = pkg.indexOf('/', i);
            if (next < 0) next = pkg.length();
            packageSet.add(pkg.substring(0, next));
            if (next == pkg.length()) break;
            i = next + 1;
        }
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    /** {@code bin/} directory, set by IncrementalCompiler after construction. */
    Path binDir;

    private static String toKey(char[][] parts) {
        if (parts == null || parts.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (char[] p : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    private static String toKey(char[][] packageParts, char[] typeName) {
        StringBuilder sb = new StringBuilder();
        if (packageParts != null) {
            for (char[] p : packageParts) {
                if (sb.length() > 0) sb.append('/');
                sb.append(p);
            }
        }
        if (sb.length() > 0) sb.append('/');
        sb.append(typeName);
        return sb.toString();
    }
}
