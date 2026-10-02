package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Every read of an existing file fails safe and says so (plan.dsflash § 2.3 of the follow-up;
 * F-34, F-36, DR-7).
 *
 * <p>The rule being pinned is one sentence: <em>an unreadable previous revision is preserved and
 * reported, never rebuilt or overwritten in silence</em>. It matters most in three places, and each
 * is a test here:</p>
 * <ul>
 *   <li>the <strong>field enum</strong> — its constant list is the persisted ordinal ledger, so
 *       rebuilding it from an unreadable file is the silent renumbering R1 exists to prevent
 *       (F-34's original finding);</li>
 *   <li>a <strong>generated builder</strong> — an error-tolerant partial parse would make every
 *       setter look missing and produce a wall of false positives about a file nobody can read;</li>
 *   <li>the <strong>view interface</strong> — the one file the developer owns, which must not be
 *       rewritten on the strength of a partial read of itself.</li>
 * </ul>
 *
 * <p>The tests are damage-based for the same reason {@code DivergenceKindTest} is: a substitution
 * that misses leaves a valid, unchanged file, and the test then passes for the wrong reason. Each
 * damage is asserted to have matched before the pass runs.</p>
 */
class ParseGuardTest {

    private static final String MARKER = """
            package guard.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package guard.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
            }
            """;

    private record Tree(Path sourceRoot, Path outputRoot, Path packageDir) {
    }

    /** A tree whose first pass has already run, so every artifact has a previous revision. */
    private Tree generatedTree() throws Exception {
        Path sourceRoot = Files.createTempDirectory("guard-source");
        Path outputRoot = Files.createTempDirectory("guard-output");
        Path packageDir = sourceRoot.resolve("guard/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(packageDir.resolve("PersonSummary.java"), VIEW);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return new Tree(sourceRoot, outputRoot, outputRoot.resolve("guard/hr"));
    }

    private static DivergenceReporter secondPass(Path sourceRoot, Path outputRoot) throws Exception {
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        return reporter;
    }

    /** Applies a damage and fails loudly if the pattern was not there to damage. */
    private static void damage(Path file, String from, String to) throws Exception {
        String source = Files.readString(file);
        Assertions.assertTrue(source.contains(from),
                "the fixture must contain the text this case damages, or the test passes vacuously: "
                        + from + " in " + file);
        Files.writeString(file, source.replace(from, to));
    }

    /** A syntax error that makes the whole file unparseable rather than partially parseable. */
    private static void breakSyntax(Path file) throws Exception {
        Files.writeString(file, Files.readString(file) + "\n@@@ not java @@@\n");
    }

    @Test
    void anUnreadableFieldEnumIsLeftExactlyAsItIsAndReported() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");
        // The bracket of a constant: JavaParser returns a PARTIAL unit for this, which is how the
        // original defect made the enum look like it had no constants at all.
        damage(enumFile, "lastName(java.lang.String.class)", "lastName(java.lang.String.class");
        String damaged = Files.readString(enumFile);

        DivergenceReporter reporter = secondPass(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertEquals(1, reporter.ofKind("enum_not_parsed").size(),
                "the unreadable enum is named exactly once, as the ledger kind: " + reporter.render());
        Assertions.assertTrue(reporter.ofKind("enum_not_parsed").get(0).contains("PersonSummary_"),
                reporter.render());
        Assertions.assertTrue(reporter.ofKind("enum_not_parsed").get(0).contains("R1"),
                "and the action says the ordinal ledger was not preserved: " + reporter.render());
        Assertions.assertEquals(damaged, Files.readString(enumFile),
                "and the file is not regenerated: rebuilding an unreadable ledger is exactly the "
                        + "silent renumbering R1 exists to prevent");
        Assertions.assertFalse(reporter.ofKind("polymorphic_root_enum_preserved").stream()
                        .anyMatch(e -> e.contains("PersonSummary_")),
                "an unreadable file is not a polymorphic root — the two facts are reported apart: "
                        + reporter.render());
    }

    @Test
    void anUnreadableBuilderIsReportedInsteadOfEverySetterLookingMissing() throws Exception {
        Tree tree = generatedTree();
        Path builder = tree.packageDir().resolve("PersonSummaryBuilder.java");
        breakSyntax(builder);

        DivergenceReporter reporter = secondPass(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertFalse(reporter.ofKind("source_not_parsed").isEmpty(),
                "the unreadable builder is named: " + reporter.render());
        Assertions.assertTrue(reporter.ofKind("source_not_parsed").stream()
                        .anyMatch(e -> e.contains("PersonSummaryBuilder")),
                reporter.render());
        Assertions.assertTrue(reporter.ofKind("missing_setter").isEmpty(),
                "a partial parse must not be mistaken for a builder whose setters are all gone — that "
                        + "would be a wall of false positives about an unreadable file: "
                        + reporter.render());
    }

    @Test
    void anUnreadableViewInterfaceIsNotRewritten() throws Exception {
        Tree tree = generatedTree();
        Path view = tree.sourceRoot().resolve("guard/hr/PersonSummary.java");
        // Remove the entry points first, so the emitter WOULD write if it decided to: the assertion
        // is then about the guard, not about a file that happened to be complete.
        damage(view, "    String firstName();", "    String firstName();\n    // entry points removed");
        breakSyntax(view);
        String damaged = Files.readString(view);

        DivergenceReporter reporter = secondPass(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertEquals(damaged, Files.readString(view),
                "the developer's own file is left untouched when it cannot be read");
        Assertions.assertTrue(reporter.ofKind("source_not_parsed").stream()
                        .anyMatch(e -> e.contains("PersonSummary")),
                "and the pass says it could not read it: " + reporter.render());
    }

    /**
     * The scan-level guard: a file nobody can read contributes nothing, and the pass says which file.
     *
     * <p>This is F-23's failure mode in its general form — a partial or skipped parse loses a whole
     * file's interfaces and declared types, and the only symptom is a missing generated file. It is
     * also what {@code source_not_parsed} was added for: before it, the walk returned early on a null
     * unit and no one could tell "no views here" from "this file was skipped".</p>
     */
    @Test
    void anUnreadableSourceFileInTheScanIsReportedAndItsTypesAreAbsent() throws Exception {
        Tree tree = generatedTree();
        Path markerFile = tree.sourceRoot().resolve("guard/hr/PersonEntity.java");
        breakSyntax(markerFile);

        DivergenceReporter reporter = secondPass(tree.sourceRoot(), tree.outputRoot());

        List<String> entries = reporter.ofKind("source_not_parsed");
        Assertions.assertTrue(entries.stream().anyMatch(e -> e.contains("guard/hr/PersonEntity.java")),
                "the skipped file is named by its path in the source root: " + reporter.render());
        Assertions.assertFalse(entries.isEmpty(), reporter.render());
        Assertions.assertTrue(entries.stream().anyMatch(e -> e.contains("contributed no interfaces")),
                "and the message says what the consequence was: " + reporter.render());
    }
}
