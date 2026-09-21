// {@link com.codebuddy.merge.CommentAddConflictResolverTest} Tests for the comment conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that two branches adding different documentation are merged rather
 * than reported as a conflict.
 */
class CommentAddConflictResolverTest extends AbstractResolverTest {

    private final CommentAddConflictResolver resolver = new CommentAddConflictResolver();

    @Override
    protected ConflictResolver resolverUnderTest() {
        return resolver;
    }

    @Override
    protected Conflict conflictFor(ConflictType type) {
        return ConflictFixtures.sample(type);
    }

    @Override
    protected List<ConflictType> unsupportedTypes() {
        return List.of(ConflictType.IMPORT_ADD, ConflictType.TYPE_CHANGE,
            ConflictType.API_INCOMPATIBILITY);
    }

    @Test
    @DisplayName("keeps the documentation from both branches")
    void keepsBothBranchesComments() {
        ConflictResolution resolution = resolver.resolve(ConflictFixtures.sample(ConflictType.COMMENT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertTrue(resolution.getResolvedCode().contains("branch 1 explains the running total"));
        assertTrue(resolution.getResolvedCode().contains("branch 2 records the currency"));
    }

    @Test
    @DisplayName("drops a comment both branches wrote identically")
    void dropsDuplicateComments() {
        Conflict conflict = new Conflict(ConflictType.COMMENT_ADD, ConflictFixtures.FILE,
            "identical comments",
            "int total = 0;",
            "// shared explanation\nint total = 0;",
            "// shared explanation\nint total = 0;");

        String resolved = resolver.resolve(conflict).getResolvedCode();
        int occurrences = resolved.split("shared explanation", -1).length - 1;
        assertEquals(1, occurrences, "an identical comment must not be duplicated: " + resolved);
    }

    @Test
    @DisplayName("declines when neither branch added a comment")
    void declinesWhenNoCommentsPresent() {
        Conflict conflict = new Conflict(ConflictType.COMMENT_ADD, ConflictFixtures.FILE,
            "no comments", "int total = 0;", "int total = 1;", "int total = 2;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("recognises line and block comments")
    void recognisesCommentForms() {
        List<String> comments = CommentAddConflictResolver.commentsIn(
            "// line comment\n/* block comment */\n* javadoc line\nint x = 1;");

        assertTrue(comments.contains("line comment"), "line comments must be found: " + comments);
        assertTrue(comments.contains("block comment"), "block comments must be found: " + comments);
        assertTrue(comments.contains("javadoc line"), "javadoc lines must be found: " + comments);
    }

    @Test
    @DisplayName("recommends keeping both branches' documentation")
    void recommendsKeepingBoth() {
        FixPath primary = resolver.getFixPaths(ConflictFixtures.sample(ConflictType.COMMENT_ADD)).get(0);
        assertTrue(primary.hasRecommendation());
        assertTrue(primary.getRecommended().startsWith("Keep both"),
            "was " + primary.getRecommended());
    }
}
