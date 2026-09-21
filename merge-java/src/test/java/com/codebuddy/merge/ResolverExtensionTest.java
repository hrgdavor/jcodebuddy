// {@link com.codebuddy.merge.ResolverExtensionTest} Pins the documented pattern for adding a resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension pattern, executed as a test.
 *
 * <p>This is the worked example behind the instructions in
 * {@link ConflictResolver} and {@link ConflictResolvers}: a new resolver is a
 * subclass of {@link AbstractConflictResolver} plus one registry line, after
 * which it takes part in orchestration, fix-path reporting and history replay
 * with no change to any other class.
 *
 * <p>If these tests fail, the documented pattern is no longer true and the
 * documentation must be corrected.
 */
class ResolverExtensionTest {

    /**
     * A worked example, written exactly as a real contribution would be: claim
     * one conflict type, resolve only the shape you understand, and describe the
     * options for everything else.
     *
     * <p>It handles {@link ConflictType#METHOD_BODY_CHANGE} when both branches
     * only <em>added</em> statements; it declines anything that involved a
     * removal, because then the union would silently resurrect deleted code.
     */
    static final class AdditiveMethodBodyResolver extends AbstractConflictResolver {

        @Override
        public ConflictType supportedType() {
            return ConflictType.METHOD_BODY_CHANGE;
        }

        @Override
        protected ConflictResolution doResolve(Conflict conflict) {
            if (!isPurelyAdditive(conflict)) {
                return null;
            }
            return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                .resolvedCode(conflict.getBranch1Code() + "\n" + conflict.getBranch2Code())
                .explanation("Both branches only added statements, so the union is safe.")
                .build();
        }

        @Override
        protected List<FixPath> describeOptions(Conflict conflict) {
            return List.of(newFixPath(conflict)
                .description("Keep the statements added by both branches")
                .options("Keep both", "Keep branch 1", "Keep branch 2")
                .recommended("Keep both")
                .justification("Neither side removed anything, so the union is additive.")
                .impact("None - both sets of statements are preserved.")
                .build());
        }

        private static boolean isPurelyAdditive(Conflict conflict) {
            List<String> base = MethodBodyChangeConflictResolver.statementsIn(conflict.getBaseCode());
            List<String> branch1 = MethodBodyChangeConflictResolver.statementsIn(conflict.getBranch1Code());
            List<String> branch2 = MethodBodyChangeConflictResolver.statementsIn(conflict.getBranch2Code());
            if (base.isEmpty() || branch1.isEmpty() || branch2.isEmpty()) {
                return false;
            }
            return branch1.containsAll(base) && branch2.containsAll(base);
        }

        @Override
        protected boolean stickyByDefault() {
            // An additive union is deterministic, so it is worth remembering.
            return true;
        }
    }

    @TempDir
    Path tempDir;

    private MergeConflictResolver resolverWith(ConflictResolver... resolvers) {
        return new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(tempDir.resolve("feature"))
            .setResolvers(List.of(resolvers))
            .build();
    }

