package hr.hrg.hipster.sql.tooling.model;

import hr.hrg.jcodebuddy.engine.index.AnnotationFacts;
import hr.hrg.jcodebuddy.engine.index.TypeAnnotation;

import java.util.List;

/**
 * One annotation written on an accessor, kept as text so a generated getter can carry it.
 *
 * <p>The name is the annotation's spelling from the source. Arguments are the written expressions, not
 * evaluated values; a marker annotation has an empty list. Copying is deliberately textual: the
 * generator must not reflow or reinterpret what the entity author wrote.</p>
 */
public record AnnotationModel(String name, List<String> arguments) {

    public AnnotationModel {
        arguments = List.copyOf(arguments);
    }

    /** The annotation text, e.g. {@code @Deprecated} or {@code @Column(name = "user_name")}. */
    public String render() {
        if (arguments.isEmpty()) {
            return "@" + name;
        }
        return "@" + name + "(" + String.join(", ", arguments) + ")";
    }

    /** The simple name, so a consumer can match without knowing the import's package. */
    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    public static AnnotationModel find(List<AnnotationModel> annotations, String simpleName) {
        for (AnnotationModel annotation : annotations) {
            if (annotation.simpleName().equals(simpleName) || annotation.name().equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /** A written string element, unquoted; {@code fallback} when absent. */
    public String stringArg(String element, String fallback) {
        return AnnotationFacts.stringArg(asTypeAnnotation(), element, fallback);
    }

    /** A written boolean element; {@code fallback} when absent. */
    public boolean booleanArg(String element, boolean fallback) {
        return AnnotationFacts.booleanArg(asTypeAnnotation(), element, fallback);
    }

    private TypeAnnotation asTypeAnnotation() {
        return new TypeAnnotation(name, arguments, null);
    }
}
