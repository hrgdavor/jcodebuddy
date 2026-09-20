package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The view-discovery fixture of plan.dsflash § 4.5/G8 (rule 0 and rules 1–2) and § 4.5/G9's shape
 * matrix, plus the real-tree census that § 9/4.1's file arithmetic depends on.
 *
 * <p>Discovery is <strong>marker-derivation <em>or</em> a {@code @View} annotation</strong>, minus the
 * package marker, minus the framework surfaces, and it never descends into a nested type of a view
 * file. Each of those clauses is asserted here because dropping any one of them silently changes the
 * generated file list — the failure mode the plan records as having produced the orphan
 * {@code Write_.java} and the missing {@code PersonCreateForm_}.</p>
 */
class ViewDiscoveryTest {

    /** The fixture package marker. */
    private static final String MARKER = """
            package disc.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /** A marker-derived view: no annotation at all, discovered by predicate (a). */
    private static final String DERIVED_VIEW = """
            package disc.hr;
            public interface DerivedView extends PersonEntity {
                String name();
            }
            """;

    /** A view that derives from nothing: discovered ONLY by predicate (b), the @View seed. */
    private static final String ANNOTATED_ONLY_VIEW = """
            package disc.hr;
            import hr.hrg.hipster.entity.api.View;
            @View
            public interface AnnotatedOnlyView extends PersonEntity {
                String name();
            }
            """;

    /** A write surface: derives from the marker but is excluded by rule 1. */
    private static final String WRITE_SURFACE = """
            package disc.hr;
            import hr.hrg.hipster.entity.api.ViewWriter;
            public interface Surface extends PersonEntity, ViewWriter {
                String name();
            }
            """;

    /** A view that nests its own Write surface, to prove rule 2 (no descent into nested types). */
    private static final String VIEW_WITH_NESTED_WRITE = """
            package disc.hr;
            import hr.hrg.hipster.entity.api.ViewWriter;
            public interface NestedHolder extends PersonEntity {
                String name();
                interface Write extends NestedHolder, ViewWriter {
                    Write name(String value);
                }
            }
            """;

    private record Generated(Path outputRoot, List<String> emittedNames) {
    }

    private Generated generate(String... sources) throws Exception {
        Path sourceRoot = Files.createTempDirectory("discovery-source");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        for (int i = 0; i < sources.length; i++) {
            Files.writeString(sourceRoot.resolve("View" + i + ".java"), sources[i]);
        }
        Path outputRoot = Files.createTempDirectory("discovery-output");
        EntityMetadataGenerator.generate(sourceRoot, outputRoot);

        List<String> names = CompileHarness.javaSourcesUnder(outputRoot).stream()
                .map(p -> p.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
        return new Generated(outputRoot, names);
    }

    @Test
    void aMarkerDerivedViewIsDiscovered() throws Exception {
        Assertions.assertTrue(generate(DERIVED_VIEW).emittedNames().contains("DerivedView_.java"),
                "predicate (a): deriving from the package marker makes an interface a view");
    }

    @Test
    void anAnnotatedButNotMarkerDerivedViewIsDiscovered() throws Exception {
        // This is the @View seed of task 3.2a. PersonCreateForm in the real tree is the live case,
        // and without this predicate the `_` enum the plan expects is never emitted.
        Assertions.assertTrue(generate(ANNOTATED_ONLY_VIEW).emittedNames().contains("AnnotatedOnlyView_.java"),
                "predicate (b): an @View annotation alone makes an interface a view");
    }

    @Test
    void aWriteSurfaceIsNotAView() throws Exception {
        Generated generated = generate(WRITE_SURFACE);

        Assertions.assertFalse(generated.emittedNames().contains("Surface_.java"),
                "rule 1: an interface extending ViewWriter is a write surface, not a view; got "
                        + generated.emittedNames());
    }

    @Test
    void aNestedWriteSurfaceIsNotRediscovered() throws Exception {
        Generated generated = generate(VIEW_WITH_NESTED_WRITE);

        Assertions.assertTrue(generated.emittedNames().contains("NestedHolder_.java"),
                "the enclosing view is discovered normally");
        Assertions.assertFalse(generated.emittedNames().contains("Write_.java"),
                "rule 2: discovery never descends into a nested type of a view file — otherwise "
                        + "regeneration would re-emit Write inside Write and collide on the "
                        + "simple-name-derived Write_.java; got " + generated.emittedNames());
    }

    @Test
    void everyDiscoveredViewIsEmittedExactlyOnce() throws Exception {
        // A view can reach two markers (the example's PersonAuditable extends Person and
        // Auditable<Long>), which must not produce two writes of the same `_` enum.
        Generated generated = generate(DERIVED_VIEW, ANNOTATED_ONLY_VIEW);

        long derivedCount = generated.emittedNames().stream().filter("DerivedView_.java"::equals).count();
        Assertions.assertEquals(1, derivedCount, "one emission per view, even with two markers");
        Assertions.assertEquals(1,
                generated.emittedNames().stream().filter("AnnotatedOnlyView_.java"::equals).count());
    }

    @Test
    void regenerationIsAFixedPoint() throws Exception {
        // Second pass over the emitted tree must not discover the generator's own output as a new
        // view. The output is written into the SAME source root here, which is the real S2
        // situation (generated source is committed into src/main/java).
        Path sourceRoot = Files.createTempDirectory("discovery-fixedpoint");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("Holder.java"), VIEW_WITH_NESTED_WRITE);

        EntityMetadataGenerator.generate(sourceRoot, sourceRoot);
        List<String> first = CompileHarness.javaSourcesUnder(sourceRoot).stream()
                .map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());

        EntityMetadataGenerator.generate(sourceRoot, sourceRoot);
        List<String> second = CompileHarness.javaSourcesUnder(sourceRoot).stream()
                .map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());

        Assertions.assertEquals(first, second,
                "regeneration must be a fixed point: the second pass may not treat the first pass's "
                        + "output as new views; first=" + first + " second=" + second);
    }

    /**
     * The real-tree census § 9/4.1's file list is derived from: {@code person.entity} must yield the
     * seven views, of which five carry {@code @View}, and {@code PersonSummary.Write} must be
     * excluded as a framework surface.
     */
    @Test
    void realTreePersonEntityCensus() throws Exception {
        Path repoRoot = CompileHarness.findRepoRoot();
        Path exampleSources = repoRoot.resolve("hipster-entity-example/src/main/java");
        Assertions.assertTrue(Files.exists(exampleSources), "the example source root must exist");

        Path outputRoot = Files.createTempDirectory("discovery-realtree");
        EntityMetadataGenerator.setGenerationPackages(
                List.of("hr.hrg.hipster.entityexample.person.entity"));
        try {
            EntityMetadataGenerator.generate(exampleSources, outputRoot);
        } finally {
            EntityMetadataGenerator.setGenerationPackages(List.of());
        }

        Path packageDir = outputRoot.resolve("hr/hrg/hipster/entityexample/person/entity");
        Set<String> emitted = CompileHarness.javaSourcesUnder(packageDir).stream()
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith("_.java"))
                .collect(Collectors.toSet());

        // The seven views of § 9/4.1, of which five carry @View. PersonCreateForm appears BECAUSE of
        // the @View seed (predicate b) — the case a marker-only predicate silently drops.
        Assertions.assertTrue(emitted.containsAll(Set.of(
                        "PersonAuditable_.java", "PersonCreateForm_.java", "PersonDetails_.java",
                        "PersonDto_.java", "PersonSummary_.java", "PersonUpdatableView_.java",
                        "PersonUpdateForm_.java")),
                "person.entity must yield all seven views; got " + emitted);

        // The marker gets no enum, and the framework surface is excluded (G8 rules 0 and 1).
        Assertions.assertFalse(emitted.contains("Person_.java"),
                "the package marker is not a view");
        Assertions.assertFalse(emitted.contains("Write_.java"),
                "PersonSummary.Write is a framework surface and is deleted, not regenerated");
    }
}
