package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link GeneratorPreflight} guards a side-car invocation, so nothing else in a normal test run
 * would exercise it — the same gap {@code GateParityTest} exists to close for the build scripts.
 * This class is that exercise.
 *
 * <p>It cannot simulate the failure the preflight is really for (an old tooling cannot run a class
 * it does not contain, and a test cannot have two versions of a class at once), so what is asserted
 * here is the invariant the in-process half checks: the invocation's flags are a subset of this
 * build's flags, and the check runs cleanly and says so.</p>
 */
class GeneratorPreflightTest {

    @Test
    void thePreflightAcceptsTheCurrentBuildAndSaysWhichOneItChecked() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Assertions.assertDoesNotThrow(() -> GeneratorPreflight.main(new String[0]),
                    "with a current tooling build the check prints and returns");
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains("preflight ok"),
                "the pass log has to show that the check ran, not only that it passed: " + output);
        Assertions.assertTrue(output.contains(EntityMetadataGenerator.GENERATOR_NAME),
                "and which generator it accepted: " + output);
    }

    /**
     * The check's whole reason for being a <em>class</em> rather than a flag: it must not exist in an
     * older tooling. That is not assertable here, but the consequence is — the class is only
     * reachable from the module that declares it, so every invocation has to name it by
     * fully-qualified name, from {@code java -cp} on the command line or from {@code exec:java}'s
     * {@code mainClass} alike.
     */
    @Test
    void theCheckIsAPublicClassWithTheMainEntryPointEveryInvocationNames() throws Exception {
        Class<?> canary = Class.forName("hr.hrg.hipster.entity.tooling.GeneratorPreflight");
        Assertions.assertTrue(java.lang.reflect.Modifier.isPublic(canary.getModifiers()),
                "exec:java and java -cp both reach it by name, so it must be public");
        Assertions.assertTrue(java.lang.reflect.Modifier.isStatic(
                        canary.getMethod("main", String[].class).getModifiers()),
                "and expose the ordinary static main(String[])");
    }
}
