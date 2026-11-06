package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * E10 of {@code plans/hipster-entity-mvp.md}: parse reuse, measured.
 *
 * <p>The gate this class asserts has three parts, and each is a number rather than a description: a
 * <strong>warm</strong> pass over an unchanged tree parses <em>zero</em> files and falls back on none; a
 * <strong>one-file edit</strong> parses <em>one</em> and falls back on none; and the tree the warm pass
 * produces is <strong>the same answer</strong> as the tree a cold pass over the <em>same input</em>
 * produces — every generated source, every metadata document and every recorded source fact byte-equal.
 * The counters come from {@link EntityMetadataGenerator.GenerationReport#parseReuse()}, which is the
 * pass's own account of what the base cache answered.</p>
 *
 * <p>The fallback counter is part of the gate because a fallback is a file whose entry <em>was</em>
 * current but which was parsed anyway: the entry could not supply a fact the reconstruction needed. Before
 * the type-parameter decode that fact was {@code Auditable<ID>}'s declared {@code <ID>} — a fact with no
 * field in {@code FileMetadata}, and DEC-041 D6 forbids adding one. It is now recovered from the
 * declaration ranges the entry already records, which is why a warm pass has neither a miss nor a
 * fallback left to explain.</p>
 *
 * <p>Byte-identity is the part that keeps the measurement honest: a cache that skips work and changes the
 * answer is the failure DEC-041's acceptance criteria exist to catch. The cold reference is a
 * <strong>second independent fixture</strong> made from the same pre-edit input — same sources, same
 * generated output, same class index — edited the same way, whose cache is then deleted. The earlier
 * version of this test deleted the cache of the <em>warm</em> tree after the warm pass had regenerated
 * it: the appended field already had its ledger ordinal there, so the cold pass read a different input
 * and the metadata legitimately differed. That is not a cache effect, it is a different tree, and
 * comparing it measured the wrong thing.</p>
 *
 * <p>Two passes at different wall-clock instants cannot share the instant a row's <em>newly changed</em>
 * content was first hashed, so {@code classes.json}'s {@code hashCalculatedAt} and {@code mtimes.json}'s
 * value are compared with that one reading removed — see
 * {@link #assertSameTreeApartFromTheClock}. Nothing else is excused: every generated source, every
 * metadata document (the appended field's ledger ordinal included) and every other recorded fact is
 * compared byte for byte, so a ledger difference fails this test rather than being explained away.</p>
 */
@Tag("generator")
class ParseReuseTest {

    /** A view whose edit costs exactly one parse and touches exactly one generated enum. */
    private static final String EDITED_VIEW =
            "hr/hrg/hipster/entityexample/person/entity/PersonDetails.java";

    /** The same file as the class index names it: module-relative, which includes the source root. */
    private static final String EDITED_FILE = "src/main/java/" + EDITED_VIEW;

    /**
     * A fixed modification time for the edited file, so the two trees' {@code mtimes.json} sidecars agree
     * on that row: the value recorded there is the file's own last-modified time when its content changed.
     */
    private static final FileTime EDITED_AT = FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));

    /** The class index, whose rows carry the instant each checksum was first calculated. */
    private static final String CLASSES_INDEX = ".jcodebuddy/index/classes.json";

    /** The derived sidecar that records a file's last-modified time when its content changed. */
    private static final String MTIMES_INDEX = ".jcodebuddy/index/mtimes.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aWarmPassParsesNothingAndProducesTheSameTreeAsThePassBeforeIt() throws Exception {
        ExamplePassFixture fixture = ExamplePassFixture.create();
        Path sourceRoot = fixture.sourceRoot();
        Path reportDir = fixture.reportDir();
        int files = javaFileCount(sourceRoot);

        Map<String, byte[]> before = snapshot(fixture);

        EntityMetadataGenerator.GenerationReport warm = EntityMetadataGenerator.generateIncremental(
                sourceRoot, reportDir, sourceRoot, null, new DivergenceReporter());

        // The cache never misses, never falls back, and therefore never parses: every file's stored facts
        // describe its current content, and every fact the reconstruction needs is readable from them.
        Assertions.assertEquals(0, warm.parseReuse().misses(),
                "no file changed, so no entry was stale: " + warm.parseReuse());
        Assertions.assertEquals(0, warm.parseReuse().fallbacks(),
                "no current entry needed a fact it could not supply: " + warm.parseReuse());
        Assertions.assertEquals(0, warm.parseReuse().parses(),
                "a warm pass over an unchanged tree parses no file at all: " + warm.parseReuse());
        Assertions.assertEquals(files, warm.parseReuse().hits(),
                "every file's stored facts were reused: " + warm.parseReuse());
        Assertions.assertTrue(warm.parseReuse().statHits() > 0,
                "and the cheap stat tier really answered some of them: " + warm.parseReuse());

        Map<String, byte[]> after = snapshot(fixture);
        assertSameTree(warm.parseReuse() + ": a warm pass must be a fixed point of the output", before, after);
    }

    @Test
    void oneFileEditParsesOneFileAndMatchesAnIndependentColdPass() throws Exception {
        // Both trees start as the SAME pre-edit input: the committed example copied and passed once, with
        // the generated source, the class index and the base cache all in place. The second fixture is an
        // independent directory (its own module root, its own report tree), so neither pass can see what
        // the other writes — only the input is shared, byte for byte, which is the point.
        ExamplePassFixture warmFixture = ExamplePassFixture.create();
        ExamplePassFixture coldFixture = warmFixture.copyOfShared();
        // The index both passes start from, read before either can rewrite the cold fixture's copy.
        Map<String, JsonNode> beforeEditRows = rows(classesOf(coldFixture.indexFile()));

        String source = Files.readString(warmFixture.sourceRoot().resolve(EDITED_VIEW));
        Assertions.assertTrue(source.contains("String email();"), "the fixture view has the edited accessor");
        String edited = source.replace("String email();", "String email();\n    String nickname();");
        // The same edit in both trees, content and modification time alike: `mtimes.json` records the
        // file's last-modified time for the row whose content changed, so a differing value there would be
        // a test artifact rather than a fact about the pass.
        for (ExamplePassFixture fixture : List.of(warmFixture, coldFixture)) {
            Files.writeString(fixture.sourceRoot().resolve(EDITED_VIEW), edited);
            Files.setLastModifiedTime(fixture.sourceRoot().resolve(EDITED_VIEW), EDITED_AT);
        }

        EntityMetadataGenerator.GenerationReport warm = EntityMetadataGenerator.generateIncremental(
                warmFixture.sourceRoot(), warmFixture.reportDir(), warmFixture.sourceRoot(),
                Set.of(EDITED_VIEW), new DivergenceReporter());

        Assertions.assertEquals(1, warm.parseReuse().misses(),
                "exactly the edited file's entry is stale: " + warm.parseReuse());
        Assertions.assertEquals(1, warm.parseReuse().parses(),
                "and exactly that one file is parsed — the type-parameter decode removed the other parse: "
                        + warm.parseReuse());
        Assertions.assertEquals(0, warm.parseReuse().fallbacks(),
                "no current entry needed a fact it could not supply: " + warm.parseReuse());
        Assertions.assertEquals(javaFileCount(warmFixture.sourceRoot()) - 1, warm.parseReuse().hits(),
                "every other file's stored facts were reused: " + warm.parseReuse());

        // The edited view's enum carries the new accessor, so the pass really did regenerate the view.
        String ownEnum = Files.readString(warmFixture.sourceRoot().resolve(
                "hr/hrg/hipster/entityexample/person/entity/PersonDetails_.java"));
        Assertions.assertTrue(ownEnum.contains("nickname"), "the edited view was regenerated");

        Map<String, byte[]> warmTree = snapshot(warmFixture);

        // The reference pass: the same input with no cache at all. Deleting only the CACHE is what makes it
        // cold — the generated tree and the class index are still the pre-pass ones, so the pass that
        // appends `nickname` is the pass being compared, in both trees.
        deleteTree(coldFixture.reportDir().resolve("cache"));
        EntityMetadataGenerator.GenerationReport cold = EntityMetadataGenerator.generateIncremental(
                coldFixture.sourceRoot(), coldFixture.reportDir(), coldFixture.sourceRoot(), null,
                new DivergenceReporter());
        Assertions.assertEquals(javaFileCount(coldFixture.sourceRoot()), cold.parseReuse().parses(),
                "the cold reference pass parses every file: " + cold.parseReuse());

        Map<String, byte[]> coldTree = snapshot(coldFixture);
        assertSameTreeApartFromTheClock(warmTree, coldTree, beforeEditRows);
    }

    /**
     * Every file a pass is expected to be byte-identical in: generated source, the class index and its
     * sidecars, the metadata documents and the tracked converter manifest.
     *
     * <p>The base cache itself is deliberately <strong>not</strong> compared: it is derived, ignored output
     * whose whole contract is that deleting it changes no answer (DEC-041 acceptance 5) — which is exactly
     * what the cold reference pass relies on.</p>
     */
    private static Map<String, byte[]> snapshot(ExamplePassFixture fixture) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();
        Path moduleRoot = fixture.moduleRoot();
        collect(files, moduleRoot, moduleRoot, path -> path.toString().endsWith(".java"));
        collect(files, moduleRoot, moduleRoot.resolve(".jcodebuddy/index"), path -> true);
        collect(files, moduleRoot, fixture.reportDir(), path -> path.toString().endsWith(".metadata.json"));
        collect(files, moduleRoot, moduleRoot.resolve(".jcodebuddy/conf"), path -> path.toString().endsWith(".json"));
        return files;
    }

    private static void collect(Map<String, byte[]> into, Path base, Path root,
                                java.util.function.Predicate<Path> filter) throws Exception {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(filter).toList()) {
                into.put(base.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
    }

    /**
     * Asserts two produced trees are the same answer, with the class index's two <strong>wall-clock
     * readings</strong> as the one stated exception.
     *
     * <p>Everything the generator emits or records — every generated source, every metadata document, the
     * imports sidecar and the converter manifest — is compared byte for byte. {@code classes.json}'s
     * {@code hashCalculatedAt} and {@code mtimes.json}'s value cannot be equal across two independent
     * trees for a row whose content the pass hashed for the first time: they date <em>when this checkout
     * wrote the bytes</em>, not what the bytes say, and the two passes run at different wall-clock
     * instants. {@code MetadataFileIdsTest} records the same exception for two independent tables. It is
     * kept narrow rather than waved through: every other file is compared without normalisation, each row
     * is compared field by field with only that reading removed, and a row whose reading moved must be a
     * row whose checksum changed since the input both passes started from.</p>
     */
    private static void assertSameTreeApartFromTheClock(Map<String, byte[]> warm, Map<String, byte[]> cold,
                                                        Map<String, JsonNode> beforeEditRows) {
        Assertions.assertEquals(warm.keySet(), cold.keySet(), "the two trees produce the same file set");
        List<String> differing = differingFiles(warm, cold);
        for (String file : differing) {
            Assertions.assertTrue(CLASSES_INDEX.equals(file) || MTIMES_INDEX.equals(file),
                    "only a class-index wall-clock reading may differ between two passes over the same"
                            + " input; this file differs instead: " + file);
        }

        Map<String, JsonNode> warmRows = rows(classesOf(warm.get(CLASSES_INDEX)));
        Map<String, JsonNode> coldRows = rows(classesOf(cold.get(CLASSES_INDEX)));
        Assertions.assertEquals(beforeEditRows.keySet(), warmRows.keySet(),
                "the pass keeps one row per type the first pass indexed");
        Assertions.assertEquals(warmRows.keySet(), coldRows.keySet(), "both passes record the same rows");

        // The rows this pass hashed for the first time: exactly the edited view and the enum it regenerates.
        Set<String> rehashed = new TreeSet<>();
        Set<String> restamped = new TreeSet<>();
        Set<String> editedRows = new TreeSet<>();
        for (String fqn : warmRows.keySet()) {
            JsonNode before = beforeEditRows.get(fqn);
            JsonNode warmRow = warmRows.get(fqn);
            JsonNode coldRow = coldRows.get(fqn);
            if (EDITED_FILE.equals(warmRow.path("path").asText(null))) {
                editedRows.add(fqn);
            }
            if (!Objects.equals(before.path("checksum").asText(null), warmRow.path("checksum").asText(null))) {
                rehashed.add(fqn);
            }
            if (!Objects.equals(warmRow.path("hashCalculatedAt").asText(null),
                    coldRow.path("hashCalculatedAt").asText(null))) {
                restamped.add(fqn);
            }
            Assertions.assertEquals(withoutInstant(warmRow), withoutInstant(coldRow),
                    fqn + " must differ in nothing but the instant its checksum was first calculated");
        }
        Assertions.assertFalse(rehashed.isEmpty(), "the one-file edit changes at least the edited view's row");
        Assertions.assertTrue(rehashed.containsAll(restamped),
                "an instant never moves for a row whose content did not change: moved " + restamped
                        + " but only " + rehashed + " were re-hashed");

        Map<String, JsonNode> warmMtimes = rows(mtimesOf(warm.get(MTIMES_INDEX)));
        Map<String, JsonNode> coldMtimes = rows(mtimesOf(cold.get(MTIMES_INDEX)));
        Assertions.assertEquals(warmMtimes.keySet(), coldMtimes.keySet(), "both passes record the same mtimes");
        Set<String> movedMtimes = new TreeSet<>();
        for (String fqn : warmMtimes.keySet()) {
            if (!warmMtimes.get(fqn).equals(coldMtimes.get(fqn))) {
                movedMtimes.add(fqn);
            }
        }
        Assertions.assertTrue(rehashed.containsAll(movedMtimes),
                "an mtime never moves for a row whose content did not change: moved " + movedMtimes
                        + " but only " + rehashed + " were rewritten");
        Assertions.assertTrue(movedMtimes.containsAll(expectedMtimeMoves(rehashed, editedRows)),
                "every row this pass wrote must carry the writing pass's own modified time: "
                        + expectedMtimeMoves(rehashed, editedRows) + " vs " + movedMtimes);
    }

    /**
     * The rows whose modified time the two passes must have written for themselves: every row this pass
     * re-hashed except the ones the test pinned. The edited file is written by the test with a fixed
     * instant, so both trees record that instant; the enum the pass regenerates is written by each pass,
     * so each records its own — which is a wall-clock fact, not an answer.
     */
    private static Set<String> expectedMtimeMoves(Set<String> rehashed, Set<String> editedRows) {
        Set<String> expected = new TreeSet<>(rehashed);
        expected.removeAll(editedRows);
        return expected;
    }

    /** Asserts two produced trees are byte-identical, with the first difference reported as text. */
    private static void assertSameTree(String message, Map<String, byte[]> expected, Map<String, byte[]> actual) {
        Assertions.assertEquals(expected.keySet(), actual.keySet(),
                message + ": the two trees produce the same file set");
        List<String> differing = differingFiles(expected, actual);
        if (differing.isEmpty()) {
            return;
        }
        String first = differing.get(0);
        byte[] before = expected.get(first);
        byte[] after = actual.get(first);
        int at = 0;
        while (at < before.length && at < after.length && before[at] == after[at]) {
            at++;
        }
        int from = Math.max(0, at - 200);
        Assertions.fail(message + ": " + differing.size() + " produced file(s) differ " + differing
                + "\n  first difference in " + first + " at byte " + at + " of " + before.length
                + " (before) / " + after.length + " (after)\n  before …"
                + new String(before, from, Math.min(400, before.length - from), StandardCharsets.UTF_8)
                + "…\n  after  …"
                + new String(after, from, Math.min(400, after.length - from), StandardCharsets.UTF_8) + "…");
    }

    private static List<String> differingFiles(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        List<String> differing = new ArrayList<>();
        for (String file : expected.keySet()) {
            if (!java.util.Arrays.equals(expected.get(file), actual.get(file))) {
                differing.add(file);
            }
        }
        return differing;
    }

    /** A row's facts with the wall-clock reading removed, so two rows can be compared as answers. */
    private static String withoutInstant(JsonNode row) {
        return row.toString().replaceAll("\"hashCalculatedAt\"\\s*:\\s*\"[^\"]*\"", "");
    }

    private static JsonNode classesOf(byte[] indexFile) {
        return MAPPER.readTree(new String(indexFile, StandardCharsets.UTF_8)).path("classes");
    }

    private static JsonNode classesOf(Path indexFile) throws Exception {
        return classesOf(Files.readAllBytes(indexFile));
    }

    private static JsonNode mtimesOf(byte[] indexFile) {
        return MAPPER.readTree(new String(indexFile, StandardCharsets.UTF_8)).path("mtimes");
    }

    /** A JSON object's properties, in written order. */
    private static Map<String, JsonNode> rows(JsonNode container) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (var it = container.propertyStream().iterator(); it.hasNext(); ) {
            var entry = it.next();
            rows.put(entry.getKey(), entry.getValue());
        }
        return rows;
    }

    private static int javaFileCount(Path sourceRoot) throws Exception {
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return (int) walk.filter(path -> path.toString().endsWith(".java")).count();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
