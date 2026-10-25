// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import org.junit.Test;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link FileFilter} matches with git's {@code wildmatch}, not the JDK's glob dialect (DEC-051).
 *
 * <p>The cases below are the ones where the two disagree: a {@code **} is a directory form only when
 * it is a whole segment, a pattern with no separator matches a basename at any depth, and a leading
 * {@code /} is what anchors a pattern to the root.</p>
 */
public class GlobFilteringTest {

    private static FileFilter excluding(String... patterns) {
        return new FileFilter(Paths.get("target/test-source").toAbsolutePath(), Collections.emptyList(),
                List.of(patterns));
    }

    @Test
    public void testFileFilter() {
        FileFilter filter = excluding("**/node_modules/**", "**/.git/**");
        Path sourcePath = Paths.get("target/test-source").toAbsolutePath();

        assertFalse("Should exclude root node_modules",
                filter.shouldInclude(sourcePath.resolve("node_modules/package.json")));
        assertFalse("Should exclude nested node_modules",
                filter.shouldInclude(sourcePath.resolve("src/node_modules/package.json")));
        assertTrue("Should include normal file", filter.shouldInclude(sourcePath.resolve("src/main.java")));
        assertFalse("Should exclude .git file", filter.shouldInclude(sourcePath.resolve(".git/config")));
        assertFalse("Should exclude a nested .git file",
                filter.shouldInclude(sourcePath.resolve("src/.git/config")));
    }

    @Test
    public void aDoubleStarIsADirectoryFormOnlyAsAWholeSegment() {
        // `**node_modules/**` is not `**/node_modules/**`: the first `**` is not a whole segment, so
        // it is an ordinary `*` and the pattern only reaches a node_modules directory at the root.
        // This is the difference the removed JDK-glob implementation hid.
        FileFilter filter = excluding("**node_modules/**");
        Path sourcePath = Paths.get("target/test-source").toAbsolutePath();

        assertFalse(filter.shouldInclude(sourcePath.resolve("node_modules/package.json")));
        assertTrue(filter.shouldInclude(sourcePath.resolve("src/node_modules/package.json")));
    }

    @Test
    public void testFileFilterWithIncludes() {
        Path sourcePath = Paths.get("target/test-source").toAbsolutePath();
        FileFilter filter = new FileFilter(sourcePath, List.of("src/**"), Collections.emptyList());

        Path srcPath = sourcePath.resolve("src/main.java");
        Path rootPath = sourcePath.resolve("readme.md");

        assertTrue("Should include file in src", filter.shouldInclude(srcPath));
        assertFalse("Should exclude file outside src", filter.shouldInclude(rootPath));
    }

    @Test
    public void anUnanchoredPatternTriesEveryDepthAndAStarStopsAtASeparator() {
        Path sourcePath = Paths.get("target/test-source").toAbsolutePath();

        FileFilter anyDepth = new FileFilter(sourcePath, List.of("*.java"), Collections.emptyList());
        assertTrue(anyDepth.shouldInclude(sourcePath.resolve("Root.java")));
        assertTrue(anyDepth.shouldInclude(sourcePath.resolve("src/main/Root.java")));
        assertFalse(anyDepth.shouldInclude(sourcePath.resolve("src/main/root.txt")));

        FileFilter oneLevel = new FileFilter(sourcePath, List.of("src/*.java"), Collections.emptyList());
        assertTrue(oneLevel.shouldInclude(sourcePath.resolve("src/Root.java")));
        assertFalse("`*` does not cross a directory boundary",
                oneLevel.shouldInclude(sourcePath.resolve("src/main/Root.java")));
    }
}
