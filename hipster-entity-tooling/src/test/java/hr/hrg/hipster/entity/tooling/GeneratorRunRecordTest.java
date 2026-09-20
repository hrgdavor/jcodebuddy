package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The opt-in run record ({@code --run-record <file>}).
 *
 * <p>It exists so a regenerated tree can be interrogated after the fact: which generator revision
 * wrote it, from which artifact, with which flags, and what it reported. That is the question a
 * reviewer of a regenerated diff asks, and the one the stale-artifact trap made unanswerable — the
 * build that ran the wrong generator looked exactly like the build that ran the right one.</p>
 *
 * <p>Two properties matter and are asserted here: it is <strong>opt-in</strong> (a pass without the
 * flag writes no record, so library callers and every pre-existing test are untouched), and it is
 * written for a <strong>failed</strong> pass as well as a successful one, because a failure is the run
 * somebody will want to read.</p>
 */
class GeneratorRunRecordTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetProcessGlobalKnobs() {
        EntityMetadataGenerator.setGenerationPackages(List.of());
        EntityMetadataGenerator.setMapperRequests(List.of());
        EntityMetadataGenerator.setGenerateAdapters(false);
    }

    private static Path writeMinimalEntitySource() throws IOException {
        Path sourceRoot = Files.createTempDirectory("record-source").resolve("src/main/java");
        Path pkg = sourceRoot.resolve("rec/entity");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("ThingEntity.java"),
                "package rec.entity;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface ThingEntity extends EntityBase<Long> {}\n");
        Files.writeString(pkg.resolve("ThingSummary.java"),
                "package rec.entity;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface ThingSummary extends ThingEntity {\n"
                        + "  String name();\n"
                        + "}\n");
        return sourceRoot;
    }

    @Test
    void aSuccessfulPassRecordsWhatItRanWith() throws Exception {
        Path sourceRoot = writeMinimalEntitySource();
        Path reportDir = Files.createTempDirectory("record-report");
        Path recordFile = reportDir.resolve("generation.json");

        EntityMetadataGenerator.main(new String[] {
                sourceRoot.toString(), reportDir.toString(),
                "--java-out", sourceRoot.toString(),
                "--packages", "rec.entity",
                "--validate",
                "--run-record", recordFile.toString() });

        Assertions.assertTrue(Files.exists(recordFile), "the record is written where it was asked for");
        JsonNode record = MAPPER.readTree(Files.readString(recordFile));

        Assertions.assertEquals("ok", record.path("status").asText(),
                "a pass that returned normally is recorded as ok");
        Assertions.assertEquals(EntityMetadataGenerator.GENERATOR_NAME,
                record.path("generator").asText(), "the record names the generator");
        Assertions.assertFalse(record.path("classpath").asText().isBlank(),
                "and where its classes came from — the field that distinguishes an installed artifact "
                        + "from the reactor's output");
        Assertions.assertEquals("REPORT", record.path("validate").asText(),
                "the validation policy that was in force");
        Assertions.assertEquals(0, record.path("validationIssues").asInt(),
                "the example tree under test is clean, so the recorded count is zero");
        Assertions.assertEquals(sourceRoot.toString(), record.path("sourceRoot").asText(),
                "and the roots the pass resolved, so the record is checkable against the invocation");
        Assertions.assertEquals(reportDir.toString(), record.path("reportDir").asText());
        Assertions.assertEquals(1, record.path("packages").size(), "the generation filter is recorded");
        Assertions.assertEquals("rec.entity", record.path("packages").get(0).asText());
        Assertions.assertTrue(record.path("divergences").isArray(),
                "and the divergence report, present even when it is empty");
    }

    /**
     * The failure this whole change exists for, recorded: the pass refuses to write generated Java into
     * a metadata directory, and the record says so.
     */
    @Test
    void aRefusedPassStillRecordsWhyItFailed() throws Exception {
        Path sourceRoot = writeMinimalEntitySource();
        Path metadataDir = Files.createTempDirectory("record-marker").resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(metadataDir);
        Path recordFile = metadataDir.getParent().resolve("generation.json");

        Assertions.assertThrows(IOException.class, () -> EntityMetadataGenerator.main(new String[] {
                sourceRoot.toString(), metadataDir.toString(),
                "--run-record", recordFile.toString() }),
                "the guard must fail the pass rather than write into the metadata root");

        Assertions.assertTrue(Files.exists(recordFile),
                "and the record is written anyway: a failed pass is the one a reader wants");
        JsonNode record = MAPPER.readTree(Files.readString(recordFile));
        Assertions.assertEquals("failed", record.path("status").asText());
        Assertions.assertTrue(record.path("failure").asText().contains(".jcodebuddy"),
                "with the reason, naming the directory it refused: " + record.path("failure").asText());
    }

    @Test
    void withoutTheFlagNoRecordIsWritten() throws Exception {
        Path sourceRoot = writeMinimalEntitySource();
        Path reportDir = Files.createTempDirectory("record-absent");

        EntityMetadataGenerator.main(new String[] {
                sourceRoot.toString(), reportDir.toString(),
                "--java-out", sourceRoot.toString() });

        try (var files = Files.list(reportDir)) {
            Assertions.assertEquals(List.of("Thing.metadata.json"),
                    files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "the pass writes its metadata and nothing else: the record is opt-in, so library "
                            + "callers and existing tests see exactly the output they saw before");
        }
    }
}
