// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import hr.hrg.gitignore.GitIgnoreFile;

import java.nio.file.Path;
import java.util.List;

/**
 * Which files a watch covers: include and exclude patterns, matched with git's {@code wildmatch}.
 *
 * <p>Both lists are read as {@code .gitignore} patterns by the published matcher
 * ({@code hr.hrg:gitignore}, DEC-051), which is the ecosystem's only wildcard syntax: {@code *} never
 * crosses a {@code /}, the three {@code **} forms cover directories, a pattern with no separator
 * matches a basename at any depth, a leading {@code /} anchors it to the filter's source root, and a
 * trailing {@code /} matches a directory and everything below it. There is no second dialect here — no
 * brace expansion, no extglob — because the repository consumes the matcher rather than implementing a
 * subset of it.</p>
 *
 * <p>An excluded path stays excluded; when {@code includes} is not empty a path must match one of its
 * patterns as well.</p>
 */
public class FileFilter {
    private final Path sourcePath;
    private final GitIgnoreFile includes;
    private final GitIgnoreFile excludes;

    public FileFilter(Path sourcePath, List<String> includes, List<String> excludes) {
        this.sourcePath = sourcePath.toAbsolutePath();
        this.includes = GitIgnoreFile.parse("", String.join("\n", includes));
        this.excludes = GitIgnoreFile.parse("", String.join("\n", excludes));
    }

    public boolean shouldInclude(Path path) {
        String relativePath = sourcePath.relativize(path.toAbsolutePath()).toString().replace('\\', '/');

        // `decide` is Optional because "no pattern had an opinion" is not "excluded"; for a filter
        // list, a path no exclude pattern matched is simply not excluded.
        if (excludes.decide(relativePath, false).orElse(false))
            return false;

        // If includes specified, must match at least one
        if (includes.ruleCount() > 0)
            return includes.decide(relativePath, false).orElse(false);

        return true;
    }
}
