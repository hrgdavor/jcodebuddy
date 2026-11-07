package hr.hrg.hipster.entity.tooling;

import hr.hrg.fsmeta.FsMetaFormat;
import hr.hrg.jcodebuddy.watch.FsMetaClient;
import hr.hrg.jcodebuddy.watch.FsMetaDaemonDiscovery;
import hr.hrg.jcodebuddy.watch.PathFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The incremental state as a daemon-generation marker (plan E2, Design B) and the {@code --incremental} pass.
 *
 * <p>The state is a cache, so every test here is about the <em>decision</em> rather than about generated
 * bytes: no daemon must cost a full pass, a missing or corrupt state must cost one, an unchanged generation
 * must do nothing at all, and a real change must be reported with the names the daemon replay produced. The
 * old trio tests are kept as behaviour — the trio itself is gone — through a fake {@link EntityBuildState.Daemon},
 * which is an in-process timeline and therefore needs no native binary.</p>
 *
 * <p>One thing the fake cannot prove is that the daemon sends only the module's subfolder; that is the
 * protocol's own filter, asserted in {@code FsMetaClientProtocolTest} and in the opt-in real-daemon test
 * {@code EntityBuildStateDaemonTest}. The one real-daemon test in this file is skipped without
 * {@code FSMETA_BIN}, exactly like {@code FsMetaClientDaemonTest}.</p>
 *
 * <p>Isolation matters: {@link EntityMetadataGenerator} keeps its flags in static fields, and its {@code main}
 * resets most of them but not every one (notably {@code --dto-projections}), so every test resets the whole
 * surface before and after itself — including the daemon factory this class sets for the CLI tests.</p>
 */
