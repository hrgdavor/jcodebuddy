package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The whole-file freeze: {@code enabled:false} in the DEC-021 header takes a file fully under manual
 * control (DEC-018, DEC-021 § 6).
 *
 * <p>The distinction this test exists to pin down is the one DEC-021 § 6 could only describe as
 * aspirational until now: <strong>cooperation</strong> decides what the generator may replace inside a
 * file it owns, and the <strong>freeze</strong> decides whether it may touch the file at all. Both are
 * spelled in the same header, on the same line, and before this knob was read the second one was a
 * promise the code did not keep — the note said so in those words ("setting <code>enabled: false</code>
 * today does not freeze the file — the next pass regenerates it").</p>
 *
 * <p>So the assertions are deliberately about <em>bytes</em> rather than about intent: a frozen file must
 * come back exactly as the developer left it, and the pass must say that it skipped it. A test that only
 * checked "no new constant appeared" would pass for a file that had been re-emitted without changes, which
 * is the failure mode being fixed.</p>
 */
class CooperativeCodegenEnabledTest {

    /** A generated enum whose header takes it out of the generator's hands. */
    private static final String FROZEN_FILE = """
            // @generated file hr.hrg.hipster.entity.tooling.FieldBoilerplateGenerator — field enum.
            // {enabled:false, entityFieldEnum:true, blockMarker: "implicit"}
            package freeze.hr;

            public enum Thing {
                ID(Long.class),
                NAME(String.class);

                private final Class<?> type;

                Thing(Class<?> type) {
                    this.type = type;
                }

                public Class<?> type() {
                    return type;
                }

                /** The developer's own helper: the pass must not reach into this file at all. */
                public String describe() {
                    return name() + ":" + type.getSimpleName();
                }
            }
            """;

    /**
     * What the generator would emit today: one more constant, and the same developer helper absent.
     *
     * <p>It differs from {@link #FROZEN_FILE} in both directions — a member added and a member removed —
     * so a pass that regenerated would be visible either way.</p>
     */
    private static final String CANONICAL = """
            // @generated file hr.hrg.hipster.entity.tooling.FieldBoilerplateGenerator — field enum.
            // {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
            package freeze.hr;

            public enum Thing {
                ID(Long.class),
                NAME(String.class),
                AGE(Integer.class);

                private final Class<?> type;

                Thing(Class<?> type) {
                    this.type = type;
                }

                public Class<?> type() {
                    return type;
                }
            }
            """;

    private Path fileWith(String text) throws Exception {
        Path tree = Files.createTempDirectory("frozen-header");
        Path file = tree.resolve("Thing.java");
        Files.writeString(file, text);
        return file;
    }

    /** The same fixture with the freeze off, so the enabled path is measurably different. */
    private static String enabled(String text) {
        return text.replace("{enabled:false,", "{enabled:true,");
    }

    @Test
    void aFrozenFileComesBackByteForByteAndThePassSaysItSkippedIt() throws Exception {
        Path file = fileWith(FROZEN_FILE);

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, "Thing", CANONICAL, CooperativeCodegen.Reconciliation.ALL, Set.of());

        Assertions.assertEquals(FROZEN_FILE, result.source(),
                "a frozen file must be returned exactly as it is on disk: not re-emitted, not reformatted, "
                        + "not stripped of the developer's helper");
        Assertions.assertEquals(1, result.divergences().size(),
                "the skip is one fact and must be reported once: " + result.divergences());
        String entry = result.divergences().get(0);
        Assertions.assertTrue(entry.startsWith("kind=file_frozen, location=Thing, "),
                "reported in DEC-022's format, naming the type: " + entry);
        Assertions.assertTrue(entry.contains("enabled:false"),
                "the cause names the header line the reader has to edit: " + entry);
        Assertions.assertTrue(entry.contains("action=no action"),
                "a freeze is honoured, not a problem to fix: " + entry);
    }

    @Test
    void enablingTheFileLetsThePassRegenerateItAndStillCooperate() throws Exception {
        Path file = fileWith(enabled(FROZEN_FILE));

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, "Thing", CANONICAL, CooperativeCodegen.Reconciliation.ALL, Set.of());

        Assertions.assertTrue(result.source().contains("AGE(Integer.class)"),
                "an enabled file is regenerated, so the new constant is emitted: " + result.source());
        Assertions.assertTrue(result.source().contains("public String describe()"),
                "and cooperation still applies inside it: the developer's member is carried through, "
                        + "which is what makes `enabled:false` a choice about ownership rather than about "
                        + "survival");
        Assertions.assertTrue(result.divergences().stream().noneMatch(e -> e.startsWith("kind=file_frozen")),
                "an enabled file is not frozen: " + result.divergences());
    }

    @Test
    void forceDoesNotOverrideTheFreeze() throws Exception {
        Path file = fileWith(FROZEN_FILE);
        CooperativeCodegen.setForce(true);
        try {
            CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                    file, "Thing", CANONICAL, CooperativeCodegen.Reconciliation.ALL, Set.of());

            Assertions.assertEquals(FROZEN_FILE, result.source(),
                    "--force means \"every member in this file belongs to the generator\"; enabled:false "
                            + "means the opposite, and the file cannot be both. Force must not rewrite the "
                            + "files a developer marked as theirs.");
            Assertions.assertEquals(1, result.divergences().size(),
                    "and it is still reported, so a force run is not quiet about the file it left alone: "
                            + result.divergences());
        } finally {
            // Process-global by design (see CooperativeCodegen.setForce), so a test that turns it on has
            // to turn it off or the next test in this JVM inherits it.
            CooperativeCodegen.setForce(false);
        }
    }

    @Test
    void forceStillRegeneratesAnEnabledFile() throws Exception {
        Path file = fileWith(enabled(FROZEN_FILE));
        CooperativeCodegen.setForce(true);
        try {
            CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                    file, "Thing", CANONICAL, CooperativeCodegen.Reconciliation.ALL, Set.of());

            Assertions.assertTrue(result.source().contains("AGE(Integer.class)"),
                    "the ordering rule is about the freeze, not about disabling force: " + result.source());
            Assertions.assertFalse(result.source().contains("public String describe()"),
                    "and force still means what it says: the canonical text, not a reconciliation");
        } finally {
            CooperativeCodegen.setForce(false);
        }
    }

    /**
     * A header nobody can read must not freeze a file for the rest of the project's life.
     *
     * <p>The two fail-safe directions in {@code EnumConstantOrderChecker.decode} are deliberately
     * opposite: an unreadable header still counts as {@code marked} (so the R1 ordinal rule keeps
     * protecting the enum) and still counts as {@code enabled} (so a typo cannot silently stop generation).
     * Regeneration is recoverable and reported — every member the developer added is preserved and every
     * edit to a generated member is reported — while a silent freeze is not.</p>
     */
    @Test
    void aMalformedHeaderDoesNotFreezeTheFile() throws Exception {
        Path file = fileWith(FROZEN_FILE.replace(
                "// {enabled:false, entityFieldEnum:true, blockMarker: \"implicit\"}",
                "// {enabled:false, entityFieldEnum:true"));

        CooperativeCodegen.Reconciled result = CooperativeCodegen.reconcileMembers(
                file, "Thing", CANONICAL, CooperativeCodegen.Reconciliation.ALL, Set.of());

        Assertions.assertTrue(result.source().contains("AGE(Integer.class)"),
                "an undecodable header leaves the file enabled: " + result.source());
        Assertions.assertFalse(CooperativeCodegen.isFrozen(file),
                "and the question itself answers false rather than throwing");
    }

    @Test
    void isFrozenAnswersForTheFilesAPassActuallyMeets() throws Exception {
        Path frozen = fileWith(FROZEN_FILE);
        Path notFrozen = fileWith(enabled(FROZEN_FILE));
        Path noHeader = fileWith("package freeze.hr;\n\npublic class Other {\n}\n");
        Path missing = frozen.getParent().resolve("Absent.java");

        Assertions.assertTrue(CooperativeCodegen.isFrozen(frozen), "enabled:false freezes the file");
        Assertions.assertFalse(CooperativeCodegen.isFrozen(notFrozen), "enabled:true does not");
        Assertions.assertFalse(CooperativeCodegen.isFrozen(noHeader),
                "a file with no DEC-021 header is not frozen — most generated files in the tree predate "
                        + "the header, and treating them as frozen would stop generating them");
        Assertions.assertFalse(CooperativeCodegen.isFrozen(missing),
                "and a file that does not exist yet cannot be frozen: the first pass has to be able to "
                        + "create it");
    }

    /**
     * The end-to-end half: a real pass over a real tree, with one generated file frozen by hand.
     *
     * <p>The unit tests above call the reconciler directly; this one proves the freeze reaches an emitter
     * that the pass drives, that the file survives a second pass byte for byte, that the pass reports it,
     * and that the result is still Java the compiler accepts — a "frozen" file that no longer compiles
     * would be a worse outcome than one that was regenerated.</p>
     */
    @Test
    void aPassLeavesAFrozenGeneratedFileExactlyAsTheDeveloperLeftIt() throws Exception {
        Path tree = Files.createTempDirectory("frozen-pass");
        Path packageDir = tree.resolve("freeze/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), """
                package freeze.hr;
                import hr.hrg.hipster.entity.api.EntityBase;
                import hr.hrg.hipster.entity.api.Identifiable;
                public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
                """);
        Files.writeString(packageDir.resolve("PersonSummary.java"), """
                package freeze.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.BUILDER_ALL)
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                    String lastName();
                }
                """);

        EntityMetadataGenerator.generate(tree, tree);
        Path fieldEnum = packageDir.resolve("PersonSummary_.java");
        Assertions.assertTrue(Files.exists(fieldEnum), "the pass emits the field enum");

        // The developer takes the file over: the freeze knob, plus a member of their own so a
        // regeneration would be visible in the bytes and not only in the header.
        String takenOver = Files.readString(fieldEnum)
                .replace("{enabled:true,", "{enabled:false,")
                .replaceFirst("\\}\\s*$", """
                            /** Mine: this file is hand-maintained now. */
                            public String frozenTweak() {
                                return name();
                            }
                        }
                        """);
        Files.writeString(fieldEnum, takenOver);
        Assertions.assertTrue(CooperativeCodegen.isFrozen(fieldEnum), "the fixture is frozen");

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(tree, tree, tree, divergences);

        Assertions.assertEquals(takenOver, Files.readString(fieldEnum),
                "a frozen generated file must come back byte for byte: the pass may not re-emit it, "
                        + "re-indent it, or drop the member the developer added");
        Assertions.assertTrue(divergences.ofKind("file_frozen").stream()
                        .anyMatch(entry -> entry.contains("PersonSummary_")),
                "and the pass reports the file it left alone: " + divergences.render());

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "frozen-generated-file",
                CompileHarness.javaSourcesUnder(tree), List.of());
    }
}
