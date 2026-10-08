// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import hr.hrg.hipster.entity.tooling.validation.EntityRule;
import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;

/**
 * Contract versus convention, per rule — plan step 6.3, the maintainer's decision of 2026-10-08: enforce *"the core
 * entity contract"*, leave *"the view hierarchy naming rule"* advisory.
 *
 * <p>Two things are worth pinning, and they are different: <b>which</b> rules are contracts (a decision, so it is
 * asserted rather than left to a reader), and <b>what a policy does with them</b> (behaviour, so it is exercised: a
 * contract violation must fail a strict pass, and a convention violation must not fail it in any policy).</p>
 */
class EntityRulesNatureTest {

    @TempDir
    Path tempDir;

    /** The decision, named per rule — moving one of these lines is how the decision changes. */
    @Test
    void everyRuleDeclaresWhetherItIsAContract() {
        var validator = new EntityRulesValidator();
        Assertions.assertEquals(5, validator.rules().size(), "the registry runs five rules");

        Assertions.assertEquals(EntityRule.Nature.CONTRACT, natureOf(validator, "MarkerEntityRule"),
                "the core entity contract: a marker that declares domain methods is not a marker");
        Assertions.assertEquals(EntityRule.Nature.CONTRACT, natureOf(validator, "EntityFieldEnumOrderRule"),
                "the R1 append-only ledger: reordering a field enum breaks ordinals stored data already carries");
        Assertions.assertEquals(EntityRule.Nature.CONTRACT, natureOf(validator, "ViewAnnotationRule"),
                "`@View` misapplied, or an addon that does not exist, makes the model wrong");

        Assertions.assertEquals(EntityRule.Nature.CONVENTION, natureOf(validator, "ViewInterfaceRule"),
                "the view hierarchy naming rule, which the maintainer left advisory");
        Assertions.assertEquals(EntityRule.Nature.CONVENTION, natureOf(validator, "AuditableRule"),
                "the Auditable package layout is a convention a project may choose differently");
    }

    private static EntityRule.Nature natureOf(EntityRulesValidator validator, String simpleName) {
        return validator.rules().stream()
                .filter(rule -> rule.getClass().getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rule named " + simpleName + " in the registry"))
                .nature();
    }

    /** A marker that declares a domain method — the contract violation the core rule exists to catch. */
    @Test
    void aContractViolationFailsAStrictPassBeforeWritingAnything(@TempDir Path root) throws Exception {
        // The shape `EntityRulesValidatorTest` proves triggers the rule: the repository's own entity package and
        // naming, an `EntityBase` marker, and one domain method on it.
        write(root, "PersonEntity.java", """
                package hr.hrg.hipster.entity.person;
                import hr.hrg.hipster.entity.api.EntityBase;
                public interface PersonEntity extends EntityBase<String> {
                    String name();
                }
                """);

        List<EntityRulesValidator.ValidationIssue> issues = new EntityRulesValidator().validate(root);
        Path output = root.resolve("out");
        Files.createDirectories(output);

        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.STRICT);
        try {
            Assertions.assertThrows(EntityMetadataGenerator.ValidationFailedException.class,
                    () -> EntityMetadataGenerator.generate(root, output, output),
                    "STRICT must fail on a contract violation: " + issues);
            try (var written = Files.list(output)) {
                Assertions.assertEquals(0, written.count(),
                        "and it must fail BEFORE writing any file");
            }
        } finally {
            EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
        }
    }

    /**
     * A tree whose only reported issue comes from a CONVENTION rule — the naming/layout advice — must be reported and
     * must <b>not</b> fail a strict pass. This is the half of the decision that keeps the framework out of a project's
     * style, so it is the half worth a test.
     */
    @Test
    void aConventionViolationIsReportedAndNeverFatal(@TempDir Path root) throws Exception {
        // An Auditable interface outside an entity module/package: AuditableRule's CONVENTION issue, and nothing else.
        write(root, "Thing.java", """
                package outside.notentity;
                public interface ThingAuditable {}
                """);

        List<EntityRulesValidator.ValidationIssue> issues = new EntityRulesValidator().validate(root);
        Assertions.assertFalse(issues.isEmpty(), "the fixture must actually produce an issue");
        Assertions.assertTrue(issues.stream().allMatch(issue -> issue.nature == EntityRule.Nature.CONVENTION),
                "and every one of them must come from a CONVENTION rule: " + issues);

        Path output = root.resolve("out");
        Files.createDirectories(output);
        EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.STRICT);
        try {
            Assertions.assertDoesNotThrow(() -> EntityMetadataGenerator.generate(root, output, output),
                    "STRICT must not fail on convention advice: " + issues);
        } finally {
            EntityMetadataGenerator.setValidationPolicy(EntityMetadataGenerator.Policy.OFF);
        }
    }

    private static void write(Path root, String name, String content) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
