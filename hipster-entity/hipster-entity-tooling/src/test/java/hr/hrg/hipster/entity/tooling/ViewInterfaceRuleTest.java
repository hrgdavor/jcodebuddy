package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.validation.EntityRulesValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link hr.hrg.hipster.entity.tooling.validation.ViewInterfaceRule} — rewritten so that it describes
 * the project's actual view conventions instead of one convention it assumed.
 *
 * <p>The old rule required a view's <em>directly</em> extended type to be literally named
 * {@code *Entity}, checking each file alone. Run against the committed example it produced 19 issues
 * about 19 correct interfaces — which is the evidence that task 1.13 ("wire the validator into the
 * generator") had not been done, and the reason the rewrite is tested rather than trusted.</p>
 *
 * <p>The cases below are the two accepted conventions, the `@View`-seeded case, and the shapes that
 * must still be reported.</p>
 */
public class ViewInterfaceRuleTest {

    private static Path tree(String... nameToSource) throws Exception {
        Path tempDir = Files.createTempDirectory("view-rule");
        for (int i = 0; i < nameToSource.length; i += 2) {
            Files.writeString(tempDir.resolve(nameToSource[i]), nameToSource[i + 1]);
        }
        return tempDir;
    }

    private static List<EntityRulesValidator.ValidationIssue> validate(Path tree) throws Exception {
        return new EntityRulesValidator().validate(tree);
    }

    private static boolean hasMessage(List<EntityRulesValidator.ValidationIssue> issues, String needle) {
        return issues.stream().anyMatch(i -> i.message.contains(needle));
    }

    /**
     * The example module's convention: a marker without the {@code *Entity} suffix, and a view named
     * with one of the project's suffixes. This is `person/entity` in miniature, and it must be clean.
     *
     * <p>Note what is <em>not</em> asserted here: that a model interface the views extend is a view
     * itself. The naming rule is about views; a shared model interface that nothing extends is a
     * marker, which is why `PersonEntity` alone produces no finding and why the example's `Person`
     * does not either.</p>
     */
    @Test
    public void theExampleConventionIsClean() throws Exception {
        Path dir = tree(
                "Person.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface Person extends EntityBase<Long> { }\n",
                "PersonSummary.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "public interface PersonSummary extends Person { String firstName(); }\n",
                "PersonAuditable.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "public interface PersonAuditable extends Person { String createdAt(); }\n");

        List<EntityRulesValidator.ValidationIssue> issues = validate(dir);
        Assertions.assertEquals(List.of(), issues,
                "a `Person` marker plus `*Summary`/`*Details` views is the shipped shape and must be "
                        + "clean; got " + issues);
    }

    /**
     * A view is <em>accepted</em> by its annotation wherever it sits in the hierarchy. The rule no
     * longer enforces naming at all (six correct example views showed why — see the rule's javadoc),
     * and it says so here so the next reader does not re-add it without reading that account.
     *
     * <p>The fixture uses {@code PersonSummaryEntity} on purpose: it is the spelling that a
     * suffix-only check would have accepted and a naive check would have rejected, so keeping it green
     * pins that neither mistake is back.</p>
     */
    @Test
    public void anAnnotatedViewIsAcceptedWhereverItSitsInTheHierarchy() throws Exception {
        Path dir = tree(
                "PersonEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface PersonEntity extends EntityBase<String> { }\n",
                "PersonSummaryEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface PersonSummaryEntity extends PersonEntity { String firstName(); }\n",
                "PersonDisplay.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface PersonDisplay extends PersonEntity { String firstName(); }\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "naming is not enforced by this rule: both spellings are views the generator handles");
    }

    /** And the same view with the marker's suffix is accepted. */
    @Test
    public void theEntitySuffixSpellingOfAViewNameIsAccepted() throws Exception {
        Path dir = tree(
                "PersonEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface PersonEntity extends EntityBase<String> { }\n",
                "PersonSummaryEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface PersonSummaryEntity extends PersonEntity { String firstName(); }\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "`PersonSummaryEntity` is `PersonSummary` with the marker's suffix, which the "
                        + "convention allows");
    }

    /**
     * The other half of the same rule, and the reason it is worth stating separately: a plain
     * {@code *Entity} marker extends {@code EntityBase} and has no {@code @View}, so it is not a view
     * and its name is not checked. Without this, every conventional marker would be reported — which is
     * what made the second rewrite of this rule report correct trees.
     */
    @Test
    public void aMarkerIsNotANamingFinding() throws Exception {
        Path dir = tree(
                "PersonEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface PersonEntity extends EntityBase<String> { }\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "a marker named `*Entity` is the convention, not a finding");
    }

    /**
     * G8 rule 0's other half: a view seeded by {@code @View} alone, deriving from nothing. The
     * generator emits an enum for it (that is why `PersonCreateForm_` exists), so it is a view and the
     * naming rule applies — but a form-suffixed name satisfies it.
     */
    @Test
    public void aViewSeededByTheAnnotationAloneIsAccepted() throws Exception {
        Path dir = tree(
                "PersonCreateForm.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface PersonCreateForm { String firstName(); }\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "a `@View`-seeded view with a form suffix is what the example ships");
    }

    /**
     * The same seed with a name outside the convention is <strong>accepted</strong>: naming is not
     * enforced by this rule. Six correct example views showed why a suffix rule needs the generator's
     * own discovery result to be sound, and the rule's javadoc carries that account.
     */
    @Test
    public void aViewSeededByTheAnnotationWithAnUnconventionalNameIsAccepted() throws Exception {
        Path dir = tree(
                "PersonDisplay.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface PersonDisplay { String firstName(); }\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "`@View` makes it a view, and this rule does not judge its name");
    }

    /** And the same for a marker-derived view: the annotation is what makes it checkable, and it is. */
    @Test
    public void anAnnotatedViewOverAMarkerIsAcceptedWhateverItsName() throws Exception {
        Path dir = tree(
                "PersonEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface PersonEntity extends EntityBase<String> { }\n",
                "PersonDisplay.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.View;\n"
                        + "@View\n"
                        + "public interface PersonDisplay extends PersonEntity { String firstName(); }\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "an annotated view is valid; the generator handles it and this rule has nothing to add");
    }

    /**
     * The old rule's regression test, kept: a view-shaped interface that reaches no marker is
     * reported. The fixture needed the marker in the same tree — the rule is tree-wide now, so a view
     * with an unresolvable parent is judged by what the source set actually contains.
     */
    @Test
    public void shouldRejectViewNotExtendingEntity() throws Exception {
        Path dir = tree(
                "PersonEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface PersonEntity extends EntityBase<String> { }\n",
                "OrderSummary.java", "package hr.hrg.hipster.entity.person;\n"
                        + "public interface OrderSummary { String firstName(); }\n");

        Assertions.assertTrue(hasMessage(validate(dir), "view_does_not_derive_from_marker"),
                "an interface named like a view but reachable from no marker is a finding: "
                        + validate(dir));
    }

    /** A view whose parent exists but leads nowhere is still a finding. */
    @Test
    public void aViewWhoseChainNeverReachesAMarkerIsReported() throws Exception {
        Path dir = tree(
                "PersonEntity.java", "package hr.hrg.hipster.entity.person;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface PersonEntity extends EntityBase<String> { }\n",
                "PersonSummary.java", "package hr.hrg.hipster.entity.person;\n"
                        + "public interface PersonSummary extends SomethingElse { String firstName(); }\n");

        Assertions.assertTrue(hasMessage(validate(dir), "view_does_not_derive_from_marker"),
                "an unresolvable parent chain is the shape of a typo or a missing file");
    }

    /**
     * The framework surfaces are never views (G8 rule 1). `Write` extends the view and `ViewWriter`,
     * and the generator deliberately emits nothing for it — so the naming rule must not fire on it
     * either, or every generated view would carry one false finding.
     */
    @Test
    public void aWriteSurfaceIsNotAView() throws Exception {
        Path dir = tree(
                "Person.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "import hr.hrg.hipster.entity.api.EntityBase;\n"
                        + "public interface Person extends EntityBase<Long> { }\n",
                "PersonSummary.java", "package hr.hrg.hipster.entityexample.person.entity;\n"
                        + "import hr.hrg.hipster.entity.api.ViewWriter;\n"
                        + "public interface PersonSummary extends Person {\n"
                        + "  interface Write extends PersonSummary, ViewWriter { }\n"
                        + "  String firstName();\n"
                        + "}\n");

        Assertions.assertEquals(List.of(), validate(dir),
                "a nested `Write` is a framework surface, not a view, and is never a naming finding");
    }
}
