package hr.hrg.jcodebuddy.engine.fresh;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.TypeFacts;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The freshness contract (plan step 3.0g): the three transitions that matter, plus the subscription that makes
 * it a contract rather than a cache.
 *
 * <p>The case worth reading first is the second test. A row depends on the file that declares it <em>and on the
 * types it names</em>, so editing {@code Person.java} stales {@code Employee}'s row although
 * {@code Employee.java} was never touched — and the fixture writes the relation the commonest way
 * ({@code implements Person}, a simple name), so a dependent search that only matched FQNs would miss it
 * entirely.</p>
 */
class FreshnessTest {

    private static ClassIndex indexOf(Path tree) {
        ClassIndex index = ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        index.addTypes("a/b/Base.java", List.of(new TypeFacts("a.b.Base", "interface", List.of("public"),
                null, 3, 0, List.of())), false);
        index.addTypes("a/b/Person.java", List.of(new TypeFacts("a.b.Person", "interface", List.of("public"),
                null, 3, 0, List.of(TypeRelation.extendsType("a.b.Base")))), false);
        // Written the commonest way on purpose: the relation names a simple name, not an FQN.
        index.addTypes("a/b/Employee.java", List.of(new TypeFacts("a.b.Employee", "class", List.of("public"),
                null, 3, 0, List.of(TypeRelation.implementsType("Person")))), false);
        return index;
    }

    @Test
    void anEditInvalidatesItsOwnRow(@TempDir Path tree) {
        Freshness freshness = Freshness.forIndex(indexOf(tree));

        Assertions.assertTrue(freshness.stateOf("a.b.Person").isSafe(), "nothing has been reported yet");

        FreshnessEvent event = freshness.report(ClassIndex.ChangeKind.CONTENT, "a/b/Person.java");

        Assertions.assertEquals(List.of("a.b.Person"), event.rows());
        Assertions.assertEquals(Freshness.Answer.STALE, freshness.stateOf("a.b.Person").answer(),
                "the row is from the last pass and its file changed");
        Assertions.assertFalse(freshness.stateOf("a.b.Person").isSafe());
    }

    @Test
    void anEditThatTouchesARelationInvalidatesTheDependentRows(@TempDir Path tree) {
        Freshness freshness = Freshness.forIndex(indexOf(tree));

        FreshnessEvent event = freshness.report(ClassIndex.ChangeKind.CONTENT, "a/b/Person.java");

        Assertions.assertEquals(List.of("a.b.Employee"), event.dependents(),
                "Employee names Person, so its row is stale although Employee.java was not touched");
        Assertions.assertEquals(Freshness.Answer.STALE, freshness.stateOf("a.b.Employee").answer());
        Assertions.assertTrue(freshness.stateOf("a.b.Employee").cause().contains("a type it names changed"),
                "and the reason names the dependency: " + freshness.stateOf("a.b.Employee").cause());
        Assertions.assertTrue(event.describe().contains("dependents=[a.b.Employee]"),
                "the event carries enough to act on: " + event.describe());
    }

    @Test
    void aDeletedFileMarksWhatNamedItUnresolved(@TempDir Path tree) {
        Freshness freshness = Freshness.forIndex(indexOf(tree));

        FreshnessEvent event = freshness.report(ClassIndex.ChangeKind.REMOVED, "a/b/Person.java");

        Assertions.assertEquals(List.of("a.b.Person"), event.rows());
        Assertions.assertEquals(List.of("a.b.Employee"), event.dependents());
        Assertions.assertTrue(event.unresolved().contains("Person"),
                "the row that named it now points at a name nothing declares, and that is reported rather than"
                        + " dropped as 'no supertype': " + event.describe());
        Assertions.assertTrue(freshness.stateOf("a.b.Person").cause().contains("removed"));
    }

    @Test
    void aSubscriberActuallyReceivesTheEventAndCanStop(@TempDir Path tree) throws Exception {
        Freshness freshness = Freshness.forIndex(indexOf(tree));
        List<FreshnessEvent> received = new ArrayList<>();

        try (AutoCloseable subscription = freshness.subscribe(received::add)) {
            FreshnessEvent event = freshness.report(ClassIndex.ChangeKind.CONTENT, "a/b/Person.java");
            Assertions.assertEquals(List.of(event), received,
                    "a freshness contract nobody can observe is a cache, so this is the subscription's test");
        }

        freshness.report(ClassIndex.ChangeKind.CONTENT, "a/b/Base.java");
        Assertions.assertEquals(1, received.size(), "a closed subscription stops receiving events");
    }

    @Test
    void aFileTheIndexHasNoRowForIsReportedRatherThanIgnored(@TempDir Path tree) {
        Freshness freshness = Freshness.forIndex(indexOf(tree));
        AtomicInteger events = new AtomicInteger();
        freshness.subscribe(event -> events.incrementAndGet());

        FreshnessEvent event = freshness.report(ClassIndex.ChangeKind.CONTENT, "a/b/Ghost.java");

        Assertions.assertFalse(event.invalidatesAnything());
        Assertions.assertTrue(event.cause().contains("no row for this path"),
                "it says why it can say nothing, so a consumer cannot read silence as safety: " + event.cause());
        Assertions.assertEquals(1, events.get(), "and it is still delivered: a change nobody hears about is a"
                + " change nobody can react to");
    }

    @Test
    void aNameTheEngineNeverSawIsNotSafeToRead(@TempDir Path tree) {
        Freshness freshness = Freshness.forIndex(indexOf(tree));

        Assertions.assertEquals(Freshness.Answer.UNKNOWN, freshness.stateOf("a.b.Ghost").answer(),
                "UNKNOWN is kept apart from SAFE: a name the engine never saw is not a name it can vouch for");
        Assertions.assertTrue(freshness.staleRows().isEmpty(), "nothing is stale before anything is reported");

        freshness.report(ClassIndex.ChangeKind.CONTENT, "a/b/Person.java");
        Assertions.assertEquals(List.of("a.b.Person", "a.b.Employee"), List.copyOf(freshness.staleRows()),
                "and after the report it is the changed row first, then the row that depends on it");
    }
}
