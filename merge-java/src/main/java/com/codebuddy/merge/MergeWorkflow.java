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
    public record Result(String branchName, String upstreamRef, String mergeBase, String baseSource,
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
                .append(" (base: ").append(baseSource)
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

            Path historyRoot = repositoryRoot.resolve(".jcodebuddy")
                .resolve("merge-history").resolve(branchName);

            // "base" here means the last-synced upstream state, not Git's merge base.
            // A recorded marker is authoritative because it is a fact about this
            // branch's history and survives a rebase; the merge base is only derived
            // from the commit graph and silently changes when the graph is rewritten.
            // It is used solely to establish a base for a branch that has never
            // synced, where there is nothing to have recorded yet.
            Optional<LastSyncMarker> marker = LastSyncMarker.read(historyRoot);
            ObjectId recordedCommit = marker
                .map(recorded -> recordedBase(repository, historyRoot, recorded))
                .orElse(null);

            // First sync of a branch: nothing has been recorded, so the only available
            // notion of "what the upstream looked like last time" is the common ancestor.
            // Every later sync uses the recorded marker instead.
            ObjectId reference = recordedCommit != null
                ? recordedCommit
                : deriveInitialBase(repository, upstream);
            String baseSource = recordedCommit != null
                ? "recorded last-sync marker " + marker.get().shortCommit()
                : "merge base, no sync recorded yet";

            List<String> targets = paths.isEmpty() ? List.of() : paths;
            List<MergeConflictResolver.MergeReport> reports = new ArrayList<>();
            List<String> resolved = new ArrayList<>();
            List<String> attention = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            MergeConflictResolver resolver = new MergeConflictResolver.Builder()
                .setBranchName(branchName)
                .setHistoryPath(historyRoot)
                .setTypeContext(typeContext)
                .build();

            for (String path : targets) {
                Optional<VersionSet> versions = readVersions(repository, upstream, reference, path);
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

            // Record what this branch has now seen, so the next run resolves against the
            // last-synced upstream rather than against a merge base recomputed from the
            // commit graph. Also recorded on a dry run, because the marker describes
            // which upstream commit was inspected, not that a merge happened.
            try {
                LastSyncMarker.of(upstream.getName(), upstreamName,
                    dryRun ? "inspected by a dry run" : "synced").write(historyRoot);
            } catch (UncheckedIOException e) {
                // Failing to record must not fail the merge: the next run simply falls
                // back to a merge base, which is what it would have done anyway.
            }

            return new Result(branchName, upstreamName, mergeBaseOf(repository, upstream), baseSource,
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
     * <p>The base is <b>not</b> Git's merge base. It is the last-synced upstream
     * state: the {@code reference} the caller resolved, which is the recorded sync
     * marker when one exists and only falls back to the merge base on a branch that
     * has never synced. See {@code docs/WHAT_IS_BASE.md} - substituting an older base
     * makes the upstream look as though it re-added everything already merged.
     *
     * <p>When the path does not exist in one of the trees the version is empty,
     * which is exactly what a merge would see for an added or deleted file.
     */
    Optional<VersionSet> readVersions(Repository repository, ObjectId upstream,
                                      ObjectId reference, String path) {
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId headId = repository.resolve(Constants.HEAD);
            if (headId == null) {
                return Optional.empty();
            }
            RevCommit ours = walk.parseCommit(headId);
            RevCommit theirs = walk.parseCommit(upstream);
            RevCommit base = walk.parseCommit(reference);

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
     * The base to use for a branch that has never recorded a sync.
     *
     * <p>Only reached on a first sync, where there is nothing recorded to consult. The
     * common ancestor of the two tips is the best available stand-in for "the upstream
     * as this branch last saw it", because it is by definition the point where the two
     * histories last agreed.
     *
     * <p>Falls back to the upstream tip when the histories are unrelated, which means
     * the branch shares no ancestor with the upstream - there is genuinely nothing to
     * compare against, and treating the upstream as unchanged is the only reading that
     * cannot invent a conflict.
     */
    private static ObjectId deriveInitialBase(Repository repository, ObjectId upstream) {
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId headId = repository.resolve(Constants.HEAD);
            if (headId == null) {
                return upstream;
            }
            RevCommit base = mergeBase(walk, walk.parseCommit(headId), walk.parseCommit(upstream));
            return base == null ? upstream : base;
        } catch (IOException e) {
            return upstream;
        }
    }

    /**
     * The commit a recorded marker names, refusing anything this repository would have
     * to <em>interpret</em>.
     *
     * <p>The format half is {@link LastSyncMarker#requireCommitId(Path)}'s: a marker
     * holding a branch name, a tag or a revision expression is an error, because such a
     * name means whatever it points at when it is read and the base would move with it.
     *
     * <p>This half is the repository's. The id must name a commit the repository can
     * actually read. A valid object that is a blob or a tree would fail
     * {@code parseCommit} inside {@link #readVersions}, which reports an unreadable
     * version by returning empty - the path would then be quietly <em>skipped</em>, and a
     * run whose only path was skipped can still look resolved. Checking here keeps that
     * from being mistaken for "nothing to do".
     *
     * <p>Deliberately does not ask <em>which</em> commit: the base is allowed to be a
     * commit the upstream no longer contains, because that is what a rebase leaves
     * behind and the marker exists precisely to survive it. Only the marker's ability to
     * name a commit is checked, never its choice of one. Recording the upstream tip
     * after a sync - which the workflow itself does, below - is a normal and correct
     * state in which the base <em>does</em> equal the upstream.
     *
     * @throws SyncMarkerException when the marker's commit is unusable in this repository
     */
    private static ObjectId recordedBase(Repository repository, Path historyRoot,
                                        LastSyncMarker marker) {
        ObjectId commit = marker.requireCommitId(historyRoot);
        try (RevWalk walk = new RevWalk(repository)) {
            walk.parseCommit(commit);
            return commit;
        } catch (IOException e) {
            throw new SyncMarkerException("the sync marker at "
                + LastSyncMarker.pathFor(historyRoot) + " records " + marker.shortCommit()
                + ", which this repository cannot read as a commit ("
                + e.getClass().getSimpleName() + ": " + e.getMessage() + "). A base that"
                + " cannot be read is not the same as a branch that never synced, so this is"
                + " refused rather than quietly skipped. Write a commit id this repository"
                + " has, or delete the marker to start again from a merge base.");
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
        return new Result(branchName, reason, null, "none", List.of(), List.of(), List.of(),
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
