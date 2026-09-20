package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recorded gate is a build script, so nothing in a normal test run asserts what it does. This
 * class is that assertion.
 *
 * <p>It exists because the two launchers <em>did</em> silently diverge. The follow-up plan's § 4.1
 * asked for {@code clean} in "both branches" of {@code scripts/mvn-jdk25.cmd} <strong>and</strong> in
 * {@code scripts/mvn-jdk25.sh}; the {@code .cmd} got it, the {@code .sh} did not, and the follow-up
 * notes recorded the item as done (D-21 verified the {@code .cmd} only; D-15 "reviewed" the {@code .sh}
 * by inspection). The result was that {@code scripts/mvn-jdk25.sh} with no arguments ran a bare
 * {@code mvn} — no {@code -pl}, no {@code -am}, no {@code test}, no {@code clean} — i.e. not the gate
 * at all, while the root README advertised it as the POSIX form of the gate.</p>
 *
 * <p>The lesson is the notes' own recurring one (F-34, F-43, F-47): a rule that is true but unasserted
 * decays into a rule that is false. A build script cannot be exercised cheaply in CI on both platforms,
 * but the <em>contract</em> it encodes can be read and asserted, which is what happens here — the same
 * POM-as-text technique {@link DependencyBoundaryTest} already uses for scopes and versions.</p>
 */
class GateParityTest {

    private static final List<String> SIX_MODULES = List.of(
            "hipster-entity-api", "hipster-entity-core", "hipster-entity-tooling",
            "hipster-entity-jackson", "hipster-entity-test", "hipster-entity-example");

    /**
     * {@code -Dmaven.compiler.useIncrementalCompilation=false} is the other half of F-47's fix
     * (follow-up plan § 4.1 item 2).
     *
     * <p>{@code clean} removes yesterday's class files; it does not stop the compiler plugin from
     * deciding, <em>within</em> one run, that a module's sources are up to date. F-47's measurement was
     * a source that did not compile while repeated non-clean runs reported {@code core 88 / BUILD
     * SUCCESS}; the point of the {@code clean} half is that the gate must be red on a broken source
     * without anyone remembering to clean first, so the incremental path is disabled outright rather
     * than relied on to notice.</p>
     */
    private static final String INCREMENTAL_OFF =
            "-Dmaven.compiler.useIncrementalCompilation=false";

