// {@link com.codebuddy.merge.RepositoryProbeTest} Tests repository discovery and the staged base read against a real JGit repository.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probe is what lets the tool tell a real merge in progress from a file
 * that merely carries markers, so it is tested against a repository built with
 * JGit - including an index holding the three unmerged stages git writes during
 * a conflict.
 */
class RepositoryProbeTest {

    @TempDir
    Path tempDir;

    private static final String RELATIVE = "src/main/java/com/example/demo/OrderService.java";

    private static final String BASE =
        "package com.example.demo;\n\npublic class OrderService {\n    int total = 10;\n}\n";

    private static DirCacheEntry entry(String path, int stage, ObjectId id) {
        DirCacheEntry entry = new DirCacheEntry(path, stage);
        entry.setFileMode(org.eclipse.jgit.lib.FileMode.REGULAR_FILE);
        entry.setObjectId(id);
        return entry;
    }

    @Test
    @DisplayName("inside a merge the probe reports the branch and the stage-1 base")
    void findsBranchAndStagedBase() throws Exception {
        Path root = tempDir.resolve("repo");
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Path file = root.resolve(RELATIVE.replace('/', java.io.File.separatorChar));
            Files.createDirectories(file.getParent());
            Files.writeString(file, "<<<<<<< ours\nconflicted\n>>>>>>> theirs\n",
                StandardCharsets.UTF_8);

            Repository repository = git.getRepository();
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                ObjectId baseId = inserter.insert(Constants.OBJ_BLOB,
                    BASE.getBytes(StandardCharsets.UTF_8));
                ObjectId oursId = inserter.insert(Constants.OBJ_BLOB,
                    "ours version".getBytes(StandardCharsets.UTF_8));
                ObjectId theirsId = inserter.insert(Constants.OBJ_BLOB,
                    "theirs version".getBytes(StandardCharsets.UTF_8));

                DirCache cache = DirCache.lock(repository, null);
                DirCacheBuilder builder = cache.builder();
                builder.add(entry(RELATIVE, DirCacheEntry.STAGE_1, baseId));
                builder.add(entry(RELATIVE, DirCacheEntry.STAGE_2, oursId));
                builder.add(entry(RELATIVE, DirCacheEntry.STAGE_3, theirsId));
                builder.commit();      // finish + write the built index + unlock
                inserter.flush();
            }

            RepositoryProbe.Findings findings = RepositoryProbe.probe(file);

            assertTrue(findings.repositoryFound());
            assertEquals(root.toRealPath(), findings.repositoryRoot().toRealPath());
            assertEquals(RELATIVE, findings.relativePath());
            assertNotNull(findings.branchName());
            assertFalse(findings.branchName().isBlank());
            assertTrue(findings.hasStagedBase());
            assertEquals(BASE, findings.stagedBase());
        }
    }

    @Test
    @DisplayName("a tracked file without an unmerged entry has no staged base")
    void noStagedBaseOutsideAMerge() throws Exception {
        Path root = tempDir.resolve("repo2");
        Files.createDirectories(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            Path other = root.resolve("Notes.txt");
            Files.writeString(other, "nothing merged here\n", StandardCharsets.UTF_8);

            RepositoryProbe.Findings findings = RepositoryProbe.probe(other);

            assertTrue(findings.repositoryFound());
            assertEquals("Notes.txt", findings.relativePath());
            assertFalse(findings.hasStagedBase());
            assertNull(findings.stagedBase());
        }
    }

    @Test
    @DisplayName("outside any repository the probe reports nothing instead of failing")
    void noRepositoryNoFindings() throws IOException {
        Path plain = tempDir.resolve("plain");
        Files.createDirectories(plain);
        Path file = plain.resolve("OrderService.java");
        Files.writeString(file, "class OrderService {}\n", StandardCharsets.UTF_8);

        RepositoryProbe.Findings findings = RepositoryProbe.probe(file);

        assertFalse(findings.repositoryFound());
        assertNull(findings.repositoryRoot());
        assertNull(findings.branchName());
        assertNull(findings.stagedBase());
    }

    @Test
    @DisplayName("a null path is no findings, never an exception")
    void nullPathIsSafe() {
        RepositoryProbe.Findings findings = RepositoryProbe.probe(null);
        assertFalse(findings.repositoryFound());
        assertFalse(findings.hasStagedBase());
    }
}
