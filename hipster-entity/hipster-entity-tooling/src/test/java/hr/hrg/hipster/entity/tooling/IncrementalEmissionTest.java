package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * E4 of {@code plans/hipster-entity-mvp.md}: a modified source emits only the views that name it.
 *
 * <p>The fixture is a real example copy with a full pass already run. The test adds one accessor to the
 * single simple view {@code PersonCreateForm}, asks the incremental pass for that one source path, and
 * asserts three things: the report names exactly that view, its field enum carries the new accessor, and
 * an unrelated generated file was not rewritten at all (the sentinel mtime survives). It then runs a full
 * pass over the same tree and compares every {@code .java} byte, which is E4's correctness contract.</p>
 */
@Tag("generator")
class IncrementalEmissionTest {

    private static final String VIEW = "hr/hrg/hipster/entityexample/person/entity/PersonDetails.java";
    private static final String OWN_ENUM =
            "hr/hrg/hipster/entityexample/person/entity/PersonDetails_.java";
    private static final String OTHER_ENUM =
            "hr/hrg/hipster/entityexample/person/entity/PersonCreateForm_.java";

    @Test
    void oneEditedViewEmitsOnlyThatViewAndMatchesAFullPass() throws Exception {
        ExamplePassFixture fixture = ExamplePassFixture.create();
        Path sourceRoot = fixture.sourceRoot();
        Path view = sourceRoot.resolve(VIEW);
        String source = Files.readString(view);
        Files.writeString(view, source.replace("String email();",
                "String email();\n    String nickname();"));

        // A sentinel that no correct incremental pass may touch. The date is deliberately ancient so
        // an accidental write is visible even on a filesystem with coarse timestamps.
        FileTime sentinel = FileTime.fromMillis(946684800000L); // 2000-01-01T00:00:00Z
        Path other = sourceRoot.resolve(OTHER_ENUM);
        Files.setLastModifiedTime(other, sentinel);
        FileTime otherBefore = Files.getLastModifiedTime(other);

        EntityMetadataGenerator.GenerationReport report = EntityMetadataGenerator.generateIncremental(
                sourceRoot, fixture.reportDir(), sourceRoot, Set.of(VIEW), new DivergenceReporter());

        Assertions.assertEquals("incremental", report.mode());
        Assertions.assertEquals(1, report.emitted(),
                "only the edited view is emitted: " + report.affected());
        Assertions.assertTrue(report.reused() > 0, "the rest of the views are reused");
        Assertions.assertTrue(report.affected().stream().anyMatch(name -> name.endsWith("PersonDetails")),
                "the affected list names the view: " + report.affected());

        String own = Files.readString(sourceRoot.resolve(OWN_ENUM));
        Assertions.assertTrue(own.contains("nickname"), "the edited view's field enum was regenerated");
        Assertions.assertEquals(otherBefore, Files.getLastModifiedTime(other),
                "an unrelated view's generated file must not be rewritten");

        Map<Path, byte[]> incremental = snapshot(sourceRoot);
        EntityMetadataGenerator.generate(sourceRoot, fixture.reportDir(), sourceRoot);
        Map<Path, byte[]> full = snapshot(sourceRoot);
        Assertions.assertEquals(full.keySet(), incremental.keySet(), "same generated file set");
        for (Path file : full.keySet()) {
            Assertions.assertArrayEquals(full.get(file), incremental.get(file),
                    "the incremental tree must be byte-identical to a full pass: " + file);
        }
    }

    @Test
    void aMapperEndpointStaysIncrementalAndTheMapperIsReEmitted() throws Exception {
        ExamplePassFixture fixture = ExamplePassFixture.create();
        Path sourceRoot = fixture.sourceRoot();
        EntityMetadataGenerator.setGenerateDtoProjections(true);
        EntityMetadataGenerator.setMapperRequests(List.of("PersonSummary:PersonDto"));
        try {
            EntityMetadataGenerator.generate(sourceRoot, fixture.reportDir(), sourceRoot);
            Path mapper = sourceRoot.resolve(
                    "hr/hrg/hipster/entityexample/person/entity/PersonSummaryToPersonDtoMapper.java");
            byte[] mapperBefore = Files.readAllBytes(mapper);

            Path view = sourceRoot.resolve(
                    "hr/hrg/hipster/entityexample/person/entity/PersonSummary.java");
            String source = Files.readString(view);
            Files.writeString(view, source.replace("    // generated boilerplate, for better dev experience",
                    "    String nickname();\n\n    // generated boilerplate, for better dev experience"));

            EntityMetadataGenerator.GenerationReport report = EntityMetadataGenerator.generateIncremental(
                    sourceRoot, fixture.reportDir(), sourceRoot,
                    Set.of("hr/hrg/hipster/entityexample/person/entity/PersonSummary.java"),
                    new DivergenceReporter());
            Assertions.assertEquals("incremental", report.mode());
            Assertions.assertFalse(java.util.Arrays.equals(mapperBefore, Files.readAllBytes(mapper)),
                    "the mapper whose target/source view changed is re-emitted");

            Map<Path, byte[]> incremental = snapshot(sourceRoot);
            EntityMetadataGenerator.generate(sourceRoot, fixture.reportDir(), sourceRoot);
            Map<Path, byte[]> full = snapshot(sourceRoot);
            for (Path file : full.keySet()) {
                Assertions.assertArrayEquals(full.get(file), incremental.get(file),
                        "the incremental tree must be byte-identical to a full pass: " + file);
            }
        } finally {
            EntityMetadataGenerator.setGenerateDtoProjections(false);
            EntityMetadataGenerator.setMapperRequests(List.of());
        }
    }

    private static Map<Path, byte[]> snapshot(Path sourceRoot) throws Exception {
        Map<Path, byte[]> files = new TreeMap<>();
        try (var walk = Files.walk(sourceRoot)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                files.put(sourceRoot.relativize(file), Files.readAllBytes(file));
            }
        }
        return files;
    }
}
