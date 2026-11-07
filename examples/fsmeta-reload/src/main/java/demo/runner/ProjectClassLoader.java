package demo.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The project-only classloader: it owns {@code demo.project.*} and delegates everything else to the parent.
 *
 * <h2>Why a reload needs a new loader, and reuses bytecode instead</h2>
 *
 * <p>Java has no way to replace the code of a class that is already loaded. A {@link Class} object, its method
 * bodies and its identity are fixed once the loader has defined it, and defining the same name again in the same
 * loader is a {@link LinkageError}. So every reload defines the project's classes in a <strong>new</strong>
 * loader and discards the old one; the old {@code Class} objects stay valid (anything still referring to them
 * keeps working) but new code can only be reached through the new classes.</p>
 *
 * <p>What <em>is</em> reusable is the <strong>bytecode</strong>. The previous loader exposes its class bytes
 * through {@link #classBytes()}, and the next loader is seeded with them: an unchanged class is defined from the
 * bytes it already had, so it is not re-read from disk and its source was not recompiled. Only the classes a
 * batch actually changed are read from the output directory (plus any nested class the compiler produced next to
 * them).</p>
 *
 * <h2>Why everything else must come from the parent</h2>
 *
 * <p>Jackson and JUnit are ordinary runner dependencies: the module's own classpath. If this loader defined its
 * own copies of them, the runner's {@code ObjectMapper} and {@code @Test} would be different types from the
 * project's, and neither Jackson nor JUnit would recognise the project's classes at all — JUnit would see a
 * {@code @Test} annotation that is not the one it knows, and Jackson would refuse a record whose annotations came
 * from its own loader. So only {@code demo.project.*} is child-first; everything else is parent-first, and the
 * project classes are deliberately <em>not</em> on the runner's classpath (Maven never compiles them), so this
 * loader is the only way to reach them.</p>
 *
 * <p>Not thread-safe by design of the reload loop (one batch is applied at a time), whose only shared state is
 * the byte map itself.</p>
 */
public final class ProjectClassLoader extends ClassLoader {

    /** The one package tree this loader owns. */
    private static final String PROJECT_PACKAGE = "demo.project";
    private static final String PROJECT_PREFIX = PROJECT_PACKAGE + ".";

    private static final String CLASS_SUFFIX = ".class";

    private final Path classesDir;

    /** Known class bytes, by fully qualified name. The snapshot the next loader is seeded from. */
    private final Map<String, byte[]> bytes = new ConcurrentHashMap<>();

    /**
     * Creates one generation of the project's classes.
     *
     * @param classesDir   where the compiler wrote the {@code .class} files
     * @param previousBytes the previous loader's {@link #classBytes()}, or an empty map for the first generation
     * @param changedNames  fully qualified names whose class files were just (re)compiled; they are re-read from
     *                      {@code classesDir} and overwrite the previous bytes
     * @param deletedNames  fully qualified names whose sources are gone; they are dropped from the seed and
     *                      excluded from this generation
     */
    public ProjectClassLoader(Path classesDir, Map<String, byte[]> previousBytes,
                              Set<String> changedNames, Set<String> deletedNames) {
        // The runner's own loader: Jackson, JUnit and the runner itself all come from here.
        super(ProjectClassLoader.class.getClassLoader());
        this.classesDir = classesDir.toAbsolutePath().normalize();

        if (previousBytes != null) {
            bytes.putAll(previousBytes);
        }
        if (deletedNames != null) {
            for (String name : deletedNames) {
                removeWithNested(name);
            }
        }
        if (changedNames != null) {
            for (String name : changedNames) {
                // A changed class must come from the fresh class file. Dropping the old bytes first means a
                // missing output file fails loudly in findClass() instead of silently serving stale bytecode.
                removeWithNested(name);
                seedFromDisk(name);
            }
        }
    }

    /**
     * The bytes this loader knows, captured for the next generation.
     *
     * <p>It covers every class the loader was seeded with, including ones it has not defined yet, so the next
     * loader does not have to touch the output directory for unchanged classes.</p>
     */
    public Map<String, byte[]> classBytes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bytes));
    }

    /** How many project classes this generation knows about. */
    public int classCount() {
        return bytes.size();
    }

    /** The output directory the missing classes are read from. */
    public Path classesDir() {
        return classesDir;
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    /**
     * Child-first for {@code demo.project.*}, parent-first for everything else.
     *
     * <p>The project package is the loader's own; the parent is only asked for what the definition of a class
     * cannot provide (the JDK, Jackson, JUnit). There is deliberately no fallback to the parent for a missing
     * project class: a silently borrowed stale class from the runner's classpath would defeat the reload.</p>
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(PROJECT_PREFIX)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = findClass(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Defines one project class from its bytes, reading them from {@code classesDir} only when the seed does not
     * have them yet (a nested class the compiler just produced, for example).
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (!name.startsWith(PROJECT_PREFIX)) {
            // Not ours: defineClass() would only look for a class file on this loader's (empty) parent URL path.
            return super.findClass(name);
        }
        byte[] bytecode = bytes.get(name);
        if (bytecode == null) {
            bytecode = readClassFile(name);
            if (bytecode == null) {
                throw new ClassNotFoundException(name + " has no class file under " + classesDir);
            }
            bytes.putIfAbsent(name, bytecode);
        }
        definePackageOf(name);
        return defineClass(name, bytecode, 0, bytecode.length);
    }

    /**
     * Defines the class's package before its first class.
     *
     * <p>{@code defineClass} would create the package implicitly; doing it explicitly keeps the loader's view of
     * what it owns in one place and lets a package attribute be attached later without changing how classes are
     * defined. Another thread may win the race, which is not an error.</p>
     */
    private void definePackageOf(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot <= 0) {
            return;
        }
        String packageName = className.substring(0, lastDot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            definePackage(packageName, null, null, null, null, null, null, null);
        } catch (IllegalArgumentException alreadyDefined) {
            // The package exists now, which is all this helper promises.
        }
    }

    // ── Bytes ─────────────────────────────────────────────────────────────────

    /** Seeds one changed class, and the nested classes the compiler wrote next to it ({@code Foo$Bar.class}). */
    private void seedFromDisk(String className) {
        int lastDot = className.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/');
        String simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);
        Path directory = packagePath.isEmpty() ? classesDir : classesDir.resolve(packagePath);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(CLASS_SUFFIX)) {
                    continue;
                }
                String stem = fileName.substring(0, fileName.length() - CLASS_SUFFIX.length());
                if (!stem.equals(simpleName) && !stem.startsWith(simpleName + "$")) {
                    continue;
                }
                String name = packagePath.isEmpty() ? stem : packagePath.replace('/', '.') + '.' + stem;
                bytes.put(name, Files.readAllBytes(file));
            }
        } catch (IOException unreadable) {
            // findClass() retries the read and names the class and the directory if it is still unreadable.
        }
    }

    /** Reads one class file by name, or {@code null} when it does not exist. */
    private byte[] readClassFile(String className) {
        int lastDot = className.lastIndexOf('.');
        Path file = lastDot < 0
                ? classesDir
                : classesDir.resolve(className.substring(0, lastDot).replace('.', '/'));
        Path classFile = file.resolve(className.substring(lastDot + 1) + CLASS_SUFFIX);
        try {
            return Files.isRegularFile(classFile) ? Files.readAllBytes(classFile) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Drops a class and its nested classes from the seed. */
    private void removeWithNested(String className) {
        bytes.remove(className);
        String nestedPrefix = className + "$";
        bytes.keySet().removeIf(name -> name.startsWith(nestedPrefix));
    }

    /**
     * Every class file under {@code classesDir}, as fully qualified names, sorted.
     *
     * <p>The first generation's changed set: everything the initial full compile just produced is new to the
     * very first loader.</p>
     */
    public static Set<String> classNamesIn(Path classesDir) {
        Path root = classesDir.toAbsolutePath().normalize();
        Set<String> names = new TreeSet<>();
        if (!Files.isDirectory(root)) {
            return names;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .forEach(file -> {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        names.add(relative.substring(0, relative.length() - CLASS_SUFFIX.length())
                                .replace('/', '.'));
                    });
        } catch (IOException unreadable) {
            // An unreadable output tree shows up as a missing class at run time, named by findClass().
        }
        return names;
    }
}
