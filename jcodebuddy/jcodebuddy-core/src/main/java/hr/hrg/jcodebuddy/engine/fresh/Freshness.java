package hr.hrg.jcodebuddy.engine.fresh;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The engine's freshness contract: what a change invalidates, and whether a row is safe to read (DEC-037
 * decision 3, plan step 3.0g).
 *
 * <h3>What the engine holds, and what it caches</h3>
 *
 * <p>The only cached thing is the <strong>class index</strong> — one row per type, with the declaring file's
 * content identity — exactly what the last pass wrote. The engine keeps no parsed tree and no source text
 * between calls, so the invalidation unit is the row: a row is stale, and reading it is a mistake, while a
 * re-read of the file is always correct. That is what makes "is this metadata safe to read?" mechanical.</p>
 *
 * <h3>What invalidates a row</h3>
 *
 * <p>Two things, and the second is the one a naive cache misses:</p>
 *
 * <ol>
 *   <li><strong>Its own file.</strong> A row's checksum and size describe the file that declares it, so any
 *       change to that file stales its rows.</li>
 *   <li><strong>The types it names.</strong> Relations are stored as names (3.0b), so {@code Employee}'s row
 *       says {@code implements Person}. If {@code Person} is edited, removed or renamed, {@code Employee}'s row
 *       is no longer trustworthy <em>although {@code Employee.java} was never touched</em>. Those rows are
 *       {@link FreshnessEvent#dependents()}.</li>
 * </ol>
 *
 * <p>Because relations are recorded as written, a dependent is found by the FQN <em>and</em> by the simple
 * name: {@code Employee} may have written {@code Person} or {@code a.b.Person}. That is the honest limit of a
 * name-based relation — a rename elsewhere is only visible when the referencing file is re-read — and it is
 * recorded here, in DEC-029's relation amendment and in {@code subtypesOf}'s javadoc rather than papered over.
 * Resolving names is search's work (3.0h).</p>
 *
 * <h3>What this class does not do</h3>
 *
 * <p>It does not watch anything (DEC-038: the engine takes no watcher). The host observes a file and reports
 * it; the events below are what every consumer then shares instead of growing a watch loop of its own.</p>
 */
public final class Freshness {

    /** Whether a row is safe to read, and why not when it is not. */
    public enum Answer {

        /** The row is what the last pass read and nothing has touched it or a type it names. */
        SAFE,

        /** Something this row depends on changed, so reading it is a mistake until the next pass. */
        STALE,

        /** This name was never in the index, so the engine cannot say anything about it — which is not the same
         * as it not existing (the distinction 3.0f-3 exists for). */
        UNKNOWN
    }

    /** {@link Answer} with the reason, so a consumer can log a fact rather than "not safe". */
    public record State(Answer answer, String cause) {

        public boolean isSafe() {
            return answer == Answer.SAFE;
        }

        @Override
        public String toString() {
            return answer + ": " + cause;
        }
    }

    private final ClassIndex index;
    private final List<FreshnessListener> listeners = new ArrayList<>();
    private final Map<String, String> stale = new LinkedHashMap<>();

    private Freshness(ClassIndex index) {
        this.index = index;
    }

    /** The contract over one module's index. */
    public static Freshness forIndex(ClassIndex index) {
        if (index == null) {
            throw new IllegalArgumentException("freshness needs the index it is about");
        }
        return new Freshness(index);
    }

    /**
     * Subscribes a listener, and returns the way to stop listening.
     *
     * <p>The returned handle is an {@link AutoCloseable} so a caller can use try-with-resources without this
     * class inventing a second lifecycle vocabulary.</p>
     */
    public AutoCloseable subscribe(FreshnessListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("a subscription needs a listener");
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Reports a file the host observed, and tells every subscriber what it invalidated.
     *
     * <p>This is the one entry point for all three transitions: a new file, an edit and a removal differ only by
     * {@code kind}, and treating them as one path is what keeps a consumer from having to handle three shapes.
     * The rows named are the ones the index holds <em>now</em> — the table of the last pass — because an event
     * reports what stopped being trustworthy, not what a future pass will write.</p>
     */
    public FreshnessEvent report(ClassIndex.ChangeKind kind, String path) {
        if (kind == null) {
            throw new IllegalArgumentException("a change needs a kind (ClassIndex.ChangeKind)");
        }
        List<ClassRecord> rowsHere = path == null ? List.of() : index.byPath(path);
        List<String> rows = rowsHere.stream().map(ClassRecord::fqn).sorted().toList();

        Set<String> dependents = new TreeSet<>();
        Set<String> unresolved = new TreeSet<>();
        for (ClassRecord row : rowsHere) {
            for (ClassRecord dependent : index.subtypesOf(row.fqn())) {
                if (!dependent.path().equals(path)) {
                    dependents.add(dependent.fqn());
                }
            }
            // ... and by the simple name, because a relation is stored as written: `implements Person` does not
            // contain the FQN `a.b.Person`, so matching only the FQN would silently miss the commonest case.
            for (ClassRecord dependent : index.subtypesOf(simpleName(row.fqn()))) {
                if (!dependent.path().equals(path)) {
                    dependents.add(dependent.fqn());
                }
            }
            if (kind == ClassIndex.ChangeKind.REMOVED) {
                // A removed type leaves the rows that named it pointing at a name nothing declares. Reported
                // rather than dropped: "unresolved" is a fact about the metadata, and a consumer that treats it
                // as "no supertype" is back to the confident wrong answer.
                for (String name : List.of(row.fqn(), simpleName(row.fqn()))) {
                    if (!index.subtypesOf(name).isEmpty()) {
                        unresolved.add(name);
                    }
                }
            }
        }

        String cause;
        if (rowsHere.isEmpty()) {
            cause = "the index has no row for this path: it may be outside the sources the engine was given"
                    + " (a file with no types is the other reading, and both mean this event invalidates"
                    + " nothing)";
        } else if (kind == ClassIndex.ChangeKind.REMOVED) {
            cause = "the file is gone, so its rows are from the last pass; " + dependents.size()
                    + " row(s) name what it declared";
        } else {
            cause = "the file's content changed, so its rows are from the last pass; " + dependents.size()
                    + " row(s) name what it declares and are stale although their own files were not touched";
        }

        FreshnessEvent event = new FreshnessEvent(kind, path, rows, List.copyOf(dependents),
                List.copyOf(unresolved), cause);
        for (String fqn : rows) {
            stale.put(fqn, kind == ClassIndex.ChangeKind.REMOVED
                    ? "its file was removed (last read by the pass)"
                    : "its file changed (last read by the pass)");
        }
        for (String fqn : dependents) {
            stale.putIfAbsent(fqn, "a type it names changed: " + path);
        }
        for (FreshnessListener listener : List.copyOf(listeners)) {
            listener.changed(event);
        }
        return event;
    }

    /**
     * Whether the row for {@code fqn} is safe to read right now.
     *
     * <p>{@link Answer#UNKNOWN} is kept apart from {@link Answer#SAFE} on purpose: a name the engine never saw
     * is not a name it can vouch for, and answering "safe" for it would be the collapse 3.0f-3 removed from the
     * index's own lookups.</p>
     */
    public State stateOf(String fqn) {
        String why = fqn == null ? null : stale.get(fqn);
        if (why != null) {
            return new State(Answer.STALE, why);
        }
        if (fqn == null || !index.answer(fqn).isFound()) {
            return new State(Answer.UNKNOWN, "no row for this name in the index, so there is nothing to vouch"
                    + " for; a re-read of the source is the answer, not this table");
        }
        return new State(Answer.SAFE, "unchanged since the pass that wrote the table");
    }

    /** The FQNs this contract has marked stale, for a pass that wants to know what to re-read first. */
    public Set<String> staleRows() {
        return new LinkedHashSet<>(stale.keySet());
    }

    /** The last character after the last dot: how a relation is commonly written. */
    static String simpleName(String fqn) {
        int dot = fqn == null ? -1 : fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}
