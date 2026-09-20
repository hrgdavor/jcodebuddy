package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Addon semantics and cross-package inheritance (plan.dsflash § 4.5/G6, § 4.7/DR-1).
 *
 * <p>Two things are asserted, and they are the two the example exposes:</p>
 * <ol>
 *   <li>an accessor inherited from a supertype declared in a <em>different</em> package is
 *       collected — the example's {@code PersonAuditable extends Person, Auditable<Long>}, where
 *       {@code Auditable} lives in the filtered-out {@code example} package;</li>
 *   <li>an {@code addons} declaration appends the addon's accessors <em>after</em> the view's own
 *       and inherited run, skipping collisions, and appends nothing to a sibling view.</li>
 * </ol>
 */
class AddonAndInheritanceTest {

    private static final String MARKER = """
            package addon.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** The addon: an audit interface in a DIFFERENT package, which the packages filter excludes. */
    private static final String AUDITABLE = """
            package addon.other;
            import hr.hrg.hipster.entity.api.EntityBase;
            import java.time.Instant;
            public interface Auditable<ID> extends EntityBase<ID> {
                Instant createdAt();
                Instant updatedAt();
            }
            """;

    /**
     * The entity's declaring view — the marker's own accessor run.
     *
     * <p>Mirrors the example's {@code Person}, which declares {@code firstName}/{@code lastName} and
     * which {@code PersonAuditable} also extends. That shared ancestry is what makes the addon's
     * colliding accessors real: an addon that contributed only genuinely new fields would never
     * collide, and the collision path would go untested.</p>
     */
    private static final String PERSON = """
            package addon.hr;
            import hr.hrg.hipster.entity.api.View;
            @View
            public interface Person extends PersonEntity {
                String firstName();
                String lastName();
            }
            """;

    /** A view that inherits the audit accessors structurally, from two supertypes. */
    private static final String STRUCTURAL = """
            package addon.hr;
            import hr.hrg.hipster.entity.api.View;
            import addon.other.Auditable;
            @View
            public interface PersonAuditable extends Person, Auditable<Long> {
            }
            """;

    /**
     * The declaring view of an addon. It extends {@link #PERSON} — the same supertype the addon
     * extends — so the addon's {@code firstName}/{@code lastName} COLLIDE with accessors this view
     * already has. That shared ancestry is what makes the collision path reachable: an addon
     * contributing only genuinely new fields would never collide, exactly as in the real example
     * where {@code PersonDetails} and {@code PersonAuditable} both extend {@code Person}.
     */
    private static final String ADDON_DECLARER = """
            package addon.hr;
            import hr.hrg.hipster.entity.api.View;
            @View(addons = {PersonAuditable.class})
            public interface PersonDetails extends Person {
                String email();
            }
            """;

    /** A sibling view with no addons declaration: it must gain nothing. */
    private static final String SIBLING = """
            package addon.hr;
            import hr.hrg.hipster.entity.api.View;
            @View
            public interface PersonSummary extends PersonEntity {
                String email();
            }
            """;

    private record Generated(Path outputRoot) {
    }

    private Generated generate(String... sources) throws Exception {
        Path sourceRoot = Files.createTempDirectory("addon-source");
        Path outputRoot = Files.createTempDirectory("addon-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        // The addon lives in a different package, mirroring the example's `example.Auditable`.
        Path otherPackage = sourceRoot.resolve("addon/other");
        Files.createDirectories(otherPackage);
        Files.writeString(otherPackage.resolve("Auditable.java"), AUDITABLE);
        for (int i = 0; i < sources.length; i++) {
            Files.writeString(sourceRoot.resolve("Src" + i + ".java"), sources[i]);
        }
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);
        return new Generated(outputRoot);
    }

    /**
     * The constant names of an emitted field enum, in declaration order.
     *
     * <p>Read from the parsed AST rather than by scanning text. The text version of this helper matched
     * every {@code name(} inside the enum body up to its first {@code ;}, which was correct only while
     * the constant list was one flat line: a constant that carries any
     * {@code @FieldSource} override gets a class body, and the first {@code ;} inside that body then
     * cut the scan short — turning the next constant's override methods into "constants" and making
     * three correct assertions look like failures.</p>
     */
    private static List<String> constants(Path enumFile) throws Exception {
        Assertions.assertTrue(Files.exists(enumFile), "the field enum must be emitted: " + enumFile);
        com.github.javaparser.ast.CompilationUnit cu = SourceReader.read(enumFile).unit();
        Assertions.assertNotNull(cu, "the emitted enum must parse: " + enumFile);
        List<String> names = new java.util.ArrayList<>();
        for (com.github.javaparser.ast.body.EnumConstantDeclaration constant
                : cu.findAll(com.github.javaparser.ast.body.EnumConstantDeclaration.class)) {
            names.add(constant.getNameAsString());
        }
        Assertions.assertFalse(names.isEmpty(), "the enum's constant list must be present: " + enumFile);
        return names;
    }

    @Test
    void accessorsInheritedFromAnotherPackageAreCollected() throws Exception {
        Generated generated = generate(PERSON, STRUCTURAL);

        List<String> personAuditable = constants(generated.outputRoot()
                .resolve("addon/hr/PersonAuditable_.java"));

        Assertions.assertEquals(List.of("id", "firstName", "lastName", "createdAt", "updatedAt"),
                personAuditable,
                "an inherited accessor from a supertype in a DIFFERENT package must be collected — "
                        + "this is what the example's PersonAuditable extends Person, Auditable<Long> "
                        + "depends on, and the filtered-out `example` package still contributes to "
                        + "indexing (X3: the packages knob filters GENERATION, not indexing)");
    }

    @Test
    void anAddonAppendsAfterTheViewsOwnRunAndSkipsCollisions() throws Exception {
        Generated generated = generate(PERSON, ADDON_DECLARER, STRUCTURAL);

        List<String> details = constants(generated.outputRoot().resolve("addon/hr/PersonDetails_.java"));

        Assertions.assertEquals(List.of("id", "firstName", "lastName", "email", "createdAt", "updatedAt"),
                details,
                "the addon's new accessors are appended AFTER the view's own and inherited run, and the "
                        + "colliding ones are skipped — ordinals are append-only (R1), so an addon field "
                        + "may never be inserted into the middle of an existing run. This matches the "
                        + "plan's § 4.5/G6 example exactly: PersonDetails stays at 0-4 and the audit "
                        + "columns land at 5 and 6.");
    }

    @Test
    void aSiblingViewGainsNothingFromAnotherViewsAddon() throws Exception {
        Generated generated = generate(PERSON, ADDON_DECLARER, STRUCTURAL, SIBLING);

        List<String> summary = constants(generated.outputRoot().resolve("addon/hr/PersonSummary_.java"));

        Assertions.assertEquals(List.of("id", "email"), summary,
                "addons resolve PER VIEW (§ 4.7/DR-1): a subtype or sibling does not inherit the "
                        + "declaration, and a view that wants the columns declares addons itself");
    }

    @Test
    void theCollisionIsReportedRatherThanSilentlySkipped() throws Exception {
        generate(PERSON, ADDON_DECLARER, STRUCTURAL);

        Assertions.assertTrue(EntityMetadataGenerator.lastDivergences().stream()
                        .anyMatch(d -> d.startsWith("kind=addon_field_collision")),
                "a skipped addon accessor is reported (G6: never a silent skip); got "
                        + EntityMetadataGenerator.lastDivergences());
        Assertions.assertTrue(EntityMetadataGenerator.lastDivergences().stream()
                        .anyMatch(d -> d.contains("PersonAuditable") && d.contains("firstName")),
                "and it names the addon and the colliding field; got "
                        + EntityMetadataGenerator.lastDivergences());
    }

    @Test
    void anUnresolvableAddonIsReported() throws Exception {
        String bogus = """
                package addon.hr;
                import hr.hrg.hipster.entity.api.View;
                @View(addons = {NoSuchAddon.class})
                public interface PersonDetails extends PersonEntity {
                    String email();
                }
                """;
        generate(bogus);

        Assertions.assertTrue(EntityMetadataGenerator.lastDivergences().stream()
                        .anyMatch(d -> d.startsWith("kind=unresolved_addon")),
                "an addon that cannot be resolved is a diagnostic, not a silent skip; got "
                        + EntityMetadataGenerator.lastDivergences());
    }

    /**
     * The addon pattern's sharpest type-resolution case (plan.dsflash § 8.2/3.6).
     *
     * <p>An addon in another package may name one of <em>its own</em> package's types without an
     * import — {@code LocalThing thing();} in {@code addon.other}, where {@code LocalThing} is
     * {@code addon.other.LocalThing}. The view that declares the addon lives in {@code addon.hr}, so
     * the generated {@code Details_} is in a third place and writes {@code LocalThing.class}: without
     * an import for the addon's own package, that file does not compile, and nothing had reported it.
     * The view's own import table cannot supply the name, because the accessor's file imported nothing
     * — only the package the accessor was declared in can resolve it.</p>
     */
    @Test
    void aTypeFromTheAddonsOwnPackageIsImportedIntoTheGeneratedView() throws Exception {
        Path sourceRoot = Files.createTempDirectory("addon-own-package-source");
        Path outputRoot = Files.createTempDirectory("addon-own-package-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Path otherPackage = sourceRoot.resolve("addon/other");
        Files.createDirectories(otherPackage);
        Files.writeString(otherPackage.resolve("AddonEntity.java"), """
                package addon.other;
                import hr.hrg.hipster.entity.api.EntityBase;
                import hr.hrg.hipster.entity.api.Identifiable;
                public interface AddonEntity extends EntityBase<Long>, Identifiable<Long> {}
                """);
        Files.writeString(otherPackage.resolve("LocalThing.java"), """
                package addon.other;
                public interface LocalThing {
                    String label();
                }
                """);
        Files.writeString(otherPackage.resolve("AuditAddon.java"), """
                package addon.other;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface AuditAddon extends AddonEntity {
                    LocalThing thing();
                }
                """);
        Files.writeString(sourceRoot.resolve("Details.java"), """
                package addon.hr;
                import hr.hrg.hipster.entity.api.View;
                import addon.other.AuditAddon;
                @View(addons = {AuditAddon.class})
                public interface Details extends PersonEntity {
                    String email();
                }
                """);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        Path enumFile = outputRoot.resolve("addon/hr/Details_.java");
        String source = Files.readString(enumFile).replace("\r\n", "\n");
        Assertions.assertTrue(source.contains("thing(LocalThing.class)"),
                "the addon's accessor becomes a field of the view's enum, spelled as the addon wrote "
                        + "it: " + source);
        Assertions.assertTrue(source.contains("import addon.other.LocalThing;"),
                "and the declaring package's own type travels with it, even though nothing in the "
                        + "addon's source imported it: " + source);

        // The compile gate is the assertion that matters: the import exists to make this succeed.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "addon own-package type",
                CompileHarness.javaSourcesUnder(outputRoot),
                CompileHarness.javaSourcesUnder(sourceRoot));
    }

    /**
     * The one case type resolution must refuse to answer (plan.dsflash § 8.7/3.20).
     *
     * <p>Two nested types in the declaring package share a simple name, and the accessor names it
     * without an import. Java's own order ({@code A.Inner} vs {@code B.Inner}) has no winner until the
     * reader knows the enclosing type, which an offline reader cannot see. Emitting either would be a
     * guess that compiles or fails depending on the coin, so the generator emits neither and reports —
     * which is the DEC-022 shape for "the generator will not decide this for you".</p>
     */
    @Test
    void aNameTheDeclaringPackageDeclaresTwiceIsReportedRatherThanGuessed() throws Exception {
        Path sourceRoot = Files.createTempDirectory("addon-ambiguous-source");
        Path outputRoot = Files.createTempDirectory("addon-ambiguous-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("Alpha.java"), """
                package addon.hr;
                public interface Alpha {
                    interface Inner {
                        String a();
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("Beta.java"), """
                package addon.hr;
                public interface Beta {
                    interface Inner {
                        String b();
                    }
                }
                """);
        Files.writeString(sourceRoot.resolve("Uses.java"), """
                package addon.hr;
                import hr.hrg.hipster.entity.api.View;
                @View
                public interface Uses extends PersonEntity {
                    Inner value();
                }
                """);

        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);

        String ambiguous = reporter.entries().stream()
                .filter(entry -> entry.startsWith("kind=type_ambiguous"))
                .findFirst().orElse("(no type_ambiguous entry in " + reporter.entries() + ")");
        Assertions.assertTrue(ambiguous.startsWith("kind=type_ambiguous"),
                "the ambiguity is reported, not resolved by coin toss; got " + reporter.entries());
        Assertions.assertTrue(ambiguous.contains("addon.hr.Alpha.Inner")
                        && ambiguous.contains("addon.hr.Beta.Inner"),
                "and both candidates are named, so the fix is obvious: " + ambiguous);

        String enumFile = Files.readString(outputRoot.resolve("addon/hr/Uses_.java")).replace("\r\n", "\n");
        Assertions.assertFalse(enumFile.contains("import addon.hr.Alpha.Inner;")
                        || enumFile.contains("import addon.hr.Beta.Inner;"),
                "neither candidate is imported: " + enumFile);
    }
}
