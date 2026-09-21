// {@link com.codebuddy.merge.MergeWorkflow} Keeps a branch up to date through JGit, without shelling out to git.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the merge-java loop against a real repository, through JGit.
 *
 * <p>This closes the gap between "resolve a file I hand you" and the actual
 * workflow: keep a long-lived branch up to date with its base, repeatedly, without
 * re-answering the same conflicts. It discovers the branch and the merge base
 * itself, reads all three versions from the object database rather than requiring
 * three files on disk, applies the independently applicable resolutions through
 * the working tree, and reports what is left for a human.
 *
 * <p>JGit is used as a library, never as a subprocess, so this works wherever the
 * JVM does.
 *
 * <h2>Safety</h2>
 *
 * <p>A workflow writes to a working tree, so it is <b>dry by default at the API
 * level</b>: {@link Builder#dryRun(boolean)} must be called explicitly to allow
 * writes, and every write is reported. Nothing outside the resolved files is
 * touched: no staging, no committing, no branch manipulation. The caller decides
 * what to do with the result.
 */
public final class MergeWorkflow {

    private final Path repositoryRoot;
    private final String upstreamRef;
    private final List<String> paths;
    private final boolean dryRun;
    private final Path reportPath;
    private final TypeContext typeContext;

    private MergeWorkflow(Builder builder) {
        this.repositoryRoot = builder.repositoryRoot;
        this.upstreamRef = builder.upstreamRef;
        this.paths = List.copyOf(builder.paths);
        this.dryRun = builder.dryRun;
        this.reportPath = builder.reportPath;
        this.typeContext = builder.typeContext != null
            ? builder.typeContext
            : TypeContext.withRuntimeClasspath(builder.repositoryRoot);
    }

    public static Builder open(Path repositoryRoot) {
        return new Builder(repositoryRoot);
    }

    /**
     * Builder for {@link MergeWorkflow}.
     */
    public static final class Builder {

        private final Path repositoryRoot;
        private String upstreamRef = "HEAD";
        private final List<String> paths = new ArrayList<>();
        private boolean dryRun = true;
        private Path reportPath;
        private TypeContext typeContext;

        private Builder(Path repositoryRoot) {
            this.repositoryRoot = repositoryRoot.toAbsolutePath();
        }

        /**
         * Supply the context needed to resolve types.
         *
         * <p>Defaults to the repository root with the current JVM's classpath,
         * which resolves JDK types - enough for the conflict types that need types
         * today. Supply an explicit classpath when the merge involves types from
         * the project under merge.
         */
        public Builder typeContext(TypeContext typeContext) {
            this.typeContext = typeContext;
            return this;
        }

        /**
         * The ref to treat as "theirs" - typically {@code origin/main}. Defaults to
         * {@code HEAD}, which makes the workflow analyse the pending merge of the
         * current branch against its upstream tracking branch.
         */
        public Builder upstream(String upstreamRef) {
            this.upstreamRef = upstreamRef;
            return this;
        }

        /**
         * Add a path to resolve. Paths must be repository-relative with forward
         * slashes.
         */
        public Builder path(String path) {
            this.paths.add(path.replace('\\', '/'));
            return this;
        }

        /**
         * Add several paths.
         */
        public Builder paths(List<String> paths) {
            paths.forEach(this::path);
            return this;
        }

        /**
         * Allow writes to the working tree. Off by default: a caller must ask for
         * it.
         */
        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        /**
         * Also write a machine-readable report to this path, for the Bun renderer.
         */
        public Builder reportTo(Path reportPath) {
            this.reportPath = reportPath;
            return this;
        }

        public MergeWorkflow build() {
            return new MergeWorkflow(this);
        }

        /**
         * Build and run.
         */
        public Result run() {
            return build().run();
        }
    }

    /**
     * What the workflow did.
     */
    public record Result(String branchName, String upstreamRef, String mergeBase,
                         List<String> filesResolved, List<String> filesNeedingAttention,
                         List<String> filesSkipped, MergeBatch.Summary summary, boolean dryRun) {

        /**
         * The exit status a CI job should use: non-zero when a human is needed.
         */
        public int exitCode() {
            return filesNeedingAttention.isEmpty() && filesSkipped.isEmpty() ? 0 : 1;
        }

        /**
         * A multi-line report suitable for a merge log.
         */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("branch ").append(branchName)
                .append(" against ").append(upstreamRef)
                .append(" (merge base ").append(mergeBase == null ? "none" : mergeBase)
                .append(")\n");
            text.append("  resolved:           ").append(filesResolved.size()).append('\n');
            text.append("  needs attention:    ").append(filesNeedingAttention.size()).append('\n');
            text.append("  skipped:            ").append(filesSkipped.size()).append('\n');
            text.append(summary.describe());
            return text.toString();
        }
    }

    /**
     * Run the workflow.
     */
    public Result run() {
        try (Repository repository = openRepository()) {
            String branchName = currentBranch(repository);
            ObjectId upstream = resolveUpstream(repository, upstreamRef);

            // A branch with no upstream has nothing to be brought up to date with.
            if (upstream == null) {
                return emptyResult(branchName, "no upstream commit found");
            }

            String upstreamName = upstreamRef.equals("HEAD")
                ? nameOfTrackingBranch(repository).orElse(upstreamRef)
                : upstreamRef;

            List<String> targets = paths.isEmpty() ? List.of() : paths;
            List<MergeConflictResolver.MergeReport> reports = new ArrayList<>();
            List<String> resolved = new ArrayList<>();
            List<String> attention = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            MergeConflictResolver resolver = new MergeConflictResolver.Builder()
                .setBranchName(branchName)
                .setHistoryPath(repositoryRoot.resolve(".jcodebuddy")
                    .resolve("merge-history").resolve(branchName))
                .setTypeContext(typeContext)
                .build();

            for (String path : targets) {
                Optional<VersionSet> versions = readVersions(repository, upstream, path);
                if (versions.isEmpty()) {
                    skipped.add(path);
                    continue;
                }
                VersionSet set = versions.get();
                MergeConflictResolver.MergeReport report = resolver.resolve(
                    path, set.base(), set.ours(), set.theirs());
                reports.add(report);

                if (report.isClean()) {
                    continue;
                }
                if (report.hasUnresolvedConflicts()
                    || !report.getReviewResolutions().isEmpty()) {
                    attention.add(path);
                }
                if (!dryRun && !report.getIndependentlyApplicable().isEmpty()) {
                    writeResolved(Path.of(path), report);
                    resolved.add(path);
                } else if (dryRun && !report.getIndependentlyApplicable().isEmpty()) {
                    resolved.add(path);
                }
            }

            MergeBatch.Summary summary = summarize(reports, skipped);
            if (reportPath != null) {
                MergeReportWriter.write(reportPath, reports, summary);
            }
            return new Result(branchName, upstreamName, mergeBaseOf(repository, upstream),
                List.copyOf(resolved), List.copyOf(attention), List.copyOf(skipped),
                summary, dryRun);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run the merge workflow", e);
        }
    }

    // ------------------------------------------------------------- repository

    private Repository openRepository() throws IOException {
        return new FileRepositoryBuilder()
            .readEnvironment()
            .findGitDir(repositoryRoot.toFile())
            .setMustExist(true)
            .build();
    }

    /**
     * The current branch's short name, or the detached HEAD id when detached.
     */
    static String currentBranch(Repository repository) throws IOException {
        String full = repository.getFullBranch();
        if (full == null) {
            return "unknown";
        }
        if (full.startsWith(Constants.R_HEADS)) {
            return full.substring(Constants.R_HEADS.length());
        }
        return full;
    }

    /**
     * The short name of the current branch's tracking branch, if configured.
     */
    private static Optional<String> nameOfTrackingBranch(Repository repository) {
        try {
            String branch = currentBranch(repository);
            org.eclipse.jgit.lib.StoredConfig config = repository.getConfig();
            String remote = config.getString("branch", branch, "remote");
            String merge = config.getString("branch", branch, "merge");
            if (merge == null) {
                return Optional.empty();
            }
            String shortMerge = merge.startsWith(Constants.R_HEADS)
                ? merge.substring(Constants.R_HEADS.length())
                : merge;
            return Optional.of(remote == null ? shortMerge : remote + "/" + shortMerge);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static ObjectId resolveUpstream(Repository repository, String ref) throws IOException {
        if ("HEAD".equals(ref)) {
            String branch = currentBranch(repository);
            ObjectId tracking = repository.resolve("refs/remotes/origin/" + branch);
            if (tracking != null) {
                return tracking;
            }
        }
        return repository.resolve(ref);
    }

    private String mergeBaseOf(Repository repository, ObjectId upstream) {
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId headId = repository.resolve(Constants.HEAD);
            if (headId == null) {
                return null;
            }
            RevCommit base = mergeBase(walk, walk.parseCommit(headId), walk.parseCommit(upstream));
            return base == null ? null : base.getName().substring(0, 12);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- versions

    /**
     * The three versions of a path: base, ours and theirs.
     */
    record VersionSet(String base, String ours, String theirs) {
    }

    /**
     * Read the three versions from the object database.
     *
     * <p>The merge base is found with a revision walk rather than by running a
     * merge, so a path that conflicts is not mistaken for a path that failed:
     * "these tips conflict" and "I could not work out the base" are different
     * answers and only the second should skip the file.
     *
     * <p>When the path does not exist in one of the trees the version is empty,
     * which is exactly what a merge would see for an added or deleted file.
     */
    Optional<VersionSet> readVersions(Repository repository, ObjectId upstream, String path) {
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId headId = repository.resolve(Constants.HEAD);
            if (headId == null) {
                return Optional.empty();
            }
            RevCommit ours = walk.parseCommit(headId);
            RevCommit theirs = walk.parseCommit(upstream);
            RevCommit base = mergeBase(walk, ours, theirs);

            String baseContent = readBlob(repository, base, path);
            String oursContent = readBlob(repository, ours, path);
            String theirsContent = readBlob(repository, theirs, path);

            if (oursContent.isEmpty() && theirsContent.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new VersionSet(baseContent, oursContent, theirsContent));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The best common ancestor of two commits, or {@code null} when they are
     * unrelated.
     */
    private static RevCommit mergeBase(RevWalk walk, RevCommit ours, RevCommit theirs)
        throws IOException {
        walk.reset();
        walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
        walk.markStart(ours);
        walk.markStart(theirs);
        return walk.next();
    }

    /**
     * Read a path's content from a commit, or the empty string when absent.
     */
    private static String readBlob(Repository repository, RevCommit commit, String path)
        throws IOException {
        if (commit == null) {
            return "";
        }
        try (org.eclipse.jgit.treewalk.TreeWalk treeWalk =
                 org.eclipse.jgit.treewalk.TreeWalk.forPath(repository, path, commit.getTree())) {
            if (treeWalk == null) {
                return "";
            }
            ObjectId blobId = treeWalk.getObjectId(0);
            ObjectLoader loader = repository.open(blobId);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Write the independently applicable resolutions back to the working tree.
     *
     * <p>Only files that exist, and only the resolutions proven disjoint from
     * everything that still needs attention, are written. Anything else keeps its
     * conflict markers so a human sees it.
     */
    private void writeResolved(Path relative, MergeConflictResolver.MergeReport report) {
        Path target = repositoryRoot.resolve(relative).normalize();
        if (!target.startsWith(repositoryRoot) || !Files.isRegularFile(target)) {
            return;
        }
        try {
            String original = Files.readString(target, StandardCharsets.UTF_8);
            Files.writeString(target, MergeBatch.applyTo(original, report.getIndependentlyApplicable()),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the resolution to " + target, e);
        }
    }

    private MergeBatch.Summary summarize(List<MergeConflictResolver.MergeReport> reports,
                                         List<String> skipped) {
        return MergeBatch.of(reports, dryRun).summarize();
    }

    private static Result emptyResult(String branchName, String reason) {
        return new Result(branchName, reason, null, List.of(), List.of(), List.of(),
            new MergeBatch.Summary(0, 0, 0, 0, 0, 0, 0, 0, true), true);
    }

    /**
     * The distinct paths across a set of reports, for callers that want a list.
     */
    public static List<String> distinctPaths(List<MergeConflictResolver.MergeReport> reports) {
        Set<String> paths = new LinkedHashSet<>();
        reports.forEach(report -> paths.add(report.getFilePath()));
        return Collections.unmodifiableList(new ArrayList<>(paths));
    }
}
