// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

public class FileFilter {
    private final Path sourcePath;
    private final List<PathMatcher> includeMatchers = new ArrayList<>();
    private final List<PathMatcher> excludeMatchers = new ArrayList<>();

    public FileFilter(Path sourcePath, List<String> includes, List<String> excludes) {
        this.sourcePath = sourcePath.toAbsolutePath();
        for (String pattern : includes) {
            includeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        }
        for (String pattern : excludes) {
            excludeMatchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
        }
    }

    public boolean shouldInclude(Path path) {
        Path relativePath = sourcePath.relativize(path.toAbsolutePath());

        // Check excludes first
        for (PathMatcher matcher : excludeMatchers) {
            if (matcher.matches(relativePath))
                return false;
        }

        // If includes specified, must match at least one
        if (!includeMatchers.isEmpty()) {
            for (PathMatcher matcher : includeMatchers) {
                if (matcher.matches(relativePath))
                    return true;
            }
            return false;
        }

        return true;
    }
}
