package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AuditableRuleTest {

    @Test
    public void shouldAllowAuditableInEntityPackage() throws Exception {
        Path tempDir = Files.createTempDirectory("auditable-rule");
        Path file = tempDir.resolve("PersonAuditable.java");
        String content = "package hr.hrg.hipster.entity.person;\n" +
                "public interface PersonAuditable {}\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertTrue(issues.isEmpty());
    }

    @Test
    public void shouldAllowAuditableInApiPackage() throws Exception {
        Path tempDir = Files.createTempDirectory("auditable-rule-api");
        Path file = tempDir.resolve("Auditable.java");
        String content = "package hr.hrg.hipster.entity.api;\n" +
                "public interface Auditable<ID> {}\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertTrue(issues.isEmpty());
    }
}
