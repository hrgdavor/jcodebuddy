package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class EntityRulesValidatorTest {

    @Test
    public void shouldDetectMarkerEntityMisconfig() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-rules");
        Path file = tempDir.resolve("PersonEntity.java");
        String content = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> { String name(); }\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertFalse(issues.isEmpty(), "Expected at least one issue for marker with method");
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("should not declare domain methods")));
    }

    @Test
    public void shouldValidateViewAndAnnotation() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-view-rules");
        Path entityFile = tempDir.resolve("PersonEntity.java");
        Path viewFile = tempDir.resolve("PersonSummary.java");

        String entityContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> {}\n";
        String viewContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "import hr.hrg.hipster.entity.api.BooleanOption;\n" +
                "@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)\n" +
                "public interface PersonSummary extends PersonEntity { String firstName(); }\n";

        Files.writeString(entityFile, entityContent);
        Files.writeString(viewFile, viewContent);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertTrue(issues.isEmpty(), "Expected no issues for valid entity/view");
    }

    @Test
    public void shouldRejectViewNotExtendingEntity() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-view-invalid");
        Path viewFile = tempDir.resolve("PersonSummary.java");
        String viewContent = "package hr.hrg.hipster.entity.person;\n" +
                "public interface PersonSummary { String firstName(); }\n";
        Files.writeString(viewFile, viewContent);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertFalse(issues.isEmpty());
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("should extend an entity marker interface")));
    }

    @Test
    public void shouldRejectViewNameConvention() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-view-name");
        Path entityFile = tempDir.resolve("PersonEntity.java");
        Path viewFile = tempDir.resolve("PersonDisplay.java");

        String entityContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> {}\n";
        String viewContent = "package hr.hrg.hipster.entity.person;\n" +
                "public interface PersonDisplay extends PersonEntity { String firstName(); }\n";

        Files.writeString(entityFile, entityContent);
        Files.writeString(viewFile, viewContent);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertFalse(issues.isEmpty());
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("name should follow EntitySummary/EntityDetails/EntityUpdate")));
    }

    @Test
    public void shouldRejectViewAnnotationMissingReadWrite() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-view-annotation");
        Path file = tempDir.resolve("PersonSummary.java");
        String content = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "@View\n" +
                "public interface PersonSummary extends PersonEntity { String firstName(); }\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertFalse(issues.isEmpty());
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("@View must declare read and/or write modes")));
    }
}
