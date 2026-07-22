package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

public record TypeDescriptor(String typeName, List<TypeDescriptor> typeArguments, boolean array, boolean primitive) {
    public boolean isParameterized() {
        return typeArguments != null && !typeArguments.isEmpty();
    }
}