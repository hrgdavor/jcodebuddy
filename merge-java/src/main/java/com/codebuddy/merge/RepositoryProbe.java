// {@link com.codebuddy.merge.RepositoryProbe} Discovers the repository context of a conflicted file, including its staged base.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * What {@link MergeFileTool} can learn about a conflict-marked file from the
 * repository around it, through JGit as a library - never a subprocess.
 *
 * <p>Two facts matter:
 *
 * <ul>
 *   <li><b>The branch name</b>, so resolutions are recorded in - and replayed
 *       from - the same per-branch history {@link MergeWorkflow} uses;</li>
 *   <li><b>The staged base</b>: while a merge is in progress, git keeps three
 *       stages per conflicted path in the index, and stage 1 is the real common
 *       base. It is the authoritative base for a file with plain (non-diff3)
 *       conflict markers, where the file itself carries only two sides.</li>
 * </ul>
 *
 * <p>Everything here is best-effort by design: a file outside any repository, a
 * repository mid-rebase, an unreadable index - all of them simply yield "no
 * findings" and the tool proceeds with what the file itself says. Probing must
 * never be the reason a fix fails.
 */
public final class RepositoryProbe {

    private RepositoryProbe() {
    }

    /**
     * The repository context of a file.
     *
     * @param repositoryFound true when the file sits inside a git working tree
     * @param repositoryRoot  the working tree root, or {@code null}
     * @param branchName      the current branch (or detached HEAD id), or {@code null}
     * @param relativePath    the file's repository-relative path with forward
     *                        slashes, or {@code null}
     * @param stagedBase      the content of the path's stage-1 index entry - the
     *                        merge's common base - or {@code null} when the path
     *                        has no unmerged index entry
     */
    public record Findings(boolean repositoryFound, Path repositoryRoot, String branchName,
                           String relativePath, String stagedBase) {

        static final Findings NONE = new Findings(false, null, null, null, null);

        /**
         * True when a real base could be read from the repository's index.
         */
        public boolean hasStagedBase() {
            return stagedBase != null;
        }
    }

    /**
     * Probe the repository around {@code file}. Never throws: any problem - no
     * repository, a bare one, an unreadable index - is reported as no findings.
     */
    public static Findings probe(Path file) {
        if (file == null) {
            return Findings.NONE;
        }
        try {
            Path absolute = file.toAbsolutePath().normalize();
            File start = absolute.getParent() == null ? absolute.toFile() : absolute.getParent().toFile();
            FileRepositoryBuilder builder = new FileRepositoryBuilder()
                .readEnvironment()
                .findGitDir(start);
            if (builder.getGitDir() == null) {
                return Findings.NONE;
            }
            try (Repository repository = builder.setMustExist(true).build()) {
                File workTree = repository.getWorkTree();
                if (workTree == null) {
                    return Findings.NONE;       // bare repository: no working tree to conflict in
                }
                Path root = workTree.toPath().toAbsolutePath().normalize();
                if (!absolute.startsWith(root)) {
                    return Findings.NONE;
                }
                String relative = root.relativize(absolute).toString().replace('\\', '/');
                String branch = MergeWorkflow.currentBranch(repository);
                String base = stagedBase(repository, relative);
                return new Findings(true, root, branch, relative, base);
            }
        } catch (IOException | RuntimeException e) {
            // A probe is context, never a precondition.
            return Findings.NONE;
        }
    }

    /**
     * The stage-1 (base) content of an unmerged path, or {@code null} when the
     * index holds no such entry - which is the case outside an active merge, and
     * for a conflict whose markers were left behind by something other than git.
     */
    private static String stagedBase(Repository repository, String relativePath) throws IOException {
        DirCache cache = DirCache.read(repository);
        for (int i = 0; i < cache.getEntryCount(); i++) {
            DirCacheEntry entry = cache.getEntry(i);
            if (entry.getStage() == DirCacheEntry.STAGE_1
                && relativePath.equals(entry.getPathString())) {
                ObjectLoader loader = repository.open(entry.getObjectId());
                return new String(loader.getBytes(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
