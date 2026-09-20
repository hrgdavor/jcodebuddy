package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The divergence kinds of plan.dsflash § 8.7/3.20, exercised on real generated files.
 *
 * <p>The plan is specific about which two must exist first — "field in the enum but not in the
 * interface" and "field in the interface but not in the enum" — because those are the two that bit
 * the example: a field set can change silently in either direction, and no compiler catches it. The
 * remaining kinds ({@code stale_switch}, {@code missing_setter}, {@code type_mismatch},
 * {@code ordinal_drift}, {@code enum_reorder_allowed}, {@code enum_not_parsed}) are asserted here
 * too, so the {@code DivergenceReporter.KINDS} list stops being a list of intentions.</p>
 *
 * <p>Every case works the same way, and deliberately so: generate once, <strong>damage</strong> the
 * generated artifact the way a hand edit or a stale file would, generate again with a reporter
 * attached, and assert the report names the damage. That is the only sequence in which these checks
 * have anything to compare — a first pass into an empty directory has no previous revision.</p>
 *
 * <p>The damage is applied with the exact text the emitter produces (the constant list is one line,
 * {@code forName} is a statement switch). That is not incidental: a substitution that misses leaves
 * the file valid and unchanged, and the test then passes for the wrong reason. The
 * {@code aCleanPass...} guard at the end of this class exists to catch that mistake for the whole
 * file.</p>
 */
class DivergenceKindTest {

    private static final String MARKER = """
            package audit.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package audit.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                Integer age();
            }
            """;

    /**
     * One emitted enum constant with its class body.
     *
     * <p>Since every {@code COLUMN} field carries a {@code column()} override, a constant is no longer
     * just {@code name(Type.class)} on a shared line — it is that plus a class body and its own
     * separator. The damage below has to address the real shape, and the assertions after it are what
     * proves the damage landed.</p>
     */
    private static String constantOf(String name, String type) {
        return name + "(" + type + ".class) {\n"
                + "\n"
                + "        @Override()\n"
                + "        public String column() {\n"
                + "            return \"" + name + "\";\n"
                + "        }\n"
                + "    }";
    }

    /** One {@code forName} arm, as the emitter writes it (a statement switch, F-14; qualified names, F-42). */
    private static String armOf(String name) {
        return "            case \"" + name + "\":\n                return PersonSummary_." + name + ";\n";
    }

    /**
     * Removes one whole emitted constant, separator and class body included.
     *
     * <p>Written as a regex because the emitter's spacing around a constant body and its leading
     * separator is a printer detail, not a contract — pinning it in a literal would make this fixture
     * fail on a purely cosmetic emitter change, and the {@link #damaged} guard already ensures a
     * non-matching damage cannot pass silently.</p>
     */
    private static String removeConstant(String source, String name) {
        String pattern = "(?s)\\s*,?\\s*" + java.util.regex.Pattern.quote(name)
                + "\\([^)]*\\)\\s*\\{.*?\\n    \\}";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(source);
        Assertions.assertTrue(matcher.find(),
                "the fixture must contain the constant this case removes, or the test passes vacuously: "
                        + name);
        return matcher.replaceFirst("");
    }

    private static final String AGE_CONSTANT = constantOf("age", "java.lang.Integer");
    private static final String AGE_ARM = armOf("age");

    private record Tree(Path sourceRoot, Path outputRoot, Path packageDir) {
    }

