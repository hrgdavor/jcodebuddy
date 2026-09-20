package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bean Validation generation (plan.dsflash § 12.3/7.9–7.12).
 *
 * <p>The plan's four rules are asserted separately, because each one is a different way to get this
 * wrong:</p>
 * <ul>
 *   <li><strong>7.9</strong> the constraint's source of truth is the annotation on the view accessor —
 *       and it survives the metadata JSON round trip, so a later regeneration pass reproduces it;</li>
 *   <li><strong>7.10</strong> it is emitted onto the generated record's components and the builders'
 *       fields where it is expressible, and a constraint that is <em>not</em> expressible is a
 *       DEC-022 diagnostic rather than a silent drop;</li>
 *   <li><strong>7.11</strong> a standalone {@code <View>Validator} carries the mechanical checks with
 *       explicit messages;</li>
 *   <li><strong>7.12</strong> a valid instance passes, every generated constraint fails its negative
 *       case, and an unsupported constraint produces a diagnostic and no generated code.</li>
 * </ul>
 *
 * <p>The dependency question is part of the contract: the constraints are compiled against
 * {@code jakarta.validation-api} 3.0.2, which the tooling declares {@code provided} and a consuming
 * project adds for itself — so a library that only uses the entities never gains a validation
 * dependency.</p>
 */
class ValidationGeneratorTest {

    private static final String MARKER = """
            package valid.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** A view whose accessors carry the constraints a form would declare. */
    private static final String CONSTRAINED_VIEW = """
            package valid.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import jakarta.validation.constraints.Max;
            import jakarta.validation.constraints.Min;
            import jakarta.validation.constraints.NotBlank;
            import jakarta.validation.constraints.NotNull;
            import jakarta.validation.constraints.Pattern;
            import jakarta.validation.constraints.Size;
            import java.util.List;
            @View(gen = GenLevel.RECORD)
            public interface PersonForm extends PersonEntity {
                @NotBlank
                @Size(min = 2, max = 40)
                String firstName();
                @NotNull
                @Min(0)
                @Max(150)
                Integer age();
                @Size(max = 3)
                List<String> tags();
                @Pattern(regexp = "[A-Z]{2}")
                String countryCode();
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot, Path packageDir,
                             List<String> divergences) {
    }

