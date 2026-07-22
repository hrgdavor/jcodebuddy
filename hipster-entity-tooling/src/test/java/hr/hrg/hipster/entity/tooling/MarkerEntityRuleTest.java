package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MarkerEntityRuleTest {

    @Test
    public void shouldFlagMarkerInterfaceWithMethods() throws Exception {
        Path tempDir = Files.createTempDirectory("marker-entity-rules");
        Path file = tempDir.resolve("PersonEntity.java");
        String content = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> { String name(); }\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertFalse(issues.isEmpty());
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("should not declare domain methods")));
    }

    @Test
    public void shouldAllowValidMarkerInterface() throws Exception {
        Path tempDir = Files.createTempDirectory("marker-entity-valid");
        Path file = tempDir.resolve("PersonEntity.java");
        String content = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> {}\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertTrue(issues.isEmpty());
    }
}
