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
 * The metadata's source paths — which since DEC-028 live in <strong>one place</strong>: the module's
 * central index, {@code .jcodebuddy/index/files.json}.
 *
 * <p>A document names each file by the short id the index assigned it, and the index states the path
 * once. That is the addressing half of the change; this test asserts the paths it carries are still the
 * ones the rest of the system depends on:</p>
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

    /** One pass's roots, the index it wrote, and the id table that index holds. */
    private record Pass(Path moduleRoot, Path sourceRoot, Path reportDir, Map<String, String> idToPath) {

        Path indexFile() {
            return reportDir.resolve("index/files.json");
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
            return EntityMetadataGenerator.fromJson(json(marker), idToPath);
        }

        /** The index's own `files` table, read straight from its JSON. */
        @SuppressWarnings("unchecked")
        Map<String, String> files() throws Exception {
            tools.jackson.databind.JsonNode root =
                    new tools.jackson.databind.ObjectMapper().readTree(indexJson());
            Map<String, String> files = new LinkedHashMap<>();
            for (var it = root.path("files").propertyStream().iterator(); it.hasNext(); ) {
                var entry = it.next();
                files.put(entry.getKey(), entry.getValue().asText());
            }
            return files;
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

        Map<String, String> idToPath = new LinkedHashMap<>();
        Pass probe = new Pass(moduleRoot, sourceRoot, reportDir, idToPath);
        idToPath.putAll(probe.files());
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
                    "the index must carry ids and paths, never source content; found " + sourceText);
        }
    }

    /** Every path the index carries is module-relative and inside the module. */
    @Test
    void everyIndexPathIsModuleRelative() throws Exception {
        Pass pass = runPass();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, String> entry : pass.files().entrySet()) {
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
                "the example must record a path for every indexed file; checked " + checked);
    }

    /**
     * Every path a document resolves to is module-relative and points at a file that declares what it is
     * attached to.
     *
     * <p>The ids are resolved through the index — the route a consumer takes — so this asserts the whole
     * chain: a document's id, the index's row, and the file the row names.</p>
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
     * The index is the contract, and it is complete in both directions.
     *
     * <p>A document names no path at all — that is the DEC-028 rule — so the assertion that the paths
     * survive is made against the index: every path the example's entities are made of is a row in the
     * table, and {@code fromJson} resolves a document's ids back to them through it.</p>
     */
    @Test
    void thePathsAreInTheIndexAndSurviveBeingReadBack() throws Exception {
        Pass pass = runPass();
        String json = pass.json("Person");
        // No document carries a path; each carries a pointer to the table that does.
        for (String key : List.of("\"markerSourcePath\"", "\"sourcePath\"")) {
            Assertions.assertFalse(json.contains(key), "the JSON must not carry " + key);
        }
        Assertions.assertTrue(json.contains("\"fileIndex\""),
                "and it must carry the pointer to the index that does");

        Map<String, String> files = pass.files();
        for (String sample : SAMPLE_PATHS) {
            Assertions.assertTrue(files.containsValue(sample),
                    "the index must record " + sample + "; it records " + files.size() + " paths");
        }

        EntityMeta parsed = EntityMetadataGenerator.fromJson(json, files);
        Assertions.assertNotNull(parsed.markerSourcePath(), "fromJson must resolve the marker's id");
        for (ViewMeta view : parsed.views()) {
            Assertions.assertNotNull(view.sourcePath(), "fromJson must resolve " + view.name() + "'s id");
        }
        Assertions.assertEquals(pathOf(parsed, "firstName"), pathOf(pass.meta("Person"), "firstName"),
                "a round trip must not move a path");
    }

    /** Without the table the ids stay unresolved rather than becoming invented paths. */
    @Test
    void withoutTheIndexIdsStayUnresolved() throws Exception {
        Pass pass = runPass();
        EntityMeta unresolved = EntityMetadataGenerator.fromJson(pass.json("Person"));
        Assertions.assertNull(unresolved.markerSourcePath(),
                "an id with no table to resolve it is not a path, and guessing one would be a wrong link");
        for (ViewMeta view : unresolved.views()) {
            Assertions.assertNull(view.sourcePath(), view.name() + "'s id must stay unresolved");
        }
    }

    /** A document written before DEC-028 still reads: the plain path keys are accepted. */
    @Test
    void aLegacyDocumentIsStillRead() throws Exception {
        Pass pass = runPass();
        String legacy = pass.json("Person")
                .replace("\"markerFile\": \"entity.Person\"",
                        "\"markerSourcePath\": \"" + SAMPLE_PATHS.get(1) + "\"")
                .replace("\"fileIndex\": \"../../index/files.json\"", "\"legacy\": true");
        EntityMeta parsed = EntityMetadataGenerator.fromJson(legacy);
        Assertions.assertEquals(SAMPLE_PATHS.get(1), parsed.markerSourcePath(),
                "the old `markerSourcePath` key must keep working, or a document written before DEC-028 "
                        + "can no longer be read at all");
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
