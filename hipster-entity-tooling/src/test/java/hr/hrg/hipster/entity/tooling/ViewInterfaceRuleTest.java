package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ViewInterfaceRuleTest {

    @Test
    public void shouldRejectViewNotExtendingEntity() throws Exception {
        Path tempDir = Files.createTempDirectory("view-rule");
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
    public void shouldEnforceViewNameConvention() throws Exception {
        Path tempDir = Files.createTempDirectory("view-rule-name");
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
}
