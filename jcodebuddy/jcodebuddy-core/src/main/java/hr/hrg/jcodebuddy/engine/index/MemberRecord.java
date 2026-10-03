package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * One member of a type declaration, as a class index row records it (DEC-029's member field, plan step 3.0r).
 *
 * <p>What a row records is what the source <em>says</em>, never what it means: a member's type is the spelling
 * the declaration used ({@code List<String>}, {@code var}, {@code T}), unresolvable here for the same reason a
 * relation's name is — resolving needs the whole index and the imports, which is search's work (3.0h). A method
 * body is never recorded: the index answers "what shape does this declaration have", and a body would be the
 * file's content in a second place, stale the moment either copy moves.</p>
 *
 * <p>{@code type} means what the member's kind makes it mean — one field rather than three that are empty for
 * two kinds out of four:</p>
 *
 * <ul>
 *   <li>{@link Kind#FIELD} — the declared type as written, type arguments included;</li>
 *   <li>{@link Kind#METHOD} — the return type as written ({@code void} for a method that returns nothing);</li>
 *   <li>{@link Kind#CONSTRUCTOR} — empty: a constructor declares no return type, and its name is the type's;</li>
 *   <li>{@link Kind#NESTED} — the nested type's simple name; its own row is keyed by the full name.</li>
 * </ul>
 *
 * <p><strong>What this deliberately does not record:</strong> enum constants (an enum's constants are
 * {@code J.EnumValue} statements, not variables) and initialisers or bodies of any kind. Both are facts this
 * table has no contract for, and a consumer that needs them asks the parse path — an absence here is a stated
 * limit, not an empty answer.</p>
 *
 * @param name           the member's name as written
 * @param kind           the member's kind — {@link Kind}, a closed vocabulary the reader refuses to extend
 * @param type           the type this member declares, in the sense the list above defines
 * @param parameterTypes a method's or constructor's parameter types as written, in order; empty otherwise
 * @param modifiers      the member's Java modifier keywords, sorted and restricted to
 *                       {@link TypeFacts#KEYWORDS}, so a reordered modifier list is not a diff
 * @param annotations    the annotations on the member, as written with their arguments as written
 *                       ({@link TypeAnnotation}), in declaration order; empty when it carries none
 */
public record MemberRecord(String name, Kind kind, String type, List<String> parameterTypes,
                           List<String> modifiers, List<TypeAnnotation> annotations) {

    /**
     * The member kinds this table has a contract for.
     *
     * <p>Closed on purpose, and read the same way {@link TypeRelation.Kind} is: an unknown kind in a table makes
     * the reader refuse the table rather than treat the member as absent, because "I do not know this kind" and
     * "this member does not exist" must not read alike.</p>
     */
    public enum Kind {
        FIELD("field"),
        METHOD("method"),
        CONSTRUCTOR("constructor"),
        NESTED("nested");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        /** The spelling this table's format uses. */
        public String json() {
            return json;
        }

        /** The kind {@code json} names, or {@code null} when this contract has no case for it. */
        public static Kind fromJson(String json) {
            for (Kind kind : values()) {
                if (kind.json.equals(json)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public MemberRecord {
        type = type == null ? "" : type;
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** A field, the commonest member, with no annotations. */
    public static MemberRecord field(String name, String type, List<String> modifiers) {
        return new MemberRecord(name, Kind.FIELD, type, List.of(), modifiers, List.of());
    }

    /**
     * How a generator names this member: its name, and for a callable its parameter types.
     *
     * <p>Present because the two questions a generator asks of a member list are "which fields are there" and
     * "is there a method with this shape", and the second needs the arity and the parameter types together. No
     * return type: Java does not overload on it.</p>
     */
    public String signature() {
        if (parameterTypes.isEmpty()) {
            return name;
        }
        return name + "(" + String.join(", ", parameterTypes) + ")";
    }
}
