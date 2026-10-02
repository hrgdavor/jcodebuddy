package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Cooperative codegen: a user-added nested class inside a generated file survives regeneration
 * (plan.dsflash § 4.5/G3, § 8.6/3.18, § 8.7/3.19; DEC-020).
 *
 * <p>The live case this reproduces is the example's hand-written {@code TrackingStrict}, a nested
 * class the developer added to the generated {@code PersonSummaryBuilderTracking}. The generator
 * has no attribute that could request it and must never emit it, so the only correct behaviour is
 * to recognise it as a member it does not own and carry it through — which is exactly what an
 * earlier revision failed to do, silently deleting it on the next pass (notes F-25).</p>
 *
 * <p>The test is deliberately a <strong>run-twice</strong> test rather than a single-pass one. A
 * single pass only shows the member was copied; the second pass is what proves the copy is a fixed
 * point, so a regeneration loop cannot erode the user's code one pass at a time — which is the
 * failure mode a whole-file emitter has without cooperative recognition.</p>
 */
class CooperativeCodegenTest {

    private static final String MARKER = """
            package coop.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package coop.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
            }
            """;

    /** The user-authored member, in the shape § 4.5/G3 describes. */
    private static final String USER_MEMBER = """
            /**
             * A user-authored variant. Never generated, always preserved — including this comment,
             * which is the whole point of the class being the developer's.
             */
            public static final class TrackingStrict extends PersonSummaryBuilderTracking {

                public TrackingStrict(PersonSummary source) {
                    super(source);
                }
            }
            """.stripTrailing();

    private Path tree() throws Exception {
        Path sourceRoot = Files.createTempDirectory("coop-tree");
        // The view interfaces must sit in their package directory, not at the source root: the
        // entry-point emitter looks the developer's own file up by package path (it is editing
        // indexed input, not generated output), so a view parked at the root would simply not be
        // found and the test would pass vacuously.
        Path packageDir = sourceRoot.resolve("coop/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(packageDir.resolve("PersonSummary.java"), VIEW);
        return sourceRoot;
    }

    private Path trackingBuilder(Path root) {
        return root.resolve("coop/hr/PersonSummaryBuilderTracking.java");
    }

    /** Generation is in place, which is the real configuration (§ 9/4.1/S2). */
    private void generateInPlace(Path tree) throws Exception {
        EntityMetadataGenerator.generate(tree, tree);
    }

    @Test
    void aUserAddedNestedClassSurvivesAndIsAFixedPoint() throws Exception {
        Path tree = tree();
        generateInPlace(tree);

        Path builder = trackingBuilder(tree);
        Assertions.assertTrue(Files.exists(builder), "BUILDER_ALL emits the tracking builder");

        // The developer adds their own nested class and stops there: no marker comment, no registry
        // entry, nothing the generator could key on except the shape of the file itself.
        String withUserMember = Files.readString(builder)
                .replaceFirst("\\}\\s*$", "\n" + USER_MEMBER + "\n}\n");
        Files.writeString(builder, withUserMember);

        generateInPlace(tree);
        String afterFirstPass = Files.readString(builder);
        Assertions.assertTrue(afterFirstPass.contains("class TrackingStrict"),
                "the user's nested class must survive regeneration (G3): " + afterFirstPass);
        Assertions.assertTrue(afterFirstPass.contains("A user-authored variant. Never generated, always preserved"),
                "and its own javadoc must survive with it — an AST round-trip drops exactly that, "
                        + "which is why the member is carried as source text");
        Assertions.assertFalse(afterFirstPass.contains("generator:begin"),
                "no strict marker pair is involved (DEC-020 discourages it)");

        generateInPlace(tree);
        String afterSecondPass = Files.readString(builder);
        Assertions.assertEquals(afterFirstPass, afterSecondPass,
                "and it must be a fixed point, or a regeneration loop erodes the user's code one "
                        + "pass at a time");
        Assertions.assertEquals(withUserMember, afterSecondPass,
                "the developer's text must come back byte for byte, not merely survive in spirit");

        // The preserved member is still a real nested type the compiler accepts, and the generated
        // body around it is intact — a preserved blob that broke the enclosing class would be worse
        // than a deleted one.
        Assertions.assertTrue(afterSecondPass.contains("public class PersonSummaryBuilderTracking"),
                "the generated class is still emitted");
        Assertions.assertTrue(afterSecondPass.contains("return mf.toImmutable();"),
                "and so is its tracked-state accessor");
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "preserved-user-member",
                CompileHarness.javaSourcesUnder(tree), List.of());
    }

