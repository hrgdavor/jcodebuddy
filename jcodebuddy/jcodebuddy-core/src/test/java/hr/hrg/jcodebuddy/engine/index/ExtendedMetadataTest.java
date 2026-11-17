package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.query.TypeDefinition;
import hr.hrg.jcodebuddy.engine.query.TypeResolver;
import hr.hrg.jcodebuddy.engine.source.SourceReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedMetadataTest {

    @TempDir
    Path temp;

    @Test
    void typeReferencesResolveImportsAndSamePackage() {
        MemberRecord method = new MemberRecord("getName", MemberRecord.Kind.METHOD, "List<String>",
                List.of(), List.of(), List.of(), false, List.of(), false, 10, null);
        ClassRecord row = new ClassRecord("demo.User", "demo/User.java", "interface", List.of("public"),
                null, 3, 0, false, "hash", null, 1,
                List.of(TypeRelation.extendsType("Auditable")), List.of(), List.of(method), null, List.of());
        TypeResolver resolver = new TypeResolver() {
            @Override
            public TypeDefinition resolve(String qualifiedName) {
                return "demo.Auditable".equals(qualifiedName)
                        ? new TypeDefinition("demo.Auditable", "Auditable", "interface", List.of(),
                                java.util.Map.of(), List.of())
                        : null;
            }

            @Override
            public List<String> knownPackages() {
                return List.of("demo");
            }
        };

        List<TypeReferenceSite> sites = TypeReferenceExtractor.of(row,
                List.of("import java.util.List;"), resolver);

        TypeReference returnType = sites.stream()
                .filter(site -> site.role().equals("return")).findFirst().orElseThrow().reference();
        assertEquals("java.util.List", returnType.resolvedFqn());
        assertEquals("import", returnType.via());
        assertEquals("java.lang.String", returnType.arguments().get(0).resolvedFqn());

        TypeReference relation = sites.stream()
                .filter(site -> site.role().equals("relation")).findFirst().orElseThrow().reference();
        assertEquals("demo.Auditable", relation.resolvedFqn());
        assertEquals("same-package", relation.via());
    }

    @Test
    void artifactAndTypeReferenceTablesRoundTrip() throws Exception {
        ArtifactsIndex artifacts = new ArtifactsIndex("demo", List.of(new ArtifactRecord(
                "demo.UserImmutable", "demo/UserImmutable.java", "hipster-sql-tooling", "1.0",
                List.of("demo.User"), List.of("genJson"), "hash")));
        artifacts.write(temp);
        ArtifactsIndex read = ArtifactsIndex.read(temp);
        assertEquals(artifacts, read);

        TypeReference reference = new TypeReference("List<String>", "List",
                List.of(new TypeReference("String", "String", List.of(), "java.lang.String",
                        TypeReference.Resolution.RESOLVED, "java.lang", List.of(), null)),
                "java.util.List", TypeReference.Resolution.RESOLVED, "import", List.of(), null);
        TypeReferencesIndex references = new TypeReferencesIndex("demo",
                List.of(new TypeReferenceSite("demo.User", "getName", "return", reference)));
        references.write(temp);
        TypeReferencesIndex readReferences = TypeReferencesIndex.read(temp);
        assertEquals("java.util.List", readReferences.sites().get(0).reference().resolvedFqn());
        assertEquals("java.lang.String",
                readReferences.sites().get(0).reference().arguments().get(0).resolvedFqn());
    }

    @Test
    void packageExtractorReadsPackageAnnotations() throws Exception {
        Path file = temp.resolve("package-info.java");
        Files.writeString(file, "@Deprecated\npackage demo;\n", StandardCharsets.UTF_8);

        PackageRecord pkg = PackageExtractor.of(temp, file);

        assertEquals("demo", pkg.name());
        assertEquals("package-info.java", pkg.path());
        assertNotNull(pkg.checksum());
        assertFalse(pkg.annotations().isEmpty());
        assertEquals("Deprecated", pkg.annotations().get(0).simpleName());
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Sample {
        String name();

        int count() default 3;
    }

    @Test
    void annotationDefaultsFillEffectiveValues() {
        TypeAnnotation written = new TypeAnnotation("Sample", List.of("count = 5"), null);
        EffectiveAnnotation effective = EffectiveAnnotation.of(written, AnnotationTypeFacts.of(Sample.class));
        assertEquals("5", effective.values().get("count"));

        TypeAnnotation onlyName = new TypeAnnotation("Sample", List.of("name = \"x\""), null);
        EffectiveAnnotation withDefault = EffectiveAnnotation.of(onlyName,
                AnnotationTypeFacts.of(Sample.class));
        assertEquals(3, withDefault.values().get("count"));
        assertEquals("x", AnnotationFacts.stringArg(onlyName, "name", ""));
    }

    @Test
    void typeUsesIndexAndModuleExtractorRoundTrip() throws Exception {
        TypeUsesIndex uses = new TypeUsesIndex("demo",
                List.of(new TypeUseRecord("demo.Demo", "run", "service#call", "method-call", -1)));
        uses.write(temp);
        assertEquals(uses, TypeUsesIndex.read(temp));

        Path moduleInfo = temp.resolve("module-info.java");
        Files.writeString(moduleInfo, """
                module demo.mod {
                    requires java.sql;
                    exports demo.api;
                    uses demo.spi.Service;
                }
                """, StandardCharsets.UTF_8);
        ModuleRecord module = ModuleExtractor.of(temp, moduleInfo);
        assertEquals("demo.mod", module.name());
        assertEquals(List.of("java.sql"), module.requires());
        assertEquals(List.of("demo.api"), module.exports());
        assertEquals(List.of("demo.spi.Service"), module.uses());

        ModulesIndex modules = new ModulesIndex("demo", List.of(module));
        modules.write(temp);
        assertEquals(module.name(), ModulesIndex.read(temp).modules().get(0).name());
    }

    @Test
    void extendedLayerWritesAndReadsTogether() throws Exception {
        ExtendedMetadataIndex extended = new ExtendedMetadataIndex("demo",
                new ArtifactsIndex("demo", List.of(new ArtifactRecord("demo.UserImmutable",
                        "demo/UserImmutable.java", "gen", "1", List.of("demo.User"), List.of(), "h"))),
                new TypeReferencesIndex("demo", List.of()),
                new PackagesIndex("demo", List.of()),
                new ModulesIndex("demo", List.of()),
                new TypeUsesIndex("demo", List.of(new TypeUseRecord("demo.User", "run", "x#y",
                        "method-call", -1))));
        extended.write(temp);

        ExtendedMetadataIndex read = ExtendedMetadataIndex.read(temp);
        assertEquals("demo.UserImmutable", read.artifacts().artifacts().get(0).type());
        assertEquals("x#y", read.typeUses().uses().get(0).target());
    }

    @Test
    void domainFactsCanBeProjectedFromGenericAnnotations() {
        TypeAnnotation table = new TypeAnnotation("HipsterEntity",
                List.of("table = \"user_table\""), null);
        TypeAnnotation column = new TypeAnnotation("HipsterColumn",
                List.of("skip = true", "initial = \"5\""), null);
        TypeAnnotation id = new TypeAnnotation("Id", List.of(), null);

        assertEquals("user_table", AnnotationFacts.stringArg(table, "table", ""));
        assertTrue(AnnotationFacts.booleanArg(column, "skip", false));
        assertEquals("5", AnnotationFacts.stringArg(column, "initial", ""));
        assertNotNull(AnnotationFacts.find(List.of(id), "Id"));
        assertEquals("HipsterColumn", AnnotationFacts.find(List.of(column), "HipsterColumn").simpleName());
    }

    @Test
    void typeUsesReadMethodBodies() {
        SourceReader.Read read = SourceReader.readText("""
                package demo;
                class Demo {
                    void run() {
                        service.call();
                        new Helper();
                    }
                }
                """);
        List<TypeUseRecord> uses = TypeUseExtractor.of(read.unit(), "demo.Demo");

        assertTrue(uses.stream().anyMatch(use -> use.kind().equals("method-call")
                && use.target().contains("call")));
        assertTrue(uses.stream().anyMatch(use -> use.kind().equals("new")
                && use.target().equals("Helper")));
        assertTrue(uses.stream().anyMatch(use -> use.member().equals("run")));
    }
}
