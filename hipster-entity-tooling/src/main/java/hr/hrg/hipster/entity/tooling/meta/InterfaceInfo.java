package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

public record InterfaceInfo(
        String packageName,
        String name,
        List<String> extendsTypes,
        List<Property> properties,
        ViewAttributes view,
        String entityBaseIdType,
        int lineNumber
) {
    public boolean isMarkerEntity() {
        return entityBaseIdType != null;
    }
}
