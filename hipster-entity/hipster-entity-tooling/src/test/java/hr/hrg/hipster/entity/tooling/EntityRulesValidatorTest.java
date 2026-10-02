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
                "import hr.hrg.hipster.entity.api.GenLevel;\n" +
                "@View(gen = GenLevel.META)\n" +
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
        Assertions.assertTrue(issues.stream()
                        .anyMatch(i -> i.message.contains("view_does_not_derive_from_marker")),
                "an interface named like a view but reachable from no marker is a finding; got " + issues);
    }

    /**
     * Renamed from {@code shouldRejectViewNameConvention}. The naming rule is no longer part of the
     * validator's output: run against the committed example it produced six findings about six correct
     * views (the polymorphic {@code *PaymentMethod} family, {@code PersonAuditable}, and a doc sample the
     * generator deliberately does not generate). A suffix rule needs to know which interfaces the
     * generator actually treats as views, and a per-file rule cannot know that — the full account is in
     * {@link hr.hrg.hipster.entity.tooling.validation.ViewInterfaceRule}'s javadoc.
     *
     * <p>What remains checkable, and is asserted here, is the annotated case being <em>accepted</em>.</p>
     */
    @Test
    public void shouldAcceptAnAnnotatedViewOverAMarker() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-view-name");
        Path entityFile = tempDir.resolve("PersonEntity.java");
        Path viewFile = tempDir.resolve("PersonDisplay.java");

        String entityContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.EntityBase;\n" +
                "public interface PersonEntity extends EntityBase<String> {}\n";
        String viewContent = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "@View\n" +
                "public interface PersonDisplay extends PersonEntity { String firstName(); }\n";

        Files.writeString(entityFile, entityContent);
        Files.writeString(viewFile, viewContent);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertTrue(issues.isEmpty(),
                "an annotated view over a marker is valid whatever it is named; naming is not enforced "
                        + "by this validator. Got " + issues);
    }

    /**
     * Replaces {@code shouldRejectViewAnnotationMissingReadWrite}, which asserted the opposite of
     * the truth: it required the validator to reject a bare {@code @View}, i.e. every valid view.
     * The real attribute contract is {@code gen}/{@code discriminatorField}/{@code addons},
     * covered exhaustively by {@code ViewAnnotationRuleTest} and {@code ViewAnnotationReaderTest}.
     */
    @Test
    public void shouldAcceptBareViewAnnotation() throws Exception {
        Path tempDir = Files.createTempDirectory("entity-view-annotation");
        Path file = tempDir.resolve("PersonSummary.java");
        String content = "package hr.hrg.hipster.entity.person;\n" +
                "import hr.hrg.hipster.entity.api.View;\n" +
                "@View\n" +
                "public interface PersonSummary extends PersonEntity { String firstName(); }\n";
        Files.writeString(file, content);

        EntityRulesValidator validator = new EntityRulesValidator();
        List<EntityRulesValidator.ValidationIssue> issues = validator.validate(tempDir);

        Assertions.assertTrue(issues.isEmpty(),
                "a bare @View is a valid view declaration; got " + issues);
    }
}