    @Test
    @DisplayName("a new resolver is routed to by the orchestrator once registered")
    void newResolverIsRoutedTo() {
        MergeConflictResolver resolver = resolverWith(new AdditiveMethodBodyResolver());

        assertSame(AdditiveMethodBodyResolver.class,
            ConflictResolvers.find(resolver.getResolvers(), ConflictType.METHOD_BODY_CHANGE)
                .orElseThrow().getClass(),
            "the orchestrator must find the registered resolver for its type");

        ConflictResolution resolution = resolver.resolve(
            ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "the custom resolver's decision must be the one applied");
        assertTrue(resolution.getExplanation().contains("only added"),
            "the custom explanation must survive: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("registering a resolver leaves other types untouched")
    void registrationDoesNotAffectOtherTypes() {
        MergeConflictResolver resolver = resolverWith(
            new AdditiveMethodBodyResolver(), new ImportConflictResolver());

        ConflictResolution imports = resolver.resolve(
            ConflictFixtures.sample(ConflictType.IMPORT_ADD));
        assertEquals(ConflictResolution.ResolutionKind.AUTO, imports.getKind(),
            "an unrelated type must keep resolving through its own resolver");

        // METHOD_BODY_CHANGE is now owned by the custom resolver, so the built-in
        // one is correctly displaced.
        assertEquals(AdditiveMethodBodyResolver.class,
            ConflictResolvers.find(resolver.getResolvers(), ConflictType.METHOD_BODY_CHANGE)
                .orElseThrow().getClass());
    }

    @Test
    @DisplayName("the base class derives supports() from supportedType()")
    void baseClassDerivesSupports() {
        ConflictResolver resolver = new AdditiveMethodBodyResolver();

        assertTrue(resolver.supports(ConflictType.METHOD_BODY_CHANGE));
        assertFalse(resolver.supports(ConflictType.IMPORT_ADD));
        assertFalse(resolver.supports(null));
        assertEquals("AdditiveMethodBody", resolver.name(),
            "the diagnostic name is derived from the class name");
    }

    @Test
    @DisplayName("the base class turns a declined conflict into the manual fallback")
    void baseClassProvidesFallback() {
        // A removal on one side: the custom resolver declines it.
        Conflict declined = new Conflict(ConflictType.METHOD_BODY_CHANGE, ConflictFixtures.FILE,
            "removal on one side",
            "int a = 1;\nint b = 2;",
            "int a = 1;",
            "int a = 1;\nint b = 2;\nint c = 3;");

        ConflictResolution resolution = new AdditiveMethodBodyResolver().resolve(declined);

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "declining must fall back to a human rather than guess");
        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode());
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "the fallback must still carry the resolver's options");
    }

    @Test
    @DisplayName("the base class survives a resolver that throws")
    void baseClassSurvivesThrowingResolver() {
        ConflictResolver throwing = new AbstractConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return ConflictType.IMPORT_ADD;
            }

            @Override
            protected ConflictResolution doResolve(Conflict conflict) {
                throw new IllegalStateException("boom");
            }

            @Override
            protected List<FixPath> describeOptions(Conflict conflict) {
                return List.of();
            }
        };

        ConflictResolution resolution = throwing.resolve(
            ConflictFixtures.sample(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "one broken resolver must not abort the whole merge");
        assertTrue(resolution.getExplanation().contains("could not resolve"),
            "the explanation must admit the failure: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("the base class supplies the escape hatch when a resolver describes none")
    void baseClassSuppliesEscapeHatch() {
        ConflictResolver terse = new AbstractConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return ConflictType.IMPORT_ADD;
            }

            @Override
            protected ConflictResolution doResolve(Conflict conflict) {
                return null;
            }

            @Override
            protected List<FixPath> describeOptions(Conflict conflict) {
                return List.of();
            }
        };

        List<FixPath> fixPaths = terse.getFixPaths(ConflictFixtures.sample(ConflictType.IMPORT_ADD));

        assertEquals(1, fixPaths.size(), "was " + fixPaths);
        assertTrue(fixPaths.get(0).getDescription().contains("by hand"),
            "the escape hatch must be self-describing: " + fixPaths.get(0));
    }

    @Test
    @DisplayName("the base class interpolates fix paths into a resolution that lacks them")
    void baseClassInterpolatesFixPaths() {
        ConflictResolution resolution = new AdditiveMethodBodyResolver()
            .resolve(ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE));

        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "a resolution built with autoResolution() must carry the resolver's options");
        assertTrue(resolution.getAlternativePaths().get(0).getConflictType()
                == ConflictType.METHOD_BODY_CHANGE,
            "newFixPath() must pre-fill the resolver's own conflict type");
    }

    @Test
    @DisplayName("stickyByDefault() reaches the produced resolution")
    void stickyByDefaultReachesResolution() {
        ConflictResolver sticky = new AbstractConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return ConflictType.PACKAGE_CHANGE;
            }

            @Override
            protected boolean stickyByDefault() {
                return true;
            }

            @Override
            protected ConflictResolution doResolve(Conflict conflict) {
                return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                    .resolvedCode(conflict.getBranch1Code())
                    .explanation("sticky by default")
                    .build();
            }

            @Override
            protected List<FixPath> describeOptions(Conflict conflict) {
                return List.of();
            }
        };

        ConflictResolution resolution = sticky.resolve(
            ConflictFixtures.sample(ConflictType.PACKAGE_CHANGE));

        assertTrue(resolution.isSticky());
        assertTrue(resolution.isReplayable(),
            "a sticky resolver's automatic resolution must be replayable");
    }

    @Test
    @DisplayName("the base class honours the review level a resolver asks for")
    void baseClassHonoursReviewLevel() {
        ConflictResolver careful = new AbstractConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return ConflictType.IMPORT_ADD;
            }

            @Override
            protected ConflictResolution doResolve(Conflict conflict) {
                return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                    .resolvedCode(conflict.getBranch1Code())
                    .explanation("correct, but worth confirming")
                    .build();
            }

            @Override
            protected List<FixPath> describeOptions(Conflict conflict) {
                return List.of();
            }
        };

        assertEquals(ConflictResolution.ResolutionKind.REVIEW,
            careful.resolve(ConflictFixtures.sample(ConflictType.IMPORT_ADD)).getKind(),
            "reviewResolution() must produce a REVIEW result, not AUTO");
    }

    @Test
    @DisplayName("a custom resolver holds no state between instances")
    void customResolverIsStateless() {
        ConflictResolver first = new AdditiveMethodBodyResolver();
        ConflictResolver second = new AdditiveMethodBodyResolver();
        Conflict conflict = ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE);

        assertEquals(first.resolve(conflict).getResolvedCode(),
            second.resolve(conflict).getResolvedCode(),
            "two instances must agree; a resolver must not hold state");
        assertNotSame(first, second);
    }

    @Test
    @DisplayName("the registry accepts an annotated, fully registered resolver set")
    void registryAcceptsCustomResolver() {
        List<ConflictResolver> extended = new java.util.ArrayList<>(
            ConflictResolvers.defaultResolvers());
        extended.set(extended.indexOf(ConflictResolvers
                .find(extended, ConflictType.METHOD_BODY_CHANGE).orElseThrow()),
            new AdditiveMethodBodyResolver());

        var index = ConflictResolvers.index(extended);

        assertEquals(ConflictType.values().length, index.size(),
            "replacing a resolver in place must keep the registry complete");
        assertSame(AdditiveMethodBodyResolver.class,
            index.get(ConflictType.METHOD_BODY_CHANGE).getClass());
        assertTrue(ConflictResolvers.unhandledTypes(extended).isEmpty());
    }
}
