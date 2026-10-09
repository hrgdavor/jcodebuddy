package hr.hrg.jcodebuddy.automation.watch;

import hr.hrg.jcodebuddy.engine.index.Staleness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The watch entry of plan step 6.6: the tiered staleness gate applied to one file-event batch.
 *
 * <p>The maintainer's design names three entries onto one question — watch mode, an on-demand command and the
 * generator pass — and requires the answer to be one implementation. {@link Staleness} is that implementation;
 * this class is how a watcher reaches it, so a batch's files are judged by the same stat-then-hash ladder the
 * {@code metadata stale} command uses rather than by a second opinion invented in the watch loop.</p>
 *
 * <h3>What it adds to a batch that a content snapshot does not</h3>
 * <p>{@link WatchedRegeneration} already decides <em>whether to run a pass</em>, using SHA-1 snapshots held in
 * memory. That is a different question, and this class does not replace it: the snapshot answers "does this tree
 * differ from what my last pass wrote" within one process, while the gate answers "do the stored metadata entries
 * still describe these files" against state on disk that survives the process. A watch loop that only had the
 * snapshot would re-derive everything after a restart, and a tool that only had the gate would regenerate on its
 * own output. Both are wanted, and the split is deliberate: this class decides <em>what is stale</em>, the
 * caller decides what to run.</p>
 *
 * <h3>Deletions and new files</h3>
 * <p>A path that no longer exists, or one that has no entry, must be treated as needing work — a deleted source
 * can mean a generated file has to go, and a new one has never been generated. {@link Staleness#check} answers
 * both as {@link Staleness.Verdict#UNKNOWN}, which this class reads as "needs work" for the same reason the
 * command exits 1 on it.</p>
 */
public final class StalenessWatch {

    /**
     * The outcome of one batch, for a caller that wants to log or assert it rather than infer it.
     *
     * @param checked     every path the batch carried, in batch order, deduplicated
     * @param stale       the subset whose entry no longer describes the file
     * @param results     the verdict per checked path, so the cause is available and not just the boolean
     * @param regenerated whether the caller's action ran
     */
    public record Batch(List<String> checked, List<String> stale, Map<String, Staleness.Result> results,
                        boolean regenerated) {
        public Batch {
            checked = List.copyOf(checked);
            stale = List.copyOf(stale);
            results = Map.copyOf(results);
        }
    }

    /** What to do when the batch has something stale in it. A no-op action is allowed. */
    @FunctionalInterface
    public interface Action {
        void run(List<String> stalePaths);
    }

    private final Staleness staleness;
    private final Path moduleRoot;
    private final Action action;
    private final Consumer<Batch> observer;
    private long batches;

    public StalenessWatch(Staleness staleness, Path moduleRoot, Action action, Consumer<Batch> observer) {
        if (staleness == null || moduleRoot == null) {
            throw new IllegalArgumentException("a staleness gate and a module root are required");
        }
        this.staleness = staleness;
        this.moduleRoot = moduleRoot.toAbsolutePath().normalize();
        this.action = action == null ? stale -> { } : action;
        this.observer = observer == null ? batch -> { } : observer;
    }

    /**
     * Judges one batch and runs the action when anything in it is stale.
     *
     * <p>Every path is checked, including the ones the caller believes are generated output: deciding which files
     * may be skipped is not this class's business, and a caller that wants to narrow the batch filters it before
     * calling — which keeps the rule visible at the call site instead of inside the gate.</p>
     *
     * @param changedPaths the absolute or module-relative paths the batch carried
     */
    public Batch onBatch(List<Path> changedPaths) {
        batches++;
        List<String> checked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> stale = new ArrayList<>();
        Map<String, Staleness.Result> results = new LinkedHashMap<>();
        for (Path path : changedPaths == null ? List.<Path>of() : changedPaths) {
            String key = Staleness.moduleRelativePath(moduleRoot, path);
            if (!seen.add(key)) {
                continue;
            }
            checked.add(key);
            Staleness.Result result = staleness.check(key, path);
            results.put(key, result);
            if (result.rebuildNeeded()) {
                stale.add(key);
            }
        }
        if (!stale.isEmpty()) {
            action.run(List.copyOf(stale));
        }
        Batch batch = new Batch(checked, stale, results, !stale.isEmpty());
        observer.accept(batch);
        return batch;
    }

    /** How many batches were judged — the counter the "watch and CLI agree" test asserts over. */
    public long batchCount() {
        return batches;
    }

    /**
     * The same verdict the on-demand command would print for one path, so the two entries cannot disagree.
     *
     * <p>It exists because "watch mode and the CLI give the same answer for the same tree" is the step's own
     * acceptance line, and asserting it needs one call that is literally both: this delegates to the same
     * {@link Staleness}, and the test compares it with a run of the command.</p>
     */
    public Staleness.Result check(Path path) throws IOException {
        Path absolute = path.isAbsolute() ? path : moduleRoot.resolve(path);
        return staleness.check(Staleness.moduleRelativePath(moduleRoot, absolute), absolute);
    }
}
