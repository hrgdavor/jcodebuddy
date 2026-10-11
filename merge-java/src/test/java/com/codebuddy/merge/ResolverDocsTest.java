// {@link com.codebuddy.merge.ResolverDocsTest} The docs/resolvers checks that need the resolver registry.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code docs/resolvers} checks that need the Java resolver registry: every registered resolver has a
 * documented folder (and every folder belongs to a registered resolver), the index links each folder and states
 * that resolver's declared analysis level on the same row, and a page names its resolver and its conflict type.
 *
 * <h3>Where the rest of the documentation check lives (2026-10-11)</h3>
 *
 * <p>Three things about these pages are checked elsewhere, and none of them here — this class parses no marker,
 * resolves no region and reads no fence:</p>
 *
 * <ul>
 *   <li><b>Does anything drift?</b> {@code npm run check:examples} — the {@code @hrg/inject-examples} package
 *       itself, the reference implementation of section searching. This class <em>used to mirror</em> that
 *       comparison in Java (its own marker parse, its own region extraction, its own fence handling and a byte
 *       comparison of every rendered block); the mirror was deleted, because a second implementation of the
 *       reference drifts and the reference wins.</li>
 *   <li><b>Does a page follow the policy?</b> {@code bun scripts/check-resolver-docs.js} — at least four
 *       examples, the canonical sample region of the resolver's conflict type, an example from the resolver's own
 *       test, every fenced block preceded by a marker, and every example from {@code src/test/}. That checker
 *       asks the reference what a marker is ({@code parseMarker}, {@code findMarkers}), so it cannot disagree
 *       with the injector about a line inside a fence, an empty {@code #} fragment or a scheme-like target —
 *       which is exactly where the Java regex this class used to carry was wrong.</li>
 *   <li><b>Do relative links resolve?</b> {@code node scripts/check-repo-links.mjs}, for the whole
 *       repository.</li>
 * </ul>
 *
 * <p>What is left here is what needs {@link ConflictResolvers#defaultResolvers()}: a policy check cannot know
 * that a folder belongs to a registered resolver or what conflict type that resolver declares.</p>
 */
class ResolverDocsTest {

    /** Surefire's working directory is the module basedir. */
    private static final Path MODULE_DIR = Path.of("").toAbsolutePath();
    private static final Path DOCS_DIR = MODULE_DIR.resolve("docs").resolve("resolvers");
    private static final Path INDEX = DOCS_DIR.resolve("README.md");

    private static void requireDocsDir() {
        assertTrue(Files.isDirectory(DOCS_DIR),
            "docs/resolvers not found under the working directory " + MODULE_DIR
                + "; this test must run with the merge-java module as basedir");
    }

    // ------------------------------------------------------- registry <-> folders

    @Test
    @DisplayName("every registered resolver has a documentation folder, and vice versa")
    void foldersMatchTheRegistry() {
        requireDocsDir();
        List<String> problems = new ArrayList<>();

        List<String> expected = new ArrayList<>();
        for (ConflictResolver resolver : ConflictResolvers.defaultResolvers()) {
            String folder = kebab(resolver.getClass().getSimpleName());
            expected.add(folder);
            if (!Files.isDirectory(DOCS_DIR.resolve(folder))) {
                problems.add("registered resolver " + resolver.getClass().getSimpleName()
                    + " has no docs/resolvers/" + folder + "/ folder");
            } else if (!Files.isRegularFile(DOCS_DIR.resolve(folder).resolve("README.md"))) {
                problems.add("docs/resolvers/" + folder + "/ has no README.md");
            }
        }

        try (var children = Files.list(DOCS_DIR)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String name = child.getFileName().toString();
                if (!expected.contains(name)) {
                    problems.add("docs/resolvers/" + name
                        + "/ does not correspond to any resolver registered in "
                        + "ConflictResolvers.defaultResolvers() (expected folder name: "
                        + "the resolver's simple class name in kebab-case)");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + DOCS_DIR, e);
        }

        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    @Test
    @DisplayName("the index links to every resolver folder")
    void indexLinksEveryResolver() {
        requireDocsDir();
        String index = read(INDEX);
        List<String> problems = new ArrayList<>();
        for (ConflictResolver resolver : ConflictResolvers.defaultResolvers()) {
            String folder = kebab(resolver.getClass().getSimpleName());
            if (!index.contains("(" + folder + "/README.md)")) {
                problems.add("docs/resolvers/README.md does not link to " + folder + "/README.md");
            }
            // The declared evidence level is a claim a reviewer reads, and it decides what happens
            // when a resolution from this resolver disagrees with another about one block. Keeping it
            // only in the class would hide the claim; the index has to carry it beside the resolver.
            boolean levelListed = index.lines().anyMatch(line ->
                line.contains("(" + folder + "/README.md)")
                    && line.contains(resolver.maxAnalysisLevel().name()));
            if (!levelListed) {
                problems.add("docs/resolvers/README.md does not state that "
                    + resolver.getClass().getSimpleName() + " reaches at most "
                    + resolver.maxAnalysisLevel() + " on the row that links to it");
            }
        }
        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    // ------------------------------------------------------- each page names its resolver

    @Test
    @DisplayName("each resolver README names its resolver and its conflict type")
    void readmesNameTheirResolver() {
        requireDocsDir();
        List<String> problems = new ArrayList<>();

        for (ConflictResolver resolver : ConflictResolvers.defaultResolvers()) {
            String simpleName = resolver.getClass().getSimpleName();
            String folder = kebab(simpleName);
            Path readme = DOCS_DIR.resolve(folder).resolve("README.md");
            if (!Files.isRegularFile(readme)) {
                continue; // reported by foldersMatchTheRegistry
            }
            String text = read(readme);
            ConflictType type = resolver.supportedType();

            if (!text.contains(simpleName)) {
                problems.add(folder + "/README.md never names " + simpleName);
            }
            if (type != null && !text.contains(type.name())) {
                problems.add(folder + "/README.md never names the conflict type " + type);
            }
        }

        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    // ------------------------------------------------------------------ helpers

    /** A class simple name in kebab-case: ImportConflictResolver to import-conflict-resolver. */
    static String kebab(String simpleName) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('-');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
