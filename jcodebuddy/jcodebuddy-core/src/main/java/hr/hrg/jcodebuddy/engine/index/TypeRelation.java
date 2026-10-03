package hr.hrg.jcodebuddy.engine.index;

import java.util.Locale;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;

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
 *   <li><strong>The relation carries a name and a range, never the source text.</strong> Metadata is a
 *       <em>pointer into</em> the source, not a copy of it: a table that spelled
 *       {@code extends ChildContext<AppContext>} out would be a second copy of a line, and a second copy is
 *       what goes stale when the source moves on. The table already records the file and its checksum, so a
 *       consumer that needs the written form slices the file at the relation's range and the checksum tells it
 *       whether that slice is still the text the row was written from. The bare {@link #name()} is what a
 *       <em>match</em> wants ({@code ChildContext}), and it is derived from the written form at extraction time
 *       — never typed in by hand.</li>
 *   <li><strong>The clause is kept, because an interface's {@code extends} is not an {@code implements}.</strong>
 *       The LST holds an interface's {@code extends} clause in {@code getImplements()} — the trap DEC-030
 *       records — so this kind is what stops a relation diagram from drawing an {@code implements} edge from a
 *       declaration that cannot have one.</li>
 * </ul>
 *
 * @param name the supertype's name for matching — the written form without its type arguments
 * @param kind whether the name came from an {@code extends} or an {@code implements} clause
 * @param span the supertype's character range in the declaring file, or {@code null} when the walk recorded
 *             none — this is how the written form ({@code ChildContext<AppContext>}, type arguments included) is
 *             recovered from the source instead of being copied into the table (DEC-040 D2, plan step 3.0t)
 */
public record TypeRelation(String name, Kind kind, TreeQueries.SourceSpan span) {

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
    }

    /** A relation whose position the caller did not read — the span is unknown rather than invented. */
    public TypeRelation(String name, Kind kind) {
        this(name, kind, null);
    }

    /**
     * An {@code extends} relation, from the text as it is written in the source.
     *
     * <p>The bare name is derived here rather than passed, because the text is what the extraction actually
     * has: a caller cannot record a relation whose name disagrees with the declaration's spelling. The text
     * itself is <strong>not stored</strong> — {@code span} points at it, so a consumer that needs the written
     * form slices the file and the row's checksum says whether that slice is still current.</p>
     */
    public static TypeRelation extendsType(String writtenText) {
        return new TypeRelation(TypeFacts.withoutTypeArguments(writtenText), Kind.EXTENDS);
    }

    /** An {@code implements} relation, from the text as written — see {@link #extendsType(String)}. */
    public static TypeRelation implementsType(String writtenText) {
        return new TypeRelation(TypeFacts.withoutTypeArguments(writtenText), Kind.IMPLEMENTS);
    }

    /** {@link #extendsType(String)} with the range the written form sits at. */
    public static TypeRelation extendsType(String writtenText, TreeQueries.SourceSpan span) {
        return new TypeRelation(TypeFacts.withoutTypeArguments(writtenText), Kind.EXTENDS, span);
    }

    /** {@link #implementsType(String)} with the range the written form sits at. */
    public static TypeRelation implementsType(String writtenText, TreeQueries.SourceSpan span) {
        return new TypeRelation(TypeFacts.withoutTypeArguments(writtenText), Kind.IMPLEMENTS, span);
    }
}
