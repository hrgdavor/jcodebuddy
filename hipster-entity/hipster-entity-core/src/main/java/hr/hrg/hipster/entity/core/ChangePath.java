package hr.hrg.hipster.entity.core;

import hr.hrg.hipster.entity.api.FieldDef;

import java.util.List;

/**
 * One step of a <strong>deep</strong> change path (plan.dsflash § 11/6.2).
 *
 * <p>A shallow change is "the field at this ordinal changed". A deep change is "the field at this
 * ordinal changed, and the change is <em>inside</em> the nested tracked value it holds" — possibly
 * several levels down, and possibly inside a collection element. A {@code ChangePath} is one link
 * of that chain, so a list of paths is a navigable description of every deep change.</p>
 *
 * <p>Deliberately an ordinary record with public accessors, not an opaque handle: walking it is a
 * direct Java call the IDE can follow (AGENTS.md § 1 / DEC-019).</p>
 *
 * @param field     the field at this level
 * @param listIndex the index inside a {@code List} field, or {@code -1} when this level is not a
 *                  collection element
 * @param next      the rest of the path, or {@code null} at the leaf — i.e. at the field that
 *                  actually changed
 */
public record ChangePath(FieldDef field, int listIndex, ChangePath next) {

    /** A path that is not a collection element. */
    public static ChangePath of(FieldDef field) {
        return new ChangePath(field, -1, null);
    }

    /** A path that is a collection element. */
    public static ChangePath of(FieldDef field, int listIndex) {
        return new ChangePath(field, listIndex, null);
    }

    /** This path extended by one more level. */
    public ChangePath then(FieldDef nextField) {
        return new ChangePath(field, listIndex, ChangePath.of(nextField));
    }

    /** Whether this level addresses a collection element rather than a whole field. */
    public boolean isListElement() {
        return listIndex >= 0;
    }

    /** The leaf field of the chain — the field that actually changed. */
    public FieldDef leaf() {
        ChangePath current = this;
        while (current.next() != null) {
            current = current.next();
        }
        return current.field();
    }

    /** The depth of the chain; 1 for a leaf-only path. */
    public int depth() {
        int depth = 1;
        ChangePath current = this;
        while (current.next() != null) {
            depth++;
            current = current.next();
        }
        return depth;
    }

    /** This path rendered as {@code outer.inner[3].leaf}, for messages and tests. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        ChangePath current = this;
        while (current != null) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(current.field() == null ? "?" : current.field().name());
            if (current.isListElement()) {
                sb.append('[').append(current.listIndex()).append(']');
            }
            current = current.next();
        }
        return sb.toString();
    }
}