    private Generated generate(String viewSource) throws Exception {
        Path sourceRoot = Files.createTempDirectory("valid-source");
        Path outputRoot = Files.createTempDirectory("valid-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("PersonForm.java"), viewSource);

        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        return new Generated(sourceRoot, outputRoot, outputRoot.resolve("valid/hr"), reporter.entries());
    }

    private static String read(Generated generated, String fileName) throws Exception {
        return Files.readString(generated.packageDir().resolve(fileName)).replace("\r\n", "\n");
    }

    private static boolean hasKind(List<String> entries, String kind) {
        return entries.stream().anyMatch(entry -> entry.startsWith("kind=" + kind));
    }

    private static String entryOf(List<String> entries, String kind) {
        return entries.stream().filter(entry -> entry.startsWith("kind=" + kind))
                .findFirst().orElse("(no " + kind + " entry in " + entries + ")");
    }

    /**
     * No <em>validation</em> problem is reported about this view.
     *
     * <p>Deliberately not "no divergence at all". A pass whose generated output is outside the module —
     * which is what this fixture's separate temp output root is — reports
     * {@code artifact_outside_module} for each artifact it cannot name in the metadata, because a
     * module-relative path is the only currency the index and the documents share (DEC-028). That is a
     * fact about the fixture's layout, not about the constraint machinery these tests are about, and
     * folding it into a broader assertion would make the test's subject unreadable.</p>
     */
    private static void assertNoValidationIssue(Generated generated) {
        List<String> issues = generated.divergences().stream()
                .filter(entry -> entry.contains("kind=validation"))
                .toList();
        Assertions.assertEquals(List.of(), issues,
                "no validation issue may be reported: " + generated.divergences());
    }

    /**
     * Re-runs the pass over an <strong>existing</strong> tree.
     *
     * <p>{@code generate(viewSource)} builds a fresh output root every time, which is right for the
     * assertions about what a pass emits and wrong for anything about re-emission: a clean output
     * directory has no previous revision, so cooperative codegen is vacuous by construction. That
     * mistake made the first version of {@link #anEditToTheGeneratedValidatorMethodIsReportedAndReverted}
     * report nothing at all. This helper exists so the distinction is visible at the call site.</p>
     */
    private static List<String> regenerateInPlace(Generated generated) throws Exception {
        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(generated.sourceRoot(), generated.outputRoot(),
                generated.outputRoot(), reporter);
        return reporter.entries();
    }

    /**
     * A user-added member inside the generated validator survives regeneration
     * (follow-up plan § 1.1, completed in round 4).
     *
     * <p>The validator is a whole-file emission like the builders and the record, and it was the last
     * one besides the mapper that still overwrote outright — so a helper a developer added to it was
     * deleted on the next pass, silently. That is the same loss F-25 records for nested types and F-51
     * for non-type members, in the two files those fixes did not reach.</p>
     */
    @Test
    void aUserMemberInsideTheGeneratedValidatorSurvives() throws Exception {
        Generated generated = generate(CONSTRAINED_VIEW);
        Path validator = generated.packageDir().resolve("PersonFormValidator.java");
        Assertions.assertTrue(Files.exists(validator), "the constrained view emits a validator");

        String method = """
                /**
                 * USER-METHOD in the validator - never generated, always preserved.
                 */
                public static String summarize() {
                    return "ok";
                }""";
        String text = Files.readString(validator);
        int lastBrace = text.lastIndexOf('}');
        Files.writeString(validator, text.substring(0, lastBrace) + "\n" + method + "\n" + text.substring(lastBrace));

        regenerateInPlace(generated);
        String after = Files.readString(validator);
        Assertions.assertTrue(after.contains("summarize()"),
                "a user's helper inside the validator must survive: " + after);
        Assertions.assertTrue(after.contains("USER-METHOD in the validator"), after);

        regenerateInPlace(generated);
        Assertions.assertEquals(after, Files.readString(validator),
                "and the carry-over must be a fixed point, not a one-pass copy");
    }

    /**
     * An edit to the generated {@code validate} method is reported and reverted (plan § 1.1's second
     * state), which is the half that keeps the file from compiling against a stale shape.
     */
    @Test
    void anEditToTheGeneratedValidatorMethodIsReportedAndReverted() throws Exception {
        Generated generated = generate(CONSTRAINED_VIEW);
        Path validator = generated.packageDir().resolve("PersonFormValidator.java");
        String canonical = Files.readString(validator);
        String marker = "List<String> violations = new ArrayList<>();";
        Assertions.assertTrue(canonical.contains(marker), "the fixture must contain the generated body");
        Files.writeString(validator, canonical.replace(marker, marker + "\n        // USER-EDIT"));

        List<String> entries = regenerateInPlace(generated);

        Assertions.assertTrue(hasKind(entries, "generated_member_diverged"),
                "the edit is reported, not silently replaced: " + entries);
        Assertions.assertTrue(entryOf(entries, "generated_member_diverged").contains("validate"),
                entryOf(entries, "generated_member_diverged"));
        Assertions.assertFalse(Files.readString(validator).contains("USER-EDIT"),
                "and the canonical body is what remains");
    }

    @Test
    void constraintsReachTheGeneratedRecordAndBothBuilders() throws Exception {
        Generated generated = generate(CONSTRAINED_VIEW);

        String record = read(generated, "PersonFormRecord.java");
        Assertions.assertTrue(record.contains("import jakarta.validation.constraints.NotBlank;"),
                "the record imports the constraint it carries: " + record);
        Assertions.assertTrue(record.contains("@NotBlank") && record.contains("@Size(min = 2, max = 40)"),
                "and carries it verbatim, in the author's spelling: " + record);
        Assertions.assertTrue(record.contains("@NotNull") && record.contains("@Min(0)")
                        && record.contains("@Max(150)"),
                "including the numeric ones: " + record);
        Assertions.assertTrue(record.contains("@Pattern(regexp = \"[A-Z]{2}\")"), record);

        // A view at RECORD has no builders, so ask for one that does.
        Generated withBuilders = generate(CONSTRAINED_VIEW
                .replace("GenLevel.RECORD", "GenLevel.BUILDER_ALL"));
        String builder = read(withBuilders, "PersonFormBuilder.java");
        String tracking = read(withBuilders, "PersonFormBuilderTracking.java");
        Assertions.assertTrue(builder.contains("@Size(min = 2, max = 40)"),
                "the mutable builder's field carries the same constraint as the record's component: "
                        + builder);
        Assertions.assertTrue(tracking.contains("@Size(min = 2, max = 40)"), tracking);

        assertCompiles(generated, "constrained record");
        assertCompiles(withBuilders, "constrained builders");
    }

    @Test
    void aGeneratedValidatorCarriesTheMechanicalChecksWithExplicitMessages() throws Exception {
        Generated generated = generate(CONSTRAINED_VIEW);
        String validator = read(generated, "PersonFormValidator.java");

        Assertions.assertTrue(validator.contains("public static List<String> validate(PersonForm view)"),
                validator);
        Assertions.assertTrue(validator.contains("violations.add(\"firstName: must not be blank\")"),
                "the message is committed source, not a bundle lookup: " + validator);
        Assertions.assertTrue(validator.contains("violations.add(\"firstName: size is out of range (min = 2, max = 40)\")"),
                validator);
        Assertions.assertTrue(validator.contains("violations.add(\"age: must be >= 0\")"), validator);
        Assertions.assertTrue(validator.contains("violations.add(\"age: must be <= 150\")"), validator);
        Assertions.assertTrue(validator.contains("violations.add(\"tags: size is out of range (max = 3)\")"),
                validator);
        Assertions.assertTrue(validator.contains("violations.add(\"countryCode: must match [A-Z]{2}\")"),
                validator);

        // Bean Validation treats a null value as valid for everything but the null constraints, so a
        // checker that threw on a null field would be stricter than the annotations it mirrors.
        Assertions.assertTrue(validator.contains("view.firstName() != null && view.firstName().isBlank()"),
                "the null guard comes first, so a null field is skipped exactly as the provider would "
                        + "skip it: " + validator);
        Assertions.assertTrue(validator.contains("view.tags() != null && !(view.tags().size() <= 3)"),
                "a Collection's size check uses size(), and an array's would use length: " + validator);

        assertCompiles(generated, "validator");
    }

    @Test
    void aValidInstancePassesAndEveryConstraintFailsItsNegativeCase() throws Exception {
        Generated generated = generate(CONSTRAINED_VIEW);
        assertCompiles(generated, "validator runtime");

        // Run the emitted validator, not a copy of it: the assertions are only worth anything if the
        // generated code is what executes.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "validator-runtime",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
        Path classes = CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "validator-runtime-2",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));

        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] { classes.toUri().toURL() },
                ValidationGeneratorTest.class.getClassLoader())) {
            Class<?> form = loader.loadClass("valid.hr.PersonForm");
            Class<?> record = loader.loadClass("valid.hr.PersonFormRecord");
            Class<?> validator = loader.loadClass("valid.hr.PersonFormValidator");

            Object valid = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "Ada", 36, List.of("a"), "GB");
            Assertions.assertEquals(List.of(), invokeValidate(validator, form, valid),
                    "a valid instance passes");

            // One negative case per constraint, so a silently dropped constraint shows up as a missing
            // violation rather than as a passing test.
            Object blankName = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "  ", 36, List.of(), "GB");
            Assertions.assertTrue(invokeValidate(validator, form, blankName).contains("firstName: must not be blank"),
                    "the blank check fires: " + invokeValidate(validator, form, blankName));

            Object shortName = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "A", 36, List.of(), "GB");
            Assertions.assertTrue(invokeValidate(validator, form, shortName).stream()
                            .anyMatch(v -> v.startsWith("firstName: size is out of range")),
                    "the size check fires: " + invokeValidate(validator, form, shortName));

            Object tooOld = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "Ada", 151, List.of(), "GB");
            Assertions.assertTrue(invokeValidate(validator, form, tooOld).contains("age: must be <= 150"));

            Object negativeAge = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "Ada", -1, List.of(), "GB");
            Assertions.assertTrue(invokeValidate(validator, form, negativeAge).contains("age: must be >= 0"));

            Object tooManyTags = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "Ada", 36, List.of("a", "b", "c", "d"), "GB");
            Assertions.assertTrue(invokeValidate(validator, form, tooManyTags).stream()
                    .anyMatch(v -> v.startsWith("tags: size is out of range")));

            Object badCountry = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "Ada", 36, List.of(), "gb");
            Assertions.assertTrue(invokeValidate(validator, form, badCountry).stream()
                    .anyMatch(v -> v.startsWith("countryCode: must match")));

            // And a null field is skipped by every check except the null constraints — the provider's
            // own rule, which the generated checker mirrors rather than tightening. `age` carries
            // @NotNull as well as @Min/@Max, so the exact violation set is the interesting assertion:
            // a checker that also compared null against 0 would report a second, wrong violation.
            Object nullAge = record.getConstructor(Long.class, String.class, Integer.class,
                    List.class, String.class).newInstance(1L, "Ada", null, null, null);
            Assertions.assertEquals(List.of("age: must not be null"),
                    invokeValidate(validator, form, nullAge),
                    "the null constraints fire and the range checks do not, because null is valid for "
                            + "@Min/@Max/@Size/@Pattern");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeValidate(Class<?> validator, Class<?> form, Object instance)
            throws Exception {
        return (List<String>) validator.getMethod("validate", form).invoke(null, instance);
    }

    @Test
    void aConstraintOnATypeItCannotApplyToIsReportedAndNotEmitted() throws Exception {
        Generated generated = generate("""
                package valid.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                import jakarta.validation.constraints.AssertTrue;
                import jakarta.validation.constraints.Size;
                @View(gen = GenLevel.RECORD)
                public interface PersonForm extends PersonEntity {
                    @Size(min = 2)
                    Integer age();
                    @AssertTrue
                    String firstName();
                }
                """);

        Assertions.assertTrue(hasKind(generated.divergences(), "validation_constraint_type_mismatch"),
                "the modelling error is reported: " + generated.divergences());
        Assertions.assertTrue(entryOf(generated.divergences(), "validation_constraint_type_mismatch")
                        .contains("Size"),
                entryOf(generated.divergences(), "validation_constraint_type_mismatch"));

        String record = read(generated, "PersonFormRecord.java");
        Assertions.assertFalse(record.contains("@Size"),
                "and no annotation is emitted for it — a constraint the provider would reject at "
                        + "validation time must not be planted in the artifact: " + record);
        Assertions.assertFalse(record.contains("@AssertTrue"), record);
        Assertions.assertFalse(record.contains("import jakarta.validation"),
                "not even the import: " + record);
        Assertions.assertFalse(Files.exists(generated.packageDir().resolve("PersonFormValidator.java")),
                "and no validator check is invented for it either: a @Size on an Integer is not a "
                        + "check the generator should guess at");
    }

    @Test
    void aConstraintOutsideTheRecognisedSetIsReported() throws Exception {
        // A custom constraint cannot be interpreted without its semantics, so the generator must say
        // so rather than pretend to check it. It is written fully-qualified under the validation
        // namespace, which is the only way the generator can tell that an unfamiliar annotation is a
        // constraint at all — an unqualified custom annotation is indistinguishable from any other
        // annotation and is deliberately ignored (documented in the tooling README).
        Generated generated = generate("""
                package valid.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.RECORD)
                public interface PersonForm extends PersonEntity {
                    @jakarta.validation.constraints.NotNull
                    String firstName();
                    @jakarta.validation.constraints.CrossField
                    String secondName();
                }
                """);

        Assertions.assertTrue(hasKind(generated.divergences(), "validation_constraint_unsupported"),
                "a validation annotation the generator does not understand is named, not dropped in "
                        + "silence: " + generated.divergences());
        Assertions.assertTrue(entryOf(generated.divergences(), "validation_constraint_unsupported")
                        .contains("CrossField"));

        String record = read(generated, "PersonFormRecord.java");
        Assertions.assertTrue(record.contains("@NotNull"),
                "while the recognised one still gets through: " + record);
        Assertions.assertFalse(record.contains("@CrossField"),
                "and the uninterpretable one is not planted as if it were understood: " + record);
    }

    @Test
    void theCascadingValidMarkerIsCarriedThrough() throws Exception {
        // @Valid is not a constraint check, it is a cascade instruction, and it lives in a different
        // package from the constraints — so the import must respect that.
        Generated generated = generate("""
                package valid.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                import jakarta.validation.Valid;
                @View(gen = GenLevel.RECORD)
                public interface PersonForm extends PersonEntity {
                    @Valid
                    PersonEntity nested();
                }
                """);

        String record = read(generated, "PersonFormRecord.java");
        Assertions.assertTrue(record.contains("import jakarta.validation.Valid;"), record);
        Assertions.assertTrue(record.contains("@Valid"), record);
        assertNoValidationIssue(generated);
    }

    @Test
    void aViewWithNoConstraintsGetsNoValidatorAndNoImports() throws Exception {
        Generated generated = generate("""
                package valid.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.BUILDER_ALL)
                public interface PersonForm extends PersonEntity {
                    String firstName();
                }
                """);

        Assertions.assertFalse(Files.exists(generated.packageDir().resolve("PersonFormValidator.java")),
                "an unconstrained view gains no file, so the feature costs nothing when unused");
        Assertions.assertFalse(read(generated, "PersonFormRecord.java").contains("jakarta.validation"),
                "and no validation import, so nothing needs the dependency");
        assertNoValidationIssue(generated);
        assertCompiles(generated, "unconstrained view");
    }

    @Test
    void theApplicabilityRulesAreBeanValidationsOwn() {
        // Spot checks against the annotation set's own applicability, because a wrong rule here
        // silently plants a constraint the provider will throw on.
        Assertions.assertTrue(applicable("Size", "List<String>"));
        Assertions.assertTrue(applicable("Size", "String"));
        Assertions.assertTrue(applicable("Size", "Map<String, Long>"));
        Assertions.assertTrue(applicable("Size", "String[]"));
        Assertions.assertFalse(applicable("Size", "Integer"));
        Assertions.assertTrue(applicable("Pattern", "String"));
        Assertions.assertFalse(applicable("Pattern", "Integer"));
        Assertions.assertTrue(applicable("Min", "BigDecimal"));
        Assertions.assertTrue(applicable("Min", "String"));
        Assertions.assertFalse(applicable("Min", "java.util.List<Long>"));
        Assertions.assertTrue(applicable("Positive", "int"));
        Assertions.assertFalse(applicable("Positive", "String"));
        Assertions.assertTrue(applicable("Past", "java.time.LocalDate"));
        Assertions.assertFalse(applicable("Past", "String"));
        Assertions.assertTrue(applicable("AssertTrue", "boolean"));
        Assertions.assertFalse(applicable("AssertTrue", "Integer"));
        Assertions.assertTrue(applicable("NotNull", "Integer"));
        Assertions.assertTrue(applicable("NotNull", "int"));
        Assertions.assertFalse(applicable("NoSuchConstraint", "String"));
    }

    private static boolean applicable(String constraint, String type) {
        return ValidationGenerator.isApplicable(
                new hr.hrg.hipster.entity.tooling.meta.FieldConstraint(constraint, ""), type);
    }

    @Test
    void constraintArgumentsAreReadFromTheSourceSpelling() {
        // The generator carries the author's declaration through rather than re-parsing Bean
        // Validation's member grammar, so both the marker and the member forms must round-trip.
        var marker = new hr.hrg.hipster.entity.tooling.meta.FieldConstraint("NotNull", "");
        Assertions.assertEquals("@NotNull", marker.annotation());
        Assertions.assertEquals("jakarta.validation.constraints.NotNull", marker.qualifiedName());

        var single = new hr.hrg.hipster.entity.tooling.meta.FieldConstraint("Min", "0");
        Assertions.assertEquals("@Min(0)", single.annotation());
        Assertions.assertEquals("0", ValidationGenerator.memberValue(single, "value", null));

        var pairs = new hr.hrg.hipster.entity.tooling.meta.FieldConstraint("Size", "min = 2, max = 40");
        Assertions.assertEquals("@Size(min = 2, max = 40)", pairs.annotation());
        Assertions.assertEquals("2", ValidationGenerator.memberValue(pairs, "min", null));
        Assertions.assertEquals("40", ValidationGenerator.memberValue(pairs, "max", null));
        Assertions.assertNull(ValidationGenerator.memberValue(pairs, "absent", null));

        // @Valid lives in a different package from the constraints, which the import must respect.
        Assertions.assertEquals("jakarta.validation.Valid",
                new hr.hrg.hipster.entity.tooling.meta.FieldConstraint("Valid", "").qualifiedName());
    }

    private void assertCompiles(Generated generated, String label) throws Exception {
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), label,
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }
}
