package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

public record EntityMeta(String entityName, String packageName, String markerInterface, String idType, List<ViewMeta> views, List<EntityFieldMeta> allFields) {
    public String getEntityName() { return entityName; }
    public String getPackageName() { return packageName; }
    public String getMarkerInterface() { return markerInterface; }
    public String getIdType() { return idType; }
    public List<ViewMeta> getViews() { return views; }
    public List<EntityFieldMeta> getAllFields() { return allFields; }
}
