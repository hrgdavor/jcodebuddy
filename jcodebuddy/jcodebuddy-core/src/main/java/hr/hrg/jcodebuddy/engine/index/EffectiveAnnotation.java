package hr.hrg.jcodebuddy.engine.index;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One annotation with its written values and the annotation type's defaults filled in.
 *
 * <p>The written value is kept as source text; an omitted element comes from {@link AnnotationTypeFacts}. This
 * is an extended-layer projection: it answers "what would a reader see as this annotation's value" without
 * making the class index evaluate source.</p>
 */
public record EffectiveAnnotation(String name, Map<String, Object> values) {

    public EffectiveAnnotation {
        name = name == null ? "" : name;
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public static EffectiveAnnotation of(TypeAnnotation written, AnnotationTypeFacts facts) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (facts != null) {
            for (AnnotationTypeFacts.Element element : facts.elements()) {
                if (element.hasDefault()) {
                    values.put(element.name(), element.defaultValue());
                }
            }
        }
        for (String argument : written.arguments()) {
            int equals = argument.indexOf('=');
            if (equals < 0) {
                continue;
            }
            values.put(argument.substring(0, equals).trim(), argument.substring(equals + 1).trim());
        }
        return new EffectiveAnnotation(written.name(), values);
    }
}
