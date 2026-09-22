package hr.hrg.hipster.entity.tooling.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Field-enum compaction (plan.dsflash § 12.4/7.13–7.16).
 *
 * <p>Compaction is the one operation in the plan that deliberately mutates a persisted ordinal
 * layout, so the tests are mostly about what it <strong>refuses</strong> to do. The positive case is
 * small and dull by design: drop tombstones, keep the survivors in order, say which ordinals moved.
 * The negative cases are where the damage would be.</p>
 */
class EnumCompactionCliTest {

    private static final String MARKED_HEADER =
            "// {enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}\n";

    private static final String UNMARKED_HEADER =
            "// {enabled:true, blockMarker: \"implicit\"}\n";

    /**
     * A field enum with a tombstone in the middle: {@code lastName} is deprecated, retires itself,
     * and is followed by a live {@code email} at ordinal 3.
     */
    private static String enumWithTombstone(String header) {
        return header
                + "package example;\n"
                + "import hr.hrg.hipster.entity.api.FieldDef;\n"
                + "public enum PersonSummary_ implements FieldDef {\n"
                + "    id(Long.class),\n"
                + "    firstName(String.class),\n"
                + "    @Deprecated\n"
                + "    lastName(java.lang.Object.class) {\n"
                + "        @Override\n"
                + "        public boolean retired() {\n"
                + "            return true;\n"
                + "        }\n"
                + "    },\n"
                + "    email(String.class);\n"
                + "    @Override\n"
                + "    public Class<?> javaType() {\n"
                + "        return null;\n"
                + "    }\n"
                + "    public static PersonSummary_ forName(String name) {\n"
                + "        switch (name) {\n"
                + "            case \"id\": return id;\n"
                + "            case \"firstName\": return firstName;\n"
                + "            case \"lastName\": return lastName;\n"
                + "            case \"email\": return email;\n"
                + "            default: return null;\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
    }

