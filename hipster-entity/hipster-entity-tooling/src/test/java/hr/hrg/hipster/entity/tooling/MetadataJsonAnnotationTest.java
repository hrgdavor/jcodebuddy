// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.FieldConstraint;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

/**
 * The metadata document's half of DEC-047 and plan step 6.10: a field's Bean Validation annotations must
 * reach the generated enum <em>through the JSON</em>, not only through the source-parsing API.
 *
 * <p>The finding behind the step was that the source pass emitted {@code annotations()} while the JSON it
 * wrote had no {@code constraints}, so a consumer reading only the document could not see the annotation.
 * The fix is the writer; this test pins both halves: the document carries the constraints, and feeding the
 * document's property list to the same enum emitter produces the same {@code FieldAnnotation} metadata the
 * source pass put in the committed enum.</p>
 *
 * <p><strong>Why the assertion is the annotation metadata rather than a whole-file diff.</strong> The
 * document's {@code properties[]} is the view's <em>own declaration</em> record (DEC-028 § 2.6): inherited
 * accessors (the {@code id} every view gains, a supertype's fields) arrive in the {@code allFields}
 * summary, not here, and the enum's {@code META} creator body is derived from the view's generation
 * level rather than from any metadata fact. A whole enum is therefore not reconstructible from this
 * document, and pretending otherwise would test a second source of truth. The annotation metadata is the
 * part 6.10 is about, and it is compared exactly — type, arguments and declaration order.</p>
 */
class MetadataJsonAnnotationTest {

    @TempDir
    Path tempDir;

    private static final String ENTITY = """
            package annot.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            public interface PersonEntity extends EntityBase<Long> {}
            """;

    /** `name` carries two constraints written two ways; `age` carries none. */
    private static final String VIEW = """
            package annot.hr;
            import hr.hrg.hipster.entity.api.View;
            @View()
            public interface PersonSummary extends PersonEntity {
                @jakarta.validation.constraints.NotNull
                @jakarta.validation.constraints.Size(min = 1, max = 64)
                String name();
                Integer age();
            }
            """;

    @Test
    void theJsonCarriesTheAnnotationsAndTheSameMetadataReachesTheEnum() throws Exception {
        Path source = tempDir.resolve("src");
        Path metadata = tempDir.resolve("meta");
        Path javaOut = tempDir.resolve("java");
        Files.createDirectories(source.resolve("annot/hr"));
        Files.createDirectories(metadata);
        Files.createDirectories(javaOut);
        Files.writeString(source.resolve("annot/hr/PersonEntity.java"), ENTITY);
        Files.writeString(source.resolve("annot/hr/PersonSummary.java"), VIEW);

        DivergenceReporter divergences = new DivergenceReporter();
        EntityMetadataGenerator.generate(source, metadata, javaOut, divergences);

        Path jsonFile = onlyMetadataFile(metadata);
        EntityMeta parsed = EntityMetadataGenerator.fromJson(Files.readString(jsonFile));
        ViewMeta view = parsed.views().stream()
                .filter(candidate -> "PersonSummary".equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the document must carry PersonSummary: "
                        + parsed.views().stream().map(ViewMeta::name).toList()));

        Property name = property(view, "name");
        Assertions.assertEquals(List.of("NotNull", "Size"),
                name.constraints().stream().map(FieldConstraint::simpleName).toList(),
                "the JSON must carry both constraints, in declaration order: " + Files.readString(jsonFile));
        Assertions.assertEquals("min = 1, max = 64", name.constraints().get(1).arguments(),
                "the argument text is carried verbatim, not re-rendered");

        Property age = property(view, "age");
        Assertions.assertTrue(age.constraints().isEmpty(),
                "an unannotated field gains no constraints key value: " + age.constraints());

        // The source pass's own enum is the reference. Feed the document's property list to the same
        // emitter, with the one option the enum needs that is derived from the view rather than stored
        // in the document (the META creator body), and compare the annotation metadata it carries.
        String sourceEnum = Files.readString(javaOut.resolve("annot/hr/PersonSummary_.java"));
        Path jsonJava = tempDir.resolve("json-java");
        FieldBoilerplateGenerator.builder("annot.hr", "PersonSummary", view.properties())
                .withEnumTypeName("PersonSummary_")
                .withMetaCreatorBody(EntityMetadataGenerator.proxyCreatorBody(view))
                .withAdditionalImports(
                        "hr.hrg.hipster.entity.core.ArrayBackedViewProxyFactory",
                        "hr.hrg.hipster.entity.core.EntityReadArray")
                .generate(jsonJava);
        String jsonEnum = Files.readString(jsonJava.resolve("annot/hr/PersonSummary_.java"));

        List<String> fromSource = annotationLiterals(sourceEnum);
        List<String> fromJson = annotationLiterals(jsonEnum);
        Assertions.assertFalse(fromSource.isEmpty(),
                "the fixture must put at least one FieldAnnotation in the source enum:\n" + sourceEnum);
        Assertions.assertEquals(fromSource, fromJson,
                "the JSON path must emit the same FieldAnnotation metadata as the source path:\nsource:\n"
                        + sourceEnum + "\njson:\n" + jsonEnum);
    }

    private static Property property(ViewMeta view, String name) {
        return view.properties().stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no property `" + name + "`: "
                        + view.properties().stream().map(Property::name).toList()));
    }

    private static Path onlyMetadataFile(Path metadata) throws Exception {
        try (var files = Files.list(metadata)) {
            List<Path> documents = files
                    .filter(file -> file.getFileName().toString().endsWith(".metadata.json"))
                    .toList();
            Assertions.assertEquals(1, documents.size(), "expected one entity document: " + documents);
            return documents.get(0);
        }
    }

    /** Every {@code new FieldAnnotation("type", "arguments")} expression, in source order. */
    private static List<String> annotationLiterals(String enumSource) {
        Matcher matcher = Pattern.compile(
                        "new hr\\.hrg\\.hipster\\.entity\\.api\\.FieldAnnotation\\(\"[^\"]*\", \"[^\"]*\"\\)")
                .matcher(enumSource);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
