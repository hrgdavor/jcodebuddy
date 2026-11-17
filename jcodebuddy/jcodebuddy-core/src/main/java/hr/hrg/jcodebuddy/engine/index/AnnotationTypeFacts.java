package hr.hrg.jcodebuddy.engine.index;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * An annotation type's declared elements and defaults, read from a class loader.
 *
 * <p>The class index records annotations as <em>written</em> and deliberately does not evaluate them. This
 * record is the optional extended fact a UI or a generator needs when it must show or apply an omitted
 * element's default. It is not stored in a file-local row, because it depends on the annotation type being
 * loadable.</p>
 */
public record AnnotationTypeFacts(String fqn, List<Element> elements) {

    public record Element(String name, String type, Object defaultValue) {
        public Element {
            name = name == null ? "" : name;
            type = type == null ? "" : type;
        }

        public boolean hasDefault() {
            return defaultValue != null;
        }
    }

    public AnnotationTypeFacts {
        fqn = fqn == null ? "" : fqn;
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    public static AnnotationTypeFacts of(String fqn, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(fqn, false, loader == null
                    ? Thread.currentThread().getContextClassLoader() : loader);
            if (!type.isAnnotation()) {
                return new AnnotationTypeFacts(fqn, List.of());
            }
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> annotationType = (Class<? extends Annotation>) type;
            return of(annotationType);
        } catch (ClassNotFoundException e) {
            return new AnnotationTypeFacts(fqn, List.of());
        }
    }

    public static AnnotationTypeFacts of(Class<? extends Annotation> type) {
        List<Element> elements = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            elements.add(new Element(method.getName(), method.getReturnType().getTypeName(),
                    method.getDefaultValue()));
        }
        return new AnnotationTypeFacts(type.getName(), elements);
    }
}
