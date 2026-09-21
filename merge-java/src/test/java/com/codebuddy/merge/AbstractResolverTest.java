// {@link com.codebuddy.merge.AbstractResolverTest} Reusable test harness for every conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable half of resolver testing: extend this, supply three small
 * factories, and the contract every resolver must honour is verified
 * automatically.
 *
 * <h2>Adding tests for a new resolver</h2>
 * <pre>{@code
 * class MyConflictResolverTest extends AbstractResolverTest {
 *
 *     private final MyConflictResolver resolver = new MyConflictResolver();
 *
 *     @Override
 *     protected ConflictResolver resolverUnderTest() {
 *         return resolver;
 *     }
 *
 *     @Override
 *     protected Conflict conflictFor(ConflictType type) {
 *         return new Conflict(type, "Sample.java", "both branches changed it",
 *             "base", "branch 1", "branch 2");
 *     }
 *
 *     @Override
 *     protected List<ConflictType> unsupportedTypes() {
 *         return List.of(ConflictType.IMPORT_ADD, ConflictType.STRUCTURAL_CHANGE);
 *     }
 * }
 * }</pre>
 *
 * <p>That inherits: type ownership, null-safety, input preservation, resolution
 * kind consistency, always-present fix paths, and behaviour for unsupported
 * types. Subclasses add only their resolver-specific expectations.
 */
public abstract class AbstractResolverTest {

    /**
     * The resolver under test.
     */
    protected abstract ConflictResolver resolverUnderTest();

    /**
     * A conflict of the given type that the resolver is expected to handle.
     */
    protected abstract Conflict conflictFor(ConflictType type);

    /**
     * Types the resolver must decline. Should not contain
     * {@link #resolverUnderTest()}'s own type.
     */
    protected abstract List<ConflictType> unsupportedTypes();

    /**
     * The single type this resolver owns, taken from the resolver itself so the
     * test never restates it.
     */
    protected final ConflictType ownedType() {
        return resolverUnderTest().supportedType();
    }

    /**
     * A conflict the resolver is expected to resolve.
     */
    protected final Conflict ownedConflict() {
        return conflictFor(ownedType());
    }

    // ---------------------------------------------------------------- ownership

    @Test
    @DisplayName("declares exactly one supported type")
    void declaresOneSupportedType() {
        assertNotNull(ownedType(), "supportedType() must not be null");
        assertTrue(resolverUnderTest().supports(ownedType()),
            "a resolver must support its own declared type");
    }

    @ParameterizedTest
    @EnumSource(ConflictType.class)
    @DisplayName("supports a conflict type if and only if it is the declared one")
    void supportsOnlyItsOwnType(ConflictType type) {
        boolean expected = type == ownedType();
        assertEquals(expected, resolverUnderTest().supports(type),
            "supports(" + type + ") should be " + expected);
    }

    @Test
    @DisplayName("rejects a null conflict type")
    void rejectsNullType() {
        assertFalse(resolverUnderTest().supports(null),
            "supports(null) must be false, not an exception");
    }

    @Test
    @DisplayName("declines the types it does not own")
    void declinesUnsupportedTypes() {
        for (ConflictType type : unsupportedTypes()) {
            assertFalse(resolverUnderTest().supports(type),
                "resolver should not claim " + type);
            Conflict foreign = conflictFor(type);
            ConflictResolution resolution = resolverUnderTest().resolve(foreign);
            assertNotNull(resolution,
                "resolving an unsupported type must still return a resolution, "
                    + "never null, so the orchestrator can offer fix paths");
        }
    }

    // ------------------------------------------------------------- resolve

    @Test
    @DisplayName("returns a resolution that preserves the conflict inputs")
    void resolvePreservesInputs() {
        Conflict conflict = ownedConflict();
        ConflictResolution resolution = resolverUnderTest().resolve(conflict);

        assertNotNull(resolution, "resolve() must never return null");
        assertEquals(conflict.getFilePath(), resolution.getFilePath());
        assertEquals(ownedType(), resolution.getType());
        assertEquals(conflict.getBaseCode(), resolution.getBaseCode());
        assertEquals(conflict.getBranch1Code(), resolution.getBranch1Code());
        assertEquals(conflict.getBranch2Code(), resolution.getBranch2Code());
        assertNotNull(resolution.getResolvedCode(), "resolved code must not be null");
        assertNotNull(resolution.getResolutionStrategy());
        assertNotNull(resolution.getKind());
        assertNotNull(resolution.getExplanation(), "a resolution must explain itself");
        assertNotNull(resolution.getResolvedAt());
        assertNotNull(resolution.getConflictId());
        assertFalse(resolution.getConflictId().isBlank());
    }

    @Test
    @DisplayName("the resolution kind agrees with the strategy")
    void kindAgreesWithStrategy() {
        ConflictResolution resolution = resolverUnderTest().resolve(ownedConflict());
        ConflictResolution.ResolutionStrategy strategy = resolution.getResolutionStrategy();
        ConflictResolution.ResolutionKind kind = resolution.getKind();

        switch (strategy) {
            case MANUAL, REJECTED -> assertEquals(ConflictResolution.ResolutionKind.MANUAL, kind,
                "manual strategies must produce a MANUAL kind");
            case STICKY_REPLAY -> assertEquals(ConflictResolution.ResolutionKind.DEFERRED, kind,
                "a replayed decision must be DEFERRED");
            default -> assertTrue(
                kind == ConflictResolution.ResolutionKind.AUTO
                    || kind == ConflictResolution.ResolutionKind.REVIEW,
                "an automatic strategy must produce AUTO or REVIEW, was " + kind);
        }
    }

