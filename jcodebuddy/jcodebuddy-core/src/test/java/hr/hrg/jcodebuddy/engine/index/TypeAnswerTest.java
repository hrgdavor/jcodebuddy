package hr.hrg.jcodebuddy.engine.index;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * The engine's answer contract (plan step 3.0f-3): a fact, or a reported inability.
 *
 * <p>What this pins is a distinction, not a feature. {@link ClassIndex#row(String)} returns {@code null} for a
 * JDK type, for another module's type and for a name the pass has not reached, exactly as it does for a
 * misspelling — and reading that as "no such type" is how the hipster-ioc prototype emitted empty wiring in
 * step 3.2. {@link ClassIndex#answer(String)} has to keep those apart in the answer itself, so no consumer
 * can collapse them by accident: the cause is part of the value, and it is phrased as what the index covers
 * rather than as what does not exist.</p>
 */
class TypeAnswerTest {

    private static ClassIndex indexOf(Path tree) {
        ClassIndex index = ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        index.addTypes("a/b/Person.java",
                List.of(new TypeFacts("a.b.Person", "interface", List.of("public"), null, 3, 0, List.of())), false);
        return index;
    }

    @Test
    void anIndexedTypeAnswersWithItsRow(@TempDir Path tree) {
        TypeAnswer answer = indexOf(tree).answer("a.b.Person");

        Assertions.assertTrue(answer.isFound(), "the index holds this row, so it is a fact: " + answer.describe());
        Assertions.assertEquals("a.b.Person", answer.type().fqn());
        Assertions.assertEquals("interface", answer.type().kind());
        Assertions.assertEquals("a/b/Person.java", answer.type().path());
        Assertions.assertEquals(3, answer.type().line());
        Assertions.assertTrue(answer.describe().contains("a/b/Person.java:3"),
                "and the description names where it is, for a diagnostic: " + answer.describe());
    }

    @Test
    void aTypeTheIndexCannotCoverIsNotAnAbsence(@TempDir Path tree) {
        TypeAnswer answer = indexOf(tree).answer("java.util.List");

        Assertions.assertFalse(answer.isFound(), "a JDK type is outside a module index");
        String described = answer.describe();
        Assertions.assertTrue(described.contains("cannot answer for java.util.List"),
                "the answer says it cannot answer rather than that the type is absent: " + described);
        Assertions.assertTrue(described.contains("not saying it does not exist"),
                "and it says what 'cannot answer' means, because the next reader will otherwise read it as"
                        + " an absence: " + described);
        Assertions.assertTrue(described.contains("module"),
                "and which module's index could not answer: " + described);
    }

    @Test
    void askingForTheRowStillWorksForTheIndexsOwnUse(@TempDir Path tree) {
        ClassIndex index = indexOf(tree);

        Assertions.assertNotNull(index.row("a.b.Person"), "the pass keeps using row() internally");
        Assertions.assertNull(index.row("java.util.List"),
                "and null is exactly why a consumer should ask `answer` instead: it cannot tell the two cases"
                        + " apart, which is what step 3.0f-3 recorded");
    }

    @Test
    void typeThrowsRatherThanHandingBackAnAbsence(@TempDir Path tree) {
        TypeAnswer answer = indexOf(tree).answer("java.util.List");

        Assertions.assertThrows(IllegalStateException.class, answer::type,
                "a caller that forgot to check isFound() gets a loud failure, not a null it might treat as"
                        + " 'no such type'");
    }
}
