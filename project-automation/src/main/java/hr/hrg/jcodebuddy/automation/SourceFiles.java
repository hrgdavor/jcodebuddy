package hr.hrg.jcodebuddy.automation;

import hr.hrg.gitignore.GitIgnoreFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Which files an automation run covers.
 *
 * <h3>Deterministic order, and wildmatch</h3>
 * <p>Two things the plan's sketch got wrong and this class fixes, both of which only show up on a real
 * tree:</p>
 * <ul>
 *   <li><strong>Order.</strong> {@code Files.walk} promises no order, and the sketch collected straight
 *       into a list. A batch report whose lines reorder between runs cannot be diffed, and this repository
 *       has already paid for an unstable iteration order once — a {@code HashMap} over markers made two
 *       runs of the generator emit byte-different output. Files are returned <strong>sorted by path</strong>
 *       so a report is reproducible.</li>
 *   <li><strong>Wildmatch.</strong> The sketch matched with {@code path.toString().matches(globPattern)},
 *       which treats the pattern as a regular expression against the <em>whole absolute path</em> — so
 *       {@code "*Controller.java"} matched nothing, and {@code ".*Controller.java"} would match
 *       everything. Matching is now git's {@code wildmatch}, read by the published
 *       {@code hr.hrg:gitignore} matcher (DEC-051): {@code "**&#47;*Controller.java"} is a controller at
 *       any depth, and a leading {@code /} anchors a pattern to the source root.</li>
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
     * The {@code .java} files under {@code sourceRoot} whose path matches {@code pattern}.
     *
     * <p>The pattern is matched against the <strong>path relative to {@code sourceRoot}</strong> with
     * forward slashes, so the same pattern means the same thing on every platform and does not depend on
     * where the tree happens to live. Matching is git's {@code wildmatch}, as implemented by
     * {@code hr.hrg:gitignore} (DEC-051): {@code "**&#47;*Controller.java"} is a controller at any depth,
     * a pattern with no separator ({@code "*Controller.java"}) is that basename at any depth, and a
     * leading {@code /} ({@code "&#47;*Controller.java"}) anchors it to the root. There is no second
     * dialect — braces and extglob are not expanded, and {@code *} never crosses a {@code /}.</p>
     *
     * @param pattern a wildmatch pattern such as {@code "**&#47;*Controller.java"} or {@code "a/b/*.java"}
     * @throws IllegalArgumentException when the pattern is null or empty
     */
    public static List<Path> findJavaFilesMatching(Path sourceRoot, String pattern) {
        Path root = requireDirectory(sourceRoot);
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern cannot be null or empty");
        }
        GitIgnoreFile matcher = GitIgnoreFile.parse("", pattern);
        List<Path> matched = new ArrayList<>();
        for (Path file : findJavaFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (matcher.decide(relative, false).orElse(false)) {
                matched.add(file);
            }
        }
        return List.copyOf(matched);
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
