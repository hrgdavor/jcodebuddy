package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.EntityFieldMeta;
import hr.hrg.hipster.entity.tooling.meta.EntityMeta;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.ViewMeta;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class EntityMetadataGeneratorTest {

    @Test
    public void generateMetadataJsonForEntityPackage() throws Exception {
        Path sourceRoot = Files.createTempDirectory("entity-metadata-source");
        Path outputRoot = Files.createTempDirectory("entity-metadata-output");

        Path personEntityFile = sourceRoot.resolve("PersonEntity.java");
        Path personSummaryFile = sourceRoot.resolve("PersonSummary.java");
        Path personDtoFile = sourceRoot.resolve("PersonDto.java");

        String entitySource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<Long> {}\n";

        String summarySource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "import hr.hrg.hipster.entity.api.BooleanOption;\n" +
                "import hr.hrg.hipster.entity.api.FieldSource;\n" +
                "import hr.hrg.hipster.entity.api.FieldKind;\n" +
                "import java.util.List;\n" +
                "import java.util.Map;\n" +
                "@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)\n" +
                "public interface PersonSummary extends PersonEntity {\n" +
                "  String firstName();\n" +
                "  String lastName();\n" +
                "  @FieldSource(kind = FieldKind.DERIVED, expression = \"YEAR(NOW()) - YEAR(birthDate)\")\n" +
                "  Integer age();\n" +
                "  @FieldSource(kind = FieldKind.JOINED, relation = \"department.name\")\n" +
                "  String departmentName();\n" +
                "  Map<String, List<Long>> metadata();\n" +
                "}\n";

        // PersonDto: same field name 'age' but as COLUMN (non-derived) with same type,
        // and 'firstName' with different type String vs in summary
        String dtoSource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "import hr.hrg.hipster.entity.api.BooleanOption;\n" +
                "@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)\n" +
                "public interface PersonDto extends PersonEntity {\n" +
                "  String firstName();\n" +
                "  Integer age();\n" +
                "}\n";

        Files.writeString(personEntityFile, entitySource);
        Files.writeString(personSummaryFile, summarySource);
        Files.writeString(personDtoFile, dtoSource);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        Path metadataFile = outputRoot.resolve("Person.metadata.json");
        Assertions.assertTrue(Files.exists(metadataFile), "Metadata file should be generated");

        String json = Files.readString(metadataFile);

        EntityMeta entityMeta = EntityMetadataGenerator.fromJson(json);

        // Entity basics
        Assertions.assertEquals("Person", entityMeta.entityName());
        Assertions.assertEquals("PersonEntity", entityMeta.markerInterface());
        Assertions.assertEquals("Long", entityMeta.idType());

        // View properties
        Assertions.assertNotNull(entityMeta.views());
        Assertions.assertEquals(2, entityMeta.views().size());

        ViewMeta personSummary = null;
        for (ViewMeta view : entityMeta.views()) {
            if ("PersonSummary".equals(view.name())) {
                personSummary = view;
                break;
            }
        }
        Assertions.assertNotNull(personSummary, "PersonSummary view should exist");
        Assertions.assertEquals(8, personSummary.lineNumber());

        Property firstNameProp = null;
        for (Property prop : personSummary.properties()) {
            if ("firstName".equals(prop.name())) {
                firstNameProp = prop;
                break;
            }
        }
        Assertions.assertNotNull(firstNameProp, "firstName property should exist");
        Assertions.assertTrue(firstNameProp.lineNumber() > 0, "firstName lineNumber should be > 0");

        Assertions.assertNotNull(entityMeta.allFields());
        EntityFieldMeta ageField = null;
        EntityFieldMeta firstNameField = null;
        for (EntityFieldMeta field : entityMeta.allFields()) {
            if ("age".equals(field.name)) ageField = field;
            if ("firstName".equals(field.name)) firstNameField = field;
        }
        Assertions.assertNotNull(ageField, "age should be in allFields");
        Assertions.assertNotNull(firstNameField, "firstName should be in allFields");
        Assertions.assertEquals("COLUMN", ageField.fieldKind);
        Assertions.assertTrue(firstNameField.lineNumber > 0, "firstName allFields lineNumber should be > 0");

        // allFields: id is COLUMN and present in both views
        Assertions.assertEquals("COLUMN", ageField.fieldKind);

        // allFields: typeByView tracking
        Assertions.assertTrue(ageField.typeByView.containsKey("PersonSummary"));
        Assertions.assertTrue(ageField.typeByView.containsKey("PersonDto"));

        // 'age' field: first seen as DERIVED in PersonSummary, then as COLUMN in PersonDto
        Assertions.assertNotNull(ageField, "age should appear in allFields");
        Assertions.assertEquals("COLUMN", ageField.fieldKind);

        Assertions.assertTrue(ageField.typeByView.containsKey("PersonSummary"));
        Assertions.assertTrue(ageField.typeByView.containsKey("PersonDto"));

        // 'firstName' appears in allFields with a line number
        Assertions.assertTrue(firstNameField.lineNumber > 0);

        // Enum generation
        Path enumFile = outputRoot.resolve("hr/hrg/hipster/entity/person/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(enumFile), "View property enum should be generated");

        String enumSource = Files.readString(enumFile);
        Assertions.assertTrue(enumSource.contains("enum PersonSummary_"));
        Assertions.assertTrue(enumSource.contains("id(java.lang.Long.class)"));
        Assertions.assertTrue(enumSource.contains("firstName(java.lang.String.class)"));
        Assertions.assertTrue(enumSource.contains("lastName(java.lang.String.class)"));
        Assertions.assertTrue(enumSource.contains("age(java.lang.Integer.class)"));
        Assertions.assertTrue(enumSource.contains("departmentName(java.lang.String.class)"));
    }

    @Test
    public void childViewPropertyOverrideUsesChildType() throws Exception {
        Path sourceRoot = Files.createTempDirectory("entity-metadata-override-source");
        Path outputRoot = Files.createTempDirectory("entity-metadata-override-output");

        Path personEntityFile = sourceRoot.resolve("PersonEntity.java");
        Path baseViewFile = sourceRoot.resolve("BaseView.java");
        Path childViewFile = sourceRoot.resolve("ChildView.java");

        String entitySource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<Long> {}\n";
        String baseViewSource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "import hr.hrg.hipster.entity.api.BooleanOption;\n" +
                "@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)\n" +
                "public interface BaseView extends PersonEntity {\n" +
                "  String status();\n" +
                "}\n";
        String childViewSource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "import hr.hrg.hipster.entity.api.BooleanOption;\n" +
                "@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)\n" +
                "public interface ChildView extends BaseView {\n" +
                "  Integer status();\n" +
                "}\n";

        Files.writeString(personEntityFile, entitySource);
        Files.writeString(baseViewFile, baseViewSource);
        Files.writeString(childViewFile, childViewSource);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        Path metadataFile = outputRoot.resolve("Person.metadata.json");
        Assertions.assertTrue(Files.exists(metadataFile), "Metadata file should be generated");

        String json = Files.readString(metadataFile);
        EntityMeta entityMeta = EntityMetadataGenerator.fromJson(json);

        ViewMeta childView = entityMeta.views().stream()
                .filter(v -> "ChildView".equals(v.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ChildView should exist"));

        Property statusProp = childView.properties().stream()
                .filter(p -> "status".equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("status property should exist"));

        Assertions.assertEquals("java.lang.Integer", statusProp.type());
    }

    @Test
    public void generatePaymentMethodMetadataFromExampleSources() throws Exception {
        Path outputRoot = Files.createTempDirectory("paymentmethod-output");

        Path moduleRoot = Path.of("").toAbsolutePath();
        if (moduleRoot.endsWith("hipster-entity-tooling")) {
            moduleRoot = moduleRoot.getParent();
        }

        Path exampleSource = moduleRoot.resolve("hipster-entity-example")
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .resolve("hr")
                .resolve("hrg")
                .resolve("hipster")
                .resolve("entityexample")
                .resolve("paymentMethod")
                .resolve("entity");
        Assertions.assertTrue(Files.exists(exampleSource), "Example paymentMethod sources must exist for this integration test");

        EntityMetadataGenerator.generate(exampleSource, outputRoot);

        Path metadataFile = outputRoot.resolve("PaymentMethod.metadata.json");
        Assertions.assertTrue(Files.exists(metadataFile), "PaymentMethod metadata should be generated");

        String json = Files.readString(metadataFile);
        Assertions.assertTrue(json.contains("PaymentMethod"), "Metadata JSON should mention PaymentMethod");
        Assertions.assertTrue(json.contains("BankTransferPaymentMethod"), "Metadata JSON should include concrete subtype names");

        Assertions.assertTrue(Files.exists(outputRoot.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity/BankTransferPaymentMethod_.java")));
        Assertions.assertTrue(Files.exists(outputRoot.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity/CreditCardPaymentMethod_.java")));
        Assertions.assertTrue(Files.exists(outputRoot.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity/CryptoPaymentMethod_.java")));
        Assertions.assertTrue(Files.exists(outputRoot.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity/PayPalPaymentMethod_.java")));

        String enumSource = Files.readString(outputRoot.resolve("hr/hrg/hipster/entityexample/paymentMethod/entity/BankTransferPaymentMethod_.java"));
        Assertions.assertTrue(enumSource.contains("enum BankTransferPaymentMethod_"));
        Assertions.assertTrue(enumSource.contains("accountNumber(java.lang.String.class)"));
    }

    @Test
    public void generateFromSingleJavaFileUsesExampleSourceRoot() throws Exception {
        Path sourceRoot = Files.createTempDirectory("single-file-source-root");
        Path packageDir = sourceRoot.resolve("hr/hrg/hipster/entity/person");
        Files.createDirectories(packageDir);

        String entitySource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<Long> {}\n";
        String summarySource = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "import hr.hrg.hipster.entity.api.BooleanOption;\n" +
                "import java.util.Map;\n" +
                "import java.util.List;\n" +
                "@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)\n" +
                "public interface PersonSummary extends PersonEntity {\n" +
                "  String firstName();\n" +
                "  String lastName();\n" +
                "  Map<String, List<Long>> metadata();\n" +
                "}\n";

        Path entityFile = packageDir.resolve("PersonEntity.java");
        Path summaryFile = packageDir.resolve("PersonSummary.java");
        Files.writeString(entityFile, entitySource);
        Files.writeString(summaryFile, summarySource);

        Path outputRoot = Files.createTempDirectory("single-file-output");
        EntityMetadataGenerator.main(new String[]{summaryFile.toString(), outputRoot.toString()});

        Path generatedEnum = packageDir.resolve("PersonSummary_.java");
        Assertions.assertTrue(Files.exists(generatedEnum), "Generated property enum should be written back into the source tree");
        String generatedSource = Files.readString(generatedEnum);
        Assertions.assertTrue(generatedSource.contains("return switch (name)"), "Generated property enum should use a switch expression for forName lookup");
        Assertions.assertTrue(generatedSource.contains("case \"id\" -> id;"), "Generated property enum should include a switch case for id");
    }

    @Test
    public void generateAndCompileExamplePersonBoilerplate() throws Exception {
        Path moduleRoot = findModuleRoot();
        Path repoRoot = moduleRoot.getFileName().toString().equals("hipster-entity-tooling")
                ? moduleRoot.getParent()
                : moduleRoot;

        Path exampleSourceRoot = repoRoot.resolve("hipster-entity-example").resolve("src").resolve("main").resolve("java");
        Assertions.assertTrue(Files.exists(exampleSourceRoot), "Example source root must exist");

        Path outputRoot = Files.createTempDirectory("person-boilerplate-compile");
        EntityMetadataGenerator.generate(exampleSourceRoot, outputRoot);

        List<Path> generatedSources = Files.walk(outputRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();
        Assertions.assertFalse(generatedSources.isEmpty(), "Generated source files should exist");

        List<Path> exampleSources = Files.walk(exampleSourceRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().endsWith("_.java"))
                .toList();
        Assertions.assertFalse(exampleSources.isEmpty(), "Example source files should exist");

        Path compileOutput = Files.createTempDirectory("person-boilerplate-classes");
        boolean compiled = compileSources(repoRoot, generatedSources, exampleSources, compileOutput);
        Assertions.assertTrue(compiled, "Generated example boilerplate should compile successfully");

        Path summaryEnum = outputRoot.resolve("hr/hrg/hipster/entityexample/person/entity/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(summaryEnum), "PersonSummary_ enum should be generated in the example output");
        String summarySource = Files.readString(summaryEnum);
        Assertions.assertTrue(summarySource.contains("switch (name)"), "Generated PersonSummary_ should use a switch-based forName lookup");
        Assertions.assertTrue(summarySource.contains("case \"id\" -> id;"), "Generated PersonSummary_ should include a switch case for id");
    }

    private Path findModuleRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate module root from current working directory");
        }
        return current;
    }

    private boolean compileSources(Path repoRoot, List<Path> generatedSources, List<Path> exampleSources, Path outputDir) throws IOException {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler, "Java compiler must be available in the test runtime");

        try (javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            List<Path> allSources = new java.util.ArrayList<>(generatedSources);
            allSources.addAll(exampleSources);
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(
                    allSources.stream().map(Path::toFile).toList());

            String pathSeparator = System.getProperty("path.separator");
            String explicitClasspath = String.join(pathSeparator,
                    repoRoot.resolve("hipster-entity-api/target/classes").toString(),
                    repoRoot.resolve("hipster-entity-core/target/classes").toString());

            javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    java.util.List.of("-d", outputDir.toString(), "-classpath", explicitClasspath),
                    null,
                    compilationUnits);

            return task.call();
        }
    }
}
