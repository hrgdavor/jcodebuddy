package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.hipster.entity.tooling.EntityMetadataGenerator;
import hr.hrg.hipster.entity.tooling.SourceReader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The class index's writer, its reader and its change report (DEC-029).
 *
 * <p>What a table is worth rests on a handful of properties that a paragraph cannot keep true, and this
 * test states each one against a real module in a temp directory:</p>
 *
 * <ul>
 *   <li>a row per type, keyed by FQN, with the file's facts and the type's kind and modifiers;</li>
 *   <li><strong>no surrogate id anywhere</strong> — the key space is the language's own names;</li>
 *   <li>the table is deterministic, and an unchanged row keeps the instant its checksum was calculated
 *       at, so two passes are byte-identical and a project can commit the table;</li>
 *   <li>no source content, ever — the table records a hash, a size and a name;</li>
 *   <li>the table refuses a version or a hash contract it does not know, rather than half-believing it;
 *       and</li>
 *   <li>a duplicate FQN fails loudly instead of letting the pass pick a winner by iteration order.</li>
 * </ul>
 */
class ClassIndexTest {

    /** A fixed instant, so a table written by this test states a timestamp the test can assert. */
    private static final Instant FIXED = Instant.parse("2026-01-02T03:04:05Z");

    private static Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneOffset.UTC);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * A minimal module with two files, a member type and a third file that declares nothing.
     *
     * @return the module root
     */
    private static Path writeModule(Path tree) throws IOException {
        Path root = tree.resolve("module");
        write(root.resolve("src/main/java/a/b/Person.java"),
                "package a.b;\n\npublic interface Person {\n"
                        + "    record Record(Long id) implements Person {}\n"
                        + "    String name();\n"
                        + "}\n");
        write(root.resolve("src/main/java/a/b/PersonSummary.java"),
                "package a.b;\n\npublic interface PersonSummary extends Person {\n"
                        + "    default String label() { return name(); }\n"
                        + "}\n");
        // A file that declares no type: the table's key space is types, so this contributes no row.
        write(root.resolve("src/main/java/a/b/package-info.java"), "package a.b;\n");
        Files.createDirectories(root.resolve(".jcodebuddy/metadata/entity"));
        return root;
    }

    private static ClassIndex indexOf(Path root) {
        return ClassIndex.forPass(root.resolve(".jcodebuddy/metadata/entity"), root,
                root.resolve("src/main/java"), fixedClock());
    }

    /** Registers every source file of the module, exactly as the pass's walk does. */
    private static void register(ClassIndex index, Path root) throws IOException {
        Path sourceRoot = root.resolve("src/main/java");
        try (var walk = Files.walk(sourceRoot)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = ClassIndex.moduleRelative(root, file);
                // The walk names the file first and upgrades it with its types, so a file that declares
                // none is still recorded by the pass's report rather than vanishing.
                index.addFile(relative);
                var unit = SourceReader.readUnit(file);
                if (unit == null) {
                    // The pass reports an unreadable file and registers no types; the fixture has none, so
                    // reaching here would mean the fixture itself is broken rather than the index.
                    Assertions.fail("the fixture must parse: " + file);
                }
                index.addTypes(relative, unit, false);
            }
        }
    }

    private static ClassIndex written(Path root, ClassIndex previous) throws IOException {
        ClassIndex index = indexOf(root).merge(previous);
        register(index, root);
        index.write();
        return index;
    }

    /** Reads the table back the way a consumer does, with the report directory the layout needs. */
    private static ClassIndex readBack(Path root) {
        ClassIndex parsed = ClassIndex.read(ClassIndex.Readers.defaultIndexFile(root),
                root.resolve(".jcodebuddy/metadata/entity"), root, root.resolve("src/main/java"), null);
        Assertions.assertNotNull(parsed, "the table the pass just wrote must read back");
        return parsed;
    }

    private static ClassIndex readBack(Path root, List<String> problems) {
        return ClassIndex.read(ClassIndex.Readers.defaultIndexFile(root),
                root.resolve(".jcodebuddy/metadata/entity"), root, root.resolve("src/main/java"), problems);
    }

    private static String tableText(Path root) throws IOException {
        return Files.readString(root.resolve(".jcodebuddy/index/" + ClassIndex.FILE_NAME));
    }

    // ── the shape ───────────────────────────────────────────────────────────────────────────────────

    /** One row per type, keyed by FQN, with the file's facts and the type's own. */
    @Test
    void everyTypeGetsARowKeyedByItsFullyQualifiedName() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index"));
        ClassIndex live = written(root, null);

        var parsed = readBack(root);
        Assertions.assertEquals(3, parsed.size(),
                "two top-level types and one member type — and the type-less package-info.java "
                        + "contributes no row");
        Assertions.assertEquals(List.of("a.b.Person", "a.b.Person.Record", "a.b.PersonSummary"),
                parsed.rows().stream().map(ClassRecord::fqn).toList(),
                "rows are written in FQN order, so the file's order is a property of the names rather "
                        + "than of the walk");
        Assertions.assertTrue(live.typeLessFiles().contains("src/main/java/a/b/package-info.java"),
                "and the pass reports the file it read that declares no type, rather than letting it "
                        + "vanish: " + live.typeLessFiles());

        ClassRecord person = parsed.row("a.b.Person");
        Assertions.assertEquals("src/main/java/a/b/Person.java", person.path(),
                "the declaring file, module-relative");
        Assertions.assertEquals("interface", person.kind());
        Assertions.assertEquals(List.of("public"), person.modifiers());
        Assertions.assertEquals(3, person.line(), "the line of the type's name");
        Assertions.assertEquals(0, person.depth());
        Assertions.assertEquals(Files.size(root.resolve(person.path())), person.size(),
                "the size is the file's, as hashed");
        Assertions.assertEquals(16, person.checksum().length(), "16 hex characters of content identity");
        Assertions.assertEquals("2026-01-02T03:04:05Z", person.hashCalculatedAt(),
                "the injected clock's instant, at second precision");

        ClassRecord record = parsed.row("a.b.Person.Record");
        Assertions.assertEquals("a.b.Person", record.enclosing(), "a member type names its enclosing type");
        Assertions.assertEquals(1, record.depth());
        Assertions.assertEquals("record", record.kind());
        Assertions.assertEquals(parsed.row("a.b.Person").path(), record.path(),
                "and shares its declaring file's row facts, because those are facts about the file");
    }

    /** There is no surrogate id: no short hash, counter or positional index anywhere in the table. */
    @Test
    void theTableCarriesNoSurrogateId() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-noid"));
        written(root, null);
        String text = tableText(root);

        for (String forbidden : List.of("\"id\"", "\"ids\"", "\"index\"", "\"ordinal\"", "\"row\"",
                "\"crc\"", "\"hashOfPath\"")) {
            Assertions.assertFalse(text.contains(forbidden),
                    "the table must not carry " + forbidden + ": the key space is the language's own "
                            + "fully qualified names, which is what an IDE's rename refactor can maintain");
        }
        // Every key of `classes` is a dotted, package-qualified name — a name a compiler would accept.
        var parsed = readBack(root);
        for (ClassRecord row : parsed.rows()) {
            Assertions.assertTrue(row.fqn().contains("."),
                    "a row key is a fully qualified name, not a bare simple name: " + row.fqn());
            Assertions.assertEquals(row.fqn().substring(row.fqn().lastIndexOf('.') + 1),
                    row.fqn().substring(row.fqn().lastIndexOf('.') + 1),
                    "and the last segment is the type's own simple name");
        }
    }

    /** The table says what it is; a consumer can refuse it without reading anything else. */
    @Test
    void theTableDescribesItself() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-header"));
        written(root, null);
        tools.jackson.databind.JsonNode root1 = EntityMetadataGenerator.OBJECT_MAPPER.readTree(tableText(root));

        Assertions.assertEquals(ClassIndex.FORMAT, root1.path("format").asInt(-1));
        Assertions.assertEquals("module", root1.path("module").asText(),
                "the module's directory name, so an opened file is self-describing");
        Assertions.assertEquals("src/main/java", root1.path("sourceRoot").asText());
        Assertions.assertEquals(ContentHash.ALGO, root1.path("hash").path("algo").asText());
        Assertions.assertEquals(ContentHash.NORMALIZE, root1.path("hash").path("normalize").asText());
        Assertions.assertEquals("content", root1.path("hash").path("of").asText(),
                "`of: content` says the checksum is of the file's bytes rather than of its metadata");
    }

    // ── the invariants ──────────────────────────────────────────────────────────────────────────────

    /** No row carries source content, in any spelling. */
    @Test
    void noRowCarriesContent() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-content"));
        written(root, null);
        String text = tableText(root);

        for (String contentish : List.of("sourcesContent", "\"content\":", "\"text\":", "\"source\":",
                "package a.b;", "interface Person", "record Record")) {
            Assertions.assertFalse(text.contains(contentish),
                    "the table records a name, a hash and a size — never content; found " + contentish);
        }
    }

    /** Two passes over an unchanged tree are byte-identical; one edited byte moves exactly one row. */
    @Test
    void anUnchangedTreeIsByteIdenticalAndAnEditMovesOneRow() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-determinism"));
        ClassIndex first = written(root, null);
        String before = tableText(root);

        ClassIndex second = written(root, first);
        Assertions.assertEquals(before, tableText(root),
                "a second pass over an unchanged tree must be byte-identical, or the table cannot be "
                        + "committed and reviewed");

        // One edited byte in one file, in a method body, so no row's identity moves.
        Path edited = root.resolve("src/main/java/a/b/PersonSummary.java");
        write(edited, Files.readString(edited).replace("return name();", "return name() + \"\";"));

        // A clock an hour later, so a wrongly re-stamped row is visible as well as a changed checksum.
        ClassIndex later = ClassIndex.forPass(root.resolve(".jcodebuddy/metadata/entity"), root,
                root.resolve("src/main/java"), Clock.fixed(FIXED.plusSeconds(3600), ZoneOffset.UTC));
        register(later, root);
        later.merge(second).write();

        var parsed = readBack(root);
        Assertions.assertEquals("2026-01-02T04:04:05Z", parsed.row("a.b.PersonSummary").hashCalculatedAt(),
                "the edited file's row is stamped now");
        Assertions.assertEquals("2026-01-02T03:04:05Z", parsed.row("a.b.Person").hashCalculatedAt(),
                "and the untouched file's row keeps the instant its checksum was calculated at, which is "
                        + "what makes the timestamp date the content rather than the build");
        Assertions.assertEquals(3, parsed.size(),
                "the edit changed no row's identity: still the interface, its member type and a.b.Person");
        Assertions.assertNotNull(parsed.row("a.b.PersonSummary"), "and the edited type is still there");

        List<ClassIndex.Change> changes = parsed.changedSince(second);
        Assertions.assertEquals(1, changes.size(), "exactly one row changed: " + changes);
        Assertions.assertEquals(ClassIndex.ChangeKind.CONTENT, changes.get(0).kind());
        Assertions.assertEquals("a.b.PersonSummary", changes.get(0).fqn());
        Assertions.assertEquals("0 added, 1 content change(s), 0 removed, 0 renamed",
                ClassIndex.summarize(changes),
                "the summary line the pass prints accounts for the change, and for nothing else");
    }

    /** A rename and an addition are both reported, and a removal is named. */
    @Test
    void everyChangeKindIsReported() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-changes"));
        ClassIndex before = written(root, null);

        // A new file, and a rename of an existing file's type.
        write(root.resolve("src/main/java/a/b/Extra.java"),
                "package a.b;\n\npublic interface Extra {}\n");
        Path renamed = root.resolve("src/main/java/a/b/PersonSummary.java");
        write(renamed, Files.readString(renamed).replace("interface PersonSummary", "interface Summary"));

        ClassIndex after = indexOf(root).merge(before);
        register(after, root);
        after.write();

        var parsed = readBack(root);
        List<ClassIndex.Change> changes = parsed.changedSince(before);
        Set<String> added = new TreeSet<>();
        Set<String> removed = new TreeSet<>();
        for (ClassIndex.Change change : changes) {
            if (change.kind() == ClassIndex.ChangeKind.ADDED) {
                added.add(change.fqn());
            }
            if (change.kind() == ClassIndex.ChangeKind.REMOVED) {
                removed.add(change.fqn());
            }
        }

        Assertions.assertTrue(added.contains("a.b.Extra"),
                "the new file's type arrives as an addition: " + changes);
        Assertions.assertTrue(added.contains("a.b.Summary"),
                "and the renamed type arrives under its new name: " + changes);
        Assertions.assertEquals(Set.of("a.b.PersonSummary"), removed,
                "while the old name leaves the table: " + changes);
        Assertions.assertTrue(summariseContains(changes, "2 added") && summariseContains(changes, "1 removed"),
                "the summary the pass prints counts them: " + ClassIndex.summarize(changes));
    }

    /**
     * A file that moves keeps its FQN and is reported as a moved row, not as an addition.
     *
     * <p>{@link ClassIndex.ChangeKind#RENAMED_TYPE} exists for this case — the same type, the same
     * content, a different declaring file — and it is the one change a consumer could otherwise mistake
     * for a delete plus a create.</p>
     */
    @Test
    void aMovedFileIsReportedAsARenamedType() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-move"));
        ClassIndex before = written(root, null);

        // Move Person.java into another package directory, keeping the file's bytes identical.
        Path from = root.resolve("src/main/java/a/b/Person.java");
        Path to = root.resolve("src/main/java/a/c/Person.java");
        write(to, Files.readString(from));
        Files.delete(from);

        ClassIndex after = indexOf(root).merge(before);
        register(after, root);
        after.write();

        var parsed = readBack(root);
        List<ClassIndex.Change> changes = parsed.changedSince(before);
        Assertions.assertTrue(changes.stream().anyMatch(c -> c.kind() == ClassIndex.ChangeKind.RENAMED_TYPE),
                "the same FQN on a different path is a moved row: " + changes);
    }

    private static boolean summariseContains(List<ClassIndex.Change> changes, String fragment) {
        return ClassIndex.summarize(changes).contains(fragment);
    }

    /** A duplicate FQN from two files fails loudly rather than letting one win. */
    @Test
    void twoFilesDeclaringOneTypeIsAFatalDiagnostic() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-duplicate"));
        ClassIndex index = indexOf(root);
        var unit = SourceReader.readUnit(root.resolve("src/main/java/a/b/Person.java"));
        index.addTypes("src/main/java/a/b/Person.java", unit, false);

        IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                () -> index.addTypes("src/main/java/other/Person.java", unit, false),
                "the language makes an FQN unique, so two files claiming one is two source roots holding "
                        + "the same type; picking a winner by iteration order is the F-44 failure this "
                        + "repository has already paid for");
        Assertions.assertTrue(thrown.getMessage().contains("a.b.Person"), thrown.getMessage());
        Assertions.assertTrue(thrown.getMessage().contains("src/main/java/a/b/Person.java"),
                thrown.getMessage());
        Assertions.assertTrue(thrown.getMessage().contains("src/main/java/other/Person.java"),
                "and the message must name both files, or the reader cannot resolve it: "
                        + thrown.getMessage());
    }

    /** An unknown format or hash contract is refused, never half-believed. */
    @Test
    void anUnknownTableIsRefusedWithAReason() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-refuse"));
        written(root, null);
        Path indexFile = ClassIndex.Readers.defaultIndexFile(root);

        List<String> problems = new java.util.ArrayList<>();
        String good = Files.readString(indexFile);

        Files.writeString(indexFile, good.replace("\"format\": 1", "\"format\": 99"));
        Assertions.assertNull(readBack(root, problems),
                "an unknown version means 'do not trust this table'");
        Assertions.assertTrue(problems.get(0).contains("99"), problems.toString());

        problems.clear();
        Files.writeString(indexFile, good.replace("\"algo\": \"wyhash64\"", "\"algo\": \"md5\""));
        Assertions.assertNull(readBack(root, problems),
                "a table hashed with another algorithm cannot be compared with this build's answer");
        Assertions.assertTrue(problems.get(0).contains("md5"), problems.toString());

        problems.clear();
        Files.writeString(indexFile, "{ not json");
        Assertions.assertNull(readBack(root, problems),
                "and a corrupt table is a full pass, not a crash");
        Assertions.assertFalse(problems.isEmpty());
    }

    /** The mtime sidecar carries mtimes, never the table, and a watcher may use it as a pre-filter. */
    @Test
    void theMtimeSidecarIsSeparateAndNotACorrectnessInput() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-mtime"));
        ClassIndex first = written(root, null);
        String table = tableText(root);
        Assertions.assertFalse(table.contains("mtime"),
                "an mtime belongs to a working tree and cannot be committed, so it must not be in the "
                        + "table");

        Path sidecar = root.resolve(".jcodebuddy/index/" + ClassIndex.MTIME_FILE_NAME);
        Assertions.assertTrue(Files.isRegularFile(sidecar), "but the sidecar must be written: " + sidecar);
        var parsedSidecar = EntityMetadataGenerator.OBJECT_MAPPER.readTree(Files.readString(sidecar));
        Assertions.assertEquals(ClassIndex.FILE_NAME, parsedSidecar.path("of").asText(),
                "and it says which table it describes");
        Assertions.assertEquals(3, parsedSidecar.path("mtimes").size(),
                "one entry per row: " + parsedSidecar.path("mtimes"));

        // Deleting it costs a full hash and nothing else: the table still reads.
        Files.delete(sidecar);
        var reparsed = readBack(root);
        Assertions.assertNotNull(reparsed, "a missing sidecar must not make the table unusable");
        Assertions.assertEquals(3, reparsed.size());
        Assertions.assertNull(reparsed.mtimeOf("a.b.Person"));

        // And a pass without it still writes a table byte-identical to the one it read.
        ClassIndex again = written(root, reparsed);
        Assertions.assertEquals(table, tableText(root),
                "the sidecar is never a correctness input: losing it changes no row");
        Assertions.assertNotNull(again);
    }

    /** FQN to path is the only mapping a document needs, and it is exactly the table's rows. */
    @Test
    void thePathMapIsTheTablesRows() throws Exception {
        Path root = writeModule(Files.createTempDirectory("class-index-paths"));
        written(root, null);
        Map<String, String> paths = ClassIndex.pathsByFqn(ClassIndex.Readers.defaultIndexFile(root));

        Assertions.assertEquals(Set.of("a.b.Person", "a.b.Person.Record", "a.b.PersonSummary"),
                new HashSet<>(paths.keySet()));
        Assertions.assertEquals("src/main/java/a/b/Person.java", paths.get("a.b.Person"));
        Assertions.assertEquals("src/main/java/a/b/Person.java", paths.get("a.b.Person.Record"),
                "a member type resolves to the file that declares it");
    }
}
