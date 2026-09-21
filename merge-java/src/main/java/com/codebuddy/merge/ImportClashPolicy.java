// {@link com.codebuddy.merge.ImportClashPolicy} What to do when one branch adds an import the other removed.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

/**
 * What to do about the one genuinely ambiguous case in an import conflict: one
 * branch adds an import that the other branch removed.
 *
 * <h2>Why this is configurable</h2>
 *
 * <p>Every other import situation has a single correct answer. Both adding the same
 * import is agreement; two branches adding different imports is the union. Only the
 * add-versus-remove case involves a judgement, and the two branches were usually
 * reasoning about different things: whichever branch removed the import may have
 * been tidying an unused one, while the other was adding a use for it.
 *
 * <p>The module does not pretend to know which happened, so it exposes the choice
 * and defaults to the safer side rather than guessing.
 */
public enum ImportClashPolicy {

    /**
     * Keep the import. <b>The default.</b>
     *
     * <p>Adding is the conservative direction for imports: an unused import is a
     * warning at most and breaks nothing, whereas removing an import that the other
     * branch's code relies on is a compile error. When the two branches were
     * thinking about different things, keeping the symbol is the choice that cannot
     * break a use the tool cannot see.
     */
    ADDITION_WINS,

    /**
     * Drop the import.
     *
     * <p>For teams that treat a removed import as deliberate - typically because the
     * branch that removed it was cleaning up, and a stale import is unwelcome even
     * though it compiles. Choose this only when that intent can be assumed, because
     * it will break a use on the other branch if there is one.
     */
    REMOVAL_WINS;

    /**
     * The default policy, named so callers and tests refer to one place.
     */
    public static ImportClashPolicy defaultPolicy() {
        return ADDITION_WINS;
    }

    /**
     * True when this policy keeps the import.
     */
    public boolean keepsTheImport() {
        return this == ADDITION_WINS;
    }

    /**
     * A one-line rationale, for the explanation shown to a reviewer.
     */
    public String rationale() {
        return switch (this) {
            case ADDITION_WINS -> "keeping the import, because an unused import is "
                + "harmless while a dropped one breaks any use of it";
            case REMOVAL_WINS -> "honouring the removal, on the basis that a removed "
                + "import was deliberate";
        };
    }
}
