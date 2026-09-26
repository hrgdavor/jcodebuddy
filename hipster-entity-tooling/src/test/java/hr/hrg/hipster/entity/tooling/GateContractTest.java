package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The recorded gate is a build script, so nothing in a normal test run asserts what it does. This class is that
 * assertion.
 *
 * <p>It used to be {@code GateParityTest}, and it existed because the two launchers <em>did</em> silently diverge:
 * the follow-up plan's § 4.1 asked for {@code clean} in "both branches" of {@code scripts/mvn-jdk25.cmd}
 * <strong>and</strong> in {@code scripts/mvn-jdk25.sh}; the {@code .cmd} got it, the {@code .sh} did not, and the
 * notes recorded the item as done (D-21 verified the {@code .cmd} only; D-15 "reviewed" the {@code .sh} by
 * inspection). The POSIX "gate" then ran a bare {@code mvn} — no {@code -pl}, no {@code -am}, no {@code test}, no
 * {@code clean} — while the root README advertised it as the gate.</p>
 *
 * <p><strong>The fix was not a better parity test; it was removing the twin.</strong> There is now one launcher,
 * {@code scripts/mvn-jdk25.js}, and the argument list it builds comes from one place,
 * {@code scripts/lib/gate.js}, which {@code gen.js} and {@code run-demo.js} import as well. This class asserts the
 * contract of that single definition (the notes' own recurring lesson — F-34, F-43, F-47 — is that a rule which
 * is true but unasserted decays into a rule which is false), and it asserts the property that makes the old
 * divergence impossible: there is no second launcher to diverge from, in any shell.</p>
 */
class GateContractTest {

    private static final List<String> SIX_MODULES = List.of(
            "hipster-entity-api", "hipster-entity-core", "hipster-entity-tooling",
            "hipster-entity-jackson", "hipster-entity-test", "hipster-entity-example");

    /**
     * {@code -Dmaven.compiler.useIncrementalCompilation=false} is the other half of F-47's fix. {@code clean}
     * removes yesterday's class files; it does not stop the compiler plugin from deciding, <em>within</em> one run,
     * that a module's sources are up to date.
     */
    private static final String INCREMENTAL_OFF =
            "-Dmaven.compiler.useIncrementalCompilation=false";

