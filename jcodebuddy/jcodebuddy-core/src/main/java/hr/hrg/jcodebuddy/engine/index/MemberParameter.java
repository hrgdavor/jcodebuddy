package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * One parameter of a method or constructor, as a class index row records it (DEC-029's member field, extended
 * by plan step 3.0e).
 *
 * <p>Types alone were not enough, and the consumer that proved it is hipster-ioc: a factory method's
 * <em>name</em> and its annotations decide how the generator wires a bean, and the annotation that matters
 * ({@code @Circular}) is on the parameter. A row that recorded only {@code [CtxMain, String]} let a generator
 * read the shape of a factory and not its meaning, which sent it back to parsing the file — the parse this
 * field exists to make unnecessary.</p>
 *
 * <p>The name is recorded because it is what the source says and what a generator writes into the code it
 * emits; the type is the spelling the source used, unresolved, like every other name in this model.</p>
 *
 * @param type        the parameter's type as written ({@code List<String>}, {@code var}, {@code T})
 * @param name        the parameter's name as written
 * @param annotations the annotations on the parameter, as written with their arguments as written, in
 *                    declaration order; empty when it carries none
 */
public record MemberParameter(String type, String name, List<TypeAnnotation> annotations) {

    public MemberParameter {
        type = type == null ? "" : type;
        name = name == null ? "" : name;
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** A parameter with no annotations — the common case. */
    public static MemberParameter of(String type, String name) {
        return new MemberParameter(type, name, List.of());
    }

    /** Whether this parameter carries an annotation with this simple name, as written. */
    public boolean hasAnnotation(String simpleName) {
        for (TypeAnnotation annotation : annotations) {
            if (annotation.name().equals(simpleName) || annotation.simpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }
}
