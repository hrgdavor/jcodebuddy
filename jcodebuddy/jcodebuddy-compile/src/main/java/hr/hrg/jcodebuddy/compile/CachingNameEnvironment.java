// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.compile;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * A persistent {@link INameEnvironment} that caches the binary contents of all
 * classpath JARs after a one-time {@link #index()} call.
 *
 * <h2>Why this exists</h2>
 * ECJ's {@code BatchCompiler} builds a fresh name environment on every invocation,
 * which re-scans every classpath JAR on every compile. For a project with a handful
 * of Jackson and SLF4J JARs (a few thousand class files), that is tens of
 * milliseconds before ECJ parses a single source line.
 *
 * <p>By pre-loading the JAR bytes into a {@code HashMap} once, every later
 * {@link #findType} call is a map lookup, and the per-compile overhead drops to
 * near zero. This is the property the incremental compiler relies on: the
 * <em>index</em> is reused, only the sources change.
 *
 * <h2>Type resolution order</h2>
 * <ol>
 *   <li>{@link #setSourceUnits(SourceUnit[]) Source units} currently being compiled
 *       (so units in one batch can resolve each other).</li>
 *   <li>The bin directory — classes written by earlier compiles of this project.
 *       Read from the filesystem on every lookup, so a class replaced by the
 *       previous compile is the one that is seen.</li>
 *   <li>The JAR cache — pre-indexed dependency class files.</li>
 *   <li>JDK class files, read on demand through the {@link Module} that owns the
 *       package (a {@code .class} resource is never encapsulated) and cached from
 *       then on. This deliberately does not depend on a class loader: under a test
 *       runner or another launcher the system loader may be a custom one that does
 *       not serve JDK module resources.</li>
 *   <li>Any other class the system or context class loader can answer for.</li>
 * </ol>
 *
 * <h2>Package discovery</h2>
 * {@link #isPackage} answers from:
 * <ul>
 *   <li>The prefix packages of every class found in every indexed JAR.</li>
 *   <li>Every package exposed by the JDK modules in {@link ModuleLayer#boot()}, so a
 *       JDK package needs no I/O and no class load.</li>
 *   <li>A directory probe of the bin directory, for packages this project compiled
 *       itself — they are not in any JAR and must still be recognised when a source
 *       imports them.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * Not thread-safe by design. An incremental compile is a single-threaded operation;
 * the caches are mutable and the source-unit map is replaced per compile.
 */
final class CachingNameEnvironment implements INameEnvironment {

    private static final Logger log = System.getLogger(CachingNameEnvironment.class.getName());

    /** Dependency JAR files to index; directories are probed through the filesystem. */
    private final List<File> jarFiles = new ArrayList<>();

    /** All class bytes loaded from dependency JARs, keyed by slash-form type name. */
    private final Map<String, byte[]> jarTypeCache = new HashMap<>();

    /** Class bytes loaded on demand and kept for later compiles. */
    private final Map<String, byte[]> jdkTypeCache = new HashMap<>();

    /** Known package paths in slash form, e.g. {@code "com/fasterxml/jackson"}. */
    private final Set<String> packageSet = new HashSet<>();

    /** The boot-layer module that owns each JDK package, keyed by slash-form package path. */
    private final Map<String, Module> packageModules = new HashMap<>();

    /** The output directory of the project; also a source of already-compiled types. */
    private final Path binDir;

    /** Source units of the compile in progress, keyed by internal type name. */
    private Map<String, SourceUnit> sourceUnits = Collections.emptyMap();

    private boolean indexed;

    /**
     * @param classpathString effective {@code -cp} string (JARs and directories),
     *                        typically {@code java.class.path} plus extra entries
     * @param binDir          the output directory whose classes are part of type
     *                        resolution
     */
    CachingNameEnvironment(String classpathString, Path binDir) {
        this.binDir = Objects.requireNonNull(binDir, "binDir").toAbsolutePath().normalize();
        String classpath = classpathString == null ? "" : classpathString;
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            String candidate = entry.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            File file = new File(candidate);
            if (file.isFile() && (candidate.endsWith(".jar") || candidate.endsWith(".zip"))) {
                jarFiles.add(file);
            }
            // Directories (including the bin directory) are checked through the filesystem.
        }
    }

    /**
     * Eagerly loads the binary content of every {@code .class} file in every indexed
     * JAR, records all JAR package paths, and enumerates every package of every JDK
     * module in {@link ModuleLayer#boot()}.
     *
     * <p>Idempotent, and cheap after the first call. Nothing here is re-done per
     * compile; that is the point of the class.
     *
     * <p>JDK <em>class bytes</em> are deliberately not loaded here: the package set
     * from the module layer makes {@link #isPackage} answer without I/O, and the
     * individual class files are read on first use and cached. That keeps the index
     * small (no tens of thousands of JDK class files in memory) while preserving the
     * one-read-only property per type.
     */
    void index() {
        if (indexed) {
            return;
        }
        indexed = true;
        long startedAt = System.nanoTime();
        int typeCount = 0;

        for (File jar : jarFiles) {
            try (JarFile jarFile = new JarFile(jar)) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".class")) {
                        continue;
                    }
                    String typeKey = name.substring(0, name.length() - ".class".length());
                    registerPackage(typeKey);
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        jarTypeCache.put(typeKey, in.readAllBytes());
                        typeCount++;
                    }
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "failed to index JAR " + jar + ": " + e);
            }
        }

        int packageCount = 0;
        for (Module module : ModuleLayer.boot().modules()) {
            for (String packageName : module.getPackages()) {
                String packagePath = packageName.replace('.', '/');
                if (packageSet.add(packagePath)) {
                    packageCount++;
                }
                // Prefix packages matter: resolving `java.lang.Object` first asks
                // `isPackage("java")`, then `isPackage("java.lang")`. Without the
                // prefixes ECJ treats `java` as a type name and never resolves Object.
                registerPackagePath(packagePath);
                packageModules.putIfAbsent(packagePath, module);
            }
        }

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        log.log(Level.DEBUG, "indexed " + typeCount + " dependency types from " + jarFiles.size()
                + " JARs and " + packageCount + " JDK packages in " + elapsedMillis + " ms");
    }

    boolean isIndexed() {
        return indexed;
    }

    /**
     * Injects the source units of the compile in progress so they can resolve each
     * other's types (e.g. {@code Main} referencing {@code Helper} in the same batch).
     * Must be called before each {@code Compiler.compile()}.
     */
    void setSourceUnits(SourceUnit[] units) {
        if (units == null || units.length == 0) {
            sourceUnits = Collections.emptyMap();
            return;
        }
        Map<String, SourceUnit> byTypeName = new HashMap<>(units.length * 2);
        for (SourceUnit unit : units) {
            byTypeName.put(unit.getInternalTypeName(), unit);
        }
        sourceUnits = byTypeName;
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
        String key = toKey(parentPackageName, packageName);
        if (key.isEmpty()) {
            return false;
        }
        if (packageSet.contains(key)) {
            return true;
        }
        // A package this project compiled itself lives under bin/, not in any JAR.
        return Files.isDirectory(binDir.resolve(key.replace('/', File.separatorChar)));
    }

    /**
     * Called by ECJ after {@code Compiler.compile()} (in a {@code finally} block).
     * Only the per-compile source-unit map is cleared; the JAR cache and the package
     * set are persistent and must survive.
     */
    @Override
    public void cleanup() {
        sourceUnits = Collections.emptyMap();
    }

    // ── Internal resolution ───────────────────────────────────────────────────

    private NameEnvironmentAnswer findByKey(String typeKey) {
        // 1. A source unit of the current batch has the highest priority.
        SourceUnit sourceUnit = sourceUnits.get(typeKey);
        if (sourceUnit != null) {
            return new NameEnvironmentAnswer(sourceUnit, null);
        }

        // 2. A class already compiled into bin/ — read every time on purpose, so the
        //    class file written by the previous compile is what the next one sees.
        NameEnvironmentAnswer fromBin = lookupInBinDir(typeKey);
        if (fromBin != null) {
            return fromBin;
        }

        // 3. A dependency class, pre-indexed in memory.
        byte[] jarBytes = jarTypeCache.get(typeKey);
        if (jarBytes != null) {
            return toAnswer(jarBytes, typeKey);
        }

        // 4. A JDK type: the boot-layer module that owns its package serves the bytes.
        byte[] cachedBytes = jdkTypeCache.get(typeKey);
        if (cachedBytes != null) {
            return toAnswer(cachedBytes, typeKey);
        }

        byte[] moduleBytes = loadFromModule(typeKey);
        if (moduleBytes != null) {
            jdkTypeCache.put(typeKey, moduleBytes);
            registerPackage(typeKey);
            return toAnswer(moduleBytes, typeKey);
        }

        // 5. Any other type the class loaders can serve (a directory that was not
        //    indexed, a launcher's own classpath), cached like the JDK ones.
        byte[] loadedBytes = loadViaClassLoader(typeKey);
        if (loadedBytes != null) {
            jdkTypeCache.put(typeKey, loadedBytes);
            registerPackage(typeKey);
            return toAnswer(loadedBytes, typeKey);
        }

        return null;
    }

    private NameEnvironmentAnswer lookupInBinDir(String typeKey) {
        Path classFile = binDir.resolve(typeKey.replace('/', File.separatorChar) + ".class");
        if (!Files.isRegularFile(classFile)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            ClassFileReader reader = new ClassFileReader(bytes, typeKey.toCharArray(), false);
            return new NameEnvironmentAnswer(reader, null);
        } catch (IOException | ClassFormatException e) {
            log.log(Level.DEBUG, "could not read " + classFile + ": " + e);
            return null;
        }
    }

    private static NameEnvironmentAnswer toAnswer(byte[] bytes, String typeKey) {
        try {
            ClassFileReader reader = new ClassFileReader(bytes, typeKey.toCharArray(), false);
            return new NameEnvironmentAnswer(reader, null);
        } catch (ClassFormatException e) {
            log.log(Level.DEBUG, "not a class file at " + typeKey + ": " + e);
            return null;
        }
    }

    /**
     * Reads a JDK class file from the boot-layer module that owns its package.
     *
     * <p>{@link Module#getResourceAsStream} serves a {@code .class} resource even
     * when the package is not open, and it does not involve the class loader chain a
     * launcher may have replaced. That is what makes JDK resolution work under
     * surefire as well as under a plain {@code java -cp}.
     */
    private byte[] loadFromModule(String typeKey) {
        int lastSlash = typeKey.lastIndexOf('/');
        String packagePath = lastSlash < 0 ? "" : typeKey.substring(0, lastSlash);
        Module module = packageModules.get(packagePath);
        if (module == null) {
            return null;
        }
        try (InputStream in = module.getResourceAsStream(typeKey + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            log.log(Level.DEBUG, "could not read " + typeKey + " from module " + module.getName() + ": " + e);
            return null;
        }
    }

    private static byte[] loadViaClassLoader(String typeKey) {
        String resource = typeKey + ".class";
        // The system loader covers the application classpath and every JDK module's
        // `.class` resources (a `.class` resource is not subject to module opens).
        try (InputStream in = ClassLoader.getSystemResourceAsStream(resource)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
            // fall through to the context loader
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context != ClassLoader.getSystemClassLoader()) {
            try (InputStream in = context.getResourceAsStream(resource)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (IOException ignored) {
                // type is not resolvable through the loaders
            }
        }
        return null;
    }

    // ── Package registration ──────────────────────────────────────────────────

    /**
     * Registers all prefix packages of a type's internal name. For
     * {@code "com/fasterxml/jackson/core/JsonParser"} it registers
     * {@code "com"}, {@code "com/fasterxml"}, {@code "com/fasterxml/jackson"}, ...
     */
    private void registerPackage(String typeKey) {
        int lastSlash = typeKey.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }
        registerPackagePath(typeKey.substring(0, lastSlash));
    }

    /** Registers a package path and each of its prefixes as a package. */
    private void registerPackagePath(String packagePath) {
        int start = 0;
        while (start <= packagePath.length()) {
            int next = packagePath.indexOf('/', start);
            if (next < 0) {
                next = packagePath.length();
            }
            packageSet.add(packagePath.substring(0, next));
            if (next == packagePath.length()) {
                break;
            }
            start = next + 1;
        }
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    private static String toKey(char[][] parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (char[] part : parts) {
            if (key.length() > 0) {
                key.append('/');
            }
            key.append(part);
        }
        return key.toString();
    }

    private static String toKey(char[][] packageParts, char[] typeName) {
        StringBuilder key = new StringBuilder();
        if (packageParts != null) {
            for (char[] part : packageParts) {
                if (key.length() > 0) {
                    key.append('/');
                }
                key.append(part);
            }
        }
        if (key.length() > 0) {
            key.append('/');
        }
        key.append(typeName);
        return key.toString();
    }
}
