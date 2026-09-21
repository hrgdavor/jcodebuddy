// {@link com.codebuddy.merge.ConflictResolversTest} Tests for the resolver registry.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the registration contract that makes adding a resolver a one-line
 * change: every conflict type must be owned by exactly one resolver, and the
 * built-in list must be internally consistent.
 */
class ConflictResolversTest {

    /**
     * When a new {@link ConflictType} is added without a resolver, this test
     * fails and names the type. That is the whole point: a forgotten registration
     * is caught here rather than becoming a runtime manual fallback.
     */
    @Test
    @DisplayName("every conflict type has a registered resolver")
    void everyConflictTypeIsHandled() {
        var unhandled = ConflictResolvers.unhandledTypes(ConflictResolvers.defaultResolvers());

        assertTrue(unhandled.isEmpty(),
            "these conflict types have no resolver; register one in "
                + "ConflictResolvers.defaultResolvers(): " + unhandled);
    }

    @Test
    @DisplayName("exactly one resolver owns each conflict type")
    void resolvesOneTypeEach() {
        Map<ConflictType, ConflictResolver> index =
            ConflictResolvers.index(ConflictResolvers.defaultResolvers());

        assertEquals(ConflictType.values().length, index.size(),
            "each conflict type must have exactly one owner");
    }

    @ParameterizedTest
    @EnumSource(ConflictType.class)
    @DisplayName("the registry resolves every conflict type")
    void registryResolvesEachType(ConflictType type) {
        ConflictResolver resolver =
            ConflictResolvers.find(ConflictResolvers.defaultResolvers(), type).orElseThrow();

        assertSame(type, resolver.supportedType(),
            "the resolver registered for " + type + " must declare that type");
    }

    @Test
    @DisplayName("the built-in resolver list is complete")
    void builtInListIsComplete() {
        List<ConflictResolver> resolvers = ConflictResolvers.defaultResolvers();

        assertNotNull(resolvers);
        assertEquals(ConflictType.values().length, resolvers.size(),
            "one resolver per conflict type, was " + resolvers.size());
        assertTrue(resolvers.stream().allMatch(resolver -> resolver != null));
    }

    @Test
    @DisplayName("a duplicate registration fails loudly")
    void duplicateRegistrationFails() {
        List<ConflictResolver> duplicated = List.of(
            new ImportConflictResolver(),
            new ImportConflictResolver());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> ConflictResolvers.index(duplicated));

        assertTrue(failure.getMessage().contains("IMPORT_ADD"),
            "the message must name the clashing type: " + failure.getMessage());
    }

    @Test
    @DisplayName("resolvers are ordered by descending priority")
    void sortsByPriority() {
        ConflictResolver low = new PriorityResolver(1, ConflictType.IMPORT_ADD);
        ConflictResolver high = new PriorityResolver(10, ConflictType.COMMENT_ADD);
        ConflictResolver middle = new PriorityResolver(5, ConflictType.CONSTANT_ADD);

        List<ConflictResolver> sorted =
            ConflictResolvers.sortByPriority(List.of(low, middle, high));

        assertEquals(List.of(high, middle, low), sorted);
    }

    @Test
    @DisplayName("find returns empty for an unregistered configuration")
    void findReturnsEmptyWhenAbsent() {
        assertTrue(ConflictResolvers.find(List.of(), ConflictType.IMPORT_ADD).isEmpty());
    }

    @Test
    @DisplayName("unhandledTypes reports every type when nothing is registered")
    void unhandledTypesReportsAll() {
        assertEquals(ConflictType.values().length,
            ConflictResolvers.unhandledTypes(List.of()).size());
    }

    @Test
    @DisplayName("resolver names are derived from the class name")
    void resolverNamesAreDerived() {
        assertAll(
            () -> assertEquals("Import", new ImportConflictResolver().name()),
            () -> assertEquals("Rename", new RenameConflictResolver().name()),
            () -> assertEquals("StructuralChange", new StructuralChangeConflictResolver().name()),
            () -> assertEquals("ApiIncompatibility", new ApiIncompatibilityConflictResolver().name())
        );
    }

    @Test
    @DisplayName("every built-in resolver has a non-blank name")
    void everyResolverHasName() {
        for (ConflictResolver resolver : ConflictResolvers.defaultResolvers()) {
            assertFalse(resolver.name().isBlank(),
                resolver.getClass().getSimpleName() + " must have a usable name");
        }
    }

    /**
     * A resolver that only exists to exercise priority ordering.
     */
    private static final class PriorityResolver implements ConflictResolver {

        private final int priority;
        private final ConflictType type;

        PriorityResolver(int priority, ConflictType type) {
            this.priority = priority;
            this.type = type;
        }

        @Override
        public ConflictType supportedType() {
            return type;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public ConflictResolution resolve(Conflict conflict) {
            return ConflictResolution.builder()
                .filePath(conflict.getFilePath())
                .type(conflict.getType())
                .resolvedCode(conflict.getBranch1Code())
                .build();
        }

        @Override
        public List<FixPath> getFixPaths(Conflict conflict) {
            return List.of();
        }
    }
}