    private static Path repoRoot() {
        return CompileHarness.findRepoRoot();
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /**
     * The module list the script declares as the entity set, in declaration order.
     *
     * <p>Two spellings, because the two launchers assign differently: the batch file writes
     * {@code set "JCODEBUDDY_HE_MODULES=a,b,c"} and the POSIX one
     * {@code : "${JCODEBUDDY_HE_MODULES:=a,b,c}"}. Both are followed by the literal comma-separated
     * list, so the capture is anchored there.</p>
     */
    private static List<String> declaredModules(String script) {
        Matcher matcher = Pattern.compile("JCODEBUDDY_HE_MODULES[=:]+\\\"?([A-Za-z0-9._,$-]+)")
                .matcher(script);
        Assertions.assertTrue(matcher.find(), "the script must declare the module list once");
        String value = matcher.group(1);
        int end = value.indexOf('}');
        if (end >= 0) {
            value = value.substring(0, end);
        }
        return List.of(value.split(","));
    }

    @Test
    void bothLaunchersDeclareTheSameRecordedGate() throws Exception {
        Path root = repoRoot();
        String cmd = read(root.resolve("scripts/mvn-jdk25.cmd"));
        String sh = read(root.resolve("scripts/mvn-jdk25.sh"));

        Assertions.assertEquals(SIX_MODULES, declaredModules(cmd),
                "the .cmd's module list is the recorded -pl set (plan.dsflash 0.3)");
        Assertions.assertEquals(SIX_MODULES, declaredModules(sh),
                "and the .sh must declare the same six modules in the same order, or the two "
                        + "launchers are not the same gate");

        // The default invocation: `clean test` in both, or the gate can be satisfied by a previous
        // revision's class files (F-47).
        Assertions.assertTrue(cmd.contains("clean test"),
                "the .cmd's default invocation is `clean test` (D-21)");
        Assertions.assertTrue(sh.contains("clean test"),
                "the .sh's default invocation must be `clean test` too. It was a bare `mvn` until this "
                        + "test existed, which made the POSIX 'gate' run Maven's help output");
        Assertions.assertFalse(sh.matches("(?s).*exec \"\\$JCODEBUDDY_MVN\" \"\\$@\".*"),
                "the .sh must not fall back to a bare `mvn \"$@\"`: that is the divergence this test "
                        + "was written for");

        // `-o -pl <six> -am` in both: offline, scoped to six modules, with dependencies.
        for (String script : List.of(cmd, sh)) {
            Assertions.assertTrue(script.contains("-o -pl"),
                    "both launchers scope the reactor with -pl and run offline");
            Assertions.assertTrue(script.contains("-am"),
                    "and both build the required upstream modules with -am");
        }
    }

    @Test
    void bothLaunchersDisableIncrementalCompilation() throws Exception {
        Path root = repoRoot();
        Assertions.assertTrue(read(root.resolve("scripts/mvn-jdk25.cmd")).contains(INCREMENTAL_OFF),
                "the .cmd must disable incremental compilation on every invocation, so F-47's broken "
                        + "source fails the gate without a manual clean");
        Assertions.assertTrue(read(root.resolve("scripts/mvn-jdk25.sh")).contains(INCREMENTAL_OFF),
                "and the .sh must do the same");
    }

    /**
     * A property argument that the shell split before the script saw it must be refused, never
     * forwarded (notes D-20). In the {@code .cmd} the fragments made Maven drop the whole
     * {@code -pl} list, so the failure of an unrelated module looked like the gate failing.
     */
    @Test
    void bothLaunchersRefuseASplitProperty() throws Exception {
        Path root = repoRoot();
        String cmd = read(root.resolve("scripts/mvn-jdk25.cmd"));
        String sh = read(root.resolve("scripts/mvn-jdk25.sh"));

        Assertions.assertTrue(cmd.contains("exit /b 2"),
                "the .cmd exits 2 on an argument it cannot faithfully forward");
        Assertions.assertTrue(cmd.contains("a property argument was split"),
                "and says which mistake it saw");
        Assertions.assertTrue(sh.contains("return 2") || sh.contains("exit 2"),
                "the .sh must refuse a split property with the same exit code");
        Assertions.assertTrue(sh.contains("a property argument was split"),
                "and with the same message, because the fix is the same at either call site");
    }

    /**
     * The {@code .cmd} must stay pure ASCII with CRLF endings: cmd.exe mis-parses a batch file with
     * LF-only endings (notes D-11, hit again in D-20 when an em dash slipped into a comment). The
     * {@code .sh} is the opposite — LF only, which is what bash needs.
     */
    @Test
    void theBatchLauncherStaysAsciiAndCrlfAndTheShellLauncherStaysLf() throws Exception {
        Path root = repoRoot();
        byte[] cmdBytes = Files.readAllBytes(root.resolve("scripts/mvn-jdk25.cmd"));
        for (byte b : cmdBytes) {
            Assertions.assertTrue(b >= 0 && b < 128,
                    "scripts/mvn-jdk25.cmd must be pure ASCII: cmd.exe mis-parses non-ASCII bytes");
        }
        String cmdText = new String(cmdBytes, StandardCharsets.US_ASCII);
        Assertions.assertFalse(cmdText.replace("\r\n", "").contains("\n"),
                "scripts/mvn-jdk25.cmd must use CRLF line endings only");
        Assertions.assertTrue(cmdText.contains("\r\n"), "and actually use CRLF");

        byte[] shBytes = Files.readAllBytes(root.resolve("scripts/mvn-jdk25.sh"));
        String shText = new String(shBytes, StandardCharsets.UTF_8);
        Assertions.assertFalse(shText.contains("\r\n"),
                "scripts/mvn-jdk25.sh must use LF line endings: a CRLF script is not portable bash");
    }

    /**
     * The root README is the only place a developer learns what the gate is, so its text is part of
     * the contract. It documented {@code -am test} after the script had moved to {@code clean test}
     * (follow-up plan § 4.1 item 3 required the README to say so, and to say why).
     */
    @Test
    void theRootReadmeDescribesTheGateTheScriptsActuallyRun() throws Exception {
        String readme = read(repoRoot().resolve("README.md"));
        Assertions.assertTrue(readme.contains("clean test"),
                "README.md must name the recorded gate as `clean test`; it said `-am test` while the "
                        + "script had already added `clean` (F-47)");
        Matcher row = Pattern.compile("\\| `scripts/mvn-jdk25\\.cmd`[^\\n]*").matcher(readme);
        Assertions.assertTrue(row.find(), "and must still carry the launcher table");
        Assertions.assertTrue(row.group().contains("clean test"),
                "the launcher table row itself must say `clean test`, not just the prose: "
                        + row.group());
    }
}