    /** A tree whose first generation pass has already run, so every artifact has a previous revision. */
    private Tree generatedTree() throws Exception {
        Path sourceRoot = Files.createTempDirectory("div-source");
        Path outputRoot = Files.createTempDirectory("div-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return new Tree(sourceRoot, outputRoot, outputRoot.resolve("audit/hr"));
    }

    private static List<String> kinds(Path sourceRoot, Path outputRoot) throws Exception {
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        return reporter.entries();
    }

    private static boolean hasKind(List<String> entries, String kind) {
        return entries.stream().anyMatch(entry -> entry.startsWith("kind=" + kind));
    }

    private static String entryOf(List<String> entries, String kind) {
        return entries.stream().filter(entry -> entry.startsWith("kind=" + kind))
                .findFirst().orElse("(no " + kind + " entry in " + entries + ")");
    }

    /** Applies a damage substitution and fails loudly if it did not match anything. */
    private static String damaged(String source, String from, String to) {
        Assertions.assertTrue(source.contains(from),
                "the fixture must contain the text this case damages, or the test passes vacuously: "
                        + from);
        return source.replace(from, to);
    }

    /**
     * Reads a generated file with its line endings normalised to {@code \n}.
     *
     * <p>The emitter prints through JavaParser, which uses the platform line separator, so on Windows
     * the generated source has {@code \r\n}. Damage patterns written as Java text blocks carry
     * {@code \n}, so a raw comparison would silently fail to match — and a substitution that does not
     * match is exactly the vacuous pass {@link #damaged} guards against.</p>
     */
    private static String read(Path file) throws Exception {
        return Files.readString(file).replace("\r\n", "\n");
    }

    @Test
    void aFieldInTheInterfaceButNotTheEnumIsReported() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");

        // Damage: the accessor stays on the interface, the constant disappears from the enum. This is
        // the "stale enum" direction and it is invisible to javac.
        Files.writeString(enumFile, damaged(
                removeConstant(read(enumFile), "age"),
                AGE_ARM, ""));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "field_in_interface_not_in_enum"),
                "the interface still declares age(): " + entries);
        Assertions.assertTrue(entryOf(entries, "field_in_interface_not_in_enum").contains("age"),
                entryOf(entries, "field_in_interface_not_in_enum"));
        Assertions.assertTrue(hasKind(entries, "enum_constant_appended"),
                "and the R1 ledger event for the same fact is still produced: " + entries);
    }

    @Test
    void aFieldInTheEnumButNotTheInterfaceIsReported() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");

        // Damage: a constant with no accessor. On a marker-carrying enum this becomes a tombstone, so
        // the ordinal is preserved — but the field set has changed and the report must say so.
        Files.writeString(enumFile, damaged(
                damaged(read(enumFile),
                        "\n    ;", "\n    , legacyCode(java.lang.String.class);\n    ;"),
                AGE_ARM, AGE_ARM + armOf("legacyCode")));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "field_in_enum_not_in_interface"), entries.toString());
        Assertions.assertTrue(entryOf(entries, "field_in_enum_not_in_interface").contains("legacyCode"));
        Assertions.assertTrue(hasKind(entries, "field_retired"),
                "and the R1 tombstone decision it implies: " + entries);
    }

    @Test
    void aSwitchArmWithNoConstantIsReported() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");

        Files.writeString(enumFile, damaged(read(enumFile), AGE_ARM,
                AGE_ARM + "            case \"ghost\":\n                return null;\n"));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "stale_switch"), entries.toString());
        Assertions.assertTrue(entryOf(entries, "stale_switch").contains("ghost"),
                "the report names the unresolved label, not just the file");
    }

    @Test
    void aConstantWhoseDeclaredTypeChangedIsReported() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");

        // Damage: someone "fixed" the age field to a String. The emitter writes Integer, so the two
        // disagree — which is exactly what the report exists to surface.
        Files.writeString(enumFile, damaged(read(enumFile),
                AGE_CONSTANT, "age(java.lang.String.class)"));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "type_mismatch"), entries.toString());
        String entry = entryOf(entries, "type_mismatch");
        Assertions.assertTrue(entry.contains("String.class") && entry.contains("Integer.class"),
                "current and canonical are both named: " + entry);
    }

    @Test
    void anUnparseableEnumIsReportedInsteadOfSilentlyRebuilt() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");

        // Damage: a hand edit that breaks a constant's brackets — what a typo in a large enum actually
        // looks like. Before the diagnostic existed the parse failure was indistinguishable from
        // "nothing to preserve", so the append-only ledger was dropped in silence — the one outcome
        // R1 exists to prevent.
        Files.writeString(enumFile, damaged(read(enumFile),
                AGE_CONSTANT, "age(java.lang.Integer.class;"));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "enum_not_parsed"), entries.toString());
        Assertions.assertTrue(entryOf(entries, "enum_not_parsed").contains("R1"),
                "and the action says the ledger was NOT preserved: " + entryOf(entries, "enum_not_parsed"));
    }

    @Test
    void declaringAFieldOutOfOrderInAMarkerLessEnumIsReportedAsDrift() throws Exception {
        Path sourceRoot = Files.createTempDirectory("div-drift-source");
        Path outputRoot = Files.createTempDirectory("div-drift-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"), VIEW);
        Files.createDirectories(outputRoot.resolve("audit/hr"));

        // A pre-R1 enum: no DEC-021 header, so no `entityFieldEnum` marker, its constant order does
        // not match the interface, and it still carries a constant for an accessor that is gone.
        // There is no committed ledger to preserve, so the bootstrap path drops the stale constant,
        // renumbers densely, and reports both facts.
        Files.writeString(outputRoot.resolve("audit/hr/PersonSummary_.java"), """
                package audit.hr;
                import hr.hrg.hipster.entity.api.FieldDef;
                import java.lang.reflect.Type;
                public enum PersonSummary_ implements FieldDef {
                    lastName(String.class),
                    id(Long.class),
                    legacy(String.class),
                    firstName(String.class),
                    age(Integer.class);
                    private final Type javaType;
                    PersonSummary_(Type javaType) { this.javaType = javaType; }
                    @Override public Type javaType() { return javaType; }
                }
                """);

        List<String> entries = kinds(sourceRoot, outputRoot);

        Assertions.assertTrue(hasKind(entries, "ordinal_drift"), entries.toString());
        String drift = entryOf(entries, "ordinal_drift");
        Assertions.assertTrue(drift.contains("current=") && drift.contains("canonical="),
                "the drift reports both positions, not just that something moved: " + drift);
        Assertions.assertTrue(hasKind(entries, "enum_constant_removed"),
                "and the marker-less path is the bootstrap one, which reports removals: " + entries);
    }

    @Test
    void aWritableFieldWithNoSetterInTheExistingBuilderIsReported() throws Exception {
        Tree tree = generatedTree();
        Path builderFile = tree.packageDir().resolve("PersonSummaryBuilder.java");

        // Damage: delete the fluent setter for `lastName`, leaving its field and read accessor. The
        // generator cannot tell a deliberate deletion from a stale file, so it reports instead.
        String builder = read(builderFile);
        String setter = "    public PersonSummaryBuilder lastName(String value) {";
        int start = builder.indexOf(setter);
        Assertions.assertTrue(start > 0, "the emitted builder has the setter to remove");
        int end = builder.indexOf("\n    }\n", start) + "\n    }\n".length();
        Files.writeString(builderFile, builder.substring(0, start) + builder.substring(end));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "missing_setter"), entries.toString());
        Assertions.assertTrue(entryOf(entries, "missing_setter").contains("lastName"));
    }

    @Test
    void theReorderEscapeHatchIsAlwaysVisibleWhenPresent() throws Exception {
        Tree tree = generatedTree();
        Path enumFile = tree.packageDir().resolve("PersonSummary_.java");

        // The escape hatch is opted into by hand, which is the only way it can appear. Its presence
        // must never be silent: a reader has to see that this enum's order is not guaranteed.
        Files.writeString(enumFile, damaged(read(enumFile),
                "{enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}",
                "{enabled:true, entityFieldEnum:true, allowReorder: true, blockMarker: \"implicit\"}"));

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        Assertions.assertTrue(hasKind(entries, "enum_reorder_allowed"), entries.toString());
    }

    @Test
    void aCleanPassReportsNoFieldSetOrSetterDivergence() throws Exception {
        Tree tree = generatedTree();

        List<String> entries = kinds(tree.sourceRoot(), tree.outputRoot());

        // The guard on the whole class: these checks must be silent when nothing is wrong, or the
        // report becomes noise and stops being read.
        for (String kind : List.of("field_in_interface_not_in_enum", "field_in_enum_not_in_interface",
                "stale_switch", "missing_setter", "type_mismatch", "ordinal_drift",
                "enum_reorder_allowed", "enum_not_parsed")) {
            Assertions.assertFalse(hasKind(entries, kind),
                    "a clean regeneration must not report " + kind + ": " + entries);
        }
    }
}
