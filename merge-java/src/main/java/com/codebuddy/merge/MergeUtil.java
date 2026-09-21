// {@link com.codebuddy.merge.MergeUtil} Convenience entry point for resolving merge conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Convenience facade over {@link MergeConflictResolver}.
 *
 * <p>This is <b>not</b> a Maven plugin, build extension or CLI. It is an
 * ordinary library class offering a one-call API for the common case:
 *
 * <pre>{@code
 * MergeUtil util = MergeUtil.create(
 *     TypeContext.of(Path.of("src/main/java"), classpath));
 *
 * MergeConflictResolver.MergeReport report = util.resolve(
 *     "src/main/java/com/example/Payment.java",
 *     "Base.java", "Ours.java", "Theirs.java",
 *     ".jcodebuddy/merge-history/feature-payments/");
 * }</pre>
 *
 * <h2>Why a type context</h2>
 *
 * <p>The default resolver set includes {@link OverloadAddConflictResolver}, which
 * decides whether two methods are the same overload by comparing <em>resolved</em>
 * parameter types. It therefore declares
 * {@link ConflictResolver#requiresTypeContext()} and cannot be built without a
 * context. That is the only resolver with the requirement: placing imports,
 * merging comments and the rest need nothing but the text.
 *
 * <p>For anything beyond the common case, build a {@link MergeConflictResolver}
 * directly so that resolvers, history location and branch name can be controlled
 * independently.
 */
public final class MergeUtil {

    /** Branch name used for resolutions that must not touch the history store. */
    public static final String IN_MEMORY_BRANCH = "in-memory";

    private final TypeContext typeContext;

    private MergeUtil(TypeContext typeContext) {
        this.typeContext = typeContext;
    }

    /**
     * Create a utility bound to the default resolver set.
     *
     * @param typeContext context for the resolvers that need to resolve types;
     *                    required because the default set includes one
     */
    public static MergeUtil create(TypeContext typeContext) {
        return new MergeUtil(typeContext);
    }

    /**
     * Detect and resolve every conflict in one file, reading the three versions
     * from disk.
     *
     * @param filePath    repository-relative path of the file being merged
     * @param basePath    path to the merge-base version
     * @param branch1Path path to "ours"
     * @param branch2Path path to "theirs"
     * @param historyPath directory where this branch's decisions are remembered
     */
    public MergeConflictResolver.MergeReport resolve(String filePath,
                                                     String basePath,
                                                     String branch1Path,
                                                     String branch2Path,
                                                     String historyPath) {
        Path history = Paths.get(historyPath);
        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName(history.getFileName() == null
                ? "unknown" : history.getFileName().toString())
            .setHistoryPath(history)
            .setTypeContext(typeContext)
            .build();
        return resolver.resolveFiles(filePath, basePath, branch1Path, branch2Path);
    }

    /**
     * Detect and resolve a file whose three versions are already in memory.
     *
     * <p>This is a pure, side-effect-free operation: a branch named
     * {@value #IN_MEMORY_BRANCH} is used and no history is read or written, so
     * repeated calls with the same input always give the same answer. Use the
     * history-aware overload when decisions should be remembered.
     */
    public MergeConflictResolver.MergeReport resolve(String filePath,
                                                     String baseCode,
                                                     String branch1Code,
                                                     String branch2Code) {
        return new MergeConflictResolver.Builder()
            .setBranchName(IN_MEMORY_BRANCH)
            .setResolvers(ConflictResolvers.defaultResolvers())
            .setTypeContext(typeContext)
            .setInMemoryOnly(true)
            .build()
            .resolve(filePath, baseCode, branch1Code, branch2Code);
    }

    /**
     * A resolver with the default resolver set, in memory only.
     */
    public MergeConflictResolver createResolver() {
        return new MergeConflictResolver.Builder()
            .setBranchName(IN_MEMORY_BRANCH)
            .setInMemoryOnly(true)
            .setTypeContext(typeContext)
            .build();
    }

    /**
     * A resolver for a specific branch, using the default resolver set.
     */
    public MergeConflictResolver createResolver(String branchName) {
        return new MergeConflictResolver.Builder()
            .setBranchName(branchName)
            .setTypeContext(typeContext)
            .build();
    }

    /**
     * The fix paths offered for a conflict type.
     */
    public List<FixPath> fixPathsFor(ConflictType type) {
        return createResolver().fixPathsFor(type);
    }

    /**
     * The type context this utility supplies to resolvers that need one.
     */
    public TypeContext getTypeContext() {
        return typeContext;
    }
}
