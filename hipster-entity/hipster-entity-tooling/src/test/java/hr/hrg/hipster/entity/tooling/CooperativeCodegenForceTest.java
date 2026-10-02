package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Force mode: the escape hatch from cooperative preservation, and the test of what it is for.
 *
 * <p>The cooperative contract is that a member belonging to an <em>earlier revision</em> is carried
 * through verbatim, which is what lets a developer edit generated code and keep the edit. The cost is
 * that a generator fix cannot reach a member the reconciliation no longer recognises as its own. The
 * documented remedy is to delete that member and regenerate; force mode is for when that is large, or
 * touches many files, and for trying an emitter change in the output before changing the generator.
 *
 * <p>Everything here is checked against {@link CooperativeCodegen} directly rather than through a full
 * generation pass. That is deliberate: the mechanism is what is under test, and a pass would also be
 * asserting the emitter, the validation policy and the file walk — none of which this mode touches.
 */
class CooperativeCodegenForceTest {

    private static final String TYPE = "ThingBuilder";

    @AfterEach
    void resetForce() {
        // Process-global by design (one pass, one answer), so a test that leaves it set changes the next
        // test's reconciliation — the same hazard the generator's own CLI resets it for.
        CooperativeCodegen.setForce(false);
    }

    /** A canonical emission with two members: one the generator owns, one it does not. */
    private static String canonical() {
        return """
                package p;

                public class ThingBuilder {

                    private int value;

                    public Object get(int ordinal) {
                        return value;
                    }
                }
                """;
    }

    /** A previous file whose `get` has a DIFFERENT arity, so reconciliation cannot match it at all. */
    private static String previousWithUnrecognisedGet() {
        return """
                package p;

                public class ThingBuilder {

                    private int value;

                    public Object get(String name, int fallback) {
                        return value;
                    }

                    /** Hand-written by a developer, and must survive a normal pass. */
                    public ThingBuilder withValue(int v) {
                        this.value = v;
                        return this;
                    }
                }
                """;
    }

    private static Path write(Path dir, String text) throws IOException {
        Path file = dir.resolve(TYPE + ".java");
        Files.writeString(file, text);
        return file;
    }

    @Test
    @DisplayName("normally, a member the generator no longer recognises is preserved as the developer's")
    void aNormalPassPreservesAnUnrecognisedMember() throws Exception {
        Path file = write(temp, previousWithUnrecognisedGet());

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, TYPE, canonical(), CooperativeCodegen.Reconciliation.ALL, Set.of());

        // This is the behaviour force mode exists to bypass: an old member that does not match by shape is
        // read as the developer's own, so the generator's current `get(int)` cannot replace it.
        Assertions.assertTrue(result.source().contains("get(String name, int fallback)"),
                "the unrecognised member is carried through by default: " + result.source());
        Assertions.assertTrue(result.source().contains("withValue(int v)"),
                "and so is a genuinely hand-written one");
        Assertions.assertTrue(result.source().contains("get(int ordinal)"),
                "while the canonical member is emitted as well");
    }

    @Test
    @DisplayName("force mode emits the canonical text and preserves nothing")
    void forceEmitsCanonicalAndPreservesNothing() throws Exception {
        Path file = write(temp, previousWithUnrecognisedGet());
        CooperativeCodegen.setForce(true);

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, TYPE, canonical(), CooperativeCodegen.Reconciliation.ALL, Set.of());

        Assertions.assertEquals(canonical(), result.source(),
                "force mode is the generator being the authority on the file");
        Assertions.assertFalse(result.source().contains("get(String name, int fallback)"),
                "the member it could not recognise is gone, which is the point");
        Assertions.assertFalse(result.source().contains("withValue(int v)"),
                "and a hand-written member goes with it — the documented cost of the flag");
    }

    @Test
    @DisplayName("force mode reports every member it discards, so the cost is not paid silently")
    void forceReportsWhatItDiscards() throws Exception {
        Path file = write(temp, previousWithUnrecognisedGet());
        CooperativeCodegen.setForce(true);

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, TYPE, canonical(), CooperativeCodegen.Reconciliation.ALL, Set.of());

        // A force pass over the example deleted a hand-written nested type and reported success with
        // nothing about it. Reporting what goes is the difference between a flag with a known cost and a
        // flag that quietly eats someone's code — "nothing diverged" described the code path, not the file.
        Assertions.assertEquals(2, result.divergences().size(),
                "both members absent from the canonical text are reported: " + result.divergences());
        Assertions.assertTrue(result.divergences().stream()
                        .allMatch(d -> d.startsWith("kind=force_discarded_member")),
                "in the same diagnostic format as every other divergence: " + result.divergences());
        Assertions.assertTrue(result.divergences().stream().anyMatch(d -> d.contains("get")),
                "the unrecognised member is named: " + result.divergences());
        Assertions.assertTrue(result.divergences().stream().anyMatch(d -> d.contains("withValue")),
                "and so is the hand-written one: " + result.divergences());
        Assertions.assertTrue(result.divergences().stream().anyMatch(d -> d.contains("action=")),
                "each carries an action, because the point is that the reader can act on it");
    }

    @Test
    @DisplayName("force mode reports nothing when the file holds only what the generator emits")
    void forceIsQuietWhenNothingIsLost() throws Exception {
        // The case the flag is actually for: regenerating a file the generator fully owns, where there is
        // no edit to lose. Being noisy here would train a reader to ignore the report.
        Path file = write(temp, canonical());
        CooperativeCodegen.setForce(true);

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, TYPE, canonical(), CooperativeCodegen.Reconciliation.ALL, Set.of());

        Assertions.assertEquals(canonical(), result.source());
        Assertions.assertEquals(List.of(), result.divergences(),
                "nothing was discarded, so there is nothing to report: " + result.divergences());
    }

    @Test
    @DisplayName("force mode does not need the previous file to exist")
    void forceWorksWithoutAPreviousFile() throws Exception {
        CooperativeCodegen.setForce(true);
        Path missing = temp.resolve("Absent.java");

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                missing, TYPE, canonical(), CooperativeCodegen.Reconciliation.ALL, Set.of());

        Assertions.assertEquals(canonical(), result.source());
        Assertions.assertEquals(List.of(), result.divergences());
    }

    @Test
    @DisplayName("force is off by default, and turning it on is explicit")
    void forceIsOptIn() {
        Assertions.assertFalse(CooperativeCodegen.isForce(),
                "losing a developer's edit to a generated block is silent, so this is never the default");
        CooperativeCodegen.setForce(true);
        Assertions.assertTrue(CooperativeCodegen.isForce());
        CooperativeCodegen.setForce(false);
        Assertions.assertFalse(CooperativeCodegen.isForce());
    }

    @Test
    @DisplayName("the flag is advertised in the generator's surface, so a caller can discover it")
    void theFlagIsInTheSupportedSurface() {
        Assertions.assertTrue(EntityMetadataGenerator.supportsFlag("--force"),
                "GeneratorPreflight asserts this list against the tooling on the classpath: a flag a caller "
                        + "passes but an older tooling does not know becomes a positional argument, and "
                        + "generated source lands in the metadata directory");

        // And it is deliberately NOT in the flags every documented invocation passes: force mode is not a
        // mode a normal pass should acquire by default.
        Assertions.assertFalse(EntityMetadataGenerator.EXECUTION_FLAGS.contains("--force"),
                "a normal generation run must not force, or every edit to a generated block would be lost");
    }

    @TempDir
    Path temp;
}
