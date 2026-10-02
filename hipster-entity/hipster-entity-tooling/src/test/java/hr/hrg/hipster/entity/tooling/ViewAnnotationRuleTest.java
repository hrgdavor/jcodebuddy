package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Validator coverage for the {@code @View} contract (plan.dsflash § 6.3/1.12, § 4.5/G9).
 *
 * <p>The two tests this replaces asserted the opposite of the truth: they required the rule to
 * reject a valid bare {@code @View} and to accept the non-existent
 * {@code @View(read = …, write = …)} form. The rule and the generator must now agree on every
 * shape, so the matrix below covers all of them.</p>
 */
public class ViewAnnotationRuleTest {

    private static final String MARKER =
            "package hr.hrg.hipster.entity.person;\n"
            + "import hr.hrg.hipster.entity.api.EntityBase;\n"
            + "public interface PersonEntity extends EntityBase<Long> {}\n";

    private List<EntityRulesValidator.ValidationIssue> validate(String viewSource) throws Exception {
        Path tempDir = Files.createTempDirectory("view-annotation-rule");
        Files.writeString(tempDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(tempDir.resolve("PersonSummary.java"), viewSource);
        return new EntityRulesValidator().validate(tempDir);
    }

    private static String view(String annotation, String body) {
        return "package hr.hrg.hipster.entity.person;\n"
                + "import hr.hrg.hipster.entity.api.View;\n"
                + "import hr.hrg.hipster.entity.api.GenLevel;\n"
                + annotation + "\n"
                + "public interface PersonSummary extends PersonEntity {\n" + body + "}\n";
    }

    @Test
    public void bareMarkerViewIsValid() throws Exception {
        List<EntityRulesValidator.ValidationIssue> issues = validate(view("@View", "  String firstName();\n"));
        Assertions.assertTrue(issues.isEmpty(), "a bare @View is valid; got " + issues);
    }

    @Test
    public void emptyParensViewIsValid() throws Exception {
        List<EntityRulesValidator.ValidationIssue> issues = validate(view("@View()", "  String firstName();\n"));
        Assertions.assertTrue(issues.isEmpty(), "an empty @View() is valid; got " + issues);
    }

    @Test
    public void simpleGenSpellingIsValid() throws Exception {
        List<EntityRulesValidator.ValidationIssue> issues =
                validate(view("@View(gen = GenLevel.META)", "  String firstName();\n"));
        Assertions.assertTrue(issues.isEmpty(), "the simple gen spelling is valid; got " + issues);
    }

    @Test
    public void fullyQualifiedGenSpellingIsValid() throws Exception {
        List<EntityRulesValidator.ValidationIssue> issues = validate(
                view("@View(gen = hr.hrg.hipster.entity.api.GenLevel.META)", "  String firstName();\n"));
        Assertions.assertTrue(issues.isEmpty(), "the fully-qualified gen spelling is valid; got " + issues);
    }

    @Test
    public void unknownGenConstantIsReported() throws Exception {
        List<EntityRulesValidator.ValidationIssue> issues =
                validate(view("@View(gen = GenLevel.NOT_A_LEVEL)", "  String firstName();\n"));
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("unknown_gen_level")),
                "an unknown GenLevel constant must be reported; got " + issues);
    }

    @Test
    public void retiredSingleMemberFormIsReported() throws Exception {
        // @View(true) cannot come from compiling source (the annotation has no value() member); it
        // survives only in the old parser's comments, so it is parsed but flagged.
        List<EntityRulesValidator.ValidationIssue> issues = validate(view("@View(true)", "  String firstName();\n"));
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("unsupported_view_annotation_form")),
                "the retired @View(true) form must be reported; got " + issues);
    }

    @Test
    public void recordLevelMatchesNestedRecordShape() throws Exception {
        String body = "  Long id();\n"
                + "  String firstName();\n"
                + "  record Record(Long id, String firstName) implements PersonSummary {}\n";
        // A nested record's component list must match the field enum order for DEFAULT to resolve
        // to RECORD; here the level is explicit, so the shape check is not consulted.
        List<EntityRulesValidator.ValidationIssue> issues = validate(view("@View(gen = GenLevel.RECORD)", body));
        Assertions.assertTrue(issues.isEmpty(), "an explicit RECORD level is valid; got " + issues);
    }

    @Test
    public void builderLevelNeedsAtLeastOneAccessor() throws Exception {
        List<EntityRulesValidator.ValidationIssue> issues =
                validate(view("@View(gen = GenLevel.BUILDER)", "  // no accessors\n"));
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("requires at least one accessor")),
                "a builder level with no accessor must be reported; got " + issues);
    }

    @Test
    public void addonsOnTheMarkerIsReported() throws Exception {
        Path tempDir = Files.createTempDirectory("view-annotation-rule-marker");
        Files.writeString(tempDir.resolve("Person.java"),
                "package hr.hrg.hipster.entity.person;\n"
                + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                + "import hr.hrg.hipster.entity.api.View;\n"
                + "@View(addons = {PersonAuditable.class})\n"
                + "public interface Person extends EntityBase<Long> {}\n");
        Files.writeString(tempDir.resolve("PersonAuditable.java"),
                "package hr.hrg.hipster.entity.person;\n"
                + "public interface PersonAuditable {}\n");
        List<EntityRulesValidator.ValidationIssue> issues = new EntityRulesValidator().validate(tempDir);
        Assertions.assertTrue(issues.stream().anyMatch(i -> i.message.contains("addon_on_non_view")),
                "addons on a non-view interface must be reported; got " + issues);
    }
}
