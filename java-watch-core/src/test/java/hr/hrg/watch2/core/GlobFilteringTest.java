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

public class GlobFilteringTest {

    @Test
    public void testFileFilter() {
        Path sourcePath = Paths.get("target/test-source").toAbsolutePath();
        
        List<String> includes = Collections.emptyList();
        List<String> excludes = List.of("**node_modules/**", "**.git/**");
        
        FileFilter filter = new FileFilter(sourcePath, includes, excludes);

        Path nodePath = sourcePath.resolve("node_modules/package.json");
        Path nestedNodePath = sourcePath.resolve("src/node_modules/package.json");
        Path normalPath = sourcePath.resolve("src/main.java");
        Path gitPath = sourcePath.resolve(".git/config");

        assertFalse("Should exclude root node_modules", filter.shouldInclude(nodePath));
        assertFalse("Should exclude nested node_modules", filter.shouldInclude(nestedNodePath));
        assertTrue("Should include normal file", filter.shouldInclude(normalPath));
        assertFalse("Should exclude .git file", filter.shouldInclude(gitPath));
    }

    @Test
    public void testFileFilterWithIncludes() {
        Path sourcePath = Paths.get("target/test-source").toAbsolutePath();
        
        List<String> includes = List.of("src/**");
        List<String> excludes = Collections.emptyList();
        
        FileFilter filter = new FileFilter(sourcePath, includes, excludes);

        Path srcPath = sourcePath.resolve("src/main.java");
        Path rootPath = sourcePath.resolve("readme.md");

        assertTrue("Should include file in src", filter.shouldInclude(srcPath));
        assertFalse("Should exclude file outside src", filter.shouldInclude(rootPath));
    }
}
