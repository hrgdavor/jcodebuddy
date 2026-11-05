// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The committed converter manifest is what a pass writes — plan step 6.9 / DEC-006.
 *
 * <p>The manifest is machinery output, not an authored allowlist: the pass writes it into the module's
 * <strong>tracked</strong> {@code .jcodebuddy/conf/}, and a committed copy that differs from a fresh pass is a
 * failure the way a stale committed enum is. This test copies the example's sources to a temporary tree, runs the
 * same mapper request {@code scripts/gen.js} makes, and compares the generated manifest with the committed one.
 * Line endings are normalised because a checkout may store either; the manifest's own format uses {@code \n}.</p>
 */
@Tag("generator")
class ExampleConverterManifestTest {

    @TempDir
    Path tempDir;

    private static final List<String> PACKAGES =
            List.of("hr.hrg.hipster.entityexample.person.entity");

    @Test
    void theCommittedExampleManifestIsWhatAPassWrites() throws Exception {
        Path repo = CompileHarness.findRepoRoot();
        Path example = repo.resolve("hipster-entity/hipster-entity-example");
        Path exampleSource = example.resolve("src/main/java");
        Path committedFile = example.resolve(".jcodebuddy/conf/converters.json");

        Path tree = tempDir.resolve("example");
        Path source = tree.resolve("src/main/java");
        Path metadata = tree.resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(source);
        Files.createDirectories(metadata);
        for (Path file : CompileHarness.javaSourcesUnder(exampleSource)) {
            Path target = source.resolve(exampleSource.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
        }

        EntityMetadataGenerator.setGenerationPackages(PACKAGES);
        EntityMetadataGenerator.setMapperRequests(List.of("PersonSummary:PersonDto"));
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
        try {
            EntityMetadataGenerator.generate(source, metadata, source, new DivergenceReporter());
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
            EntityMetadataGenerator.setMapperRequests(List.of());
        }

        String generated = normalise(Files.readString(tree.resolve(".jcodebuddy/conf/converters.json")));
        String committed = normalise(Files.readString(committedFile));
        Assertions.assertEquals(committed, generated,
                "the committed converter manifest must be exactly what a pass writes; regenerate with "
                        + "`bun scripts/gen.js` rather than editing it");

        Path mapper = Path.of("hr/hrg/hipster/entityexample/person/entity/PersonSummaryToPersonDtoMapper.java");
        Assertions.assertEquals(
                normalise(Files.readString(exampleSource.resolve(mapper))),
                normalise(Files.readString(source.resolve(mapper))),
                "the mapper the manifest describes is generated too, so its committed copy must be the pass's output");
    }

    private static String normalise(String text) {
        return text.replace("\r\n", "\n");
    }
}