    @Test
    void aGeneratorEmittedNestedTypeIsNotDuplicated() throws Exception {
        Path tree = tree();
        generateInPlace(tree);
        Path builder = trackingBuilder(tree);
        String generated = Files.readString(builder);

        // A second pass over an untouched generated file must be byte-identical: recognition found
        // nothing it does not own, so nothing extra was appended.
        generateInPlace(tree);
        Assertions.assertEquals(generated, Files.readString(builder),
                "a clean generated file must be rewritten identically, not grown");
    }

    /**
     * The same preservation for the two other whole-file emissions (plan.dsflash § 8.7/3.19).
     *
     * <p>The builders were the first files a developer extended, but they are not the only ones: a
     * record and a field enum are just as much "generated source that lives next to hand-written
     * code", and a nested extension point added to either was silently deleted on the next pass. The
     * emitter produces no nested types at all, so there is nothing for the generator to own here —
     * everything found is the developer's.</p>
     */
    @Test
    void aUserMemberInsideTheGeneratedRecordAndEnumSurvives() throws Exception {
        Path tree = tree();
        generateInPlace(tree);

        String member = """
                /**
                 * USER-MEMBER-IN-%s doc — never generated, always preserved.
                 */
                public static final class UserStrict%s { }
                """.stripTrailing();

        List<Path> generated = List.of(
                tree.resolve("coop/hr/PersonSummaryRecord.java"),
                tree.resolve("coop/hr/PersonSummary_.java"));
        for (Path file : generated) {
            Assertions.assertTrue(Files.exists(file), "the level emits this file: " + file);
            String text = Files.readString(file);
            int lastBrace = text.lastIndexOf('}');
            Assertions.assertTrue(lastBrace > 0, "the file has a top-level type: " + file);
            String name = file.getFileName().toString().replace(".java", "").replace("PersonSummary", "");
            Files.writeString(file, text.substring(0, lastBrace)
                    + "\n" + member.formatted(name, name) + "\n" + text.substring(lastBrace));
        }

        generateInPlace(tree);
        List<String> afterFirstPass = generated.stream().map(CooperativeCodegenTest::read).toList();
        for (int i = 0; i < generated.size(); i++) {
            String name = generated.get(i).getFileName().toString();
            Assertions.assertTrue(afterFirstPass.get(i).contains("class UserStrict"),
                    "a user's nested class inside " + name + " must survive regeneration: " + afterFirstPass.get(i));
            Assertions.assertTrue(afterFirstPass.get(i).contains("never generated, always preserved"),
                    "and its javadoc with it: " + name);
        }

        generateInPlace(tree);
        for (int i = 0; i < generated.size(); i++) {
            Assertions.assertEquals(afterFirstPass.get(i), read(generated.get(i)),
                    "and the carry-over is a fixed point, not a one-pass copy: "
                            + generated.get(i).getFileName());
        }

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "preserved-record-and-enum",
                CompileHarness.javaSourcesUnder(tree), List.of());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Generation in place, keeping the pass's divergence report. */
    private static List<String> generateInPlaceReporting(Path tree) throws Exception {
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(tree, tree, tree, reporter);
        return reporter.entries();
    }

    /**
     * The reconciliation itself, isolated from any emitter — including the {@code final class} shape the
     * mapper and the validator use.
     *
     * <p>Written because the emitter-level test for the validator reported nothing, and the cause had to
     * be located on one side or the other. Keeping it is worthwhile on its own: it is the only test that
     * exercises the three states without a generator in the loop, so a future policy change is
     * diagnosable in one place.</p>
     */
    @Test
    void theThreeStatesAreDecidedByMemberIdentityAndText() throws Exception {
        Path dir = Files.createTempDirectory("reconcile-direct");
        Path file = dir.resolve("Thing.java");
        Files.writeString(file, """
                package p;
                public final class Thing {
                    private Thing() {
                    }
                    public static String validate(String view) {
                        // USER-EDIT inside a generated method
                        return view;
                    }
                    /** USER-METHOD - the developer's own. */
                    public static String helper() {
                        return "h";
                    }
                }
                """);
        String canonical = """
                package p;
                public final class Thing {
                    private Thing() {
                    }
                    public static String validate(String view) {
                        return view;
                    }
                }
                """;

        CooperativeCodegen.Reconciled reconciled =
                CooperativeCodegen.reconcileMembers(file, "Thing", canonical);

        Assertions.assertTrue(reconciled.divergences().stream()
                        .anyMatch(entry -> entry.startsWith("kind=generated_member_diverged")
                                && entry.contains("Thing.validate")),
                "a generated member whose body was edited is reported: " + reconciled.divergences());
        Assertions.assertTrue(reconciled.source().contains("helper()"),
                "a member the canonical emission does not contain is preserved: " + reconciled.source());
        Assertions.assertTrue(reconciled.source().contains("USER-METHOD - the developer's own."),
                "with its javadoc, because it is carried as text: " + reconciled.source());
        Assertions.assertFalse(reconciled.source().contains("USER-EDIT"),
                "and the edited generated body is replaced by the canonical one: " + reconciled.source());
    }

