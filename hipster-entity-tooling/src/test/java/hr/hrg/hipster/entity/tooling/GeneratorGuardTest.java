package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The layout rule, as an executable assertion: generated Java never lands in a {@code .jcodebuddy/}
 * directory.
 *
 * <p>The rule itself is DEC-026 / {@code AGENTS.md} section 2, and the reason it needs a guard rather
 * than a paragraph is recorded in {@code hipster-entity-example/codebuddy.md} section 6.1: a tooling
 * build that predates {@code --java-out} ignores the flag, treats it as a positional argument, and
 * writes generated source into positional 2 — the module's metadata directory. Generation in the
 * <em>right</em> place is asserted by {@code ExampleRegenerationTest}; this class asserts the
 * <em>wrong</em> place is refused, at every entry point — CLI, script, watcher — rather than only on
 * whatever path happened to be exercised last.</p>
 */
class GeneratorGuardTest {

    @BeforeEach
    void resetProcessGlobalKnobs() {
        // The generator's knobs are process-global because the CLI and the library callers share one
        // flag surface; a test that leaves them set changes the next test's pass.
        EntityMetadataGenerator.setGenerationPackages(List.of());
        EntityMetadataGenerator.setMapperRequests(List.of());
        EntityMetadataGenerator.setGenerateAdapters(false);
    }

    /** A minimal but real entity package: a marker, and one view derived from it. */
    private static Path writeMinimalEntitySource() throws IOException {
        Path sourceRoot = Files.createTempDirectory("guard-source").resolve("src/main/java");
        Path pkg = sourceRoot.resolve("guard/entity");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("ThingEntity.java"),
                "package guard.entity;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface ThingEntity extends EntityBase<Long> {}\n");
        Files.writeString(pkg.resolve("ThingSummary.java"),
                "package guard.entity;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface ThingSummary extends ThingEntity {\n"
                        + "  String name();\n"
                        + "}\n");
        return sourceRoot;
    }

    /**
     * The exact shape of the stale-artifact failure: Java output resolves to the metadata directory.
     */
    @Test
    void generatedJavaIsRefusedUnderAJcodebuddyDirectory() throws Exception {
        Path sourceRoot = writeMinimalEntitySource();
        Path reportDir = Files.createTempDirectory("guard-report");
        Path javaOut = reportDir.resolve(".jcodebuddy/metadata/entity");

        IOException failure = Assertions.assertThrows(IOException.class,
                () -> EntityMetadataGenerator.generate(sourceRoot, javaOut, javaOut),
                "writing generated .java under .jcodebuddy/ must be refused, not performed");

        String message = failure.getMessage();
        Assertions.assertTrue(message.contains(".jcodebuddy"),
                "the message must name the directory it refused: " + message);
        Assertions.assertTrue(message.contains("--java-out"),
                "and name the flag whose absence caused it: " + message);
        Assertions.assertFalse(Files.exists(javaOut.resolve("guard/entity/ThingSummary_.java")),
                "and nothing may have been written into it");
    }

    /**
     * The two-argument form is the one a stale artifact effectively runs: positional 2 is both the
     * report directory and the Java output. It is refused for the same reason.
     */
    @Test
    void theTwoArgumentFormIntoAJcodebuddyDirectoryIsRefusedToo() throws Exception {
        Path sourceRoot = writeMinimalEntitySource();
        Path metadataDir = Files.createTempDirectory("guard-marker").resolve(".jcodebuddy/metadata/entity");
        Files.createDirectories(metadataDir);

        Assertions.assertThrows(IOException.class,
                () -> EntityMetadataGenerator.generate(sourceRoot, metadataDir),
                "generate(sourceRoot, metadataDir) means javaOutputRoot == metadataDir, so it is the "
                        + "same mistake through a different door");
    }

    /**
     * The guard must not cost anything when the output is where it belongs — a {@code .jcodebuddy}
     * path component only, never a substring of a legitimate path.
     */
    @Test
    void generatedJavaIsStillWrittenBesideTheViewWhenJavaOutIsTheSourceRoot() throws Exception {
        Path sourceRoot = writeMinimalEntitySource();
        Path reportDir = Files.createTempDirectory("guard-report-ok");

        EntityMetadataGenerator.generate(sourceRoot, reportDir, sourceRoot);

        Path generated = sourceRoot.resolve("guard/entity/ThingSummary_.java");
        Assertions.assertTrue(Files.exists(generated),
                "with --java-out pointing at the source root, the field enum is regenerated in place");
        Assertions.assertTrue(Files.exists(reportDir.resolve("Thing.metadata.json")),
                "and the entity JSON goes to the report directory");
    }
}
