package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * § 6.4 test 25, generator half: a removed accessor becomes an R1.4 tombstone
 * (plan.dsflash § 4.6/R1.4, § 8.3/3.7a, § 4.7/DR-2).
 *
 * <h3>The defect this class was written around</h3>
 * <p>A marker-carrying enum that loses an accessor keeps the constant <strong>in place</strong>:
 * {@code @Deprecated}, a short reason, {@code retired() == true}, still resolvable by {@code forName},
 * unchanged constant order. That much came out of {@code planLedger}.</p>
 *
 * <p>What did <em>not</em> come out of it — and what this test found — is that the rest of the
 * generator computed a field's ordinal from the index of the field in the <strong>interface's
 * resolved list</strong>, while the enum defines it by the <strong>ledger</strong>. The two agree
 * exactly while no tombstone precedes a live field. Retire a middle accessor and the enum said
 * "email is ordinal 3" while the JDBC binder said "email is ordinal 2", so the binder read the
 * retired {@code lastName} slot and wrote it into the email column; the tracking builder marked
 * ordinal 2 for email, colliding with the tombstone's own ordinal; and the record
 * had three components for a four-slot enum. None of it looks wrong, and no compiler catches it.</p>
 *
 * <p>The fix is {@code EntityMetadataGenerator.ledgerOrderedProperties}: the ordinal space is made
 * explicit once, at the one place that can see both the interface and the committed enum, and every
 * emitter that maps a field to an ordinal consumes that list. A retired constant becomes a
 * {@code RETIRED} placeholder property, which is not writable — so it keeps its slot in
 * {@code get(int)}, in the record's components and in the positional array, and gets no setter, no
 * read accessor and no column. The class asserts the whole chain, ending with a compile gate,
 * because the compiler is the only artifact that can check all of it at once.</p>
 */
class TombstoneLedgerTest {

