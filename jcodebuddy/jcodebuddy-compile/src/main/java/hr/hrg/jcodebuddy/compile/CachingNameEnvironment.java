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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * A persistent {@link INameEnvironment} that indexes dependency type locations and reads class bytes
 * on demand, after a one-time {@link #index()} call.
 *
 * <h2>Why this exists</h2>
 * ECJ's {@code BatchCompiler} builds a fresh name environment on every invocation,
 * which re-scans every classpath JAR on every compile. For a project with a handful
 * of Jackson and SLF4J JARs (a few thousand class files), that is tens of
 * milliseconds before ECJ parses a single source line.
 *
 * <p>The index stores entry locations, not class bytes; classes are read on demand and kept in a
 * bounded LRU hot cache.</p>
 *
 * <h2>Type resolution order</h2>
 * <ol>
 *   <li>{@link #setSourceUnits(SourceUnit[]) Source units} currently being compiled
 *       (so units in one batch can resolve each other).</li>
 *   <li>The bin directory — classes written by earlier compiles of this project.
 *       Read from the filesystem on every lookup, so a class replaced by the
 *       previous compile is the one that is seen.</li>
 *   <li>The JAR type index — entry locations; class bytes are read on demand from the JAR and cached
 *       only in a bounded hot set.</li>
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

    /**
     * The expensive half: JAR/JDK class bytes and package information. Several compilers over the same
     * project classpath share one instance; the per-compiler {@link CachingNameEnvironment} keeps only
     * its output directory and current source units.
     */
    static final class SharedIndex {
        private static final long MAX_JAR_CACHE_BYTES =
                Long.getLong("jcodebuddy.compile.jarCacheBytes", 32L * 1024 * 1024);
        private final Map<String, JarLocation> jarTypes = new HashMap<>();
        private final LinkedHashMap<String, byte[]> jarBytes = new LinkedHashMap<>(256, 0.75f, true);
        private long jarBytesSize;
        private final Map<String, byte[]> jdkTypeCache = new HashMap<>();
        private long jdkBytesSize;
        private final Set<String> packageSet = new HashSet<>();
        private final Map<String, Module> packageModules = new HashMap<>();
        private boolean indexed;

        private byte[] cachedJarType(String typeKey) {
            return jarBytes.get(typeKey);
        }

        private void rememberJdkType(String typeKey, byte[] bytes) {
            byte[] previous = jdkTypeCache.put(typeKey, bytes);
            if (previous != null) {
                jdkBytesSize -= previous.length;
            }
            jdkBytesSize += bytes.length;
        }

        private CompilerMemory memoryStats() {
            long jarIndexBytes = 0;
            for (Map.Entry<String, JarLocation> entry : jarTypes.entrySet()) {
                jarIndexBytes += 48 + 2L * entry.getKey().length();
                jarIndexBytes += 96 + 2L * entry.getValue().entryName().length();
            }
            long packageBytes = 0;
            for (String packageName : packageSet) {
                packageBytes += 40 + 2L * packageName.length();
            }
            packageBytes += packageModules.size() * 64L;
            long jdkIndexBytes = 0;
            for (String typeName : jdkTypeCache.keySet()) {
                jdkIndexBytes += 48 + 2L * typeName.length();
            }
            long estimated = jarBytesSize + jdkBytesSize + jarIndexBytes + packageBytes + jdkIndexBytes;
            return new CompilerMemory(jarTypes.size(), jarBytesSize, jdkBytesSize, estimated);
        }

        private void rememberJarType(String typeKey, byte[] bytes) {
            byte[] previous = jarBytes.put(typeKey, bytes);
            if (previous != null) {
                jarBytesSize -= previous.length;
            }
            jarBytesSize += bytes.length;
            // The JAR is the backing store; this map is only a bounded hot set. Evict in access order
            // until the budget is met, so a large classpath cannot pin every dependency class in heap.
            var iterator = jarBytes.entrySet().iterator();
            while (jarBytesSize > MAX_JAR_CACHE_BYTES && iterator.hasNext()) {
                Map.Entry<String, byte[]> eldest = iterator.next();
                if (eldest.getKey().equals(typeKey)) {
                    continue;
                }
                jarBytesSize -= eldest.getValue().length;
                iterator.remove();
            }
        }
    }

    private record JarLocation(File jar, String entryName) {
    }

    private final SharedIndex shared;

    /** The output directory of the project; also a source of already-compiled types. */
    private final Path binDir;

    /** Source units of the compile in progress, keyed by internal type name. */
    private Map<String, SourceUnit> sourceUnits = Collections.emptyMap();

    /**
     * @param classpathString effective {@code -cp} string (JARs and directories),
     *                        typically {@code java.class.path} plus extra entries
     * @param binDir          the output directory whose classes are part of type
     *                        resolution
     */
    CachingNameEnvironment(String classpathString, Path binDir) {
        this(classpathString, binDir, new SharedIndex());
    }

    CachingNameEnvironment(String classpathString, Path binDir, SharedIndex shared) {
        this.binDir = Objects.requireNonNull(binDir, "binDir").toAbsolutePath().normalize();
        this.shared = Objects.requireNonNull(shared, "shared");
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
     * Scans every indexed JAR's central directory, records each class's type name and entry location,
     * records package paths, and enumerates every package of every JDK module in {@link ModuleLayer#boot()}.
     * Class bytes are not loaded here; they are read from their JAR on first use.
     *
     * <p>Idempotent, and cheap after the first call. Nothing here is re-done per compile.</p>
     */
    void index() {
        if (shared.indexed) {
            return;
        }
        shared.indexed = true;
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
                    shared.jarTypes.put(typeKey, new JarLocation(jar, name));
                    typeCount++;
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "failed to index JAR " + jar + ": " + e);
            }
        }

        int packageCount = 0;
        for (Module module : ModuleLayer.boot().modules()) {
            for (String packageName : module.getPackages()) {
                String packagePath = packageName.replace('.', '/');
                if (shared.packageSet.add(packagePath)) {
                    packageCount++;
                }
                // Prefix packages matter: resolving `java.lang.Object` first asks
                // `isPackage("java")`, then `isPackage("java.lang")`. Without the
                // prefixes ECJ treats `java` as a type name and never resolves Object.
                registerPackagePath(packagePath);
                shared.packageModules.putIfAbsent(packagePath, module);
            }
        }

        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        log.log(Level.DEBUG, "indexed " + typeCount + " dependency types from " + jarFiles.size()
                + " JARs and " + packageCount + " JDK packages in " + elapsedMillis + " ms");
    }

    boolean isIndexed() {
        return shared.indexed;
    }

    /** Approximate heap use of the shared index; see {@link CompilerMemory}. */
    CompilerMemory memoryStats() {
        return shared.memoryStats();
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
        if (shared.packageSet.contains(key)) {
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

        // 3. A dependency class: read it from its JAR now, then keep it in the bounded hot cache.
        JarLocation jarLocation = shared.jarTypes.get(typeKey);
        if (jarLocation != null) {
            byte[] jarBytes = shared.cachedJarType(typeKey);
            if (jarBytes == null) {
                jarBytes = readJarType(jarLocation);
                if (jarBytes != null) {
                    shared.rememberJarType(typeKey, jarBytes);
                }
            }
            if (jarBytes != null) {
                return toAnswer(jarBytes, typeKey);
            }
        }

        // 4. A JDK type: the boot-layer module that owns its package serves the bytes.
        byte[] cachedBytes = shared.jdkTypeCache.get(typeKey);
        if (cachedBytes != null) {
            return toAnswer(cachedBytes, typeKey);
        }

        byte[] moduleBytes = loadFromModule(typeKey);
        if (moduleBytes != null) {
            shared.rememberJdkType(typeKey, moduleBytes);
            registerPackage(typeKey);
            return toAnswer(moduleBytes, typeKey);
        }

        // 5. Any other type the class loaders can serve (a directory that was not
        //    indexed, a launcher's own classpath), cached like the JDK ones.
        byte[] loadedBytes = loadViaClassLoader(typeKey);
        if (loadedBytes != null) {
            shared.rememberJdkType(typeKey, loadedBytes);
            registerPackage(typeKey);
            return toAnswer(loadedBytes, typeKey);
        }

        return null;
    }

    /**
     * Reads one class file from its JAR on demand. The JAR stays on disk; the OS page cache is the
     * real backing store, so only the bounded hot set in {@link SharedIndex} occupies Java heap.
     */
    private static byte[] readJarType(JarLocation location) {
        try (JarFile jar = new JarFile(location.jar())) {
            JarEntry entry = jar.getJarEntry(location.entryName());
            if (entry == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException failed) {
            log.log(Level.DEBUG, "could not read " + location.entryName() + " from " + location.jar()
                    + ": " + failed);
            return null;
        }
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
        Module module = shared.packageModules.get(packagePath);
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
            shared.packageSet.add(packagePath.substring(0, next));
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
