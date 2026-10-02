package hr.hrg.hipster.entity.api.meta;

import java.util.List;

public record AnnotationMeta(String qualifiedName, List<MemberValue> values) {
    public AnnotationMeta(String qualifiedName) {
        this(qualifiedName, List.of());
    }
}
