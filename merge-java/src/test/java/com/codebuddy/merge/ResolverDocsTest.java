// {@link com.codebuddy.merge.ResolverDocsTest} Keeps docs/resolvers in sync with the test fixtures it includes.
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documentation half of the resolver contract: every example in
 * {@code docs/resolvers/} is included from test material, and nothing included
 * may drift.
 *
 * <p>The READMEs under {@code docs/resolvers/} carry injection markers — lines
 * that are nothing but a markdown link labelled with its own target path,
 * optionally with a {@code #region:name} fragment — that
 * {@code scripts/inject-examples.mjs} (Node, through the repository-root
 * {@code test-fixtures.js}) materializes into the fenced block below each
 * marker. Marker paths resolve relative to the document first and to the
 * repository root second, so every marker doubles as a working link to its
 * source. This test re-checks the same relationship inside the Java build, so
 * a stale example fails {@code mvn test} even where Node is not run:
 *
 * <ul>
 *   <li>every registered resolver has a documentation folder, and every folder
 *       belongs to a registered resolver — a new resolver without docs fails
 *       here, exactly like a new type without a resolver fails
 *       {@code ConflictResolversTest};</li>
 *   <li>every marker resolves to a real file under {@code src/test/} — the
 *       examples come from test fixtures and tests, never from prose or from
 *       main sources;</li>
 *   <li>marker lines are unique per document (the injection script rejects
 *       duplicates) and every region name a marker references starts exactly
 *       once in its file — the script takes the first match, so an ambiguous
 *       name would silently shadow a second region;</li>
 *   <li>every rendered block matches its source byte for byte (same semantics
 *       as {@code resolveMarker}: whole file minus one trailing newline, or
 *       the lines strictly between the region markers);</li>
 *   <li>in the per-resolver READMEs every fenced code block is an injection
 *       block — a hand-written example is the copy that goes stale, so it is
 *       not accepted;</li>
 *   <li>every relative markdown link resolves.</li>
 * </ul>
 *
 * <p>After editing a fixture or a marked test, re-run
 * {@code node scripts/inject-examples.mjs merge-java/docs/resolvers} from the
 * repository root; the failure messages here name the offending block.
 */
class ResolverDocsTest {

    /** Surefire's working directory is the module basedir. */
    private static final Path MODULE_DIR = Path.of("").toAbsolutePath();
    private static final Path REPO_ROOT = MODULE_DIR.getParent();
    private static final Path DOCS_DIR = MODULE_DIR.resolve("docs").resolve("resolvers");
    private static final Path INDEX = DOCS_DIR.resolve("README.md");
    private static final Path TEST_ROOT = MODULE_DIR.resolve("src").resolve("test");

    /** Same whole-line shape {@code findMarkers} in test-fixtures.js accepts. */
    private static final Pattern MARKER_LINE = Pattern.compile("^\\[([^\\]]+)]\\(([^)\\s]+)\\)$");
    /** Same region markers {@code resolveMarker} understands. */
    private static final Pattern REGION_END =
        Pattern.compile("^\\s*(?://|/\\*+|<!--|#)\\s*#?endregion\\b");
    /** A markdown link target. */
    private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s]+)\\)");
    /** The fence token both the injection script and this test scan for. */
    private static final String FENCE = "```";

    /** One parsed injection marker. */
    private record Marker(String raw, String path, String region) {}

    private static List<Path> allDocs() {
        requireDocsDir();
        List<Path> docs = new ArrayList<>();
        docs.add(INDEX);
        try (var children = Files.list(DOCS_DIR)) {
            children.filter(Files::isDirectory)
                .map(dir -> dir.resolve("README.md"))
                .sorted()
                .forEach(docs::add);
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + DOCS_DIR, e);
        }
        return docs;
    }

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
        }
        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    // ------------------------------------------------------------- include sync

    @Test
    @DisplayName("every marker resolves to test material and matches its rendered block")
    void markersResolveAndAreInSync() {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Path doc : allDocs()) {
            List<String> lines = readLines(doc);
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                Marker marker = parseMarker(lines.get(i));
                if (marker == null) {
                    continue;
                }
                checked++;
                String where = doc.getFileName() + ":" + (i + 1);
                if (!seen.add(marker.raw())) {
                    problems.add(where + ": duplicate marker line - the injection script "
                        + "rejects duplicates, and only the first would ever be served: "
                        + marker.raw());
                    continue;
                }

                Path source = resolveSource(doc.getParent(), marker.path());
                if (source == null) {
                    problems.add(where + ": included file does not exist (looked relative to "
                        + "the document and the repository root): " + marker.path());
                    continue;
                }
                Path testRoot = TEST_ROOT.toAbsolutePath().normalize();
                if (!source.normalize().startsWith(testRoot)) {
                    problems.add(where + ": examples must come from test fixtures or tests "
                        + "under src/test/, not from " + source);
                    continue;
                }

                String snippet = snippetOf(source, marker.region(), where, problems);
                if (snippet == null) {
                    continue; // problem already recorded
                }

                String block = blockAfter(lines, i, where, problems);
                if (block == null) {
                    continue; // problem already recorded
                }
                if (!block.equals(snippet)) {
                    problems.add(where + ": rendered block is out of sync with " + marker.path()
                        + (marker.region() == null ? "" : "#" + marker.region())
                        + " - re-run: node scripts/inject-examples.mjs merge-java/docs/resolvers");
                }
            }
        }

        assertTrue(checked > 0, "no injection markers found - the docs lost their examples");
        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    /**
     * Parses one line as an injection marker: the trimmed line is exactly
     * {@code [label](target)}, the label is the target's path, and the target
     * carries no fragment or a {@code #region:name} fragment — the same rules
     * {@code findMarkers} in test-fixtures.js applies. Returns null for every
     * other line, including ordinary prose links.
     */
    private static Marker parseMarker(String line) {
        Matcher m = MARKER_LINE.matcher(line.trim());
        if (!m.matches()) {
            return null;
        }
        String label = m.group(1);
        String target = m.group(2);
        int hash = target.indexOf('#');
        String path = stripDotSlash(hash < 0 ? target : target.substring(0, hash));
        if (path.isEmpty() || !stripDotSlash(label).equals(path)) {
            return null;
        }
        String region = null;
        if (hash >= 0) {
            String fragment = target.substring(hash + 1);
            if (!fragment.startsWith("region:") || fragment.length() == "region:".length()) {
                return null;
            }
            region = fragment.substring("region:".length());
        }
        return new Marker(line.trim(), path, region);
    }

    private static String stripDotSlash(String path) {
        return path.startsWith("./") ? path.substring(2) : path;
    }

    /**
     * The current content of a file or region, mirroring {@code resolveMarker}
     * in test-fixtures.js: a whole file minus one trailing newline, or the
     * lines strictly between the region markers. Returns null when a problem
     * was recorded instead.
     */
    private static String snippetOf(Path source, String region, String where,
                                    List<String> problems) {
        String text = read(source);
        if (region == null) {
            if (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        }

        List<String> lines = List.of(text.split("\n", -1));
        Pattern start = Pattern.compile(
            "^\\s*(?://|/\\*+|<!--|#)\\s*#?region\\s+" + Pattern.quote(region) + "\\b");
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (start.matcher(lines.get(i)).find()) {
                starts.add(i);
            }
        }
        if (starts.isEmpty()) {
            problems.add(where + ": region '" + region + "' not found in " + source);
            return null;
        }
        if (starts.size() > 1) {
            problems.add(where + ": region '" + region + "' starts " + starts.size()
                + " times in " + source + "; a region name must be unique per file - the "
                + "injection script would silently serve the first match only");
            return null;
        }

        int end = -1;
        for (int i = starts.get(0) + 1; i < lines.size(); i++) {
            if (REGION_END.matcher(lines.get(i)).find()) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            problems.add(where + ": region '" + region + "' has no endregion in " + source);
            return null;
        }
        String snippet = String.join("\n", lines.subList(starts.get(0) + 1, end));
        if (snippet.isBlank()) {
            problems.add(where + ": region '" + region + "' in " + source + " is empty");
            return null;
        }
        return snippet;
    }

    /**
     * The content of the fenced block that follows a marker (blank lines
     * between are allowed), mirroring the injection script's fence handling:
     * the opening fence is the first line starting with the fence token, the
     * closing fence is the next such line. Returns null when a problem was
     * recorded instead.
     */
    private static String blockAfter(List<String> lines, int markerIdx, String where,
                                     List<String> problems) {
        int j = markerIdx + 1;
        while (j < lines.size() && lines.get(j).isBlank()) {
            j++;
        }
        if (j >= lines.size()) {
            problems.add(where + ": document ends before the fenced block of this marker");
            return null;
        }
        if (!lines.get(j).startsWith(FENCE)) {
            problems.add(where + ": expected a fenced code block right after this marker");
            return null;
        }
        for (int k = j + 1; k < lines.size(); k++) {
            if (lines.get(k).startsWith(FENCE)) {
                return String.join("\n", lines.subList(j + 1, k));
            }
        }
        problems.add(where + ": unclosed code block after this marker");
        return null;
    }

    /** Same resolution order as the injection script: document dir, then repository root. */
    private static Path resolveSource(Path mdDir, String ref) {
        Path fromDoc = mdDir.resolve(ref).toAbsolutePath().normalize();
        if (Files.isRegularFile(fromDoc)) {
            return fromDoc;
        }
        Path fromRoot = REPO_ROOT.resolve(ref).toAbsolutePath().normalize();
        if (Files.isRegularFile(fromRoot)) {
            return fromRoot;
        }
        return null;
    }

    // ------------------------------------------------------- per-resolver rules

    @Test
    @DisplayName("each resolver README documents its resolver with fixture-backed examples")
    void resolverReadmeCoversItsResolver() {
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

            List<Marker> markers = markersIn(text);
            if (markers.size() < 4) {
                problems.add(folder + "/README.md includes only " + markers.size()
                    + " example block(s); a resolver README must show at least four");
            }
            String sampleRegion = type == null ? ""
                : type.name().toLowerCase().replace('_', '-') + "-sample";
            if (markers.stream().noneMatch(marker ->
                    marker.path().endsWith("ConflictFixtures.java")
                        && sampleRegion.equals(marker.region()))) {
                problems.add(folder + "/README.md does not include the canonical sample region "
                    + "ConflictFixtures.java#region:" + sampleRegion);
            }
            String ownTest = simpleName + "Test.java";
            if (markers.stream().noneMatch(marker -> marker.path().contains(ownTest))) {
                problems.add(folder + "/README.md includes no example from " + ownTest);
            }
        }

        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    @Test
    @DisplayName("every fenced block in a resolver README is an injection block")
    void onlyInjectionBlocksAreFenced() {
        requireDocsDir();
        List<String> problems = new ArrayList<>();

        try (var children = Files.list(DOCS_DIR)) {
            for (Path dir : children.filter(Files::isDirectory).sorted().toList()) {
                Path readme = dir.resolve("README.md");
                if (!Files.isRegularFile(readme)) {
                    continue;
                }
                List<String> lines = readLines(readme);
                boolean inBlock = false;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.startsWith(FENCE)) {
                        continue;
                    }
                    if (inBlock) {
                        inBlock = false; // closing fence
                        continue;
                    }
                    // Opening a block: the closest non-blank line above must be
                    // the injection marker this block materializes.
                    int j = i - 1;
                    while (j >= 0 && lines.get(j).isBlank()) {
                        j--;
                    }
                    if (j < 0 || parseMarker(lines.get(j)) == null) {
                        problems.add(dir.getFileName() + "/README.md:" + (i + 1)
                            + ": fenced block is not preceded by an injection marker -"
                            + " examples must be included from test fixtures, never written"
                            + " by hand");
                    }
                    inBlock = true;
                }
                if (inBlock) {
                    problems.add(dir.getFileName() + "/README.md: unterminated code fence");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + DOCS_DIR, e);
        }

        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    // -------------------------------------------------------------------- links

    @Test
    @DisplayName("every relative link in the resolver docs resolves")
    void relativeLinksResolve() {
        List<String> problems = new ArrayList<>();

        for (Path doc : allDocs()) {
            if (!Files.isRegularFile(doc)) {
                problems.add("missing document: " + doc);
                continue;
            }
            Matcher link = MD_LINK.matcher(read(doc));
            while (link.find()) {
                String target = link.group(1);
                if (target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("mailto:")) {
                    continue;
                }
                int anchor = target.indexOf('#');
                String pathPart = anchor >= 0 ? target.substring(0, anchor) : target;
                if (pathPart.isEmpty()) {
                    continue; // pure in-page anchor
                }
                Path resolved = doc.getParent().resolve(pathPart).toAbsolutePath().normalize();
                if (!Files.exists(resolved)) {
                    problems.add(doc.getFileName() + ": dead link " + target
                        + " (resolved to " + resolved + ")");
                }
            }
        }

        assertTrue(problems.isEmpty(), String.join(System.lineSeparator(), problems));
    }

    // ------------------------------------------------------------------ helpers

    private static List<Marker> markersIn(String text) {
        List<Marker> markers = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Marker marker = parseMarker(line);
            if (marker != null) {
                markers.add(marker);
            }
        }
        return markers;
    }

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

    private static List<String> readLines(Path path) {
        return List.of(read(path).split("\n", -1));
    }
}
