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
 *   <li>{@link Kind#ENUM_CONSTANT} — empty: a constant declares no type, and its name is its own;</li>
 *   <li>{@link Kind#NESTED} — the nested type's simple name; its own row is keyed by the full name.</li>
 * </ul>
 *
 * <p><strong>Which facts apply to which kind</strong> — stated once here rather than inferred per consumer,
 * because a member row is the widest thing this format writes and "empty" must not be read as "none":
 * {@code parameters}, {@code throwsClause} and {@code hasBody} are a callable's; {@code type} is a field's, a
 * method's or a nested type's; {@code hasInitializer} is a field's. Every field is still written for every
 * member, so a consumer reads one shape.</p>
 *
 * <p><strong>And an arbitrary expression is a range question, not a text one</strong> (DEC-040 D2, corrected
 * 2026-10-03 mid-step). A field's initialiser and an enum constant's arguments are the two facts whose written
 * form is an <em>expression</em> — potentially a whole array or a method chain — so neither is stored as text:
 * the constant's arguments and the field's initial value are inside the member's own {@link #span}, and a
 * consumer that needs them slices the declaring file there. Two reasons, and the second is the one that found
 * this: the rule the model follows is "point at the source", and the LST has no source printer for every
 * expression — a first version stored an array initialiser's text and got a Lombok debug dump containing a
 * fresh UUID per parse, which is non-determinism in a table that must be byte-identical across passes.</p>
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
 * @param hasInitializer whether a <strong>field</strong> declares an initialiser (DEC-040 D1). The initialiser
 *                       itself is not stored: it is inside {@link #span} and recovered by slicing. {@code false}
 *                       for an enum constant, whose arguments are recovered the same way from its own span
 * @param throwsClause   a <strong>callable's</strong> {@code throws} clause as written, in order; empty when it
 *                       declares none or is not a callable (DEC-040 D1 — a generator that emits a call must
 *                       declare what it throws). Kept as text because a thrown type is a <em>name</em>, not an
 *                       arbitrary expression: it is small, structured, and printed as source by the LST
 * @param hasBody        whether a <strong>callable</strong> has a body: {@code false} for an abstract or
 *                       interface method, {@code true} for one that declares {@code {…}}. A generator reads it
 *                       to know whether it may emit a call or must emit an override (DEC-040 D1)
 * @param line           the line the member's <em>name</em> sits on, 1-based, or
 *                       {@link hr.hrg.jcodebuddy.engine.source.JavacPositions#UNKNOWN_LINE} when it could not be
 *                       located — carried because a consumer has to be able to <em>point</em> at the member, and
 *                       a navigation diagram, a review page or an IDE jump needs a line rather than a
 *                       description (DEC-040 D6)
 * @param span           the member's character span in the declaring file, or {@code null} when javac's walk
 *                       recorded none — this is what recovers the member's written form, generic arguments and
 *                       initialisers and all, because the table points at the source instead of copying it
 *                       (DEC-040 D2)
 */
public record MemberRecord(String name, Kind kind, String type, List<MemberParameter> parameters,
                           List<String> modifiers, List<TypeAnnotation> annotations, boolean hasInitializer,
                           List<String> throwsClause, boolean hasBody, int line, TreeQueries.SourceSpan span) {

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
        /**
         * An enum constant, which is a member of the enum rather than a field of it.
         *
         * <p>Added 2026-10-03 (DEC-040 D1): the entity work reads enum constants as the values a view exposes,
         * and before this the table recorded them nowhere — an omission a consumer could only work around by
         * parsing the file. Its spelling matches javac's own vocabulary for the same fact
         * ({@code enum-constant}), which is what keeps the position lookup keyed the same way as every other
         * kind.</p>
         */
        ENUM_CONSTANT("enum-constant"),
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
        throwsClause = throwsClause == null ? List.of() : List.copyOf(throwsClause);
    }

    /**
     * A member carrying no kind-specific facts and no position — the shape every caller that only knows a name,
     * a kind and a type wants, and the one the earlier steps' fixtures use.
     */
    public MemberRecord(String name, Kind kind, String type, List<MemberParameter> parameters,
                        List<String> modifiers, List<TypeAnnotation> annotations) {
        this(name, kind, type, parameters, modifiers, annotations, false, List.of(), false,
                hr.hrg.jcodebuddy.engine.source.JavacPositions.UNKNOWN_LINE, null);
    }

    /** A member with kind-specific facts but no position, for a caller that read the facts and not the file. */
    public MemberRecord(String name, Kind kind, String type, List<MemberParameter> parameters,
                        List<String> modifiers, List<TypeAnnotation> annotations, boolean hasInitializer,
                        List<String> throwsClause, boolean hasBody) {
        this(name, kind, type, parameters, modifiers, annotations, hasInitializer, throwsClause, hasBody,
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
