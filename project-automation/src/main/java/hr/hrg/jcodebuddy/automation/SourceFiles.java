package hr.hrg.jcodebuddy.automation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Which files an automation run covers.
 *
 * <h3>Deterministic order, and a real glob</h3>
 * <p>Two things the plan's sketch got wrong and this class fixes, both of which only show up on a real
 * tree:</p>
 * <ul>
 *   <li><strong>Order.</strong> {@code Files.walk} promises no order, and the sketch collected straight
 *       into a list. A batch report whose lines reorder between runs cannot be diffed, and this repository
 *       has already paid for an unstable iteration order once — a {@code HashMap} over markers made two
 *       runs of the generator emit byte-different output. Files are returned <strong>sorted by path</strong>
 *       so a report is reproducible.</li>
 *   <li><strong>Glob.</strong> The sketch matched with {@code path.toString().matches(globPattern)}, which
 *       treats the pattern as a regular expression against the <em>whole absolute path</em> — so
 *       {@code "*Controller.java"} matches nothing at all, and {@code ".*Controller.java"} would match
 *       everything. {@link PathMatcher} is the glob engine the JDK actually provides, and
 *       {@link #findJavaFilesMatching} uses it, so {@code "**&#47;*Controller.java"} and
 *       {@code "*Controller.java"} both mean what a reader expects.</li>
 * </ul>
 *
 * <p>Generated and derived trees are skipped: {@code target/} (build output) and {@code .jcodebuddy/}
 * (DEC-026 metadata and caches) are not source, and an automation run that rewrites them would be editing
 * files nobody reads. The same exclusion list the migration tooling uses.</p>
 */
public final class SourceFiles {

    /** Directory names that never hold hand-written source, at any depth. */
    private static final List<String> EXCLUDED_DIRECTORIES = List.of("target", ".jcodebuddy", ".git");

    private SourceFiles() {
    }

    /** Whether a path names a Java source file. */
    public static boolean isJavaFile(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().endsWith(".java");
    }

    /**
     * Every {@code .java} file under {@code sourceRoot}, sorted by path.
     *
     * @throws IllegalArgumentException when {@code sourceRoot} is null, or is not a directory — a caller
     *                                  that passes a file has made a mistake, and returning an empty list
     *                                  would hide it behind "nothing to do"
     */
    public static List<Path> findJavaFiles(Path sourceRoot) {
        Path root = requireDirectory(sourceRoot);
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(SourceFiles::isJavaFile)
                    .filter(path -> !isExcluded(root, path))
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
        found.sort(Comparator.comparing(Path::toString));
        return List.copyOf(found);
    }

    /**
     * The {@code .java} files under {@code sourceRoot} whose path matches {@code glob}.
     *
     * <p>The pattern is matched against the <strong>path relative to {@code sourceRoot}</strong> with
     * forward slashes, so the same pattern means the same thing on every platform and does not depend on
     * where the tree happens to live: {@code "**&#47;*Controller.java"} is every controller in the tree,
     * {@code "*Controller.java"} is only those at the root of it.</p>
     *
     * <p><strong>One deviation from the JDK's glob, on purpose.</strong> In Java's glob a {@code **}
     * segment must stand for at least one directory, so {@code "**&#47;*Controller.java"} does
     * <em>not</em> match a controller sitting at the root of the tree — measured, not assumed. A caller who
     * writes that pattern means "a controller anywhere", and silently missing the root-level ones is
     * exactly the class of quiet omission this class exists to remove, so a leading {@code **&#47;} is also
     * tried as a root-level pattern. {@code "*Controller.java"} keeps its literal meaning: the root, and
     * nothing below it.</p>
     *
     * @param glob a glob such as {@code "**&#47;*Controller.java"} or {@code "a/b/*.java"}; {@code *} does
     *             not cross a directory boundary in a glob, which is why the recursive form is {@code **}
     * @throws IllegalArgumentException when the pattern is null, empty, or not a valid glob
     */
    public static List<Path> findJavaFilesMatching(Path sourceRoot, String glob) {
        Path root = requireDirectory(sourceRoot);
        if (glob == null || glob.isEmpty()) {
            throw new IllegalArgumentException("globPattern cannot be null or empty");
        }
        List<PathMatcher> matchers = new ArrayList<>(2);
        matchers.add(compile(glob));
        if (glob.startsWith("**/")) {
            matchers.add(compile(glob.substring("**/".length())));
        }
        List<Path> matched = new ArrayList<>();
        for (Path file : findJavaFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            Path relativePath = FileSystems.getDefault().getPath(relative);
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(relativePath)) {
                    matched.add(file);
                    break;
                }
            }
        }
        return List.copyOf(matched);
    }

    /** Compiles one glob, reporting an unparseable pattern as a caller error. */
    private static PathMatcher compile(String glob) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a valid glob: '" + glob + "'", e);
        }
    }

    /** Whether any path segment of {@code path} below {@code root} is an excluded directory. */
    private static boolean isExcluded(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Path requireDirectory(Path sourceRoot) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalArgumentException("sourceRoot is not a directory: " + sourceRoot);
        }
        return sourceRoot.toAbsolutePath().normalize();
    }
}
