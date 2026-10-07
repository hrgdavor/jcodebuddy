// {@link com.codebuddy.merge.TestTrees} Copies a generated fixture tree, so a test can have its own.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A directory copy for tests that need a fixture tree of their own.
 *
 * <p>Two test classes generate a fixture tree that is identical for every test in the class — a git
 * repository built with JGit, and one built by a Bun script — and both used to rebuild it per test.
 * Building it once and copying it per test gives each test a pristine tree, which is what their
 * isolation actually requires, for the cost of a filesystem copy instead of a rebuild: measured, that
 * took the two classes from 131 s and 101 s to a fraction of either.
 *
 * <p>Attributes are deliberately not copied. A generated repository can contain read-only files, and
 * carrying that bit into a copy makes the copy un-deletable on Windows — a test failing in teardown
 * for a reason that has nothing to do with the test.
 */
final class TestTrees {

    private TestTrees() {
    }

    /** Copy {@code from} to {@code to}, creating directories as needed, without file attributes. */
    static void copy(Path from, Path to) throws IOException {
        try (var paths = Files.walk(from)) {
            for (Path source : paths.toList()) {
                Path destination = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(source, destination);
                }
            }
        }
    }
}