    private static Path repoRoot() {
        return CompileHarness.findRepoRoot();
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String gateModule() throws Exception {
        return read(repoRoot().resolve("scripts/lib/gate.js"));
    }

    private static String launcher() throws Exception {
        return read(repoRoot().resolve("scripts/mvn-jdk25.js"));
    }

    /** The module list the shared gate definition declares, in declaration order. */
    private static List<String> declaredModules(String gate) {
        Matcher matcher = Pattern.compile("export const SIX_MODULES = \\[(.*?)\\]\\.join", Pattern.DOTALL)
                .matcher(gate);
        Assertions.assertTrue(matcher.find(), "the gate must declare the module list once");
        List<String> modules = new ArrayList<>();
        Matcher entry = Pattern.compile("'([a-z-]+)'").matcher(matcher.group(1));
        while (entry.find()) {
            modules.add(entry.group(1));
        }
        return modules;
    }

    @Test
    void theGateDeclaresTheRecordedModuleSet() throws Exception {
        Assertions.assertEquals(SIX_MODULES, declaredModules(gateModule()),
                "the -pl set is the recorded six modules in order (plan.dsflash 0.3)");
    }

    /**
     * The default invocation: {@code clean test}, or the gate can be satisfied by a previous revision's class
     * files (F-47). Asserted on the shared definition, so the launcher, {@code gen.js}'s forwarded mode and
     * {@code run-demo.js} cannot disagree about it.
     */
    @Test
    void theGateDefaultsToCleanTest() throws Exception {
        String gate = gateModule();
        Assertions.assertTrue(gate.contains("export const DEFAULT_GOALS = ['clean', 'test']"),
                "the recorded gate is `clean test` (D-21); it must be declared as such, in one place");
        Assertions.assertTrue(gate.contains("const goals = rest.length === 0 ? defaultGoals : rest"),
                "and it is the DEFAULT that cleans: an explicit goal list is the caller's request, which is how "
                        + "run-demo.js reuses built classes without a rebuild");
        Assertions.assertFalse(gate.contains("'-pl', SIX_MODULES, '-am'"),
                "offline and -am are part of the scoped invocation; a launcher that drops them is not the gate");
    }

    @Test
    void theGateScopesTheReactorAndRunsOffline() throws Exception {
        String gate = gateModule();
        Assertions.assertTrue(gate.contains("['-o', '-pl', modules, '-am', INCREMENTAL_OFF, ...goals]"),
                "the scoped invocation is `-o -pl <six> -am -Dmaven...clean test`");
    }

    @Test
    void theGateDisablesIncrementalCompilationOnEveryInvocation() throws Exception {
        String gate = gateModule();
        Assertions.assertTrue(gate.contains(INCREMENTAL_OFF),
                "both the shortcut and the free-form path must disable incremental compilation, so F-47's broken "
                        + "source fails the gate without a manual clean");
        long uses = gate.lines().filter(line -> line.contains("INCREMENTAL_OFF, ...")).count();
        Assertions.assertEquals(2, uses,
                "exactly the two invocations (free-form and shortcut) build their argument list around it");
    }

    /**
     * A property argument that a shell split before the script saw it must be refused, never forwarded (notes
     * D-20). In the old {@code .cmd} the fragments made Maven drop the whole {@code -pl} list, so the failure of an
     * unrelated module looked like the gate failing.
     */
    @Test
    void theGateRefusesASplitProperty() throws Exception {
        String gate = gateModule();
        String launcher = launcher();
        Assertions.assertTrue(gate.contains("export function splitProperty"),
                "the split-property check is part of the shared definition");
        Assertions.assertTrue(gate.contains("!arg.includes('=')"),
                "a -D token without `=` is the signature of a split property");
        Assertions.assertTrue(launcher.contains("return 2"),
                "the launcher exits 2 on an argument it cannot faithfully forward");
        Assertions.assertTrue(launcher.contains("splitPropertyAdvice"),
                "and says which mistake it saw, from the message that lives next to the rule");
    }

    /**
     * The structural fix, asserted: there is no second launcher, and no shell script at all.
     *
     * <p>{@code AGENTS.md} § 2 requires scripts and tests to be Bun JavaScript — a check that only runs in one
     * shell on one OS is invisible wiring for the workflow — so this also fails the build if someone adds a
     * {@code .cmd}, {@code .sh} or {@code .ps1} under {@code scripts/} again. The Gradle wrapper is not under
     * {@code scripts/} and is generated by Gradle itself.</p>
     */
    @Test
    void thereIsExactlyOneLauncherAndNoShellWrapper() throws Exception {
        Path scripts = repoRoot().resolve("scripts");
        List<String> wrappers = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(scripts)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".sh")
                        || name.endsWith(".ps1")) {
                    wrappers.add(scripts.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        Assertions.assertEquals(List.of(), wrappers,
                "scripts/ must hold no shell wrappers: the gate is `bun scripts/mvn-jdk25.js`. "
                        + "Found: " + wrappers);

        Assertions.assertFalse(Files.exists(scripts.resolve("mvn-jdk25.cmd")),
                "scripts/mvn-jdk25.cmd was replaced by scripts/mvn-jdk25.js");
        Assertions.assertFalse(Files.exists(scripts.resolve("mvn-jdk25.sh")),
                "scripts/mvn-jdk25.sh was replaced by the same single implementation");
    }

    /**
     * The root README is the only place a developer learns what the gate is, so its text is part of the contract.
     * It once documented {@code -am test} after the script had moved to {@code clean test}.
     */
    @Test
    void theRootReadmeDescribesTheGateTheScriptActuallyRuns() throws Exception {
        String readme = read(repoRoot().resolve("README.md"));
        Assertions.assertTrue(readme.contains("clean test"),
                "README.md must name the recorded gate as `clean test` (F-47)");
        Matcher row = Pattern.compile("\\| `scripts/mvn-jdk25\\.js`[^\\n]*").matcher(readme);
        Assertions.assertTrue(row.find(),
                "and must carry the launcher row for the script that exists, scripts/mvn-jdk25.js");
        Assertions.assertTrue(row.group().contains("clean test"),
                "the launcher row itself must say `clean test`, not just the prose: " + row.group());
        // No *command row* for a removed launcher. The README may still name the old files where it explains why
        // they were replaced — that history is the point of the note — but a row is an instruction to run it.
        Assertions.assertFalse(
                Pattern.compile("(?m)^\\| `scripts/mvn-jdk25\\.cmd`").matcher(readme).find(),
                "README.md's command table must not offer the removed batch launcher");
    }

    /**
     * A JDK is selected by running it, never assumed: a path that exists but holds Java 21 produces
     * {@code UnsupportedClassVersionError} from inside the generator, which reads like a classpath bug and sends
     * the reader after the wrong thing. Asserted as the contract it is — the toolchain helper must verify the
     * version rather than trust the path.
     */
    @Test
    void theToolchainVerifiesTheJdkInsteadOfAssumingIt() throws Exception {
        String toolchain = read(repoRoot().resolve("scripts/lib/toolchain.js"));
        Assertions.assertTrue(toolchain.contains("export const REQUIRED_JAVA = 25"),
                "the tooling classes are class file 69, so the requirement is stated once, as a value");
        Assertions.assertTrue(toolchain.contains("javaMajor(java)"),
                "and the candidate is run to read its version");
        Assertions.assertTrue(toolchain.contains("version < REQUIRED_JAVA"),
                "with a refusal that names the version it found");
    }
}
