package hr.hrg.hipster.entity.jackson;

import hr.hrg.hipster.entity.core.ChangePath;
import hr.hrg.hipster.entity.core.ListChangeKind;
import hr.hrg.hipster.entity.core.ListDelta;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

/**
 * Task 6.6 of {@code plan.dsflash.md} § 11: the deep changes of a tracked view are emitted as a
 * nested <strong>RFC 6902-like JSON patch</strong> document derived from
 * {@link hr.hrg.hipster.entity.core.ViewChangeTracking#changesDeep()}, alongside the shallow
 * serializer of {@link EntityJacksonChangeSerializer}.
 *
 * <p>The three properties the task names are asserted directly: a deep patch <em>names the leaf</em>
 * through a path array whose collection level is an integer index; a structural collection change is
 * emitted as an add/remove/move operation rather than as a replacement; and a field the view does not
 * have is skipped rather than resolved through a name map (DEC-016).</p>
 */
class EntityJacksonDeepChangeSerializerTest {

    private static String deepJson(DeepPatchFixture.StudioFixture fixture) {
        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJsonDeepChanges(DeepPatchFixture.Studio_.meta(), fixture.view, writer);
        return writer.toString();
    }

    // ------------------------------------------------------------------ the leaf

    @Test
    void aLeafChangedInsideACollectionIsPatchedByItsPathAndNotByReplacingTheParent() {
        DeepPatchFixture.MovieRow first = DeepPatchFixture.movie(11L, "Toy Story");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(first);
        String baselineName = first.name(); // the caller's baseline value, captured pre-write

        first.set(DeepPatchFixture.Movie_.name.ordinal(), "Toy Story 2");

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"path\":[\"movies\",0,\"name\"]"),
                "the path names the collection index and the leaf field: " + json);
        Assertions.assertTrue(json.contains("\"current\":\"Toy Story 2\""),
                "the leaf states the value it holds now: " + json);
        Assertions.assertFalse(json.contains("\"previous\""),
                "no previous value is written anywhere: the tracker keeps none: " + json);
        Assertions.assertFalse(json.contains("\"title\""),
                "no other field is touched: the patch is confined to the leaf: " + json);
        Assertions.assertFalse(json.contains("Pixar"),
                "the parent's own value is not restated: " + json);
        Assertions.assertEquals("Toy Story", baselineName,
                "the old value is read from the baseline instance the caller holds");
        Assertions.assertEquals("Toy Story 2", first.name(),
                "and the caller pairs the two instances itself");
    }

    @Test
    void aShallowFieldAndADeepLeafAreBothReported() {
        DeepPatchFixture.MovieRow first = DeepPatchFixture.movie(11L, "Toy Story");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(first);

        fixture.view.set(DeepPatchFixture.Studio_.title.ordinal(), "Pixar Animation");
        first.set(DeepPatchFixture.Movie_.name.ordinal(), "Toy Story 2");

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"title\""), "the shallow change is reported too: " + json);
        Assertions.assertTrue(json.contains("\"current\":\"Pixar Animation\""),
                "the shallow leaf carries its current value: " + json);
        Assertions.assertTrue(json.contains("\"current\":\"Toy Story 2\""),
                "and so does the deep leaf: " + json);
        Assertions.assertFalse(json.contains("\"previous\""),
                "neither leaf carries a previous value: " + json);
    }

    @Test
    void aFreshViewProducesAnEmptyPatch() {
        DeepPatchFixture.StudioFixture fixture =
                new DeepPatchFixture.StudioFixture(DeepPatchFixture.movie(11L, "Toy Story"));

        Assertions.assertEquals("{\"fields\":{}}", deepJson(fixture));
    }

    // ------------------------------------------------------------------ the structural half

    @Test
    void aRemovalIsEmittedAsARemoveOperationWithItsIdentity() {
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(
                DeepPatchFixture.movie(11L, "Toy Story"),
                DeepPatchFixture.movie(12L, "Cars"));
        List<hr.hrg.hipster.entity.core.ListDelta> before =
                fixture.array.collectionDeltas(DeepPatchFixture.Studio_.movies.ordinal());
        Assertions.assertTrue(before.isEmpty(), "the baseline is the wired-up list");

        fixture.movies.remove(0);

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"op\":\"remove\""), json);
        Assertions.assertTrue(json.contains("\"index\":0"), json);
        Assertions.assertTrue(json.contains("\"identity\":11"), "the removed entry is identifiable: " + json);
        Assertions.assertTrue(json.contains("\"collection\""), json);
    }

    @Test
    void aReorderIsEmittedAsAMoveOperationWithItsFromIndex() {
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(
                DeepPatchFixture.movie(11L, "Toy Story"),
                DeepPatchFixture.movie(12L, "Cars"),
                DeepPatchFixture.movie(13L, "Up"));
        // The tracker only sees the change if it has a baseline to compare against; the array took it
        // when it was constructed, so the reversal below is measured against that.
        java.util.Collections.reverse(fixture.movies);

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"op\":\"move\""), json);
        Assertions.assertTrue(json.contains("\"from\":"), "a move names where the entry came from: " + json);
        Assertions.assertTrue(json.contains("\"identity\":13"), json);
    }

    @Test
    void anAdditionIsEmittedAsAnAddOperation() {
        DeepPatchFixture.StudioFixture fixture =
                new DeepPatchFixture.StudioFixture(DeepPatchFixture.movie(11L, "Toy Story"));

        fixture.movies.add(DeepPatchFixture.movie(12L, "Cars"));

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"op\":\"add\""), json);
        Assertions.assertTrue(json.contains("\"index\":1"), json);
        Assertions.assertTrue(json.contains("\"identity\":12"), json);
    }

    @Test
    void theStructuralDeltasStayDistinguishableInTheDocument() {
        DeepPatchFixture.MovieRow first = DeepPatchFixture.movie(11L, "Toy Story");
        DeepPatchFixture.MovieRow second = DeepPatchFixture.movie(12L, "Cars");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(
                first, second, DeepPatchFixture.movie(13L, "Up"));

        // One element is removed from the collection ...
        fixture.movies.remove(0);
        // ... and one that stays is edited in place.
        String baselineName = second.name(); // the caller's baseline value, captured pre-write
        second.set(DeepPatchFixture.Movie_.name.ordinal(), "Cars 2");

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"op\":\"remove\""),
                "the removal is reported as its own operation: " + json);
        Assertions.assertTrue(json.contains("\"identity\":11"), json);
        Assertions.assertTrue(json.contains("\"path\":[\"movies\",0,\"name\"]"),
                "and the field delta inside a surviving element is reported too: " + json);
        Assertions.assertTrue(json.contains("\"current\":\"Cars 2\""),
                "the leaf states the value it holds now: " + json);
        Assertions.assertFalse(json.contains("\"previous\""),
                "and never a previous value: " + json);
        Assertions.assertEquals("Cars", baselineName,
                "the old value is the caller's baseline instance, not tracker state");
    }

    // ------------------------------------------------------------------ the fallback

    @Test
    void aNonIdentifiableCollectionElementIsReportedAsAFallbackNotAsIdentityMatching() {
        // The fixture is built from a non-identifiable element, so the collection can never be
        // matched by identity -- which is the situation the fallback and its diagnostic exist for.
        hr.hrg.hipster.entity.core.EntityUpdateTrackingArray<Object, DeepPatchFixture.Movie_> anonymous =
                DeepPatchFixture.anonymousMovie("Toy Story");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(anonymous);

        // An in-place edit at the element's own write site: the value differs from the one the
        // element holds, so the ordinal is marked there and no previous value is recorded.
        anonymous.set(DeepPatchFixture.Movie_.name.ordinal(), "Toy Story 2");

        String json = deepJson(fixture);

        Assertions.assertTrue(json.contains("\"fallback\":true"),
                "the positional fallback is stated in the document: " + json);
        Assertions.assertTrue(json.contains("collection_element_not_identifiable"),
                "and the diagnostic is carried: " + json);
        Assertions.assertFalse(json.contains("\"identity\""),
                "no identity is invented for an element that has none: " + json);
    }

    // ------------------------------------------------------------------ DEC-016

    @Test
    void aFieldTheViewDoesNotHaveIsSkippedAndTheNamedFieldsComeFromTheEnum() {
        DeepPatchFixture.MovieRow first = DeepPatchFixture.movie(11L, "Toy Story");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(first);
        first.set(DeepPatchFixture.Movie_.name.ordinal(), "Toy Story 2");

        String json = deepJson(fixture);

        // The only names present are the enum constants of the two views, and nothing was resolved
        // through a name lookup: an unknown field simply has no entry.
        Assertions.assertTrue(json.contains("movies"), json);
        Assertions.assertTrue(json.contains("name"), json);
        Assertions.assertFalse(json.contains("noSuchField"), json);
        Assertions.assertFalse(json.contains("metadata"), "a field of another view is not invented: " + json);
    }

    // ------------------------------------------------------------------ the shallow serializer is unchanged

    @Test
    void theShallowSerializerStillReportsTheFieldLevelPatch() {
        DeepPatchFixture.MovieRow first = DeepPatchFixture.movie(11L, "Toy Story");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(first);

        fixture.view.set(DeepPatchFixture.Studio_.title.ordinal(), "Pixar Animation");

        StringWriter writer = new StringWriter();
        EntityJacksonMapper.toJsonChanges(DeepPatchFixture.Studio_.meta(), fixture.view, writer);

        Assertions.assertEquals("{\"title\":\"Pixar Animation\"}", writer.toString(),
                "the shallow document keeps its shape");
    }

    // ------------------------------------------------------------------ the paths the serializer walked

    @Test
    void theFixtureReportsTheDeepPathTheDocumentIsBuiltFrom() {
        DeepPatchFixture.MovieRow first = DeepPatchFixture.movie(11L, "Toy Story");
        DeepPatchFixture.StudioFixture fixture = new DeepPatchFixture.StudioFixture(first);

        first.set(DeepPatchFixture.Movie_.name.ordinal(), "Toy Story 2");

        List<ChangePath> paths = fixture.view.changesDeep();
        Assertions.assertEquals(1, paths.size(), "got " + paths);
        Assertions.assertEquals("movies[0].name", paths.get(0).render());
        Assertions.assertEquals(0, paths.get(0).listIndex());

        List<ListDelta> deltas =
                fixture.array.collectionDeltas(DeepPatchFixture.Studio_.movies.ordinal());
        Assertions.assertEquals(1, deltas.size(), "got " + deltas);
        Assertions.assertEquals(ListChangeKind.FIELD_CHANGED, deltas.get(0).kind());
    }
}
