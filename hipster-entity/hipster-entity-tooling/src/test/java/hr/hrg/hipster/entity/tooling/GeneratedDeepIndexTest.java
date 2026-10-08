// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Task 6.5's claim, tested on <b>generated</b> output rather than on the hand-written fixture — plan step 6.2's first
 * half.
 *
 * <p>DEC-024's follow-up says *"a generated tracking builder must detect that a field's declared type — including a
 * generic collection of them — is itself a trackable {@code @View} type and emit the deep index, rather than the array
 * path's value-level discovery."* Reading the generator showed that emission already exists (`classifyNested` →
 * `deepAccessors`), so what this class settles is the question the plan could not answer from its own text: <b>is it
 * true, and is it wired at the ordinal the runtime expects?</b> Until now the only materializations were the array path
 * and a hand-written fixture, which is exactly the gap DEC-024 names.</p>
 */
class GeneratedDeepIndexTest {

    @TempDir
    Path tempDir;

    private static final String MARKER = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface Thing extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** A nested view generated at a tracking level, which is what makes it reachable from the parent's deep walk. */
    private static final String NESTED = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_TRACKED)
            public interface Address extends Thing {
                @FieldSource(kind = FieldKind.COLUMN)
                String city();
            }
            """;

    /** The parent: one directly nested view, and one collection of them, so both classification branches are exercised. */
    private static final String PARENT = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.FieldKind;
            import hr.hrg.hipster.entity.api.FieldSource;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends Thing {
                @FieldSource(kind = FieldKind.COLUMN)
                String firstName();
                Address address();
                List<Address> previousAddresses();
            }
            """;

    private Path generateTree(String addressSource) throws Exception {
        Path root = tempDir.resolve("tree" + Math.abs(addressSource.hashCode()));
        Path pkg = root.resolve("deep/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("Address.java"), addressSource);
        Files.writeString(pkg.resolve("PersonSummary.java"), PARENT);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(root, root, root, divergences);

        Path builder = pkg.resolve("PersonSummaryBuilderTracking.java");
        Assertions.assertTrue(Files.exists(builder),
                "the tracking builder must be generated: " + Files.walk(root).toList() + " — " + divergences.entries());
        return root;
    }

    @Test
    void aGeneratedTrackingBuilderEmitsTheDeepIndexForBothShapes() throws Exception {
        Path root = generateTree(NESTED);
        String emitted = Files.readString(root.resolve("deep/hr/PersonSummaryBuilderTracking.java"));

        Assertions.assertTrue(emitted.contains("nestedTrackers()"),
                "the generated builder must answer nestedTrackers() from its own typed fields:\n" + emitted);
        Assertions.assertTrue(emitted.contains("changesDeep()"),
                "and it must override changesDeep(), or a nested-only change would read as no change at all:\n"
                        + emitted);

        // The ordinal must be the one the RUNTIME reads, and the runtime reads the field enum's constant order — so
        // the check is against the generated enum rather than against an expectation written here. A mismatch between
        // the property order and the enum order would make the deep walk look up the wrong child, which is a bug no
        // amount of "the method exists" would catch.
        String enumSource = Files.readString(root.resolve("deep/hr/PersonSummary_.java"));
        // Constants after the first are emitted with the separating comma on the SAME line (`, address(Address.class) {`),
        // so the comma is stripped before matching rather than assumed to sit on its own line.
        List<String> constants = enumSource.lines()
                .map(String::trim)
                .map(line -> line.startsWith(",") ? line.substring(1).trim() : line)
                .filter(line -> line.matches("[A-Za-z_$][\\w$]*\\(.*"))
                .map(line -> line.substring(0, line.indexOf('(')))
                .toList();
        int addressOrdinal = constants.indexOf("address");
        Assertions.assertTrue(addressOrdinal >= 0,
                "the enum must carry an `address` constant: " + constants + "\n--- enum ---\n" + enumSource
                        + "\n--- builder ---\n" + emitted);
        Assertions.assertTrue(emitted.contains("all.put(" + addressOrdinal + ", "),
                "the directly nested field must be keyed by ITS OWN ordinal in the generated enum (" + addressOrdinal
                        + "), or the deep walk finds the wrong child:\n" + emitted);

        // The collection shape carries a per-element index rather than a plain ordinal (DEC-024 § 3).
        Assertions.assertTrue(emitted.contains("previousAddresses"),
                "and the nested collection must be walked too, not silently skipped:\n" + emitted);
        Assertions.assertTrue(emitted.contains("hr.hrg.hipster.entity.core.ChangePath"),
                "the deep paths are ChangePath values, the same type the array path reports:\n" + emitted);
    }

    /**
     * The one actionable mistake this wiring can make: a field holds a view of this project that is <b>not</b> at a
     * tracking level, so the author asked for tracking on the parent and cannot get it there. It is reported rather
     * than silently unreachable — the divergence kind the generator owns is the evidence that the trigger is a
     * classification and not an accident.
     */
    @Test
    void aNestedViewWithoutATrackingLevelIsReportedRatherThanSilentlySkipped() throws Exception {
        Path root = tempDir.resolve("plain");
        Path pkg = root.resolve("deep/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        // Same shape, but the nested view is generated at a level that tracks nothing.
        Files.writeString(pkg.resolve("Address.java"), NESTED.replace("GenLevel.BUILDER_TRACKED", "GenLevel.RECORD"));
        Files.writeString(pkg.resolve("PersonSummary.java"), PARENT);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(root, root, root, divergences);

        Assertions.assertTrue(divergences.entries().stream()
                        .anyMatch(entry -> entry.contains("deep_tracking_type_not_enabled")),
                "a nested view that cannot be reached must be reported, with the fix in the message: "
                        + divergences.entries());
    }
}
