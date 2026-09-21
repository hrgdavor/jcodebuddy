// {@link com.codebuddy.merge.ThreeWayFixture} A three-way merge fixture stored as real source files.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One merge case, held as real files on disk rather than as strings in a test.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 * src/test/resources/fixtures/&lt;case&gt;/
 *   base/&lt;File&gt;.java.txt     the merge base - the whole file, not a hunk
 *   ours/&lt;File&gt;.java.txt     our branch
 *   theirs/&lt;File&gt;.java.txt   their branch
 *   ours.diff                what ours changed, for a human to read
 *   theirs.diff              what theirs changed, for a human to read
 * </pre>
 *
 * <h2>Why files and not strings</h2>
 *
 * <p>Two reasons, both of which cost real defects:
 *
 * <ol>
 *   <li><b>The parser needs a real file.</b> Type attribution depends on a source
 *       path and a classpath. Inline fragments forced a synthetic source root and
 *       exercised a code path no caller would use.</li>
 *   <li><b>A fragment hides what the change was.</b> A conflict is a
 *       <em>change</em>, and a change is only visible against the base. Fixtures
 *       written as bare hunks could not express "ours added an import while theirs
 *       removed a different one" - the case where a text diff corrupts the import
 *       block and the resolver has to recognise it.</li>
 * </ol>
 *
 * <p>These are complete, compilable Java files. The {@code .java.txt} suffix keeps
 * them out of the compilation while leaving them as ordinary readable source.
 *
 * <h2>The diffs are documentation, not input</h2>
 *
 * <p>{@code ours.diff} and {@code theirs.diff} state the intended change so a
 * reviewer can see at a glance what each side did. They are <b>never parsed</b>:
 * the authoritative change is computed structurally by comparing the branch file
 * against the base, because a resolver that parsed diffs would inherit exactly the
 * fragility this module exists to remove. A test asserts the two agree.
 *
 * @param name       the fixture directory name
 * @param filePath   the repository-relative path the source should report
 * @param base       the merge base source
 * @param ours       our branch source
 * @param theirs     their branch source
 * @param oursDiff   the human-readable diff for ours, read from disk
 * @param theirsDiff the human-readable diff for theirs, read from disk
 */
record ThreeWayFixture(String name, String filePath, String base, String ours, String theirs,
                       String oursDiff, String theirsDiff) {

    /** Where fixtures live on the test classpath. */
    static final String ROOT = "fixtures";

    /**
     * Load a fixture by directory name, assuming the standard file name.
     */
    static ThreeWayFixture load(String name) {
        return load(name, "src/main/java/com/example/payments/PaymentProcessor.java");
    }

    /**
     * Load a fixture by directory name, reporting a specific repository path.
     *
     * @param name     fixture directory
     * @param filePath the path the loaded sources should present themselves as
     */
    static ThreeWayFixture load(String name, String filePath) {
        Path root = resolveRoot(name);
        String simpleName = Path.of(filePath).getFileName().toString();

        return new ThreeWayFixture(
            name,
            filePath,
            read(root.resolve("base").resolve(simpleName + ".txt"), name),
            read(root.resolve("ours").resolve(simpleName + ".txt"), name),
            read(root.resolve("theirs").resolve(simpleName + ".txt"), name),
            readOptional(root.resolve("ours.diff")),
            readOptional(root.resolve("theirs.diff")));
    }

    /**
     * The fixture's directory on disk.
     */
    Path directory() {
        return resolveRoot(name);
    }

    /**
     * A conflict built from this fixture, anchored to its file path and carrying a
     * type context so resolvers that need one can resolve types against real files.
     */
    Conflict conflict(ConflictType type) {
        return new Conflict(type, filePath, name + " fixture", base, ours, theirs,
            Region.unknown(), TestTypeContexts.jdk());
    }

    /**
     * What ours changed about the imports, relative to base.
     */
    Optional<ImportChange> oursImportChange() {
        return importChange(ours);
    }

    /**
     * What theirs changed about the imports, relative to base.
     */
    Optional<ImportChange> theirsImportChange() {
        return importChange(theirs);
    }

    private Optional<ImportChange> importChange(String branch) {
        Optional<java.util.Set<ImportChange.ImportRef>> baseImports =
            ImportChange.readImports(base, filePath, TestTypeContexts.jdk());
        Optional<java.util.Set<ImportChange.ImportRef>> branchImports =
            ImportChange.readImports(branch, filePath, TestTypeContexts.jdk());
        if (baseImports.isEmpty() || branchImports.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ImportChange.between(baseImports.get(), branchImports.get()));
    }

    /**
     * The import names a diff adds, read from the diff text.
     *
     * <p>Only used to check the computed change against the documented intent.
     */
    static java.util.Set<String> addedImportsInDiff(String diff) {
        java.util.Set<String> added = new java.util.LinkedHashSet<>();
        for (String line : diff.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("+") && !trimmed.startsWith("+++")
                && trimmed.substring(1).strip().startsWith("import ")) {
                added.add(trimmed.substring(1).strip());
            }
        }
        return added;
    }

    /**
     * The import names a diff removes, read from the diff text.
     */
    static java.util.Set<String> removedImportsInDiff(String diff) {
        java.util.Set<String> removed = new java.util.LinkedHashSet<>();
        for (String line : diff.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("-") && !trimmed.startsWith("---")
                && trimmed.substring(1).strip().startsWith("import ")) {
                removed.add(trimmed.substring(1).strip());
            }
        }
        return removed;
    }

    private static Path resolveRoot(String name) {
        Path fromClasspath = classpathRoot(name);
        if (fromClasspath != null) {
            return fromClasspath;
        }
        // Fall back to the source tree, so a fixture problem is reported as a
        // missing directory rather than as an unexplained null.
        Path fromSource = Path.of("src", "test", "resources", ROOT, name);
        if (Files.isDirectory(fromSource)) {
            return fromSource;
        }
        throw new IllegalStateException("no fixture directory for '" + name + "' (looked in "
            + fromClasspath + " and " + fromSource.toAbsolutePath() + ")");
    }

    private static Path classpathRoot(String name) {
        var url = ThreeWayFixture.class.getClassLoader().getResource(ROOT + "/" + name);
        if (url == null || !"file".equals(url.getProtocol())) {
            return null;
        }
        try {
            return Path.of(url.toURI());
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(Path path, String fixtureName) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                "fixture '" + fixtureName + "' is incomplete: missing " + path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    private static String readOptional(Path path) {
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
