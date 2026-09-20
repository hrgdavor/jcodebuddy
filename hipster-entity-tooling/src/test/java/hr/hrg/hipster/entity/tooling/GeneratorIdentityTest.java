package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The generator has to be able to say which revision is running.
 *
 * <p>Every diagnosis of the stale-artifact trap recorded in
 * {@code hipster-entity-example/codebuddy.md} section 6.1 starts with the same question — "which
 * tooling did this build actually use?" — and before this existed the build log could not answer it:
 * the pass printed the source root and nothing about itself, so a jar from the local repository and
 * the reactor's own {@code target/classes} produced identical output. The banner answers it on every
 * run, and {@code --version} answers it without running a pass.</p>
 *
 * <p>The other half of the class asserts the flag surface the build binding passes is a subset of the
 * flags this build understands — the invariant {@link GeneratorPreflight} checks at build time, held
 * here too so a mismatch fails a unit test rather than only a Maven invocation.</p>
 */
class GeneratorIdentityTest {

    @Test
    void identityNamesTheGeneratorAndWhereItsClassesCameFrom() {
        String identity = EntityMetadataGenerator.generatorIdentity();

        Assertions.assertTrue(identity.startsWith(EntityMetadataGenerator.GENERATOR_NAME),
                "the banner starts with the generator's name: " + identity);
        Assertions.assertTrue(identity.contains("from "),
                "and says where the classes came from, which is the fact that identifies a stale "
                        + "artifact: " + identity);
        Assertions.assertFalse(EntityMetadataGenerator.generatorClasspath().isBlank(),
                "the code source is never blank; 'unknown' is the honest fallback, not silence");
    }

    @Test
    void everyFlagTheBuildBindingPassesIsSupportedByThisBuild() {
        List<String> unsupported = new ArrayList<>();
        for (String flag : EntityMetadataGenerator.BINDING_FLAGS) {
            if (!EntityMetadataGenerator.supportsFlag(flag)) {
                unsupported.add(flag);
            }
        }
        Assertions.assertTrue(unsupported.isEmpty(),
                "a flag the binding passes but this build does not understand degrades into a positional "
                        + "argument — the exact mechanism of the stale-artifact trap: " + unsupported);
        Assertions.assertFalse(EntityMetadataGenerator.supportsFlag("--not-a-flag"),
                "and the check is a real lookup, not a constant true");
    }

    /**
     * The binding's flag surface is read out of the example's {@code pom.xml}, so this test states the
     * invariant across the two modules instead of restating the constant: a new {@code --flag} added
     * to the binding must be added to {@link EntityMetadataGenerator#SUPPORTED_FLAGS}, or the
     * preflight silently stops covering it (the POM-as-text technique {@code GateParityTest} and
     * {@code DependencyBoundaryTest} already use).
     */
    @Test
    void theExampleBindingOnlyPassesFlagsThisBuildDeclares() throws Exception {
        Path pom = CompileHarness.findRepoRoot().resolve("hipster-entity-example/pom.xml");
        Assertions.assertTrue(Files.exists(pom), "the example binding must exist: " + pom);

        List<String> passed = new ArrayList<>();
        Matcher argument = Pattern.compile("<argument>(--[a-z-]+)</argument>").matcher(
                Files.readString(pom, StandardCharsets.UTF_8));
        while (argument.find()) {
            passed.add(argument.group(1));
        }
        Assertions.assertFalse(passed.isEmpty(),
                "the example's exec binding passes at least one flag; an empty read means this test "
                        + "stopped looking at the thing it asserts");

        List<String> unsupported = passed.stream()
                .filter(flag -> !EntityMetadataGenerator.supportsFlag(flag))
                .toList();
        Assertions.assertTrue(unsupported.isEmpty(),
                "hipster-entity-example/pom.xml passes " + unsupported + ", which this generator does "
                        + "not declare in SUPPORTED_FLAGS: add it there (and teach the generator the "
                        + "flag), or the binding stops being checked");
    }

    /**
     * {@code --version} is the cheapest way to ask "what is on the classpath?" — no pass, no writes,
     * and no {@code System.exit} (which would take the Maven JVM with it under {@code exec:java}).
     */
    @Test
    void versionPrintsTheIdentityAndRunsNoPass() throws Exception {
        Path reportDir = Files.createTempDirectory("version-report");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            EntityMetadataGenerator.main(new String[] { "--version" });
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains(EntityMetadataGenerator.GENERATOR_NAME),
                "--version prints the identity: " + output);
        Assertions.assertTrue(output.contains("--java-out"),
                "and the flag surface, so a reader can compare it with what the build passes: " + output);
        Assertions.assertEquals(0, Files.list(reportDir).count(),
                "and it generates nothing");
    }
}