    private Path tree(String enumSource, String fileName) throws Exception {
        Path root = Files.createTempDirectory("compact-repo");
        Path packageDir = root.resolve("example");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve(fileName), enumSource);
        return root;
    }

    @Test
    void compactionRefusesWithoutBothAcknowledgements() throws Exception {
        Path root = tree(enumWithTombstone(MARKED_HEADER), "PersonSummary_.java");

        Assertions.assertEquals(EnumCompactionCli.EXIT_REFUSED,
                EnumCompactionCli.run(new String[] { "--repo", root.toString() }),
                "no acknowledgement at all is a refusal");
        Assertions.assertEquals(EnumCompactionCli.EXIT_REFUSED,
                EnumCompactionCli.run(new String[] { "--repo", root.toString(), "--allow-reorder" }),
                "and one flag is not the other: --allow-reorder says the layout may change, not that "
                        + "the data is gone");
        Assertions.assertEquals(EnumCompactionCli.EXIT_REFUSED,
                EnumCompactionCli.run(new String[] { "--repo", root.toString(),
                        "--acknowledge-drained-data" }),
                "nor is the operational acknowledgement sufficient on its own");
        Assertions.assertEquals(EnumCompactionCli.EXIT_ERROR,
                EnumCompactionCli.run(new String[] { "--allow-reorder", "--acknowledge-drained-data" }),
                "a missing --repo is a usage error, not a refusal");

        Assertions.assertTrue(
                Files.readString(root.resolve("example/PersonSummary_.java")).contains("lastName"),
                "and a refused run must not have touched the file");
    }

    @Test
    void aTombstoneIsDroppedAndTheSurvivorsAreRenumberedDensely() throws Exception {
        Path root = tree(enumWithTombstone(MARKED_HEADER), "PersonSummary_.java");

        EnumCompactionCli.Report report = EnumCompactionCli.compact(root, null);

        Assertions.assertEquals(1, report.dropped().size(), report.render());
        Assertions.assertEquals("lastName", report.dropped().get(0).constant());
        Assertions.assertEquals(2, report.dropped().get(0).ordinal(),
                "the report says where it used to be, which is what the migration needs");

        Assertions.assertEquals(List.of("email"), report.moves().stream()
                        .map(EnumCompactionCli.Move::constant).toList(),
                "only the constant after the tombstone moves: " + report.render());
        Assertions.assertEquals(3, report.moves().get(0).fromOrdinal());
        Assertions.assertEquals(2, report.moves().get(0).toOrdinal());

        String compacted = Files.readString(root.resolve("example/PersonSummary_.java"));
        Assertions.assertFalse(compacted.contains("lastName"),
                "the tombstone is gone from the compacted enum: " + compacted);
        Assertions.assertTrue(compacted.contains("id(") && compacted.contains("firstName(")
                        && compacted.contains("email("),
                "and every live constant survives: " + compacted);
        Assertions.assertFalse(compacted.contains("case \"lastName\""),
                "the forName arm for a dropped constant must go too, or the enum does not compile "
                        + "and the switch disagrees with the constant list: " + compacted);
        Assertions.assertTrue(compacted.contains("entityFieldEnum:true"),
                "the enum is still a ledger — compaction renumbers it, it does not opt it out: "
                        + compacted);
    }

    @Test
    void theCompactedListIsStillASubsequenceOfTheOriginal() throws Exception {
        Path root = tree(enumWithTombstone(MARKED_HEADER), "PersonSummary_.java");
        List<String> before = constantNames(
                Files.readString(root.resolve("example/PersonSummary_.java")));

        EnumCompactionCli.compact(root, null);

        List<String> after = constantNames(
                Files.readString(root.resolve("example/PersonSummary_.java")));

        // Compaction removes and renumbers; it never shuffles. This is the property that keeps the
        // ledger interpretable after a migration, and it is asserted rather than assumed because a
        // shuffle here would silently renumber live fields.
        Assertions.assertTrue(EnumCompactionCli.isSubsequenceInOrder(before, after),
                before + " -> " + after);
        Assertions.assertEquals(List.of("id", "firstName", "email"), after);

        // And the R1 checker's verdict against the PRE-migration baseline is a removal, not a clean
        // pass — which is correct, and is the migration record. Compaction is the one sanctioned
        // removal, so the checker deliberately cannot be the gate that authorises it.
        EnumConstantOrderChecker.OrderVerdict verdict = EnumConstantOrderChecker.compare(before, after);
        Assertions.assertFalse(verdict.ok(),
                "the checker reports the removal, by design: " + verdict.violations());
        Assertions.assertTrue(verdict.violations().stream()
                        .anyMatch(violation -> violation.contains("lastName")),
                "and names the constant it was told to drop: " + verdict.violations());
    }

    @Test
    void theSubsequenceCheckRejectsAShuffle() {
        Assertions.assertTrue(EnumCompactionCli.isSubsequenceInOrder(
                List.of("id", "firstName", "lastName", "email"), List.of("id", "firstName", "email")));
        Assertions.assertTrue(EnumCompactionCli.isSubsequenceInOrder(
                List.of("id", "firstName"), List.of("id", "firstName")));
        Assertions.assertTrue(EnumCompactionCli.isSubsequenceInOrder(
                List.of("id", "firstName"), List.of()));
        Assertions.assertFalse(EnumCompactionCli.isSubsequenceInOrder(
                List.of("id", "firstName", "lastName"), List.of("id", "lastName", "firstName")),
                "a swap is a shuffle, not a removal");
        Assertions.assertFalse(EnumCompactionCli.isSubsequenceInOrder(
                List.of("id", "firstName"), List.of("id", "email")),
                "a survivor that was never in the baseline is not a removal either");
    }

    @Test
    void aDeprecatedConstantThatDoesNotRetireItselfIsNotTouched() throws Exception {
        // The annotation alone is not enough. A developer documenting an ordinary deprecation would
        // otherwise lose the constant and every ordinal after it would move.
        String source = MARKED_HEADER
                + "package example;\n"
                + "public enum PersonSummary_ {\n"
                + "    id(Long.class),\n"
                + "    @Deprecated\n"
                + "    legacyCode(String.class),\n"
                + "    email(String.class);\n"
                + "    PersonSummary_(Class<?> type) { }\n"
                + "}\n";
        Path root = tree(source, "PersonSummary_.java");

        EnumCompactionCli.Report report = EnumCompactionCli.compact(root, null);

        Assertions.assertTrue(report.dropped().isEmpty(), report.render());
        Assertions.assertTrue(report.compactedFiles().isEmpty(),
                "nothing to drop means nothing to rewrite: " + report.render());
        Assertions.assertTrue(Files.readString(root.resolve("example/PersonSummary_.java")).contains("legacyCode"));
    }

    @Test
    void aMarkerLessEnumIsLeftAlone() throws Exception {
        Path root = tree(enumWithTombstone(UNMARKED_HEADER), "PersonSummary_.java");

        EnumCompactionCli.Report report = EnumCompactionCli.compact(root, null);

        Assertions.assertTrue(report.compactedFiles().isEmpty(), report.render());
        Assertions.assertTrue(report.skipped().stream().anyMatch(note -> note.contains("no")
                        && note.contains("marker")),
                "the reason is named, not silent: " + report.render());
        Assertions.assertTrue(Files.readString(root.resolve("example/PersonSummary_.java")).contains("lastName"),
                "a marker-less enum has no committed ledger and is bootstrapped by a normal pass, not "
                        + "compacted here");
    }

    @Test
    void anUnparseableEnumIsRefusedRatherThanPartlyRewritten() throws Exception {
        Path root = tree(MARKED_HEADER + "package example;\npublic enum PersonSummary_ {\n"
                + "    id(Long.class), broken(\n}\n", "PersonSummary_.java");
        String before = Files.readString(root.resolve("example/PersonSummary_.java"));

        EnumCompactionCli.Report report = EnumCompactionCli.compact(root, null);

        Assertions.assertTrue(report.compactedFiles().isEmpty(), report.render());
        Assertions.assertEquals(before, Files.readString(root.resolve("example/PersonSummary_.java")),
                "a file that cannot be read is not a file to rewrite");
    }

    @Test
    void theTargetFilterRestrictsWhatIsCompacted() throws Exception {
        Path root = Files.createTempDirectory("compact-target");
        Files.createDirectories(root.resolve("kept"));
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve("kept/PersonSummary_.java"), enumWithTombstone(MARKED_HEADER));
        Files.writeString(root.resolve("ignored/OtherSummary_.java"),
                enumWithTombstone(MARKED_HEADER).replace("PersonSummary_", "OtherSummary_"));

        EnumCompactionCli.Report report = EnumCompactionCli.compact(root, "kept/");

        Assertions.assertEquals(1, report.compactedFiles().size(), report.render());
        Assertions.assertTrue(Files.readString(root.resolve("ignored/OtherSummary_.java")).contains("lastName"),
                "the filter is honoured in both directions");
    }

    @Test
    void buildOutputsAndSecondCheckoutsAreNotScanned() throws Exception {
        Path root = Files.createTempDirectory("compact-excluded");
        for (String excluded : List.of("target/generated", "tmp/regen4", ".kilo/worktrees/other")) {
            Path dir = root.resolve(excluded);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("PersonSummary_.java"), enumWithTombstone(MARKED_HEADER));
        }
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/PersonSummary_.java"), enumWithTombstone(MARKED_HEADER));

        EnumCompactionCli.Report report = EnumCompactionCli.compact(root, null);

        Assertions.assertEquals(1, report.compactedFiles().size(),
                "only the real source file is compacted; the same exclusion list the order checker "
                        + "uses applies here (gate review GR-6): " + report.render());
        Assertions.assertTrue(report.compactedFiles().get(0).toString().contains("src"));
    }

    /** The constant names in declaration order, read back out of the source through the LST. */
    private static List<String> constantNames(String source) throws Exception {
        var unit = hr.hrg.hipster.entity.tooling.SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the enum must parse");
        var declaration = hr.hrg.hipster.entity.tooling.TreeQueries.enums(unit).get(0);
        List<String> names = new java.util.ArrayList<>();
        for (var statement : declaration.getBody().getStatements()) {
            if (statement instanceof org.openrewrite.java.tree.J.EnumValueSet values) {
                for (var value : values.getEnums()) {
                    names.add(value.getName().getSimpleName());
                }
            }
        }
        return names;
    }
}
