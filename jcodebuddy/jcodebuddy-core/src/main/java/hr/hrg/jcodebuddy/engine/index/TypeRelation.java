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
 *   <li><strong>Type arguments are not part of the relation.</strong> {@code extends Identifiable<Long>} is a
 *       relation to {@code Identifiable}; the argument is source text, and
 *       {@link hr.hrg.jcodebuddy.engine.source.TreeQueries#supertypeTexts} is the read for a caller that needs
 *       it. Keeping it here would put source text in a table whose whole point is names.</li>
 *   <li><strong>The clause is kept, because an interface's {@code extends} is not an {@code implements}.</strong>
 *       The LST holds an interface's {@code extends} clause in {@code getImplements()} — the trap DEC-030
 *       records — so this kind is what stops a relation diagram from drawing an {@code implements} edge from a
 *       declaration that cannot have one.</li>
 * </ul>
 *
 * @param name the supertype's name as written in the declaration, without type arguments
 * @param kind whether the name came from an {@code extends} or an {@code implements} clause
 */
public record TypeRelation(String name, Kind kind) {

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

    /** An {@code extends} relation. */
    public static TypeRelation extendsType(String name) {
        return new TypeRelation(name, Kind.EXTENDS);
    }

    /** An {@code implements} relation. */
    public static TypeRelation implementsType(String name) {
        return new TypeRelation(name, Kind.IMPLEMENTS);
    }
}
