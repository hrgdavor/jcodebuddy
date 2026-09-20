package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.EntityFieldMeta;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The metadata's source paths — which since DEC-029 live in <strong>one place</strong>: the module's
 * class index, {@code .jcodebuddy/index/classes.json}.
 *
 * <p>A document names each type by its fully qualified name, and the index states the path once. That is
 * the addressing half of the change; this test asserts the paths it carries are still the ones the rest
 * of the system depends on:</p>
 *
 * <ul>
 *   <li><strong>module-relative</strong>, never project-relative and never absolute. A report (the Bun
 *       page of DEC-027) resolves a path against the module root, and a multi-module build resolves it
 *       inside the module that holds the class — a project-relative path would be correct in exactly one
 *       module and broken in every other.</li>
 *   <li><strong>the declaring file</strong>, not the view's: an inherited or addon field is declared in a
 *       different interface than the view that exposes it, and a view-relative path would send a reader
 *       to the wrong file.</li>
 *   <li><strong>a location, not content</strong>: the metadata names files, it never quotes them.</li>
 * </ul>
 *
 * <p>The pass is run here rather than read from the committed {@code .jcodebuddy/metadata} tree, so the
 * test states the contract instead of whatever a previous pass happened to leave on disk.</p>
 */
class MetadataSourcePathTest {

    private static final List<String> MARKERS = List.of("Auditable", "PaymentMethod", "Person");

    private static final List<String> SAMPLE_PATHS = List.of(
            "src/main/java/hr/hrg/hipster/entityexample/example/Auditable.java",
            "src/main/java/hr/hrg/hipster/entityexample/person/entity/Person.java",
            "src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java",
            "src/main/java/hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethodAuditable.java");

    /** One pass's roots, the class index it wrote, and the FQN table that index holds. */
    private record Pass(Path moduleRoot, Path sourceRoot, Path reportDir, Map<String, String> fqnToPath) {

        Path indexFile() {
            return reportDir.resolve("index/classes.json");
        }

        String indexJson() throws Exception {
            Assertions.assertTrue(Files.exists(indexFile()), "the pass must write " + indexFile());
            return new String(Files.readAllBytes(indexFile()), StandardCharsets.UTF_8);
        }

        String json(String marker) throws Exception {
            Path file = reportDir.resolve(marker + ".metadata.json");
            Assertions.assertTrue(Files.exists(file), "the pass must write " + file);
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }

        EntityMeta meta(String marker) throws Exception {
            return EntityMetadataGenerator.fromJson(json(marker), fqnToPath);
        }

        /** The index's own `classes` table, read straight from its JSON. */
        Map<String, String> classes() throws Exception {
            tools.jackson.databind.JsonNode root =
                    new tools.jackson.databind.ObjectMapper().readTree(indexJson());
            Map<String, String> classes = new LinkedHashMap<>();
            for (var it = root.path("classes").propertyStream().iterator(); it.hasNext(); ) {
                var entry = it.next();
                classes.put(entry.getKey(), entry.getValue().path("path").asText());
            }
            return classes;
        }
    }

    /**
     * Runs a real pass over the committed example into a temp report directory.
     *
     * <p>The report directory is deliberately outside the module: the module root is then resolved from
     * the marker above the <em>source root</em>, which is the path DEC-026 § 2 says must win — a nearer
     * {@code pom.xml} or a report directory somewhere else must not move the base. Generated source goes
     * to its own temp root, because a report directory holds metadata and never a copy of a source file
     * ({@code GeneratorGuardTest} asserts that).</p>
     *
     * <p>That temp java-output root is also why this pass reports {@code artifact_outside_module} for the
     * files it emits: an artifact outside the module has no module-relative path, so it is absent from
     * the index rather than named by a path that would be wrong. The paths this test checks are all
     * declared under the source root and are unaffected.</p>
     */
    private static Pass runPass() throws Exception {
        Path moduleRoot = CompileHarness.findRepoRoot().resolve("hipster-entity-example");
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        Path reportDir = Files.createTempDirectory("source-path-report");
        Path javaOut = Files.createTempDirectory("source-path-java");
        EntityMetadataGenerator.generate(sourceRoot, reportDir, javaOut);

        Map<String, String> fqnToPath = new LinkedHashMap<>();
        Pass probe = new Pass(moduleRoot, sourceRoot, reportDir, fqnToPath);
        fqnToPath.putAll(probe.classes());
        return probe;
    }

