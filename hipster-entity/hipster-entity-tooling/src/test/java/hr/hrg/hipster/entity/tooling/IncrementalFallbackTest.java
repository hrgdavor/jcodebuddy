package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * E6's generated-file row: a hand edit to a generated artifact cannot be repaired by re-emitting only
 * the views whose <em>source</em> inputs changed, because the edited file is itself an input of the pass.
 * The incremental request must fall back to a full pass and say why.
 */
@Tag("generator")
class IncrementalFallbackTest {

    @Test
    void aHandEditedGeneratedFileForcesFullAndRestoresIt() throws Exception {
        ExamplePassFixture fixture = ExamplePassFixture.create();
        Path sourceRoot = fixture.sourceRoot();
        String relative = "hr/hrg/hipster/entityexample/person/entity/PersonDetails_.java";
        Path generated = sourceRoot.resolve(relative);
        byte[] original = Files.readAllBytes(generated);
        Files.writeString(generated, Files.readString(generated) + "\n// hand edit\n");

        EntityMetadataGenerator.GenerationReport report = EntityMetadataGenerator.generateIncremental(
                sourceRoot, fixture.reportDir(), sourceRoot, Set.of(relative), new DivergenceReporter());

        Assertions.assertEquals("full", report.mode(), report.fallbackReason());
        Assertions.assertTrue(report.fallbackReason() != null
                        && report.fallbackReason().contains("generated source file"),
                "the fallback names the generated-file rule: " + report.fallbackReason());
        Assertions.assertArrayEquals(original, Files.readAllBytes(generated),
                "the full pass restored the generated artifact");
    }
}
