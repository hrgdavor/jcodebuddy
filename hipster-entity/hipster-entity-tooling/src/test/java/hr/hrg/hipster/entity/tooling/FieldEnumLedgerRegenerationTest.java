package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The append-only ledger regeneration contract of plan.dsflash § 8.3/3.7a, § 4.6/R1 and § 4.5/G7
 * (test 25's generator half).
 *
 * <p>Each test generates twice: once for the "before" view, then again for the "after" view, and
 * asserts what happened to the constant list. This is the only place the generator sees an enum
 * constant with no matching accessor, so the tombstone logic is exercised here rather than through
 * the general emission path.</p>
 */
class FieldEnumLedgerRegenerationTest {

    private static final String MARKER = """
            package ledger.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {}
            """;

    private static String view(String... accessors) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ledger.hr;\n");
        sb.append("public interface PersonSummary extends PersonEntity {\n");
        for (String accessor : accessors) {
            sb.append("    ").append(accessor).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Generates the enum twice, returning the source after each pass. */
    private String[] generateTwice(String[] before, String[] after) throws Exception {
        Path sourceRoot = Files.createTempDirectory("ledger-source");
        Path outputRoot = Files.createTempDirectory("ledger-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Path viewFile = sourceRoot.resolve("PersonSummary.java");

        Files.writeString(viewFile, view(before));
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        Path enumFile = outputRoot.resolve("ledger/hr/PersonSummary_.java");
        String first = Files.readString(enumFile);

        Files.writeString(viewFile, view(after));
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return new String[]{first, Files.readString(enumFile)};
    }

    /** The constant names of the first enum declaration in a source file, in declaration order. */
    private static List<String> constants(String source) {
        var ledgers = hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker.readLedgers(source);
        return ledgers.values().stream().findFirst()
                .map(hr.hrg.hipster.entity.tooling.validation.EnumConstantOrderChecker.EnumLedger::constants)
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
    }

    @Test
    void newFieldIsAppendedAndExistingOrdinalsAreUntouched() throws Exception {
        String[] passes = generateTwice(
                new String[]{"String firstName()", "String lastName()"},
                new String[]{"String firstName()", "String lastName()", "String email()"});

        List<String> before = constants(passes[0]);
        List<String> after = constants(passes[1]);

        Assertions.assertEquals(List.of("id", "firstName", "lastName"), before);
        Assertions.assertEquals(List.of("id", "firstName", "lastName", "email"), after,
                "the new constant is appended after every existing one, never inserted");
        Assertions.assertEquals(before, after.subList(0, before.size()),
                "every existing ordinal kept its position (R1)");
    }

    @Test
    void reorderingInTheSourceDoesNotReorderTheEnum() throws Exception {
        // The accessors move in the interface; the ledger must not follow.
        String[] passes = generateTwice(
                new String[]{"String firstName()", "String lastName()"},
                new String[]{"String lastName()", "String firstName()"});

        Assertions.assertEquals(constants(passes[0]), constants(passes[1]),
                "the generator's enum comparison is the one place where DEC-020's "
                        + "'recognize by shape, re-emit canonically' logic must be order-sensitive");
    }

    @Test
    void removedAccessorBecomesADeprecatedTombstoneThatKeepsItsOrdinal() throws Exception {
        String[] passes = generateTwice(
                new String[]{"String firstName()", "String middleName()", "String lastName()"},
                new String[]{"String firstName()", "String lastName()"});

        List<String> after = constants(passes[1]);

        Assertions.assertEquals(List.of("id", "firstName", "middleName", "lastName"), after,
                "the removed accessor's constant is KEPT in place, so every later ordinal is unchanged");
        Assertions.assertTrue(passes[1].contains("@Deprecated"),
                "and it is marked @Deprecated with a reason");
        Assertions.assertTrue(passes[1].contains("retained to preserve ordinals"),
                "the deprecation carries the R1.4 reason: " + passes[1]);
        Assertions.assertTrue(passes[1].contains("public boolean retired()"),
                "a tombstone reports retired() so every generated writer skips it (§ 4.7/DR-2)");
        // forName still resolves the retired name, so an incoming payload still carrying it is
        // accepted and bound to the tombstone rather than silently dropped.
        Assertions.assertTrue(passes[1].contains("case \"middleName\""),
                "forName still resolves the retired name (R1.4)");
    }

    @Test
    void markerLessExistingEnumIsBootstrappedAndStaleConstantsAreDropped() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ledger-bootstrap-source");
        Path outputRoot = Files.createTempDirectory("ledger-bootstrap-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"),
                view("String firstName()", "String lastName()"));

        // Seed a pre-R1, marker-less enum carrying the leaked constants § 2.3 describes.
        Path packageDir = outputRoot.resolve("ledger/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonSummary_.java"), """
                package ledger.hr;
                public enum PersonSummary_ {
                    id(java.lang.Long.class),
                    firstName(java.lang.String.class),
                    lastName(java.lang.String.class),
                    toBuilder(java.lang.Object.class),
                    toBuilderTracking(java.lang.Object.class);
                    private final java.lang.reflect.Type javaType;
                    PersonSummary_(java.lang.reflect.Type javaType) { this.javaType = javaType; }
                    public java.lang.reflect.Type javaType() { return javaType; }
                }
                """);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        String emitted = Files.readString(packageDir.resolve("PersonSummary_.java"));

        Assertions.assertEquals(List.of("id", "firstName", "lastName"), constants(emitted),
                "a marker-less enum has no committed ledger, so it is bootstrapped fresh: the leaked "
                        + "pre-R1 constants are DROPPED, not tombstoned (G7)");
        Assertions.assertFalse(emitted.contains("toBuilder"),
                "the leaked default-method constants disappear: " + emitted);
        Assertions.assertTrue(emitted.contains("entityFieldEnum:true"),
                "and the marker is written in the same pass, so the NEXT generation is protected");
    }

    @Test
    void regenerationIsIdempotentOnceTheMarkerIsPresent() throws Exception {
        Path sourceRoot = Files.createTempDirectory("ledger-idem-source");
        Path outputRoot = Files.createTempDirectory("ledger-idem-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonSummary.java"),
                view("String firstName()", "String lastName()"));

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        Path enumFile = outputRoot.resolve("ledger/hr/PersonSummary_.java");
        String first = Files.readString(enumFile);
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        String second = Files.readString(enumFile);

        Assertions.assertEquals(first, second,
                "running the generator twice on the same tree must be byte-identical (§ 8.7/3.21)");
        Assertions.assertEquals(List.of("id", "firstName", "lastName"), constants(second));
    }
}