    private static final String MARKER = """
            package tomb.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW_WITH_ALL_THREE = """
            package tomb.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
                String email();
            }
            """;

    /** The trailing-removal revision: {@code email} is the tombstone, so nothing follows it. */
    private static final String WITHOUT_EMAIL = """
            package tomb.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
            }
            """;

    /** The mid-list revision: the tombstone sits at ordinal 2 and a live field follows at 3. */
    private static final String WITHOUT_LAST_NAME = """
            package tomb.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String email();
            }
            """;

    private record Tree(Path sourceRoot, Path outputRoot, Path packageDir, String enumSource,
                        DivergenceReporter divergences) {

        String read(String fileName) throws Exception {
            return Files.readString(packageDir.resolve(fileName)).replace("\r\n", "\n");
        }
    }

    private Tree generateThenRewriteTheView(String secondRevision) throws Exception {
        Path sourceRoot = Files.createTempDirectory("tomb-source");
        Path outputRoot = Files.createTempDirectory("tomb-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Path viewFile = sourceRoot.resolve("PersonSummary.java");
        Files.writeString(viewFile, VIEW_WITH_ALL_THREE);

        EntityMetadataGenerator.setGenerateAdapters(true);
        try {
            // In place, because the ledger is read from the file it is about to replace.
            EntityMetadataGenerator.generate(sourceRoot, outputRoot);
            Files.writeString(viewFile, secondRevision);

            DivergenceReporter reporter = new DivergenceReporter();
            EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);

            Path packageDir = outputRoot.resolve("tomb/hr");
            return new Tree(sourceRoot, outputRoot, packageDir,
                    Files.readString(packageDir.resolve("PersonSummary_.java")).replace("\r\n", "\n"),
                    reporter);
        } finally {
            EntityMetadataGenerator.setGenerateAdapters(false);
        }
    }

    @Test
    void theRemovedAccessorsConstantIsKeptInPlaceAndRetired() throws Exception {
        Tree tree = generateThenRewriteTheView(WITHOUT_EMAIL);
        String source = tree.enumSource();

        Assertions.assertTrue(source.contains("email(java.lang.Object.class)"),
                "the constant stays: R1.4 keeps the slot so no ordinal moves. Its declared type is "
                        + "Object because the accessor that carried the real type is gone — the "
                        + "tombstone promises only that the slot exists: " + source);
        Assertions.assertTrue(source.contains("retired()") && source.contains("return true;"),
                "and reports itself retired, which is what every writer consults: " + source);

        int email = source.indexOf("email(java.lang.Object.class)");
        Assertions.assertTrue(source.substring(Math.max(0, email - 400), email).contains("@Deprecated"),
                "the constant is @Deprecated with a reason, so a reader sees why it is still there");
        Assertions.assertTrue(source.contains("no longer an accessor on PersonSummary"),
                "the reason names the view: " + source);
    }

    @Test
    void theConstantOrderAndCountAreUnchanged() throws Exception {
        String source = generateThenRewriteTheView(WITHOUT_EMAIL).enumSource();

        int id = source.indexOf("id(java.lang.Long.class)");
        int firstName = source.indexOf("firstName(java.lang.String.class)");
        int lastName = source.indexOf("lastName(java.lang.String.class)");
        int email = source.indexOf("email(java.lang.Object.class)");

        Assertions.assertTrue(id >= 0 && firstName > id && lastName > firstName && email > lastName,
                "the ordinal ledger is append-only: the tombstone keeps its position at the end, and "
                        + "no live constant moved: " + source);
        Assertions.assertEquals(4, countConstants(source),
                "four constants, tombstone included — fieldCount must not shrink or the positional "
                        + "array contract breaks");
    }

    @Test
    void forNameStillResolvesTheRetiredName() throws Exception {
        String source = generateThenRewriteTheView(WITHOUT_EMAIL).enumSource();

        // An incoming payload written before the removal still carries the field. Rejecting it would
        // make the tombstone useless; the whole point of keeping the constant is to bind it.
        Assertions.assertTrue(source.contains("case \"email\":"),
                "forName must keep an arm for the retired constant (R1.4): " + source);
        Assertions.assertTrue(source.contains("return PersonSummary_.email;"), source);
    }

    @Test
    void noBuilderSetterIsEmittedForTheRetiredField() throws Exception {
        Tree tree = generateThenRewriteTheView(WITHOUT_EMAIL);

        for (String builderName : List.of("PersonSummaryBuilder.java",
                "PersonSummaryBuilderTracking.java")) {
            String builder = tree.read(builderName);
            Assertions.assertFalse(builder.contains("Builder email(String value)")
                            || builder.contains("BuilderTracking email(String value)"),
                    builderName + ": a retired field gets no setter, or a caller could write a value "
                            + "no column accepts");
            Assertions.assertFalse(builder.contains("mf.addOrdinal(3)"),
                    builderName + ": and no tracking arm records a change against the retired ordinal");
        }
    }

    @Test
    void theReportSaysTheFieldWasRetired() throws Exception {
        Tree tree = generateThenRewriteTheView(WITHOUT_EMAIL);

        Assertions.assertTrue(tree.divergences().ofKind("field_retired").stream()
                        .anyMatch(entry -> entry.contains("email")),
                "the regeneration report must name the tombstone: " + tree.divergences().render());
    }

    @Test
    void theRetiredFieldKeepsItsSlotInThePositionalRead() throws Exception {
        Tree tree = generateThenRewriteTheView(WITHOUT_EMAIL);
        String adapter = tree.read("PersonSummaryRowAdapter.java");

        Assertions.assertTrue(adapter.contains("meta.fieldNameAt(ordinal)"),
                "the reader resolves names through the enum, so it needs no ordinal arithmetic of its "
                        + "own: " + adapter);
    }

    @Test
    void aTombstoneBeforeALiveFieldKeepsEveryEmitterOnTheSameOrdinal() throws Exception {
        Tree tree = generateThenRewriteTheView(WITHOUT_LAST_NAME);

        // The enum's ledger: id=0, firstName=1, lastName=2 (retired), email=3.
        String enumSource = tree.enumSource();
        Assertions.assertTrue(enumSource.contains("lastName(java.lang.Object.class)"),
                "the tombstone is in the middle where the accessor was: " + enumSource);

        // The writer half computes ordinals itself, so it is where the divergence showed up.
        String binder = tree.read("PersonSummaryBinder.java");
        Assertions.assertTrue(binder.contains("ORDINALS = {0, 1, 3}"),
                "the binder must read email from ordinal 3, not from the retired slot at 2: " + binder);
        Assertions.assertFalse(binder.contains("\"lastName\""),
                "and no column may carry the retired field: " + binder);

        String tracking = tree.read("PersonSummaryBuilderTracking.java");
        String emailSetter = "public PersonSummaryBuilderTracking email(String value)";
        int emailSetterStart = tracking.indexOf(emailSetter);
        Assertions.assertTrue(emailSetterStart >= 0,
                "the tracking builder keeps a setter for the live email field: " + tracking);
        String emailSetterBody = tracking.substring(emailSetterStart,
                tracking.indexOf("\n    }", emailSetterStart));
        Assertions.assertTrue(emailSetterBody.contains("mf.addOrdinal(3);"),
                "the tracking setter marks the enum's ordinal for email, not the interface's "
                        + "position: " + emailSetterBody);
        Assertions.assertFalse(tracking.contains("mf.addOrdinal(2)"),
                "and nothing records a change against the tombstone's ordinal: " + tracking);

        String record = tree.read("PersonSummaryRecord.java");
        Assertions.assertTrue(record.contains("java.lang.Object lastName"),
                "the record keeps a nullable component for the tombstone, or create()'s positional "
                        + "mapping shifts by one: " + record);

        String builder = tree.read("PersonSummaryBuilder.java");
        Assertions.assertTrue(builder.contains("new PersonSummaryRecord(id, firstName, lastName, email)"),
                "and the builder passes the tombstone's slot through positionally: " + builder);
        Assertions.assertFalse(builder.contains("source.lastName()"),
                "while never calling an accessor the interface no longer declares: " + builder);
    }

    @Test
    void theTombstonedTreeCompiles() throws Exception {
        Tree tree = generateThenRewriteTheView(WITHOUT_LAST_NAME);

        // The compile gate is the assertion that covers the whole chain at once: the builder's
        // copy-constructor line for a retired field, the record's arity, the `set(int)` arms and the
        // `get(int)` coverage all have to agree, and javac checks them together.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "tombstone",
                CompileHarness.javaSourcesUnder(tree.outputRoot()),
                CompileHarness.javaSourcesUnder(tree.sourceRoot()));
    }

    /** Counts the constants in the emitted enum's constant list, tombstones and all. */
    private static int countConstants(String source) {
        int listStart = source.indexOf('\n', source.indexOf("implements FieldDef {")) + 1;
        int listEnd = source.indexOf("\n    ;", listStart);
        Assertions.assertTrue(listEnd > listStart, "the constant list must be findable: " + source);
        String list = source.substring(listStart, listEnd);
        int depth = 0;
        int count = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count + 1;
    }
}
