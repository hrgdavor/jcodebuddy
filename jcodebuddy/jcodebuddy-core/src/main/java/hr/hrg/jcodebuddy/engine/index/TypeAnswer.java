package hr.hrg.jcodebuddy.engine.index;

/**
 * What the engine can say about a type: a fact, or that it cannot answer.
 *
 * <p>The rule this type exists for is DEC-037's, and there is a real failure behind it: <strong>a missing
 * answer is reported, never inferred as absent</strong>. Before this type the only way to ask the index about
 * a type was {@link ClassIndex#row(String)}, and its {@code null} means two different things — "this index has
 * no such type" and "this type is not something this index covers". The second case is the common one: a JDK
 * type, a type declared in another module, a type the pass has not reached yet. A consumer that reads that
 * {@code null} as "no such type" produces a confident wrong answer, which is precisely what the hipster-ioc
 * prototype did in step 3.2 (it looked for an interface, found nothing because it never read the file, and
 * emitted wiring with no factories rather than an error). {@link ClassIndex#byPath(String)} has the same shape
 * today: an empty list means either "the file declares nothing" or "the file is not in this index".</p>
 *
 * <p>So an answer is one of two things, and a caller has to handle both:</p>
 *
 * <ul>
 *   <li>{@link Found} — the index holds the row, and a consumer may treat it as a fact;</li>
 *   <li>{@link NotIndexed} — the engine <em>cannot answer</em>, and the cause says why. A consumer's job here
 *       is to report it (through {@link hr.hrg.jcodebuddy.engine.DiagnosticSink}) or to fall back to the parse
 *       path, never to conclude that the type, member or relation does not exist.</li>
 * </ul>
 *
 * <p>What this type does <em>not</em> yet carry is deliberate. A {@link ClassRecord} has the declaration's
 * facts (kind, modifiers, enclosing chain, line, file, checksum) and, since 3.0b, its {@code relations} — the
 * supertypes as written, with the reverse direction answered by {@link ClassIndex#subtypesOf(String)}. It has
 * no <em>members</em>: asking for those today must answer {@code NotIndexed} with a cause that says so, rather
 * than an empty list that reads like "none" — the same mistake in a new place, and the reason this contract
 * exists before the facts do (3.0h grows the model; this type is what keeps that growth honest).</p>
 */
public sealed interface TypeAnswer permits TypeAnswer.Found, TypeAnswer.NotIndexed {

    /** The index holds the row: this is a fact a consumer may act on. */
    record Found(ClassRecord type) implements TypeAnswer {
    }

    /**
     * The engine cannot answer this question about this name.
     *
     * @param fqn   the name that was asked about
     * @param cause why the engine cannot answer — never phrased as "it does not exist"
     */
    record NotIndexed(String fqn, String cause) implements TypeAnswer {
    }

    /** A found answer. */
    static TypeAnswer found(ClassRecord type) {
        return new Found(type);
    }

    /** An answer the engine cannot give. The cause must say what is missing, not that the type is absent. */
    static TypeAnswer notIndexed(String fqn, String cause) {
        return new NotIndexed(fqn, cause);
    }

    /** Whether the engine answered with a fact. */
    default boolean isFound() {
        return this instanceof Found;
    }

    /** The name that was asked about, whether or not the engine could answer — the one field both cases carry,
     * so a caller can report a question it could not get an answer to. */
    default String fqn() {
        return switch (this) {
            case Found found -> found.type().fqn();
            case NotIndexed missing -> missing.fqn();
        };
    }

    /** The row, for a caller that has already established {@link #isFound()}. */
    default ClassRecord type() {
        if (this instanceof Found found) {
            return found.type();
        }
        throw new IllegalStateException("no answer to give: " + describe());
    }

    /** One line for a diagnostic: the fact, or the reason there is none. */
    default String describe() {
        return switch (this) {
            case Found found -> found.type().fqn() + " is indexed as a " + found.type().kind()
                    + " in " + found.type().path() + ":" + found.type().line();
            case NotIndexed missing -> "cannot answer for " + missing.fqn() + ": " + missing.cause();
        };
    }
}
