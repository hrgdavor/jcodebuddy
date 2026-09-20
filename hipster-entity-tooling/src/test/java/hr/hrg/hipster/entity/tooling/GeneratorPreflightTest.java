package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link GeneratorPreflight} is a build-time guard, so nothing in a normal test run would exercise it —
 * the same gap {@code GateParityTest} exists to close for the build scripts. This class is that
 * exercise.
 *
 * <p>It cannot simulate the failure the preflight is really for (an old artifact on the classpath
 * cannot run a class it does not contain, and a test cannot have two versions of a class at once), so
 * what is asserted here is the invariant the in-process half checks: the binding's flags are a subset
 * of this build's flags, and the canary runs cleanly and says so.</p>
 */
class GeneratorPreflightTest {

    @Test
    void thePreflightAcceptsTheCurrentBuildAndSaysWhichOneItChecked() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Assertions.assertDoesNotThrow(() -> GeneratorPreflight.main(new String[0]),
                    "with a current tooling build the canary prints and returns");
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains("preflight ok"),
                "the build log has to show that the check ran, not only that it passed: " + output);
        Assertions.assertTrue(output.contains(EntityMetadataGenerator.GENERATOR_NAME),
                "and which generator it accepted: " + output);
    }

    /**
     * The canary's whole reason for being a <em>class</em> rather than a flag: it must not exist in an
     * older artifact. That is not assertable here, but the consequence is — the class is only reachable
     * from the module that declares it, so the build binding has to name it by fully-qualified name.
     */
    @Test
    void theCanaryIsAPublicClassWithTheMainEntryPointTheBindingNames() throws Exception {
        Class<?> canary = Class.forName("hr.hrg.hipster.entity.tooling.GeneratorPreflight");
        Assertions.assertTrue(java.lang.reflect.Modifier.isPublic(canary.getModifiers()),
                "exec:java reaches it by name, so it must be public");
        Assertions.assertTrue(java.lang.reflect.Modifier.isStatic(
                        canary.getMethod("main", String[].class).getModifiers()),
                "and expose the ordinary static main(String[])");
    }
}
