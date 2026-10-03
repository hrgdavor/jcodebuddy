package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;

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
 * @param parameters     a method's or constructor's parameters, in order, each with its type as written, its
 *                       name and its own annotations ({@link MemberParameter}); empty otherwise
 * @param modifiers      the member's Java modifier keywords, sorted and restricted to
 *                       {@link TypeFacts#KEYWORDS} — which includes {@code default}, the keyword that
 *                       separates a factory from an accessor (see the note on that set)
 * @param annotations    the annotations on the member, as written with their arguments as written
 *                       ({@link TypeAnnotation}), in declaration order; empty when it carries none
 * @param line           the line the member's <em>name</em> sits on, 1-based, or
 *                       {@link hr.hrg.jcodebuddy.engine.source.JavacPositions#UNKNOWN_LINE} when it could not be
 *                       located — carried because a consumer has to be able to <em>point</em> at the member, and
 *                       a navigation diagram, a review page or an IDE jump needs a line rather than a
 *                       description (DEC-040 D6)
 * @param span           the member's character span in the declaring file, or {@code null} when javac's walk
 *                       recorded none — this is what recovers the member's written form, generic arguments and
 *                       all, because the table points at the source instead of copying it (DEC-040 D2)
 */
public record MemberRecord(String name, Kind kind, String type, List<MemberParameter> parameters,
                           List<String> modifiers, List<TypeAnnotation> annotations, int line,
                           TreeQueries.SourceSpan span) {

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

        /**
         * The spelling a javac position lookup uses for this kind.
         *
         * <p>The two vocabularies agree on {@code field}, {@code method} and {@code constructor} and differ on
         * exactly one: a nested type is a {@code type} to javac, because that is what it declared. Kept here so
         * the mapping has one home rather than one call site per extraction.</p>
         */
        public String positionKind() {
            return this == NESTED ? "type" : json;
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
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /**
     * A member whose position the caller did not read — the two position fields are "unknown", which is a fact
     * a reader can tell from a line (DEC-040 D4).
     */
    public MemberRecord(String name, Kind kind, String type, List<MemberParameter> parameters,
                        List<String> modifiers, List<TypeAnnotation> annotations) {
        this(name, kind, type, parameters, modifiers, annotations,
                hr.hrg.jcodebuddy.engine.source.JavacPositions.UNKNOWN_LINE, null);
    }

    /** The member's arity, which is what tells two overloads of one name apart in a position lookup. */
    public int parameterCount() {
        return parameters.size();
    }

    /** A field, the commonest member, with no annotations and no position. */
    public static MemberRecord field(String name, String type, List<String> modifiers) {
        return new MemberRecord(name, Kind.FIELD, type, List.of(), modifiers, List.of());
    }

    /**
     * This member's parameter types alone, in order.
     *
     * <p>Derived rather than stored: a caller that wants the shape of a callable (a signature, an arity, a
     * name that is unique by types) does not need the names or the annotations, and this model should not make
     * it read four fields to ask one question. The row still carries the full parameter ({@link #parameters}),
     * because a consumer that wires a bean needs the annotation that is on it.</p>
     */
    public List<String> parameterTypes() {
        List<String> types = new java.util.ArrayList<>(parameters.size());
        for (MemberParameter parameter : parameters) {
            types.add(parameter.type());
        }
        return List.copyOf(types);
    }

    /**
     * How a generator names this member: its name, and for a callable its parameter types.
     *
     * <p>Present because the two questions a generator asks of a member list are "which fields are there" and
     * "is there a method with this shape", and the second needs the arity and the parameter types together. No
     * return type: Java does not overload on it.</p>
     */
    public String signature() {
        if (parameters.isEmpty()) {
            return name;
        }
        return name + "(" + String.join(", ", parameterTypes()) + ")";
    }
}