    @Test
    @DisplayName("is deterministic for the same input")
    void resolveIsDeterministic() {
        ConflictResolution first = resolverUnderTest().resolve(ownedConflict());
        ConflictResolution second = resolverUnderTest().resolve(ownedConflict());

        assertEquals(first.getResolvedCode(), second.getResolvedCode(),
            "the same conflict must resolve to the same code");
        assertEquals(first.getResolutionStrategy(), second.getResolutionStrategy());
        assertEquals(first.getKind(), second.getKind());
        assertEquals(first.getExplanation(), second.getExplanation());
    }

    @Test
    @DisplayName("is stateless across different conflicts")
    void resolveIsStateless() {
        Conflict first = ownedConflict();
        Conflict second = new Conflict(ownedType(), "Other.java", "a different conflict",
            "base two", "branch one two", "branch two two");

        ConflictResolution firstResult = resolverUnderTest().resolve(first);
        resolverUnderTest().resolve(second);
        ConflictResolution firstAgain = resolverUnderTest().resolve(first);

        assertEquals(firstResult.getResolvedCode(), firstAgain.getResolvedCode(),
            "resolving another conflict must not change this one's outcome");
    }

    @Test
    @DisplayName("resolves an empty conflict without throwing")
    void resolveHandlesEmptyInput() {
        Conflict empty = new Conflict(ownedType(), "Empty.java", "nothing to merge", "", "", "");
        ConflictResolution resolution = resolverUnderTest().resolve(empty);
        assertNotNull(resolution, "an empty conflict must still produce a resolution");
    }

    @Test
    @DisplayName("resolving a null conflict fails fast")
    void resolveRejectsNull() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
            () -> resolverUnderTest().resolve(null));
    }

    // ------------------------------------------------------------ fix paths

    @Test
    @DisplayName("always offers at least one fix path")
    void alwaysOffersFixPaths() {
        List<FixPath> fixPaths = resolverUnderTest().getFixPaths(ownedConflict());
        assertNotNull(fixPaths, "getFixPaths() must never return null");
        assertFalse(fixPaths.isEmpty(),
            "a reviewer must always have at least the manual escape hatch");
    }

    @Test
    @DisplayName("fix paths are fully described")
    void fixPathsAreFullyDescribed() {
        for (FixPath fixPath : resolverUnderTest().getFixPaths(ownedConflict())) {
            assertNotNull(fixPath.getDescription(),
                "every fix path needs a description for the reviewer");
            assertFalse(fixPath.getDescription().isBlank());
            assertNotNull(fixPath.getOptions(), "options must not be null");
            assertFalse(fixPath.getOptions().isEmpty(),
                "a fix path with no options is not actionable");
            assertNotNull(fixPath.getJustification(), "a fix path needs a justification");
            assertFalse(fixPath.getJustification().isBlank());
        }
    }

    @Test
    @DisplayName("a recommendation, when present, is one of the options")
    void recommendationIsAnOption() {
        for (FixPath fixPath : resolverUnderTest().getFixPaths(ownedConflict())) {
            if (fixPath.hasRecommendation()) {
                assertTrue(fixPath.getOptions().contains(fixPath.getRecommended()),
                    "recommended '" + fixPath.getRecommended()
                        + "' must appear in options " + fixPath.getOptions());
            }
        }
    }

    @Test
    @DisplayName("fix paths are exposed on the resolution when it needs a decision")
    void resolutionCarriesFixPathsWhenNotAuto() {
        ConflictResolution resolution = resolverUnderTest().resolve(ownedConflict());
        if (resolution.getKind() == ConflictResolution.ResolutionKind.MANUAL
            || resolution.getKind() == ConflictResolution.ResolutionKind.REVIEW) {
            assertFalse(resolution.getAlternativePaths().isEmpty(),
                "a resolution needing a human must carry the options");
        }
    }

    @Test
    @DisplayName("getFixPaths is stable for the same conflict")
    void fixPathsAreStable() {
        Conflict conflict = ownedConflict();
        assertEquals(resolverUnderTest().getFixPaths(conflict),
            resolverUnderTest().getFixPaths(conflict),
            "fix paths must be reproducible so history diffs stay quiet");
    }

    @Test
    @DisplayName("names itself for diagnostics")
    void hasDiagnosticName() {
        assertNotNull(resolverUnderTest().name());
        assertFalse(resolverUnderTest().name().isBlank());
    }

    @Test
    @DisplayName("exposes the resolver declared by the registry for its type")
    void registryAgreesWithType() {
        ConflictResolvers.find(ConflictResolvers.defaultResolvers(), ownedType())
            .ifPresent(registered -> assertSame(resolverUnderTest().getClass(),
                registered.getClass(),
                "the registry must resolve " + ownedType() + " to this resolver"));
    }
}
