// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The generated patch applier — DEC-048 § 2, plan step 6.2's second half.
 *
 * <p>The applier is generated rather than generic because DEC-019 forbids resolving a field by name through a
 * {@code Map<String, Method>} and a view's setters are typed methods: only an emitted {@code switch} can make a patch
 * operation reach a setter an IDE can follow. This class pins the two properties that matter — <b>it compiles</b>, and
 * it <b>reports</b> every operation it cannot apply instead of guessing.</p>
 */
class GeneratedPatchApplierTest {

    @TempDir
    Path tempDir;

    private static final String MARKER = """
            package patch.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface Thing extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /**
     * One convertible String, one convertible boxed number, and one type with no direct conversion — plus the
     * <b>hand-written</b> {@code Write} interface the applier writes through, exactly as the example declares it.
     * That interface is the developer's, which is why the emission requires a builder level: a view without setters
     * would get an applier that cannot compile.
     */
    private static final String VIEW = """
            package patch.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import hr.hrg.hipster.entity.api.ViewWriter;
            import java.util.Map;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends Thing {
                String firstName();
                Integer age();
                Map<String, Long> counters();
                interface Write extends PersonSummary, ViewWriter {
                    Write id(Long value);
                    Write firstName(String value);
                    Write age(Integer value);
                    Write counters(Map<String, Long> value);
                }
            }
            """;

    private Path generateWithAppliers() throws Exception {
        Path root = tempDir.resolve("tree");
        Path pkg = root.resolve("patch/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.setGeneratePatchAppliers(true);
        try {
            EntityMetadataGenerator.generate(root, root, root, divergences);
        } finally {
            EntityMetadataGenerator.setGeneratePatchAppliers(false);
        }
        Path applier = pkg.resolve("PersonSummaryPatchApplier.java");
        Assertions.assertTrue(Files.exists(applier),
                "the applier must be generated when the pass asked for it: " + Files.walk(root).toList());
        return root;
    }

    @Test
    void theGeneratedApplierDispatchesByNameAndCompiles() throws Exception {
        Path root = generateWithAppliers();
        String emitted = Files.readString(root.resolve("patch/hr/PersonSummaryPatchApplier.java"));

        Assertions.assertTrue(emitted.contains("public static java.util.List<String> apply(")
                        && emitted.contains("JsonNode document, PersonSummary.Write target)"),
                "the entry point takes the document and the view's own Write target:\n" + emitted);
        Assertions.assertTrue(emitted.contains("case \"firstName\" ->") && emitted.contains("case \"age\" ->"),
                "a writable field gets a direct-call arm, which is the whole reason this class is generated:\n"
                        + emitted);
        Assertions.assertTrue(emitted.contains("target.firstName(delta.path(\"current\").asText())"),
                "the arm reads `current` and calls the TYPED setter:\n" + emitted);
        Assertions.assertTrue(emitted.contains("target.age(delta.path(\"current\").asInt())"),
                "and converts per the declared type:\n" + emitted);
        Assertions.assertTrue(emitted.contains("unknown_field: "),
                "a field the view does not have is reported, never resolved through a hash map (DEC-016):\n" + emitted);
        Assertions.assertTrue(emitted.contains("positional_fallback: "),
                "and the emitter's identity-fallback marker is carried into the report:\n" + emitted);
        Assertions.assertTrue(emitted.contains("unsupported_type: counters"),
                "a writable field this revision cannot convert gets its OWN arm that says why — leaving it to the "
                        + "default arm would report it as a field the view does not have, which is false:\n" + emitted);

        // The strongest available check: the emitted class is compiled by the same harness the other generated
        // artifacts are compiled with, so a typo in the emission is a test failure rather than a user's problem.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "patch-applier",
                CompileHarness.javaSourcesUnder(root), List.of());
    }

    @Test
    void nothingIsGeneratedUnlessThePassAskedForIt() throws Exception {
        Path root = tempDir.resolve("off");
        Path pkg = root.resolve("patch/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW);

        EntityMetadataGenerator.generate(root, root, root, new DivergenceReporter());

        Assertions.assertFalse(Files.exists(pkg.resolve("PersonSummaryPatchApplier.java")),
                "the flag is opt-in, because the emitted class imports Jackson's node type and must not appear in a "
                        + "project that never asked for it: " + Files.walk(root).toList());
    }

    /**
     * The applier writes through the developer's {@code Write}, so it may only emit an arm for a setter that interface
     * <b>declares</b>. A writable field it omits gets a report — and the class still compiles, which is the property
     * that matters: before this, the emitter called a setter that was not there and the generated file was broken.
     */
    @Test
    void aWritableFieldTheWriteOmitsIsReportedAndTheClassStillCompiles() throws Exception {
        Path root = tempDir.resolve("partial");
        Path pkg = root.resolve("patch/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        // `age` is writable by the framework's rule but has no setter here: exactly the case that used to emit a call
        // to a method that does not exist.
        Files.writeString(pkg.resolve("PersonSummary.java"), VIEW.replace("    Write age(Integer value);\n", ""));

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.setGeneratePatchAppliers(true);
        try {
            EntityMetadataGenerator.generate(root, root, root, divergences);
        } finally {
            EntityMetadataGenerator.setGeneratePatchAppliers(false);
        }
        String emitted = Files.readString(pkg.resolve("PersonSummaryPatchApplier.java"));

        Assertions.assertTrue(emitted.contains("missing_setter: PersonSummary.Write declares no setter for age"),
                "a writable field the Write interface omits is reported in the vocabulary the builder generator "
                        + "already uses:\n" + emitted);
        Assertions.assertFalse(emitted.contains("target.age("),
                "and no call is emitted for a setter that does not exist:\n" + emitted);

        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "patch-applier-partial",
                CompileHarness.javaSourcesUnder(root), List.of());
    }

    /**
     * The nested case, in the shape DEC-048 § 5 chose: a <b>caller-supplied writer resolver</b>.
     *
     * <p>Nothing generated implements {@code Write}, and {@code toBuilder()} yields a builder that does not, so an
     * applier cannot reach inside a child view on its own. The generated {@code Children} interface is where the caller
     * says how a nested writer is obtained, and the child's own applier does the writing — which is what keeps the
     * recursion typed and navigable rather than a name lookup.</p>
     */
    @Test
    void aNestedViewIsAppliedThroughACallerSuppliedWriterAndCompiles() throws Exception {
        Path root = tempDir.resolve("nested");
        Path pkg = root.resolve("patch/hr");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Thing.java"), MARKER);
        Files.writeString(pkg.resolve("Address.java"), """
                package patch.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                import hr.hrg.hipster.entity.api.ViewWriter;
                @View(gen = GenLevel.BUILDER_ALL)
                public interface Address extends Thing {
                    String city();
                    interface Write extends Address, ViewWriter {
                        Write city(String value);
                    }
                }
                """);
        Files.writeString(pkg.resolve("PersonSummary.java"), """
                package patch.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                import hr.hrg.hipster.entity.api.ViewWriter;
                import java.util.List;
                @View(gen = GenLevel.BUILDER_ALL)
                public interface PersonSummary extends Thing {
                    String firstName();
                    Address address();
                    List<Address> previousAddresses();
                    interface Write extends PersonSummary, ViewWriter {
                        Write firstName(String value);
                        Write address(Address value);
                        Write previousAddresses(List<Address> value);
                    }
                }
                """);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.setGeneratePatchAppliers(true);
        try {
            EntityMetadataGenerator.generate(root, root, root, divergences);
        } finally {
            EntityMetadataGenerator.setGeneratePatchAppliers(false);
        }
        String emitted = Files.readString(pkg.resolve("PersonSummaryPatchApplier.java"));

        Assertions.assertTrue(emitted.contains("public interface Children {"),
                "the resolver is where the caller says how a nested writer is obtained (DEC-048 § 5):\n" + emitted);
        Assertions.assertTrue(emitted.contains("Write address();"),
                "and it is TYPED on the child's own Write, so the recursion stays navigable:\n" + emitted);
        Assertions.assertTrue(emitted.contains("AddressPatchApplier.apply(childDocument(delta, 1), child)"),
                "the nested operation is handed to the child's own applier with the child's field name first in the "
                        + "path:\n" + emitted);
        Assertions.assertTrue(emitted.contains("no_child_writer: address"),
                "and a caller that supplies none gets a report rather than silence:\n" + emitted);
        Assertions.assertTrue(emitted.contains("Write previousAddresses(int index);"),
                "a COLLECTION nested field resolves per element, so an addition at the end is not confused with a "
                        + "document member (DEC-024 § 3):\n" + emitted);
        Assertions.assertTrue(emitted.contains("childDocument(entry, 2)"),
                "and its paths carry the element index, so the child's field name is one deeper:\n" + emitted);
        Assertions.assertTrue(emitted.contains("return apply(document, target, null);"),
                "the two-argument overload is the no-nested-writer case:\n" + emitted);

        // The child's own applier must exist too, and both must compile — the check that makes the resolver real.
        Assertions.assertTrue(Files.exists(pkg.resolve("AddressPatchApplier.java")),
                "the child view got its own applier: " + Files.walk(root).toList());
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "patch-applier-nested",
                CompileHarness.javaSourcesUnder(root), List.of());
    }
}
