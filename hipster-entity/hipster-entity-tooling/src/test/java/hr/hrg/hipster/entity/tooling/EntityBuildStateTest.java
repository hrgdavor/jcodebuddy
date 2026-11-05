package hr.hrg.hipster.entity.tooling;

import hr.hrg.fsmeta.FsMetaFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code fs-meta} build state and the {@code --incremental} no-op pass (plan E2).
 *
 * <p>The state is a cache, so every test here is about the <em>decision</em> rather than about generated
 * bytes: a missing, corrupt or foreign trio must cost a full pass, a real change must be reported with the
 * names the diff produced, and an unchanged tree must do nothing at all. The last two tests run the CLI
 * in-process, because the wiring (decide before {@code generate}, publish after it) is what E2 adds.</p>
 *
 * <p>Isolation matters: {@link EntityMetadataGenerator} keeps its flags in static fields, and its
 * {@code main} resets most of them but not every one (notably {@code --dto-projections}), so every test
 * resets the whole surface before and after itself.</p>
 */
class EntityBuildStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetBefore() {
        resetGeneratorKnobs();
    }

    @AfterEach
    void resetAfter() {
        resetGeneratorKnobs();
    }

    private static void resetGeneratorKnobs() {
        EntityMetadataGenerator.setGenerationPackages(List.of());
        EntityMetadataGenerator.setMapperRequests(List.of());
        EntityMetadataGenerator.setMergeRequests(List.of());
        EntityMetadataGenerator.setGenerateAdapters(false);
        EntityMetadataGenerator.setGeneratePatchAppliers(false);
        EntityMetadataGenerator.setGenerateDtoProjections(false);
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
        CooperativeCodegen.setForce(false);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A temp source root with one ordinary {@code .java} file. Content is irrelevant: only bytes are hashed. */
    private static Path tree() throws Exception {
        Path root = Files.createTempDirectory("entity-build-tree").resolve("src/main/java");
        Path pkg = root.resolve("build/entity");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), "package build.entity;\npublic interface Thing {}\n");
        return root;
    }

    /** The single {@code .java} file {@link #tree()} created. */
    private static Path thing(Path sourceRoot) {
        return sourceRoot.resolve("build/entity/Thing.java");
    }

    /**
     * The metadata directory a test tree would use. It does not have to exist: the state directory is
     * resolved from it the way a real pass resolves it, and nothing here writes entity metadata.
     */
    private static Path metadataFor(Path sourceRoot) {
        Path module = sourceRoot.getParent().getParent().getParent();
        return module.resolve(".jcodebuddy/metadata/entity");
    }

    private static EntityBuildState state(Path sourceRoot) {
        return EntityBuildState.forPass(sourceRoot, metadataFor(sourceRoot));
    }

    private static EntityBuildState.Config config(Path sourceRoot) {
        return config(sourceRoot, EntityMetadataGenerator.Policy.OFF, false);
    }

    private static EntityBuildState.Config config(Path sourceRoot, EntityMetadataGenerator.Policy policy,
                                                  boolean adapters) {
        return new EntityBuildState.Config(metadataFor(sourceRoot), sourceRoot, List.of("build.entity"),
                adapters, false, List.of(), List.of(), false, false, policy);
    }

    // ── the fallback matrix ──────────────────────────────────────────────────

    @Test
    void aMissingStateFallsBackToAFullPass() throws Exception {
        Path sourceRoot = tree();

        EntityBuildState.Decision decision = state(sourceRoot).decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode());
        Assertions.assertTrue(decision.reason().contains("no build state"), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), "no diff exists to report without a state");
    }

    @Test
    void publishThenAnUnchangedTreeIsANoop() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);

        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);
        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), decision.changed().toString());
    }

    @Test
    void aSameContentTouchIsANoop() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);

        // Same bytes, a later mtime: fs-meta reports TOUCHED, which the pass deliberately ignores.
        Path thing = thing(sourceRoot);
        Files.setLastModifiedTime(thing, FileTime.fromMillis(Files.getLastModifiedTime(thing).toMillis() + 60_000));

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), "a touch is not a content change");
    }

    @Test
    void modifyingAnExistingJavaFileIsIncremental() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);

        Files.writeString(thing(sourceRoot),
                "package build.entity;\npublic interface Thing { int changed(); }\n");

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.INCREMENTAL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Thing.java"), decision.changed(),
                "the changed name is source-root relative and /-separated, as fs-meta records it");
    }

    /**
     * The state tracks what the pass reads, not what git tracks. The generator reads every {@code .java}
     * under the source root, so a file a project happens to ignore must still be part of the state; a
     * {@code .gitignore}-respecting scan would answer "nothing changed" for an edit the pass would read.
     */
    @Test
    void aGitignoredJavaFileIsStillTracked() throws Exception {
        Path sourceRoot = tree();
        Files.writeString(sourceRoot.resolve(".gitignore"), "**/Thing.java\n");
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);

        Files.writeString(thing(sourceRoot),
                "package build.entity;\npublic interface Thing { int changed(); }\n");

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.INCREMENTAL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Thing.java"), decision.changed());
    }

    @Test
    void addingAJavaFileFallsBackToAFullPass() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);

        Files.writeString(sourceRoot.resolve("build/entity/Another.java"),
                "package build.entity;\npublic interface Another {}\n");

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Another.java"), decision.changed());
    }

    @Test
    void deletingAJavaFileFallsBackToAFullPass() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);

        Files.delete(thing(sourceRoot));

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Thing.java"), decision.changed());
    }

    @Test
    void aCorruptTrioFallsBackToAFullPass() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);
        Files.writeString(state.directory().resolve(FsMetaFormat.META_FILE), "not a trio");

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("not usable"), decision.reason());
    }

    @Test
    void anUnknownStateFormatFallsBackToAFullPass() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);
        Files.writeString(state.directory().resolve(FsMetaFormat.JSON_FILE), "{}");

        EntityBuildState.Decision decision = state.decide(config(sourceRoot));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
    }

    @Test
    void aChangedFingerprintFallsBackToAFullPass() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot), EntityBuildState.ValidationOutcome.NOT_RUN);

        // Same tree, same bytes, one output-changing flag different.
        EntityBuildState.Decision decision = state.decide(config(sourceRoot,
                EntityMetadataGenerator.Policy.OFF, true));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("adapters"), decision.reason());
    }

    // ── validation gating ────────────────────────────────────────────────────

    @Test
    void theFingerprintSidecarRecordsTheInvocationAndItsValidation() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        state.publish(config(sourceRoot, EntityMetadataGenerator.Policy.REPORT, false),
                EntityBuildState.ValidationOutcome.CLEAN);

        String fingerprint = Files.readString(state.directory().resolve(EntityBuildState.FINGERPRINT_FILE));
        for (String key : List.of("format=", "generator=", "jdk=", "sourceRoot=", "metadataOutput=",
                "javaOutput=", "packages=", "adapters=", "dtoProjections=", "mappers=", "merges=",
                "patchAppliers=", "force=", "validationPolicy=", "validation=clean")) {
            Assertions.assertTrue(fingerprint.contains(key),
                    "the sidecar must record '" + key + "', which is what decides a full pass: " + fingerprint);
        }
    }

    @Test
    void aCleanValidationLicensesANoop() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        EntityBuildState.Config config = config(sourceRoot, EntityMetadataGenerator.Policy.REPORT, false);
        state.publish(config, EntityBuildState.ValidationOutcome.CLEAN);

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, state.decide(config).mode());
    }

    @Test
    void aReportedValidationDoesNotLicenseANoop() throws Exception {
        Path sourceRoot = tree();
        EntityBuildState state = state(sourceRoot);
        EntityBuildState.Config config = config(sourceRoot, EntityMetadataGenerator.Policy.REPORT, false);
        state.publish(config, EntityBuildState.ValidationOutcome.REPORTED);

        EntityBuildState.Decision decision = state.decide(config);

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("validation"), decision.reason());
    }

    // ── placement ────────────────────────────────────────────────────────────

    @Test
    void theStateLivesUnderTheNearestMarkerNotTheSourceRoot() throws Exception {
        Path module = Files.createTempDirectory("entity-build-marked");
        Path sourceRoot = module.resolve("src/main/java");
        Files.createDirectories(sourceRoot.resolve("build/entity"));
        Files.createDirectories(module.resolve(".jcodebuddy"));
        Path report = module.resolve(".jcodebuddy/metadata/entity");

        Assertions.assertEquals(module.resolve(".jcodebuddy/cache/entity-build"),
                EntityBuildState.stateDirectory(sourceRoot, report),
                "the marker above the report directory owns the state, not src/main/java");

        Path unmarked = Files.createTempDirectory("entity-build-unmarked").resolve("src/main/java");
        Files.createDirectories(unmarked);
        Path unmarkedReport = Files.createTempDirectory("entity-build-unmarked-report");

        Assertions.assertEquals(unmarked.resolve(".jcodebuddy/cache/entity-build"),
                EntityBuildState.stateDirectory(unmarked, unmarkedReport),
                "with no marker, the source root is the documented fallback");
    }

    // ── the CLI wiring, in-process ───────────────────────────────────────────

    /**
     * A first {@code --incremental} run has no state, so it must run the normal pass and publish the state
     * the next {@code --incremental} run can then answer from.
     */
    @Test
    void anIncrementalFirstRunFallsBackToFullAndPublishesState() throws Exception {
        Path module = Files.createTempDirectory("entity-build-cli-first");
        Path sourceRoot = module.resolve("src/main/java");
        writeMinimalEntitySources(sourceRoot);
        Path reportDir = module.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(reportDir);
        Path recordFile = module.resolve(".jcodebuddy/metadata/first.json");
        Path stateDir = EntityBuildState.stateDirectory(sourceRoot, reportDir);

        EntityMetadataGenerator.main(new String[] {
                sourceRoot.toString(), reportDir.toString(),
                "--java-out", sourceRoot.toString(),
                "--packages", "build.entity",
                "--incremental",
                "--run-record", recordFile.toString() });

        JsonNode record = MAPPER.readTree(Files.readString(recordFile));
        Assertions.assertEquals("full", record.path("mode").asText(),
                "no state means the reference pass, not a no-op");
        Assertions.assertTrue(record.path("fallbackReason").asText().contains("no build state"),
                record.path("fallbackReason").asText());
        Assertions.assertTrue(Files.isRegularFile(stateDir.resolve(FsMetaFormat.JSON_FILE)),
                "a successful full pass publishes the state it read next time");
        Assertions.assertTrue(Files.isRegularFile(stateDir.resolve(
                        EntityBuildState.FINGERPRINT_FILE)),
                "including the fingerprint sidecar");
        Assertions.assertTrue(Files.exists(sourceRoot.resolve("build/entity/ThingSummary_.java")),
                "and the normal pass still generated the view");
    }

    /**
     * The E2 gate in one test: a full pass, then an {@code --incremental} pass over the same tree, does no
     * work, writes no generated output and rewrites no state.
     */
    @Test
    void anIncrementalRunOverAnUnchangedTreeIsANoopAndWritesNothing() throws Exception {
        Path module = Files.createTempDirectory("entity-build-cli-noop");
        Path sourceRoot = module.resolve("src/main/java");
        writeMinimalEntitySources(sourceRoot);
        Path reportDir = module.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(reportDir);
        Path firstRecord = module.resolve(".jcodebuddy/metadata/first.json");
        Path secondRecord = module.resolve(".jcodebuddy/metadata/second.json");
        Path stateDir = EntityBuildState.stateDirectory(sourceRoot, reportDir);

        EntityMetadataGenerator.main(new String[] {
                sourceRoot.toString(), reportDir.toString(),
                "--java-out", sourceRoot.toString(),
                "--packages", "build.entity",
                "--run-record", firstRecord.toString() });

        List<String> generatedBefore = javaSnapshot(sourceRoot);
        Assertions.assertTrue(generatedBefore.size() > 4,
                "the first pass must have written at least one generated .java file, or the no-op proves nothing");
        String trioBefore = Files.readString(stateDir.resolve(FsMetaFormat.JSON_FILE));
        String namesBefore = Files.readString(stateDir.resolve(FsMetaFormat.TXT_FILE));

        EntityMetadataGenerator.main(new String[] {
                sourceRoot.toString(), reportDir.toString(),
                "--java-out", sourceRoot.toString(),
                "--packages", "build.entity",
                "--incremental",
                "--run-record", secondRecord.toString() });

        JsonNode record = MAPPER.readTree(Files.readString(secondRecord));
        Assertions.assertEquals("noop", record.path("mode").asText(),
                "an unchanged tree must not run a pass; record: " + record);
        Assertions.assertEquals("ok", record.path("status").asText());
        Assertions.assertEquals(0, record.path("changed").size(), "nothing changed");
        Assertions.assertEquals(0, record.path("affected").size(), "nothing was affected");
        Assertions.assertEquals(0, record.path("emitted").asInt(), "nothing was emitted");
        Assertions.assertEquals(0, record.path("reused").asInt(), "nothing was reused");
        Assertions.assertFalse(record.path("fallbackReason").asText().isBlank(),
                "the one-line no-op reason is recorded");

        Assertions.assertEquals(generatedBefore, javaSnapshot(sourceRoot),
                "a no-op must not change generated output");
        Assertions.assertEquals(trioBefore, Files.readString(stateDir.resolve(FsMetaFormat.JSON_FILE)),
                "a no-op must not rewrite the state's commit record");
        Assertions.assertEquals(namesBefore, Files.readString(stateDir.resolve(FsMetaFormat.TXT_FILE)),
                "nor its names file");
    }

    // ── CLI fixtures and helpers ─────────────────────────────────────────────

    private static void writeMinimalEntitySources(Path sourceRoot) throws Exception {
        Path pkg = sourceRoot.resolve("build/entity");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("ThingEntity.java"),
                "package build.entity;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface ThingEntity extends EntityBase<Long> {}\n");
        Files.writeString(pkg.resolve("ThingSummary.java"),
                "package build.entity;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface ThingSummary extends ThingEntity {\n"
                        + "  String name();\n"
                        + "}\n");
    }

    /** Every {@code .java} file's name and content, sorted, so one comparison covers the whole output. */
    private static List<String> javaSnapshot(Path root) throws Exception {
        List<String> snapshot = new ArrayList<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                snapshot.add(root.relativize(file).toString().replace('\\', '/'));
                snapshot.add(Files.readString(file));
            }
        }
        return snapshot;
    }
}