    /**
     * A user-added <strong>method</strong> inside a generated builder survives regeneration
     * (follow-up plan § 1.1; notes F-51's remaining mechanism).
     *
     * <p>Nested types were already carried through. A helper method was not: the emitter owns no method
     * of that name, so the whole-file rewrite deleted it, silently — the same loss F-25 records, in a
     * shape F-25's fix did not reach. The application here is the one § 1.1 asks for: a member the
     * canonical emission does not contain is the developer's and is carried over verbatim.</p>
     */
    @Test
    void aUserAddedHelperMethodSurvivesEveryEmitter() throws Exception {
        Path tree = tree();
        generateInPlace(tree);

        String method = """
                /**
                 * USER-METHOD doc - never generated, always preserved.
                 */
                public String displayName() {
                    return firstName + " " + lastName;
                }""";

        List<Path> files = List.of(
                tree.resolve("coop/hr/PersonSummaryBuilder.java"),
                tree.resolve("coop/hr/PersonSummaryBuilderTracking.java"),
                tree.resolve("coop/hr/PersonSummaryRecord.java"),
                tree.resolve("coop/hr/PersonSummary_.java"));
        for (Path file : files) {
            Assertions.assertTrue(Files.exists(file), "the level emits this file: " + file);
            String text = Files.readString(file);
            int lastBrace = text.lastIndexOf('}');
            Files.writeString(file, text.substring(0, lastBrace) + "\n" + method + "\n" + text.substring(lastBrace));
        }

        generateInPlace(tree);
        List<String> afterFirst = files.stream().map(CooperativeCodegenTest::read).toList();
        for (int i = 0; i < files.size(); i++) {
            String name = files.get(i).getFileName().toString();
            Assertions.assertTrue(afterFirst.get(i).contains("displayName()"),
                    "a user's helper method inside " + name + " must survive regeneration: " + afterFirst.get(i));
            Assertions.assertTrue(afterFirst.get(i).contains("USER-METHOD doc"),
                    "and its javadoc with it — the member is carried as text, not re-printed: " + name);
        }

        generateInPlace(tree);
        for (int i = 0; i < files.size(); i++) {
            Assertions.assertEquals(afterFirst.get(i), read(files.get(i)),
                    "and the carry-over is a fixed point, not a one-pass copy: "
                            + files.get(i).getFileName());
        }

        // The whole point of the mechanism is that the result is still real, compiling source.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "preserved-user-method",
                CompileHarness.javaSourcesUnder(tree), List.of());
    }

    /**
     * A user-added <strong>field</strong> is carried through too, which is the other non-type shape
     * § 1.1 names.
     */
    @Test
    void aUserAddedFieldSurvives() throws Exception {
        Path tree = tree();
        generateInPlace(tree);
        Path builder = tree.resolve("coop/hr/PersonSummaryBuilder.java");

        String field = "    /** USER-FIELD doc - never generated. */\n    private int passCount;\n";
        String text = Files.readString(builder);
        int lastBrace = text.lastIndexOf('}');
        Files.writeString(builder, text.substring(0, lastBrace) + field + text.substring(lastBrace));

        generateInPlace(tree);
        String after = read(builder);
        Assertions.assertTrue(after.contains("private int passCount;"),
                "a user's own field must survive: " + after);
        Assertions.assertTrue(after.contains("USER-FIELD doc"), after);
    }

    /**
     * An edit to a <strong>generated</strong> method's body is reported and reverted — DEC-020's second
     * state (follow-up plan § 1.1).
     *
     * <p>The direction is deliberate. Silently replacing the edit is what this whole subsystem exists to
     * stop; silently <em>keeping</em> it would leave a file compiling against a stale shape the next time
     * the emitter changes, which is a slower version of the same problem. So the canonical body is
     * emitted and the report names the member, which is the same treatment the R1 ledger gives a
     * hand-edited constant list.</p>
     */
    @Test
    void anEditToAGeneratedMethodIsReportedAndRevertedToTheCanonicalBody() throws Exception {
        Path tree = tree();
        generateInPlace(tree);
        Path builder = tree.resolve("coop/hr/PersonSummaryBuilder.java");

        String canonical = read(builder);
        // The marker is inside a method the emitter unmistakably owns: the positional dispatcher's
        // default arm. (The fixture has no nested record, so `build()` targets `META.create` — hence a
        // marker in `get(int)` rather than in `build()`.)
        String marker = "default -> null;";
        Assertions.assertTrue(canonical.contains(marker),
                "the fixture must contain the generated body this case edits: " + canonical);
        String edited = canonical.replace(marker, "// USER-EDIT\n            " + marker);
        Files.writeString(builder, edited);

        List<String> entries = generateInPlaceReporting(tree);

        Assertions.assertTrue(entries.stream().anyMatch(entry -> entry.startsWith("kind=generated_member_diverged")),
                "the edit to a generator-owned member is reported, not silently replaced: " + entries);
        Assertions.assertTrue(entries.stream().anyMatch(entry -> entry.contains("location=PersonSummaryBuilder.get")),
                "and the report names the member (the class AND the method), so a reader knows what to "
                        + "move: " + entries);
        String after = read(builder);
        Assertions.assertFalse(after.contains("USER-EDIT"),
                "the canonical body is emitted: " + after);

        // And the report is a one-off, not a permanent complaint: the next pass has nothing to say.
        List<String> second = generateInPlaceReporting(tree);
        Assertions.assertTrue(second.stream().noneMatch(entry -> entry.startsWith("kind=generated_member_diverged")),
                "a healthy file must stop being reported: " + second);
    }

    @Test
    void theViewInterfaceGainsTheBuilderEntryPointsWhenTheDeveloperOmittedThem() throws Exception {
        Path tree = tree();
        generateInPlace(tree);

        String view = Files.readString(tree.resolve("coop/hr/PersonSummary.java"));
        Assertions.assertTrue(view.contains("toBuilder()"),
                "§ 8.2/G2: BUILDER_ALL views expose toBuilder() by default: " + view);
        Assertions.assertTrue(view.contains("toBuilderTracking()"),
                "and toBuilderTracking(), because the level includes the tracking builder: " + view);
        Assertions.assertTrue(view.contains("new PersonSummaryBuilder(this)"));
        Assertions.assertTrue(view.contains("new PersonSummaryBuilderTracking(this)"));

        // Idempotent: the developer now owns two methods the generator recognises by shape.
        generateInPlace(tree);
        Assertions.assertEquals(view, Files.readString(tree.resolve("coop/hr/PersonSummary.java")),
                "a second pass must not re-add them");

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "view-entry-points",
                CompileHarness.javaSourcesUnder(tree), List.of());
    }

    @Test
    void aDeveloperWhoEditedTheEntryPointBodyKeepsIt() throws Exception {
        Path tree = tree();
        // The view already declares both methods; the generator must not touch the file at all, so
        // the developer's own body — here a hand-written constraining one — is exactly what stays.
        Files.writeString(tree.resolve("coop/hr/PersonSummary.java"), """
                package coop.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.BUILDER_ALL)
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                    String lastName();
                    public default PersonSummaryBuilder toBuilder() {
                        return new PersonSummaryBuilder(this).firstName("constrained");
                    }
                    public default PersonSummaryBuilderTracking toBuilderTracking() {
                        return new PersonSummaryBuilderTracking(this);
                    }
                }
                """);

        String before = Files.readString(tree.resolve("coop/hr/PersonSummary.java"));
        generateInPlace(tree);

        Assertions.assertEquals(before, Files.readString(tree.resolve("coop/hr/PersonSummary.java")),
                "shape recognition means an existing method is the developer's, whatever its body "
                        + "says (DEC-020's three-state model)");
    }

    @Test
    void levelsBelowBuilderGetNoEntryPoints() throws Exception {
        Path tree = Files.createTempDirectory("coop-meta-tree");
        Path packageDir = tree.resolve("coop/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(packageDir.resolve("PersonSummary.java"), """
                package coop.hr;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface PersonSummary extends PersonEntity {
                    String firstName();
                }
                """);
        String before = Files.readString(packageDir.resolve("PersonSummary.java"));

        generateInPlace(tree);

        Assertions.assertEquals(before, Files.readString(packageDir.resolve("PersonSummary.java")),
                "no builder exists below BUILDER, so there is no entry point to declare and the "
                        + "view file must be left completely alone");
    }
}
