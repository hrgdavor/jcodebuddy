package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * Generic annotation queries over {@link TypeAnnotation} facts.
 *
 * <p>This class knows <em>how to read an annotation</em>, never what any annotation means. That boundary is what
 * lets a consumer such as hipster-sql derive its table, primary and column options from the generic annotation
 * rows without asking the engine to know those concepts.</p>
 */
public final class AnnotationFacts {

    private AnnotationFacts() {
    }

    /** The annotation with this simple name, or {@code null}. */
    public static TypeAnnotation find(List<TypeAnnotation> annotations, String simpleName) {
        if (annotations == null) {
            return null;
        }
        for (TypeAnnotation annotation : annotations) {
            if (annotation.simpleName().equals(simpleName) || annotation.name().equals(simpleName)) {
                return annotation;
            }
        }
        return null;
    }

    /** A written string element, unquoted; {@code fallback} when absent. */
    public static String stringArg(TypeAnnotation annotation, String name, String fallback) {
        String value = writtenArg(annotation, name);
        if (value == null) {
            return fallback;
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }

    /** A written boolean element; {@code fallback} when absent. */
    public static boolean booleanArg(TypeAnnotation annotation, String name, boolean fallback) {
        String value = writtenArg(annotation, name);
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    /** A written {@code String[]} element's items, unquoted; empty when absent. */
    public static List<String> stringArrayArg(TypeAnnotation annotation, String name) {
        String value = writtenArg(annotation, name);
        if (value == null || !value.startsWith("{") || !value.endsWith("}")) {
            return List.of();
        }
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .map(item -> item.startsWith("\"") && item.endsWith("\"")
                        ? item.substring(1, item.length() - 1) : item)
                .toList();
    }

    private static String writtenArg(TypeAnnotation annotation, String name) {
        if (annotation == null) {
            return null;
        }
        for (String argument : annotation.arguments()) {
            int equals = argument.indexOf('=');
            if (equals > 0 && argument.substring(0, equals).trim().equals(name)) {
                return argument.substring(equals + 1).trim();
            }
        }
        return null;
    }
}
