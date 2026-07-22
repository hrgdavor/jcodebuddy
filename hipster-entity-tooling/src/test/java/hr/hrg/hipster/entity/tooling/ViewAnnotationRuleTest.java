package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ViewAnnotationRuleTest {

    @Test
    public void shouldRejectViewAnnotationMissingReadWrite() throws Exception {
        Path tempDir = Files.createTempDirectory("view-annotation-rule");
        Path entityFile = tempDir.resolve("PersonEntity.java");
        Path viewFile = tempDir.resolve("PersonSummary.java");

        String entityContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> {}\n";
        String viewContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "@View\n" +
                "public interface PersonSummary extends PersonEntity { String firstName(); }\n";

        Files.writeString(entityFile, entityContent);
        Files.writeString(viewFile, viewContent);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertFalse(issues.isEmpty());
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("@View must declare read and/or write modes")));
    }

    @Test
    public void shouldAllowViewAnnotationWithReadWrite() throws Exception {
        Path tempDir = Files.createTempDirectory("view-annotation-rule-valid");
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

        Assertions.assertTrue(issues.isEmpty());
    }
}