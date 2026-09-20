package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EnumCompactionCli;
import hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Phase 7 exit gate's compaction half (plan.dsflash § 12.4, § 12.5): <em>"the R1 checker passes
 * after a compaction round-trip"</em>.
 *
 * <p>The compaction tests next door assert what the command does and refuses. This one asserts the
 * property the gate is actually about, which is a property of the <strong>sequence</strong>
 * compact → regenerate → keep working:</p>
 *
 * <ol>
 *   <li>a ledger with a tombstone is compacted, and the compacted list becomes the new baseline;</li>
 *   <li>a generation pass over that tree is a no-op — the reclaimed layout is stable and the enum is
 *       immediately frozen in its new shape (DEC-025 § 6);</li>
 *   <li>a field added afterwards lands at the <strong>end</strong>, and the R1 checker accepts it
 *       against the compacted baseline — so migration is a one-time event after which the ledger is
 *       append-only again, which is the whole point of the escape hatch.</li>
 * </ol>
 *
 * <p>It is also the test that would catch the failure mode the checker cannot: a compaction that
 * renumbers a <em>live</em> field, or one that leaves the file in a shape the generator then
 * re-expands.</p>
 */
class CompactionRoundTripTest {

    private static final String MARKER = """
            package mig.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package mig.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                String email();
            }
            """;

    /** The revision before the migration: the middle accessor removed, so its constant is a tombstone. */
    private static final String VIEW_WITHOUT_LAST_NAME = VIEW.replace("String lastName();\n", "");

    /**
     * The revision after the migration: a new field on the view that has already lost {@code lastName}.
     *
     * <p>Deliberately built from {@link #VIEW_WITHOUT_LAST_NAME} rather than from {@link #VIEW}: the
     * migration is what removed {@code lastName} from the code, so a revision that re-added it would
     * be testing the append-a-new-constant path instead of the append-only-after-migration property.
     * (It would also be correct R1 behaviour — a returning field gets a fresh ordinal at the end — but
     * that is the subject of {@code FieldEnumLedgerRegenerationTest}, not of this gate.)</p>
     */
    private static final String VIEW_PLUS_ONE = VIEW_WITHOUT_LAST_NAME.replace("String email();",
            "String email();\n    String phoneNumber();");

    private static List<String> constants(Path enumFile) throws Exception {
        var declaration = new com.github.javaparser.JavaParser().parse(Files.readString(enumFile))
                .getResult().orElseThrow()
                .findFirst(com.github.javaparser.ast.body.EnumDeclaration.class).orElseThrow();
        return declaration.getEntries().stream()
                .map(com.github.javaparser.ast.body.EnumConstantDeclaration::getNameAsString)
                .toList();
    }

    @Test
    void theLedgerIsAppendOnlyAgainAfterACompaction() throws Exception {
        // ---- 1. a ledger with a tombstone in the middle --------------------------------
        Path tree = Files.createTempDirectory("migration-tree");
        Path packageDir = tree.resolve("mig/hr");
        Files.createDirectories(packageDir);
        Path viewFile = packageDir.resolve("PersonSummary.java");
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(viewFile, VIEW);

        EntityMetadataGenerator.generate(tree, tree);
        Files.writeString(viewFile, VIEW_WITHOUT_LAST_NAME);
        EntityMetadataGenerator.generate(tree, tree);

        Path enumFile = packageDir.resolve("PersonSummary_.java");
        Assertions.assertEquals(List.of("id", "firstName", "lastName", "email"), constants(enumFile),
                "the middle accessor is gone and its constant is a tombstone in place");
        Assertions.assertTrue(Files.readString(enumFile).contains("retired()"),
                "and it reports itself retired");

        // ---- 2. compactions, with both acknowledgements --------------------------------
        EnumCompactionCli.Report report = EnumCompactionCli.compact(tree, null);
        Assertions.assertEquals(1, report.compactedFiles().size(), report.render());
        List<String> compactedBaseline = constants(enumFile);
        Assertions.assertEquals(List.of("id", "firstName", "email"), compactedBaseline,
                "the tombstone is dropped and the survivors are dense: " + report.render());
        Assertions.assertTrue(report.moves().stream().anyMatch(move -> move.constant().equals("email")
                        && move.fromOrdinal() == 3 && move.toOrdinal() == 2),
                "and the migration record names the ordinal that moved: " + report.render());

        // ---- 3. the reclaimed layout is stable -----------------------------------------
        EntityMetadataGenerator.generate(tree, tree);
        Assertions.assertEquals(compactedBaseline, constants(enumFile),
                "a generation pass over the compacted tree is a no-op: compaction is a migration, "
                        + "not something the generator then undoes");
        Assertions.assertTrue(Files.readString(enumFile).contains("entityFieldEnum:true"),
                "and the enum is still a guarded ledger (DEC-025 section 6)");

        // ---- 4. appending still works, and the checker passes --------------------------
        Files.writeString(viewFile, VIEW_PLUS_ONE);
        EntityMetadataGenerator.generate(tree, tree);

        List<String> afterAppend = constants(enumFile);
        Assertions.assertEquals(List.of("id", "firstName", "email", "phoneNumber"), afterAppend,
                "the new field is appended at the end — the compacted order is the ledger now");

        EnumConstantOrderChecker.OrderVerdict verdict =
                EnumConstantOrderChecker.compare(compactedBaseline, afterAppend);
        Assertions.assertTrue(verdict.ok(),
                "the R1 checker passes after a compaction round-trip, which is the Phase 7 exit gate: "
                        + verdict.violations());

        // And the *pre-migration* baseline would have reported the compaction itself — that is the
        // checker doing its job, and the reason the baseline has to be advanced by the migration
        // commit rather than the migration being smuggled past the checker.
        EnumConstantOrderChecker.OrderVerdict againstOld =
                EnumConstantOrderChecker.compare(List.of("id", "firstName", "lastName", "email"), afterAppend);
        Assertions.assertFalse(againstOld.ok(),
                "the pre-migration baseline still reports the tombstone's removal: "
                        + againstOld.violations());
    }

    @Test
    void compactionAndRegenerationAgreeOnTheFilesContent() throws Exception {
        Path tree = Files.createTempDirectory("migration-agree");
        Path packageDir = tree.resolve("mig/hr");
        Files.createDirectories(packageDir);
        Path viewFile = packageDir.resolve("PersonSummary.java");
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(viewFile, VIEW);
        EntityMetadataGenerator.generate(tree, tree);
        Files.writeString(viewFile, VIEW_WITHOUT_LAST_NAME);
        EntityMetadataGenerator.generate(tree, tree);

        Path enumFile = packageDir.resolve("PersonSummary_.java");
        EnumCompactionCli.compact(tree, null);
        EntityMetadataGenerator.generate(tree, tree);

        // Compaction rewrites generated source through a parser, so its output is not byte-identical to
        // the generator's canonical emission. The documented procedure therefore ends with a generation
        // pass, and this asserts what that pass leaves behind: a byte-identical fixed point, which is
        // what makes the migration commit reviewable.
        String afterFirstPass = Files.readString(enumFile);
        EntityMetadataGenerator.generate(tree, tree);
        Assertions.assertEquals(afterFirstPass, Files.readString(enumFile),
                "the post-migration tree is a fixed point of the generator");
    }
}
