// {@link com.codebuddy.merge.MergeUtilTest} Tests for the convenience facade.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the convenience entry point is an ordinary library facade: it
 * wires a resolver for the caller and adds no behaviour of its own. It is not a
 * Maven plugin, build extension or CLI.
 */
class MergeUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("create() returns a usable facade")
    void createReturnsUsableFacade() {
        assertNotNull(MergeUtil.create(TestTypeContexts.jdk()));
    }

    @Test
    @DisplayName("resolves from in-memory versions")
    void resolvesInMemory() {
        MergeUtil util = MergeUtil.create(TestTypeContexts.jdk());

        MergeConflictResolver.MergeReport report = util.resolve(
            ConflictFixtures.FILE,
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;\nimport java.time.Instant;");

        assertFalse(report.isClean());
        assertFalse(report.getAutoResolutions().isEmpty(),
            "the common import conflict must resolve without configuration");
        assertTrue(report.getAutoResolutions().get(0).getResolvedCode()
                .contains("import java.time.Instant;"));
    }

    @Test
    @DisplayName("resolves from files on disk, remembering decisions in the given directory")
    void resolvesFromDiskWithHistory() throws IOException {
        Path base = tempDir.resolve("Base.java");
        Path branch1 = tempDir.resolve("Branch1.java");
        Path branch2 = tempDir.resolve("Branch2.java");
        Files.writeString(base, "import java.util.List;", StandardCharsets.UTF_8);
        Files.writeString(branch1, "import java.util.List;\nimport java.math.BigDecimal;",
            StandardCharsets.UTF_8);
        Files.writeString(branch2, "import java.util.List;\nimport java.time.Instant;",
            StandardCharsets.UTF_8);

        Path history = tempDir.resolve("merge-history").resolve("feature-payments");
        MergeConflictResolver.MergeReport report = MergeUtil.create(TestTypeContexts.jdk()).resolve(
            "src/main/java/Payment.java",
            base.toString(), branch1.toString(), branch2.toString(),
            history.toString());

        assertEquals("src/main/java/Payment.java", report.getFilePath());
        assertFalse(report.getAutoResolutions().isEmpty());
    }

    @Test
    @DisplayName("the branch name comes from the history directory")
    void derivesBranchNameFromHistoryDirectory() {
        MergeConflictResolver resolver = MergeUtil.create(TestTypeContexts.jdk())
            .createResolver("feature-payments");

        assertEquals("feature-payments", resolver.getBranchName());
    }

    @Test
    @DisplayName("createResolver() returns the default configuration")
    void createResolverReturnsDefaults() {
        MergeConflictResolver resolver = MergeUtil.create(TestTypeContexts.jdk()).createResolver();

        assertNotNull(resolver);
        assertEquals(ConflictResolvers.defaultResolvers().size(), resolver.getResolvers().size(),
            "the facade must use the shared registry");
    }

    @Test
    @DisplayName("exposes fix paths for a conflict type")
    void exposesFixPaths() {
        List<FixPath> fixPaths = MergeUtil.create(TestTypeContexts.jdk()).fixPathsFor(ConflictType.VARIABLE_RENAME);

        assertFalse(fixPaths.isEmpty(), "a rename must offer the reviewer options");
        assertTrue(fixPaths.stream().allMatch(path -> path.getConflictType() == ConflictType.VARIABLE_RENAME));
    }

    @Test
    @DisplayName("offers fix paths for every conflict type through the registry")
    void offersFixPathsForEveryType() {
        MergeUtil util = MergeUtil.create(TestTypeContexts.jdk());

        for (ConflictType type : ConflictType.values()) {
            assertFalse(util.fixPathsFor(type).isEmpty(),
                "no options were offered for " + type);
        }
    }

    @Test
    @DisplayName("resolves the same way whether reached through the facade or directly")
    void facadeMatchesDirectUse() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);

        ConflictResolution viaFacade = MergeUtil.create(TestTypeContexts.jdk()).createResolver().resolve(conflict);
        ConflictResolution direct = MergeConflictResolver.create(TestTypeContexts.jdk()).resolve(conflict);

        assertEquals(direct.getResolvedCode(), viaFacade.getResolvedCode());
        assertEquals(direct.getKind(), viaFacade.getKind());
    }
}
