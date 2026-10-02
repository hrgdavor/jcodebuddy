package hr.hrg.hipster.entity.api.meta;

import java.util.List;

public record TypeDescriptor(
    String typeName,
    List<TypeDescriptor> typeArguments,
    boolean array,
    boolean primitive,
    List<AnnotationMeta> annotations
) {
    public boolean isParameterized() {
        return typeArguments != null && !typeArguments.isEmpty();
    }
}
