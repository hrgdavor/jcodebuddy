package hr.hrg.jcodebuddy.engine.fresh;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;

import java.util.List;

/**
 * One change to the sources, as a consumer needs to hear it (plan step 3.0g).
 *
 * <p>The hard part of freshness is not noticing that a file changed; it is knowing <strong>what else stopped
 * being true</strong>. A row depends on the file that declares it <em>and on the types it names</em>, so an edit
 * to {@code Person.java} stales {@code Employee}'s row even though {@code Employee.java} was never touched. That
 * second dependency is why this type carries {@link #dependents} rather than only {@link #rows}, and it is
 * computed from the relations 3.0b put in the index ({@code ClassIndex.subtypesOf}) rather than by parsing
 * anything.</p>
 *
 * @param kind       what the host reported. {@link ClassIndex.ChangeKind} is reused deliberately: the index
 *                   already reports these transitions in {@code changedSince}, and two vocabularies for "what
 *                   happened to a type" is how two parts of one engine start disagreeing about a rename
 * @param path       the source file, module-relative with forward slashes — the same spelling rows use
 * @param rows       the FQNs the index holds for that path. Empty means the index has no row for this file,
 *                   which {@link #cause} says out loud: a file outside the sources the engine was given is not
 *                   a file with no types
 * @param dependents the FQNs whose rows name one of {@link #rows} as a supertype — stale although their own
 *                   file did not change. Sorted, and never including a row of this event's own file
 * @param unresolved names that surviving rows still reference and that no longer have a declaring row, after a
 *                   removal. Empty when nothing is left dangling
 * @param cause      one line saying why this event says what it says, including when it can say nothing
 */
public record FreshnessEvent(ClassIndex.ChangeKind kind, String path, List<String> rows,
                             List<String> dependents, List<String> unresolved, String cause) {

    public FreshnessEvent {
        rows = rows == null ? List.of() : List.copyOf(rows);
        dependents = dependents == null ? List.of() : List.copyOf(dependents);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    /**
     * Whether this event invalidated anything at all.
     *
     * <p>{@code false} is a real answer and reads as "the engine has nothing to say about this file", which is
     * why {@link #cause} is never empty: an event that silently invalidated nothing would be
     * indistinguishable from a change nobody reported.</p>
     */
    public boolean invalidatesAnything() {
        return !rows.isEmpty() || !dependents.isEmpty();
    }

    /** One line for a log or a diagnostic, in DEC-022's spirit: what, where, and the consequence. */
    public String describe() {
        return kind + " " + path + " -> rows=" + rows + " dependents=" + dependents
                + (unresolved.isEmpty() ? "" : " unresolved=" + unresolved) + " (" + cause + ")";
    }
}
