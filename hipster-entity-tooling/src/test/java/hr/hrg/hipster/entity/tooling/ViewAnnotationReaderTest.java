package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;

import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.tooling.meta.ViewAttributes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The shape matrix of plan.dsflash § 4.5/G9 / § 4.7/DR-9 for {@link ViewAnnotationReader}.
 *
 * <p>Every one of the four shapes the tree contains must parse, and the two {@code gen} spellings
 * must both resolve. A {@code NormalAnnotationExpr}-restricted parser silently drops the bare
 * {@code @View} marker form, which is live on {@code PersonDto} and {@code PersonUpdateForm} — two
 * of the five {@code @View}-carrying views the plan expects to generate.</p>
 */
class ViewAnnotationReaderTest {

    private AnnotationExpr annotationOf(String source) {
        CompilationUnit cu = new JavaParser().parse(source).getResult().orElseThrow();
        ClassOrInterfaceDeclaration decl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        return decl.getAnnotationByName("View").orElseThrow();
    }

    private ViewAttributes read(String annotation) {
        return ViewAnnotationReader.read(annotationOf(
                "import hr.hrg.hipster.entity.api.View;\n"
                + "import hr.hrg.hipster.entity.api.GenLevel;\n"
                + annotation + "\n"
                + "public interface V {}\n"));
    }

    private ViewAnnotationReader.Parsed parse(String annotation) {
        return ViewAnnotationReader.parse(annotationOf(
                "import hr.hrg.hipster.entity.api.View;\n"
                + "import hr.hrg.hipster.entity.api.GenLevel;\n"
                + annotation + "\n"
                + "public interface V {}\n"));
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

    @Test
    void emptyAddonsListParses() {
        Assertions.assertTrue(read("@View(addons = {})").addons().isEmpty());
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
            AnnotationExpr annotation = annotationOf(
                    "import hr.hrg.hipster.entity.api.View;\n"
                    + "import hr.hrg.hipster.entity.api.GenLevel;\n"
                    + shape + "\n"
                    + "public interface V {}\n");
            Assertions.assertNotNull(annotation, shape + " must be discoverable");
        }
    }
}
