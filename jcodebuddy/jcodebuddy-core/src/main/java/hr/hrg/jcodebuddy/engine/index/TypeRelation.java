package hr.hrg.jcodebuddy.engine.index;

import java.util.Locale;

/**
 * One relation from a declaring type to a supertype: the name as it is written, and which clause it came from
 * (DEC-029's relation half, plan step 3.0b).
 *
 * <p>Three decisions are in this record, and each has a reason a reader should not have to guess:</p>
 *
 * <ul>
 *   <li><strong>The name is the one written in the source, not a resolved FQN.</strong> Resolution needs the
 *       whole index and the declaring file's imports, while a row is a fact about one declaration; storing a
 *       resolved name would also make rows change when an unrelated type appears. So the table records what
 *       the source says ({@code Person}, {@code java.io.Serializable}, {@code EntityBase}) and the reverse
 *       lookup compares that spelling. It follows that a simple name is matched as written — a consumer
 *       asking "who implements {@code a.b.CtxModule}" must ask in the same spelling the sources use, which is
 *       the gap search (3.0h) closes rather than something a row pretends to know.</li>
 *   <li><strong>Type arguments ARE part of the relation, and the bare name is derived from them.</strong>
 *       {@code extends ChildContext<AppContext>} records the text it was written with, because type arguments
 *       are exactly what the compiler erases — a model that dropped them would be no better positioned than
 *       runtime code ([DEC-040](../../../../../../doc-hipster-entity/architecture/decisions/DEC-040.md), D1 and
 *       D2). What a <em>match</em> wants is the bare name ({@code ChildContext}), so {@link #name()} is that
 *       form, derived from the text and never the reverse: the text is the guaranteed fact, and a name-only
 *       table (written before this field existed) reads as text == name rather than pretending to more.</li>
 *   <li><strong>The clause is kept, because an interface's {@code extends} is not an {@code implements}.</strong>
 *       The LST holds an interface's {@code extends} clause in {@code getImplements()} — the trap DEC-030
 *       records — so this kind is what stops a relation diagram from drawing an {@code implements} edge from a
 *       declaration that cannot have one.</li>
 * </ul>
 *
 * @param name the supertype's name for matching — the text without its type arguments
 * @param kind whether the name came from an {@code extends} or an {@code implements} clause
 * @param text the supertype as the source wrote it, type arguments included — the fact this record exists for
 */
public record TypeRelation(String name, Kind kind, String text) {

    /** Which clause the name came from. */
    public enum Kind {

        /** An {@code extends} clause — including an <em>interface's</em> {@code extends}, which the LST holds
         * in {@code getImplements()}. */
        EXTENDS,

        /** An {@code implements} clause, which only a class, enum or record can have. */
        IMPLEMENTS;

        /** The spelling a table carries, so the format is not a Java enum name. */
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The kind a table's spelling names, or {@code null} when it names something this contract has no
         * case for — a reader that gets {@code null} must refuse the table rather than guess. */
        public static Kind fromJson(String json) {
            if (json == null) {
                return null;
            }
            for (Kind kind : values()) {
                if (kind.json().equals(json)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public TypeRelation {
        name = name == null ? "" : name;
        kind = kind == null ? Kind.EXTENDS : kind;
        // A relation with no text is one a caller built from a name alone (and a table written before the
        // field existed reads exactly that way): the name is then all the source is known to have said.
        text = text == null || text.isEmpty() ? name : text;
    }

    /** The two-part form: a caller that has no separate text — the name is all the source said. */
    public TypeRelation(String name, Kind kind) {
        this(name, kind, name);
    }

    /**
     * An {@code extends} relation, from the text as written.
     *
     * <p>The bare name is derived here rather than passed, which is DEC-040's D2 in one line: the text is the
     * fact, the name is what a match wants, and a caller cannot accidentally store one without the other.</p>
     */
    public static TypeRelation extendsType(String writtenText) {
        return new TypeRelation(TypeFacts.withoutTypeArguments(writtenText), Kind.EXTENDS, writtenText);
    }

    /** An {@code implements} relation, from the text as written — see {@link #extendsType(String)}. */
    public static TypeRelation implementsType(String writtenText) {
        return new TypeRelation(TypeFacts.withoutTypeArguments(writtenText), Kind.IMPLEMENTS, writtenText);
    }
}