@Tag("generator")
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
        EntityBuildState.setDaemonFactory(null);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A project root with one module under it, because Design B's subject is the ancestor relationship: the
     * daemon serves {@code <project>} and the state records a generation for {@code <project>/module}.
     */
    private record Fixture(Path projectRoot, Path moduleRoot, Path sourceRoot) {

        static Fixture create() throws Exception {
            Path project = Files.createTempDirectory("entity-build-project");
            Path module = project.resolve("module");
            Path sourceRoot = module.resolve("src/main/java");
            Path pkg = sourceRoot.resolve("build/entity");
            Files.createDirectories(pkg);
            Files.writeString(pkg.resolve("Thing.java"), "package build.entity;\npublic interface Thing {}\n");
            return new Fixture(project, module, sourceRoot);
        }

        Path thing() {
            return sourceRoot.resolve("build/entity/Thing.java");
        }

        Path metadata() {
            return moduleRoot.resolve(".jcodebuddy/metadata/entity");
        }

        Path stateDirectory() {
            return moduleRoot.resolve(".jcodebuddy/cache/entity-build");
        }

        Path runFile() {
            return stateDirectory().resolve(EntityBuildState.RUN_FILE);
        }

        FakeDaemon daemon() {
            return new FakeDaemon(projectRoot, sourceRoot);
        }

        EntityBuildState state(FakeDaemon daemon) {
            return EntityBuildState.forPass(sourceRoot, metadata(), daemon);
        }

        EntityBuildState.Config config() {
            return config(EntityMetadataGenerator.Policy.OFF, false);
        }

        EntityBuildState.Config config(EntityMetadataGenerator.Policy policy, boolean adapters) {
            return new EntityBuildState.Config(metadata(), sourceRoot, List.of("build.entity"),
                    adapters, false, List.of(), List.of(), false, false, policy);
        }
    }

    // ── the fallback matrix ──────────────────────────────────────────────────

    /**
     * The hard requirement: without a daemon there is no baseline and no event stream, so the answer is a full
     * pass with a reason a person can act on — never a guessed no-op.
     */
    @Test
    void aMissingDaemonFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        daemon.detach("no fsmeta root list at " + fixture.projectRoot().resolve("roots.json"));

        EntityBuildState.Decision decision = fixture.state(daemon).decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode());
        Assertions.assertTrue(decision.reason().contains("no fs-meta daemon"), decision.reason());
        Assertions.assertTrue(decision.reason().contains("fsmeta root list"), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), "no diff exists to report without a daemon");
    }

    @Test
    void aMissingStateFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();

        EntityBuildState.Decision decision = fixture.state(daemon).decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode());
        Assertions.assertTrue(decision.reason().contains("no build state"), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), "no diff exists to report without a state");
    }

    @Test
    void publishThenAnUnchangedGenerationIsANoop() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);

        EntityBuildState.Publish published = state.publish(fixture.config(),
                EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        Assertions.assertTrue(published.recorded(), published.problem());
        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), decision.changed().toString());
        Assertions.assertTrue(decision.reason().contains("still at generation"), decision.reason());
    }

    /**
     * The daemon publishes a batch only when content changed; a same-content save is {@code TOUCHED} and
     * produces none. So a touched file with the daemon's generation unchanged is exactly the no-op case.
     */
    @Test
    void aSameContentTouchIsANoop() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        Path thing = fixture.thing();
        Files.setLastModifiedTime(thing, FileTime.fromMillis(Files.getLastModifiedTime(thing).toMillis() + 60_000));

        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.changed().isEmpty(), "a touch is not a content change");
    }

    @Test
    void modifyingAnExistingJavaFileIsIncremental() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        daemon.publish(new FsMetaClient.Change("build/entity/Thing.java", FsMetaClient.Kind.CHANGED));
        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.INCREMENTAL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Thing.java"), decision.changed(),
                "the changed name is source-root relative and /-separated, as the pass records it");
    }

    @Test
    void addingAJavaFileFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        daemon.publish(new FsMetaClient.Change("build/entity/Another.java", FsMetaClient.Kind.ADDED));
        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Another.java"), decision.changed());
        Assertions.assertTrue(decision.reason().contains("source set changed"), decision.reason());
    }

    @Test
    void deletingAJavaFileFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        daemon.publish(new FsMetaClient.Change("build/entity/Thing.java", FsMetaClient.Kind.DELETED));
        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertEquals(List.of("build/entity/Thing.java"), decision.changed());
    }

    /**
     * The pass reads only Java. The daemon tracks every file, so a resource changing under the source root is a
     * real generation but not work for this pass — and a no-op never rewrites state to "catch up" to it.
     */
    @Test
    void aNonJavaChangeUnderTheSourceRootIsANoop() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        byte[] before = Files.readAllBytes(fixture.runFile());

        daemon.publish(new FsMetaClient.Change("build/entity/Thing.txt", FsMetaClient.Kind.CHANGED));
        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, decision.mode(), decision.reason());
        Assertions.assertArrayEquals(before, Files.readAllBytes(fixture.runFile()),
                "a no-op must not rewrite the state, not even to advance the generation");
    }

    @Test
    void aCorruptRunJsonFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        Files.writeString(fixture.runFile(), "not json");

        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("cannot be read"), decision.reason());
    }

    @Test
    void anUnknownStateFormatFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        Files.writeString(fixture.runFile(), "{\"format\":\"jcodebuddy-entity-build/1\"}");

        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("has format"), decision.reason());
    }

    /**
     * A trio left by a previous tooling revision is never read as a state and never diffed: the pass is full,
     * the reason says why, and the first successful pass deletes it and writes {@code run.json} instead.
     */
    @Test
    void aLegacyTrioIsReplacedNotRead() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        Files.createDirectories(fixture.stateDirectory());
        Path legacyMeta = fixture.stateDirectory().resolve(FsMetaFormat.META_FILE);
        Files.writeString(legacyMeta, "old trio");

        EntityBuildState.Decision decision = state.decide(fixture.config());
        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("legacy fs-meta trio"), decision.reason());

        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        Assertions.assertTrue(Files.isRegularFile(fixture.runFile()), "the new state is written");
        Assertions.assertFalse(Files.exists(legacyMeta), "and the trio-era file is gone");
        for (String legacy : List.of(FsMetaFormat.TXT_FILE, FsMetaFormat.JSON_FILE,
                EntityBuildState.LEGACY_FINGERPRINT_FILE)) {
            Assertions.assertFalse(Files.exists(fixture.stateDirectory().resolve(legacy)),
                    "the trio era left nothing behind: " + legacy);
        }
    }

    @Test
    void aChangedFingerprintFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        // Same tree, same generation, one output-changing flag different.
        EntityBuildState.Decision decision = state.decide(fixture.config(EntityMetadataGenerator.Policy.OFF, true));

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("adapters"), decision.reason());
    }

    @Test
    void aGenerationThatMovedBackwardsFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        daemon.generation(9);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        // The daemon restarted with a fresh history: the recorded generation no longer exists.
        daemon.generation(2);
        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("before the recorded"), decision.reason());
    }

    @Test
    void aBatchTooOldReplayFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        daemon.refuse(FsMetaClient.ERROR_BATCH_TOO_OLD, "batch-too-old: no batch 1");

        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("cannot replay from generation"), decision.reason());
        Assertions.assertTrue(decision.reason().contains("batch-too-old"), decision.reason());
    }

    @Test
    void aBatchGapReplayFallsBackToAFullPass() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        daemon.refuse(FsMetaClient.ERROR_BATCH_GAP, "batch-gap: 4 has no directory");

        EntityBuildState.Decision decision = state.decide(fixture.config());

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("cannot replay from generation"), decision.reason());
    }

    // ── validation gating ────────────────────────────────────────────────────

    @Test
    void aCleanValidationLicensesANoop() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        EntityBuildState.Config config = fixture.config(EntityMetadataGenerator.Policy.REPORT, false);
        state.publish(config, EntityBuildState.ValidationOutcome.CLEAN, EntityBuildState.Selection.full());

        Assertions.assertEquals(EntityBuildState.Mode.NOOP, state.decide(config).mode());
    }

    @Test
    void aReportedValidationDoesNotLicenseANoop() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        EntityBuildState.Config config = fixture.config(EntityMetadataGenerator.Policy.REPORT, false);
        state.publish(config, EntityBuildState.ValidationOutcome.REPORTED, EntityBuildState.Selection.full());

        EntityBuildState.Decision decision = state.decide(config);

        Assertions.assertEquals(EntityBuildState.Mode.FULL, decision.mode(), decision.reason());
        Assertions.assertTrue(decision.reason().contains("validation"), decision.reason());
    }

    // ── the state format ─────────────────────────────────────────────────────

    @Test
    void theRunJsonRecordsTheInvocationAndItsDaemonGeneration() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        daemon.generation(7);
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(EntityMetadataGenerator.Policy.REPORT, false),
                EntityBuildState.ValidationOutcome.CLEAN,
                new EntityBuildState.Selection("incremental",
                        List.of("build/entity/Thing.java"), List.of("build.entity.ThingSummary")));

        JsonNode json = MAPPER.readTree(Files.readString(fixture.runFile()));

        Assertions.assertEquals(EntityBuildState.FORMAT, json.path("format").asText());
        Assertions.assertEquals(EntityMetadataGenerator.generatorIdentity(), json.path("generator").asText());
        Assertions.assertEquals(normalized(fixture.sourceRoot()), json.path("sourceRoot").asText());
        Assertions.assertEquals(normalized(fixture.projectRoot()), json.path("projectRoot").asText());
        Assertions.assertEquals("a1b2c3d4", json.path("daemon").path("id").asText());
        Assertions.assertEquals(normalized(fixture.projectRoot()), json.path("daemon").path("root").asText());
        Assertions.assertEquals(7, json.path("daemon").path("generation").asLong());
        Assertions.assertEquals("clean", json.path("validation").asText());
        Assertions.assertEquals("incremental", json.path("selection").path("mode").asText());
        Assertions.assertEquals("build/entity/Thing.java",
                json.path("selection").path("changed").get(0).asText());
        Assertions.assertEquals("build.entity.ThingSummary",
                json.path("selection").path("affected").get(0).asText());

        JsonNode fingerprint = json.path("fingerprint");
        for (String key : List.of("format", "generator", "jdk", "sourceRoot", "projectRoot",
                "metadataOutput", "javaOutput", "packages", "adapters", "dtoProjections", "mappers", "merges",
                "patchAppliers", "force", "validationPolicy")) {
            Assertions.assertTrue(fingerprint.has(key),
                    "the fingerprint must record '" + key + "', which is what decides a full pass: " + fingerprint);
        }
    }

    /** The trio is gone, not merely unused: the first recorded pass leaves three files, and none is a trio. */
    @Test
    void aRecordedPassWritesOnlyRunJson() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        fixture.state(daemon).publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN,
                EntityBuildState.Selection.full());

        List<String> files;
        try (var walk = Files.list(fixture.stateDirectory())) {
            files = walk.map(path -> path.getFileName().toString()).sorted().toList();
        }
        Assertions.assertEquals(List.of(EntityBuildState.RUN_FILE), files,
                "the state is one generation marker, not a Java-written fs-meta trio");
    }

    // ── the post-pass generation ─────────────────────────────────────────────

    /**
     * The pass wrote content, so the daemon published a generation for it; the recorded baseline is that
     * generation, not the one before, and the next run is therefore a no-op instead of replaying the pass's own
     * writes as a source change.
     */
    @Test
    void generatedWritesReceiveANewBaseline() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        // The pass's own output: the daemon publishes it as the next generation.
        daemon.publish(new FsMetaClient.Change("build/entity/ThingSummary_.java", FsMetaClient.Kind.ADDED));
        EntityBuildState.Publish published = state.publish(fixture.config(),
                EntityBuildState.ValidationOutcome.NOT_RUN,
                new EntityBuildState.Selection("incremental", List.of(), List.of("build.entity.ThingSummary")));

        Assertions.assertTrue(published.recorded(), published.problem());
        Assertions.assertEquals(1, published.generation(), "the baseline is the generation with the writes in it");
        Assertions.assertEquals(1, MAPPER.readTree(Files.readString(fixture.runFile()))
                .path("daemon").path("generation").asLong());
        Assertions.assertEquals(EntityBuildState.Mode.NOOP, state.decide(fixture.config()).mode(),
                "the pass's own output must not be replayed as a source change");
    }

    @Test
    void aPassThatWroteNothingRecordsTheCurrentGeneration() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        daemon.generation(4);
        EntityBuildState state = fixture.state(daemon);

        EntityBuildState.Publish published = state.publish(fixture.config(),
                EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        Assertions.assertTrue(published.recorded(), published.problem());
        Assertions.assertEquals(4, published.generation(),
                "an unchanged tree produces no batch, so the head it read is the truthful baseline");
        Assertions.assertEquals(EntityBuildState.Mode.NOOP, state.decide(fixture.config()).mode());
    }

    /** A daemon that cannot be read at all leaves the previous baseline exactly as it was, and says why. */
    @Test
    void anUnreadableDaemonLeavesTheOldState() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        daemon.generation(5);
        EntityBuildState state = fixture.state(daemon);
        state.publish(fixture.config(), EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());
        byte[] before = Files.readAllBytes(fixture.runFile());

        daemon.generation(6);
        daemon.failHead(new IOException("the daemon closed the socket"));
        EntityBuildState.Publish published = state.publish(fixture.config(),
                EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        Assertions.assertFalse(published.recorded());
        Assertions.assertTrue(published.problem().contains("could not confirm the pass"), published.problem());
        Assertions.assertArrayEquals(before, Files.readAllBytes(fixture.runFile()),
                "a guessed generation would hide the next run, so the old one stays");
    }

    @Test
    void noDaemonPublishWritesNoStateAndNoTrio() throws Exception {
        Fixture fixture = Fixture.create();
        FakeDaemon daemon = fixture.daemon();
        daemon.detach("no fsmeta root list at " + fixture.projectRoot().resolve("roots.json"));

        EntityBuildState.Publish published = fixture.state(daemon).publish(fixture.config(),
                EntityBuildState.ValidationOutcome.NOT_RUN, EntityBuildState.Selection.full());

        Assertions.assertFalse(published.recorded());
        Assertions.assertTrue(published.problem().contains("no fs-meta daemon"), published.problem());
        Assertions.assertFalse(Files.exists(fixture.runFile()), "no daemon, no baseline");
        Assertions.assertFalse(Files.exists(fixture.stateDirectory()), "and no state directory is created");
    }

    // ── the subfolder filter ─────────────────────────────────────────────────

    /**
     * The daemon serves the project root; this module asks for its own source-root subfolder with the protocol's
     * include pattern, which is the whole point of not creating a second root.
     */
    @Test
    void theDaemonFilterIsTheSourceRootRelativeToTheProjectRoot() {
        Assertions.assertEquals(List.of("module/src/main/java/**"),
                FsMetaDaemonState.includePatterns(Path.of("/project"), Path.of("/project/module/src/main/java")));
        Assertions.assertEquals(List.of(),
                FsMetaDaemonState.includePatterns(Path.of("/project"), Path.of("/project")),
                "a source root that is the daemon root needs no narrowing");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> FsMetaDaemonState.includePatterns(Path.of("/project"), Path.of("/elsewhere/src/main/java")),
                "a source root outside the daemon root cannot be described by a filter, and must be refused");

        // The one fallback that exists for a daemon predating its own documented filter applies the same
        // wildmatch locally, so the patterns must actually select what the server would have selected.
        PathFilter fallback = PathFilter.of(
                FsMetaDaemonState.includePatterns(Path.of("/project"), Path.of("/project/module/src/main/java")),
                List.of("**/.jcodebuddy/**"));
        Assertions.assertTrue(fallback.shouldInclude("module/src/main/java/build/entity/Thing.java"),
                "a source under the module's source root is the pass's input");
        Assertions.assertFalse(fallback.shouldInclude("module/src/test/java/build/entity/Thing.java"),
                "a sibling module subfolder is not");
        Assertions.assertFalse(fallback.shouldInclude("module/src/main/java/.jcodebuddy/Thing.java"),
                "and tool state inside it never re-enters the loop");
    }

    @Test
    void aChangeOutsideTheSubfolderIsDropped() {
        FsMetaClient.Change inside = new FsMetaClient.Change(
                "module/src/main/java/build/entity/Thing.java", FsMetaClient.Kind.CHANGED);
        FsMetaClient.Change relative = FsMetaDaemonState.relativize(inside, "module/src/main/java");
        Assertions.assertEquals("build/entity/Thing.java", relative.name(),
                "the pass needs source-root-relative names");
        Assertions.assertEquals(FsMetaClient.Kind.CHANGED, relative.kind());

        Assertions.assertNull(FsMetaDaemonState.relativize(
                        new FsMetaClient.Change("module/src/test/java/build/entity/Thing.java",
                                FsMetaClient.Kind.CHANGED),
                        "module/src/main/java"),
                "another module subfolder is not this pass's input");

        Assertions.assertEquals(inside, FsMetaDaemonState.relativize(inside, ""),
                "when the source root is the daemon root the name is already relative");
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

    @Test
    void aReportPathNamesTheModuleBeforeTheMarkerExists() throws Exception {
        // A consumer project has no `.jcodebuddy` before its first pass. The report path still says
        // where the module is, and using it keeps the first and second pass on the same state.
        Path module = Files.createTempDirectory("entity-build-first-pass");
        Path sourceRoot = module.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Path report = module.resolve(".jcodebuddy/metadata/entity");

        Assertions.assertEquals(module.resolve(".jcodebuddy/cache/entity-build"),
                EntityBuildState.stateDirectory(sourceRoot, report),
                "the report path names the module even before `.jcodebuddy` exists");
    }

    // ── the CLI wiring, in-process, with an injected daemon ──────────────────

    /**
     * A first {@code --incremental} run has no state, so it must run the normal pass and record the daemon
     * generation the pass left behind — the baseline the next run answers from.
     */
    @Test
    void anIncrementalFirstRunFallsBackToFullAndRecordsTheBaseline() throws Exception {
        Fixture fixture = Fixture.create();
        writeMinimalEntitySources(fixture.sourceRoot());
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState.setDaemonFactory(path -> daemon);
        Path recordFile = fixture.moduleRoot().resolve(".jcodebuddy/metadata/first.json");

        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
                "--packages", "build.entity",
                "--incremental",
                "--run-record", recordFile.toString() });

        JsonNode record = MAPPER.readTree(Files.readString(recordFile));
        Assertions.assertEquals("full", record.path("mode").asText(),
                "no state means the reference pass, not a no-op");
        Assertions.assertTrue(record.path("fallbackReason").asText().contains("no build state"),
                record.path("fallbackReason").asText());
        Assertions.assertTrue(record.path("buildState").path("recorded").asBoolean(),
                "a successful full pass records the baseline it read next time: " + record);
        Assertions.assertTrue(Files.isRegularFile(fixture.runFile()));
        Assertions.assertFalse(Files.exists(fixture.stateDirectory().resolve(FsMetaFormat.META_FILE)),
                "the state is run.json, never the trio it replaced");
        Assertions.assertTrue(Files.exists(fixture.sourceRoot().resolve("build/entity/ThingSummary_.java")),
                "and the normal pass still generated the view");
    }

    /**
     * The hard requirement at the CLI boundary: with no daemon an {@code --incremental} run is a full pass with
     * an actionable reason, and it writes no baseline at all — not {@code run.json}, not the trio it replaced.
     * A {@code run.json} written without a daemon would be a baseline that hides the next run.
     */
    @Test
    void anIncrementalCliRunWithNoDaemonFallsBackToFullAndWritesNoState() throws Exception {
        Fixture fixture = Fixture.create();
        writeMinimalEntitySources(fixture.sourceRoot());
        FakeDaemon daemon = fixture.daemon();
        daemon.detach("no fsmeta root list at " + fixture.projectRoot().resolve("roots.json"));
        EntityBuildState.setDaemonFactory(path -> daemon);
        Path recordFile = fixture.moduleRoot().resolve(".jcodebuddy/metadata/nodaemon.json");

        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
                "--packages", "build.entity",
                "--incremental",
                "--run-record", recordFile.toString() });

        JsonNode record = MAPPER.readTree(Files.readString(recordFile));
        Assertions.assertEquals("full", record.path("mode").asText(),
                "no daemon means no no-op, whatever the tree looks like");
        Assertions.assertTrue(record.path("fallbackReason").asText().contains("no fs-meta daemon"),
                record.path("fallbackReason").asText());
        Assertions.assertFalse(record.path("buildState").path("recorded").asBoolean(),
                "the record says the baseline was not written: " + record);
        Assertions.assertFalse(Files.exists(fixture.runFile()), "no daemon, no baseline");
        for (String legacy : List.of(FsMetaFormat.META_FILE, FsMetaFormat.TXT_FILE, FsMetaFormat.JSON_FILE,
                EntityBuildState.LEGACY_FINGERPRINT_FILE)) {
            Assertions.assertFalse(Files.exists(fixture.stateDirectory().resolve(legacy)),
                    "and no trio-era file either: " + legacy);
        }
        Assertions.assertTrue(Files.exists(fixture.sourceRoot().resolve("build/entity/ThingSummary_.java")),
                "the full pass still ran and generated the view");
    }

    /**
     * The E2 gate in one test: a full pass, then an {@code --incremental} pass over the same tree, does no work,
     * writes no generated output and rewrites no state.
     */
    @Test
    void anIncrementalRunOverAnUnchangedTreeIsANoopAndWritesNothing() throws Exception {
        Fixture fixture = Fixture.create();
        writeMinimalEntitySources(fixture.sourceRoot());
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState.setDaemonFactory(path -> daemon);
        Path firstRecord = fixture.moduleRoot().resolve(".jcodebuddy/metadata/first.json");
        Path secondRecord = fixture.moduleRoot().resolve(".jcodebuddy/metadata/second.json");

        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
                "--packages", "build.entity",
                "--run-record", firstRecord.toString() });

        List<String> generatedBefore = javaSnapshot(fixture.sourceRoot());
        Assertions.assertTrue(generatedBefore.size() > 4,
                "the first pass must have written at least one generated .java file, or the no-op proves nothing");
        byte[] stateBefore = Files.readAllBytes(fixture.runFile());

        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
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

        Assertions.assertEquals(generatedBefore, javaSnapshot(fixture.sourceRoot()),
                "a no-op must not change generated output");
        Assertions.assertArrayEquals(stateBefore, Files.readAllBytes(fixture.runFile()),
                "a no-op must not rewrite the generation marker");
    }

    /**
     * A source edit published by the daemon becomes an incremental decision, and the pass that runs records the
     * generation its own writes produced — so a third run no-ops.
     */
    @Test
    void aDaemonPublishedSourceChangeDrivesTheCliIncrementalPass() throws Exception {
        Fixture fixture = Fixture.create();
        writeMinimalEntitySources(fixture.sourceRoot());
        FakeDaemon daemon = fixture.daemon();
        EntityBuildState.setDaemonFactory(path -> daemon);
        Path firstRecord = fixture.moduleRoot().resolve(".jcodebuddy/metadata/first.json");
        Path secondRecord = fixture.moduleRoot().resolve(".jcodebuddy/metadata/second.json");
        Path thirdRecord = fixture.moduleRoot().resolve(".jcodebuddy/metadata/third.json");

        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
                "--packages", "build.entity",
                "--run-record", firstRecord.toString() });

        daemon.publish(new FsMetaClient.Change("build/entity/ThingSummary.java", FsMetaClient.Kind.CHANGED));
        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
                "--packages", "build.entity",
                "--incremental",
                "--run-record", secondRecord.toString() });

        JsonNode second = MAPPER.readTree(Files.readString(secondRecord));
        Assertions.assertEquals("incremental", second.path("mode").asText(), second.toString());
        Assertions.assertEquals("build/entity/ThingSummary.java",
                second.path("changed").get(0).asText());
        Assertions.assertTrue(second.path("buildState").path("recorded").asBoolean(), second.toString());
        Assertions.assertEquals(1, second.path("buildState").path("generation").asLong());

        EntityMetadataGenerator.main(new String[] {
                fixture.sourceRoot().toString(), fixture.metadata().toString(),
                "--java-out", fixture.sourceRoot().toString(),
                "--packages", "build.entity",
                "--incremental",
                "--run-record", thirdRecord.toString() });

        Assertions.assertEquals("noop", MAPPER.readTree(Files.readString(thirdRecord)).path("mode").asText(),
                "the second pass's own writes must be inside the generation it recorded");
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

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * The in-process daemon timeline: a generation counter and the batches published against it.
     *
     * <p>It is deliberately a faithful model of the protocol's contract, not a shortcut: {@code replay} refuses
     * with the protocol's own error codes, {@code head} can fail like a dead socket, and a batch is only visible
     * after the generation that carries it. What it cannot model is the server-side filter, which is asserted in
     * the watch module and against the real daemon.</p>
     */
    private static final class FakeDaemon implements EntityBuildState.Daemon {

        private final Path projectRoot;
        private final Path sourceRoot;
        private final Map<Long, List<FsMetaClient.Change>> batches = new LinkedHashMap<>();
        private final Path socket;
        private boolean attached = true;
        private String problem = "";
        private long generation;
        private int refusalCode;
        private String refusalMessage = "";
        private IOException headFailure;

        FakeDaemon(Path projectRoot, Path sourceRoot) {
            this.projectRoot = projectRoot;
            this.sourceRoot = sourceRoot;
            this.socket = projectRoot.resolve("roots").resolve("a1b2c3d4.sock");
        }

        /** The daemon published one batch; the generation advances and the changes are replayable after it. */
        void publish(FsMetaClient.Change... changes) {
            generation++;
            batches.put(generation, List.of(changes));
        }

        void generation(long value) {
            generation = value;
        }

        void detach(String why) {
            attached = false;
            problem = why;
        }

        void refuse(int code, String message) {
            refusalCode = code;
            refusalMessage = message;
        }

        void failHead(IOException failure) {
            headFailure = failure;
        }

        @Override
        public FsMetaDaemonDiscovery.Attach discover(Path path) {
            if (!attached) {
                return new FsMetaDaemonDiscovery.Attach(null, problem);
            }
            return new FsMetaDaemonDiscovery.Attach(new FsMetaDaemonDiscovery.Location(
                    projectRoot.resolve("run"), projectRoot.resolve("config/fsmeta/roots.json"),
                    "a1b2c3d4", projectRoot, socket), "");
        }

        @Override
        public long head(FsMetaDaemonDiscovery.Location location) throws IOException {
            if (headFailure != null) {
                throw headFailure;
            }
            return generation;
        }

        @Override
        public Replay replay(FsMetaDaemonDiscovery.Location location, long fromBatch) throws IOException {
            if (refusalCode != 0) {
                throw new FsMetaClient.ProtocolException(refusalCode, refusalMessage);
            }
            List<FsMetaClient.Change> changes = new ArrayList<>();
            for (Map.Entry<Long, List<FsMetaClient.Change>> batch : batches.entrySet()) {
                if (batch.getKey() > fromBatch) {
                    changes.addAll(batch.getValue());
                }
            }
            return new Replay(generation, changes);
        }

        @Override
        public long acknowledge(FsMetaDaemonDiscovery.Location location, long base) throws IOException {
            if (headFailure != null) {
                throw headFailure;
            }
            return Math.max(base, generation);
        }
    }
}