    /** Checks one recorded path, collecting the reason instead of failing on the first one. */
    private static int check(List<String> problems, Pass pass, String owner, String sourcePath,
                             String declaredName) throws Exception {
        if (sourcePath == null) {
            problems.add(owner + ": no sourcePath");
            return 0;
        }
        if (!sourcePath.matches("src/main/java/[A-Za-z0-9_$/]+\\.java")) {
            // The shape is the contract: a relative path under the module's source root, ending in
            // `.java` — not an absolute path, not a project-relative one, and not source content.
            problems.add(owner + ": not a module-relative path to a .java file: " + sourcePath);
        }
        if (sourcePath.isBlank() || sourcePath.startsWith("/") || sourcePath.startsWith("\\")) {
            problems.add(owner + ": not a relative path: " + sourcePath);
        }
        if (sourcePath.contains("\\")) {
            problems.add(owner + ": must use forward slashes: " + sourcePath);
        }
        if (sourcePath.contains("..")) {
            problems.add(owner + ": must not reach outside the module: " + sourcePath);
        }
        if (sourcePath.matches("^[A-Za-z]:.*")) {
            problems.add(owner + ": absolute (drive-lettered) path: " + sourcePath);
        }
        if (sourcePath.startsWith("hipster-entity-example/")) {
            problems.add(owner + ": project-relative, not module-relative: " + sourcePath);
        }
        if (!sourcePath.startsWith("src/main/java/")) {
            problems.add(owner + ": expected a path under the module's source root: " + sourcePath);
        }
        Path file = pass.moduleRoot().resolve(sourcePath);
        if (!Files.isRegularFile(file)) {
            problems.add(owner + ": no such file under the module root: " + sourcePath);
            return 0;
        }
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!source.contains(declaredName)) {
            problems.add(owner + ": " + sourcePath + " does not mention " + declaredName);
        }
        return 1;
    }

    /**
     * The metadata names source files; it does not quote them.
     *
     * <p>A record that carried the file's text would be a second copy of the tree — stale the moment
     * anyone edits the source — and it is unnecessary: the path is what opens the file, in the IDE and in
     * the report. The markers below are the ones any Java file would contain, so their absence is the
     * assertion that what was recorded is a location and not a body.</p>
     *
     * <p>Neither the documents nor the index may carry content. The index is new and carries more paths
     * than any document did, so it is checked too.</p>
     */
    @Test
    void theMetadataRecordsPathsNotSourceContent() throws Exception {
        Pass pass = runPass();
        for (String marker : MARKERS) {
            String json = pass.json(marker);
            for (String sourceText : List.of("\\n", "@Override", "public interface ", "public enum ",
                    "public final class ", "{@link", "package hr.hrg.")) {
                Assertions.assertFalse(json.contains(sourceText),
                        marker + ".metadata.json must carry a path, not source content; found "
                                + sourceText);
            }
        }
        String index = pass.indexJson();
        for (String sourceText : List.of("@Override", "public interface ", "public enum ",
                "public final class ", "{@link")) {
            Assertions.assertFalse(index.contains(sourceText),
                    "the class index must carry names, paths and hashes, never source content; found "
                            + sourceText);
        }
    }

    /** Every path the index carries is module-relative and inside the module. */
    @Test
    void everyIndexPathIsModuleRelative() throws Exception {
        Pass pass = runPass();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> entry : pass.classes().entrySet()) {
            String path = entry.getValue();
            if (path.isBlank() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")
                    || path.contains("\\") || path.contains("..")) {
                problems.add(entry.getKey() + ": not a module-relative path: " + path);
                continue;
            }
            if (!Files.isRegularFile(pass.moduleRoot().resolve(path))) {
                problems.add(entry.getKey() + ": no such file under the module root: " + path);
                continue;
            }
            checked++;
        }
        Assertions.assertEquals("", String.join("; ", problems));
        Assertions.assertTrue(checked >= 20,
                "the example must record a path for every indexed type; checked " + checked);
    }

    /**
     * Every path a document resolves to is module-relative and points at a file that declares what it is
     * attached to.
     *
     * <p>The FQNs are resolved through the class index — the route a consumer takes — so this asserts the
     * whole chain: a document's reference, the index's row, and the file the row names.</p>
     */
    @Test
    void everyRecordedPathIsModuleRelativeAndDeclaresItsOwner() throws Exception {
        Pass pass = runPass();
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String marker : MARKERS) {
            EntityMeta meta = pass.meta(marker);
            checked += check(problems, pass, marker + " marker", meta.markerSourcePath(),
                    meta.markerInterface());
            for (ViewMeta view : meta.views()) {
                checked += check(problems, pass, marker + " view " + view.name(), view.sourcePath(),
                        view.name());
                for (Property property : view.properties()) {
                    checked += check(problems, pass, marker + " " + view.name() + "." + property.name(),
                            property.sourcePath(), property.name());
                }
                for (var artifact : view.artifacts()) {
                    // An artifact's file id must resolve too: the page links every aspect card and every
                    // column header through it, so an unresolvable one is a dead link.
                    if (artifact.file() == null) {
                        continue;
                    }
                    Assertions.assertTrue(
                            Files.isRegularFile(pass.moduleRoot().resolve(artifact.file())),
                            "artifact " + artifact.name() + " names a file that exists: " + artifact.file());
                }
            }
            for (EntityFieldMeta field : meta.allFields()) {
                if (field.getSourcePath() == null) {
                    continue; // the inherited `id`, declared outside the source root
                }
                checked += check(problems, pass, marker + " allFields." + field.name,
                        field.getSourcePath(), field.name);
            }
        }

        Assertions.assertEquals("", String.join("; ", problems));
        Assertions.assertTrue(checked >= 20,
                "the example must record a path for a marker, every view and every field; checked " + checked);
    }

    /**
     * An inherited or addon field carries <em>its own</em> declaring file, not the view's.
     *
     * <p>This is the fact a report cannot derive: {@code PersonDetails} declares two accessors and
     * inherits three, and its addon contributes two more, so four of its seven fields live in files other
     * than its own. A view-relative path would send a reader to the wrong file for all four.</p>
     */
    @Test
    void anInheritedOrAddonFieldCarriesItsOwnDeclaringFile() throws Exception {
        Pass pass = runPass();
        EntityMeta person = pass.meta("Person");

        ViewMeta details = person.views().stream()
                .filter(view -> "PersonDetails".equals(view.name()))
                .findFirst().orElseThrow();
        Assertions.assertTrue(details.sourcePath().endsWith("person/entity/PersonDetails.java"),
                "the view's own path: " + details.sourcePath());

        Assertions.assertTrue(pathOf(person, "firstName").endsWith("person/entity/Person.java"),
                "firstName is declared by Person: " + pathOf(person, "firstName"));
        Assertions.assertTrue(pathOf(person, "createdAt").endsWith("example/Auditable.java"),
                "createdAt comes from the addon: " + pathOf(person, "createdAt"));
        Assertions.assertTrue(pathOf(person, "email").endsWith("person/entity/PersonCreateForm.java"),
                "email is inherited from PersonCreateForm: " + pathOf(person, "email"));

        Assertions.assertNull(field(person, "id").getSourcePath(),
                "id is declared by Identifiable in hipster-entity-api, so it has no path here");
    }

    /**
     * A view outside its marker's package keeps its own path.
     *
     * <p>The {@code Auditable} marker lives in {@code example/} while its views live in
     * {@code person/entity/} and {@code paymentMethod/entity/}. Reconstructing a path from the marker's
     * package — the only route a JSON without a path leaves — would name a file that does not exist.</p>
     */
    @Test
    void aViewOutsideItsMarkersPackageKeepsItsOwnPath() throws Exception {
        Pass pass = runPass();
        EntityMeta auditable = pass.meta("Auditable");

        Assertions.assertTrue(auditable.markerSourcePath().endsWith("example/Auditable.java"),
                "the marker's own file: " + auditable.markerSourcePath());
        ViewMeta paymentAuditable = auditable.views().stream()
                .filter(view -> "PaymentMethodAuditable".equals(view.name()))
                .findFirst().orElseThrow();
        Assertions.assertTrue(
                paymentAuditable.sourcePath()
                        .endsWith("paymentMethod/entity/PaymentMethodAuditable.java"),
                "the view's own package, not the marker's: " + paymentAuditable.sourcePath());
    }

    /**
     * The class index is the contract, and it is complete in both directions.
     *
     * <p>A document names no path at all — that is the DEC-029 rule — so the assertion that the paths
     * survive is made against the index: every path the example's entities are made of is a row in the
     * table, and {@code fromJson} resolves a document's references back to them through it.</p>
     */
    @Test
    void thePathsAreInTheIndexAndSurviveBeingReadBack() throws Exception {
        Pass pass = runPass();
        String json = pass.json("Person");
        // No document carries a path; each carries a pointer to the table that does.
        for (String key : List.of("\"markerSourcePath\"", "\"sourcePath\"")) {
            Assertions.assertFalse(json.contains(key), "the JSON must not carry " + key);
        }
        Assertions.assertTrue(json.contains("\"classIndex\""),
                "and it must carry the pointer to the class index that does");

        Map<String, String> classes = pass.classes();
        for (String sample : SAMPLE_PATHS) {
            Assertions.assertTrue(classes.containsValue(sample),
                    "the class index must record " + sample + "; it records " + classes.size() + " paths");
        }

        EntityMeta parsed = EntityMetadataGenerator.fromJson(json, classes);
        Assertions.assertNotNull(parsed.markerSourcePath(), "fromJson must resolve the marker's reference");
        for (ViewMeta view : parsed.views()) {
            Assertions.assertNotNull(view.sourcePath(), "fromJson must resolve " + view.name() + "'s reference");
        }
        Assertions.assertEquals(pathOf(parsed, "firstName"), pathOf(pass.meta("Person"), "firstName"),
                "a round trip must not move a path");
    }

    /** Without the table the references stay unresolved rather than becoming invented paths. */
    @Test
    void withoutTheIndexReferencesStayUnresolved() throws Exception {
        Pass pass = runPass();
        EntityMeta unresolved = EntityMetadataGenerator.fromJson(pass.json("Person"));
        Assertions.assertNull(unresolved.markerSourcePath(),
                "a reference with no table to resolve it is not a path, and guessing one would be a wrong "
                        + "link");
        for (ViewMeta view : unresolved.views()) {
            Assertions.assertNull(view.sourcePath(), view.name() + "'s reference must stay unresolved");
        }
    }

    /**
     * A document written before DEC-029 still reads, in both of the spellings that existed.
     *
     * <p>Two earlier revisions are accepted for one release: the plain {@code markerSourcePath} path keys
     * of the pre-index documents, and the DEC-028 readable ids (with {@code fileIndex}) that a
     * {@code files.json} table resolved. Nothing emits either spelling any more; a reader is tolerant so a
     * project mid-upgrade can still open its own output.</p>
     */
    @Test
    void aLegacyDocumentIsStillRead() throws Exception {
        Pass pass = runPass();
        // The pre-index spelling: a plain path under the old key.
        String legacyPath = pass.json("Person")
                .replaceAll("\"markerFile\": \"[^\"]*\"",
                        "\"markerSourcePath\": \"" + SAMPLE_PATHS.get(1) + "\"")
                .replace("\"classIndex\": \"../../index/classes.json\"", "\"legacy\": true");
        EntityMeta parsed = EntityMetadataGenerator.fromJson(legacyPath);
        Assertions.assertEquals(SAMPLE_PATHS.get(1), parsed.markerSourcePath(),
                "the old `markerSourcePath` key must keep working, or a document written before the class "
                        + "index can no longer be read at all");

        // The DEC-028 spelling: a readable id plus the pointer to the table that resolved it.
        String legacyId = pass.json("Person")
                .replaceAll("\"markerFile\": \"[^\"]*\"", "\"markerFile\": \"entity.Person\"")
                .replace("\"classIndex\": \"../../index/classes.json\"",
                        "\"fileIndex\": \"../../index/files.json\"");
        Map<String, String> legacyIds = Map.of("entity.Person", SAMPLE_PATHS.get(1));
        EntityMeta resolved = EntityMetadataGenerator.fromJson(legacyId, null, legacyIds);
        Assertions.assertEquals(SAMPLE_PATHS.get(1), resolved.markerSourcePath(),
                "a DEC-028 readable id must still resolve through the table that defined it");
    }

    /** The length of the longest run of newlines in a file — its number of lines, whatever the endings. */
    private static int lineCount(String text) {
        return text.split("\\R", -1).length;
    }

    @org.junit.jupiter.api.Test
    void renamingATypeUpdatesEveryReferenceThePassWrites() throws Exception {
        Path moduleRoot = CompileHarness.findRepoRoot().resolve("hipster-entity-example");
        Path sourceRoot = moduleRoot.resolve("src/main/java");
        Path tree = Files.createTempDirectory("source-path-rename");
        for (Path source : CompileHarness.javaSourcesUnder(sourceRoot)) {
            Path target = tree.resolve(moduleRoot.relativize(source).toString());
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        Path reportDir = tree.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(reportDir);
        Path renamedRoot = tree.resolve("src/main/java");

        // Before: the type is `PersonDetails`, and the documents name it.
        EntityMetadataGenerator.generate(renamedRoot, reportDir, renamedRoot);
        String before = Files.readString(reportDir.resolve("Person.metadata.json"), StandardCharsets.UTF_8);
        Assertions.assertTrue(before.contains(".PersonDetails\""),
                "the fixture must reference the type before the rename, or this test proves nothing");

        // Rename exactly what an IDE's rename refactor would reach in a text file — the type's simple name
        // wherever it occurs — and delete the generated siblings under the OLD name, because a pass
        // regenerates those rather than renaming them. That is the mechanism this design depends on: a
        // fully qualified name is a fact an IDE will update, a surrogate id is not.
        try (var walk = Files.walk(renamedRoot)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains("PersonDetails")) {
                    continue;
                }
                if (file.getFileName().toString().startsWith("PersonDetails_")) {
                    // A generated artifact whose input was renamed: the pass emits its replacement, and the
                    // stale file is what a developer deletes. Leaving it would make two files declare one
                    // FQN, which the class index refuses (correctly) rather than resolving by walk order.
                    Files.delete(file);
                    continue;
                }
                Files.writeString(file, text.replace("PersonDetails", "PersonOverview"), StandardCharsets.UTF_8);
            }
        }
        Files.move(renamedRoot.resolve("hr/hrg/hipster/entityexample/person/entity/PersonDetails.java"),
                renamedRoot.resolve("hr/hrg/hipster/entityexample/person/entity/PersonOverview.java"));

        EntityMetadataGenerator.generate(renamedRoot, reportDir, renamedRoot);
        String after = Files.readString(reportDir.resolve("Person.metadata.json"), StandardCharsets.UTF_8);
        Assertions.assertFalse(after.contains("PersonDetails"),
                "no document may keep naming a type that no longer exists: a stale FQN is a dead link the "
                        + "page cannot even diagnose");
        Assertions.assertTrue(after.contains(".PersonOverview\""),
                "and the new name must be the one every reference carries");
        Assertions.assertTrue(after.split("\\.PersonOverview\"").length - 1 >= 1,
                "which includes the `views[].file` reference, not only the surface name");

        // The index followed too: one row for the new FQN on the moved file, and none for the old name.
        String index = Files.readString(tree.resolve(".jcodebuddy/index/classes.json"), StandardCharsets.UTF_8);
        Assertions.assertFalse(index.contains("PersonDetails"),
                "the class index is regenerated from the tree, so the old row is gone");
        Assertions.assertTrue(index.contains(".PersonOverview"), "and the new row is there");
        Assertions.assertTrue(lineCount(index) > 40, "the table really was rewritten");
    }

    private static String pathOf(EntityMeta meta, String fieldName) {
        String path = field(meta, fieldName).getSourcePath();
        Assertions.assertNotNull(path, fieldName + " must carry a source path");
        return path;
    }

    private static EntityFieldMeta field(EntityMeta meta, String fieldName) {
        return meta.allFields().stream()
                .filter(candidate -> candidate.name.equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + fieldName));
    }
}
