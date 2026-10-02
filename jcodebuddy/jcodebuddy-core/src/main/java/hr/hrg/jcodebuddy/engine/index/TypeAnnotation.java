package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * One annotation on a declaration: the annotation type's name as written, and its arguments as written
 * (DEC-029's annotation field, asked for by the maintainer on 2026-10-02: core metadata carries annotation
 * info).
 *
 * <p>Three things this record is, and one it deliberately is not:</p>
 *
 * <ul>
 *   <li><strong>General, not entity-specific.</strong> Core records <em>that</em> a declaration carries
 *       annotations and what they say; it never records what they <em>mean</em>. {@code @View} is not special
 *       here and nothing in the engine may name it — hipster-entity extracts its own meaning from this fact,
 *       which is DEC-037's boundary ("the engine parses and analyses; a consumer projects").</li>
 *   <li><strong>The name as written</strong>, like a relation: {@code View}, {@code hr.hrg.View} and
 *       {@code jakarta.inject.Inject} are recorded as the source spelled them, and resolution against the
 *       indexed world happens at query time (3.0h's rules), because a row is a fact about one declaration.</li>
 *   <li><strong>Arguments as source text, in order, and not evaluated.</strong> {@code name = "person"} is
 *       recorded as that text. There is no constant folding, no class loading and no default-value resolution:
 *       evaluating an annotation would make the engine a compiler with a classpath, and an argument that
 *       cannot be read as written is a question for a consumer that has the type. An empty list means the
 *       annotation has no arguments <em>as written</em> — which is not the same as "its members all take
 *       defaults", and a consumer must not read it that way.</li>
 * </ul>
 *
 * @param name      the annotation type's name as written in the declaration, without a leading {@code @}
 * @param arguments the arguments as source text, in declaration order; empty when none were written
 */
public record TypeAnnotation(String name, List<String> arguments) {

    public TypeAnnotation {
        name = name == null ? "" : name;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** An annotation written without arguments. */
    public static TypeAnnotation of(String name) {
        return new TypeAnnotation(name, List.of());
    }

    /** The last segment of {@link #name()}, which is how an annotation is usually written and asked about. */
    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
