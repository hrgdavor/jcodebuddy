package hr.hrg.hipster.entity.tooling;

import org.openrewrite.java.tree.J;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The shape matrix of plan.dsflash § 4.5/G9 / § 4.7/DR-9 for {@link ViewAnnotationReader}.
 *
 * <p>Every one of the four shapes the tree contains must parse, and the two {@code gen} spellings
 * must both resolve. The reader is shape-blind by construction, which is why the bare {@code @View}
 * marker form — live on {@code PersonDto} and {@code PersonUpdateForm} — must be found rather than
 * dropped.</p>
 *
 * <h3>Phase 6</h3>
 * <p>The test used to build its fixtures with JavaParser and hand the reader a JavaParser
 * {@code AnnotationExpr}. It now parses through {@link SourceReader} and finds the annotation on an
 * LST declaration, so it exercises the same route the production callers use. Every assertion is
 * unchanged: the shapes, the two {@code gen} spellings, the fallbacks and the four diagnostics are the
 * reader's contract, and none of them depends on which parser produced the tree.</p>
 */
class ViewAnnotationReaderTest {

    /**
     * The {@code @View} annotation on the interface a fixture declares.
     *
     * <p>{@code TreeQueries.annotationNamed} rather than a direct {@code getLeadingAnnotations()} walk,
     * because the annotation's type may be spelled bare or fully qualified and only the last segment is
     * the name being looked for — the same reason the production discovery path uses it.</p>
     */
    private J.Annotation annotationOf(String source) {
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");
        for (J.ClassDeclaration declaration : TreeQueries.interfaces(unit)) {
            J.Annotation view = TreeQueries.annotationNamed(declaration, "View");
            if (view != null) {
                return view;
            }
        }
        throw new AssertionError("no @View annotation found in fixture");
    }

    private ViewAttributes read(String annotation) {
        return ViewAnnotationReader.read(annotationOf(fixture(annotation)));
    }

    private ViewAnnotationReader.Parsed parse(String annotation) {
        return ViewAnnotationReader.parse(annotationOf(fixture(annotation)));
    }

    private static String fixture(String annotation) {
        return "import hr.hrg.hipster.entity.api.View;\n"
                + "import hr.hrg.hipster.entity.api.GenLevel;\n"
                + annotation + "\n"
                + "public interface V {}\n";
    }

    @Test
    void bareMarkerFormParses() {
        ViewAttributes attributes = read("@View");
        Assertions.assertEquals(GenLevel.DEFAULT, attributes.gen());
        Assertions.assertEquals("", attributes.discriminatorField());
        Assertions.assertTrue(attributes.addons().isEmpty());
    }

    @Test
    void emptyParensFormParses() {
        Assertions.assertEquals(GenLevel.DEFAULT, read("@View()").gen());
    }

    @Test
    void simpleGenSpellingParses() {
        Assertions.assertEquals(GenLevel.BUILDER_ALL, read("@View(gen = GenLevel.BUILDER_ALL)").gen());
    }

    @Test
    void fullyQualifiedGenSpellingParses() {
        Assertions.assertEquals(GenLevel.META,
                read("@View(gen = hr.hrg.hipster.entity.api.GenLevel.META)").gen());
    }

    @Test
    void unknownGenConstantFallsBackToDefaultWithADiagnostic() {
        ViewAnnotationReader.Parsed parsed = parse("@View(gen = GenLevel.NOPE)");
        Assertions.assertEquals(GenLevel.DEFAULT, parsed.attributes().gen(),
                "an unknown constant must never resolve silently to META");
        Assertions.assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.startsWith("unknown_gen_level")));
    }

    @Test
    void retiredSingleMemberFormIsDiagnosed() {
        ViewAnnotationReader.Parsed parsed = parse("@View(true)");
        Assertions.assertEquals(GenLevel.DEFAULT, parsed.attributes().gen());
        Assertions.assertTrue(parsed.diagnostics().stream()
                .anyMatch(d -> d.startsWith("unsupported_view_annotation_form")));
    }

    @Test
    void discriminatorFieldIsRead() {
        Assertions.assertEquals("type",
                read("@View(discriminatorField = \"type\")").discriminatorField());
    }

    @Test
    void addonsAreReadAsSimpleNames() {
        ViewAttributes attributes = read("@View(addons = {hr.hrg.example.PersonAuditable.class, Other.class})");
        Assertions.assertEquals(List.of("PersonAuditable", "Other"), attributes.addons());
    }

    /**
     * An empty addons list means "no addons", which the LST expresses as a single {@code J.Empty}
     * placeholder rather than an empty list.
     *
     * <p>This test caught a real defect in the ported reader: the placeholder was being read as an addon
     * named {@code Empty}, so {@code @View(addons = {})} reported one phantom addon and no diagnostic.
     * The same {@code J.Empty} shape appears for an empty parameter list, which is why the migration
     * caveats call it out as a class of trap rather than a one-off.</p>
     */
    @Test
    void emptyAddonsListParses() {
        Assertions.assertTrue(read("@View(addons = {})").addons().isEmpty(),
                "an empty addons list must yield no addons, not one placeholder");
    }

    @Test
    void unknownAttributeIsDiagnosedNotIgnored() {
        ViewAnnotationReader.Parsed parsed = parse("@View(read = BooleanOption.TRUE)");
        Assertions.assertTrue(parsed.diagnostics().stream().anyMatch(d -> d.startsWith("unknown_view_attribute")),
                "an attribute the annotation does not declare must be reported, not silently dropped");
        Assertions.assertEquals(GenLevel.DEFAULT, parsed.attributes().gen());
    }

    /**
     * The regression the reader exists for: discovery is shape-blind, so the marker form must be
     * found. This mirrors the real-tree assertion in § 4.5/G9.
     */
    @Test
    void discoveryIsShapeBlind() {
        for (String shape : List.of("@View", "@View()", "@View(gen = GenLevel.META)",
                "@View(gen = GenLevel.BUILDER_ALL)")) {
            J.Annotation annotation = annotationOf(fixture(shape));
            Assertions.assertNotNull(annotation, shape + " must be discoverable");
        }
    }
}
