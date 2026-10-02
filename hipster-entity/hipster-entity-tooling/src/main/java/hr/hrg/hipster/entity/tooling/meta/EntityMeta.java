package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

/**
 * One entity marker and everything discovered under it.
 *
 * @param markerSourcePath the file that declares the marker interface, relative to the module root, or
 *                         {@code null} when the pass did not resolve it. The marker is a class like any
 *                         other the metadata is about, so it carries its own path rather than leaving a
 *                         consumer to assume it sits in {@code packageName} — it need not.
 * @param markerLine       the declaration line of the marker interface <em>inside</em> that file, or
 *                         {@code -1} when the pass did not resolve it. Carried beside the path so the page
 *                         can link the marker without scanning its file for the declaration: the marker is
 *                         a class the metadata is about, and "which file, which line" is the pair every
 *                         other link on the page is made of.
 */
public record EntityMeta(String entityName, String packageName, String markerInterface, String idType,
                         List<ViewMeta> views, List<EntityFieldMeta> allFields, String markerSourcePath,
                         int markerLine) {

    /** Back-compatible convenience for callers that predate the marker's declaration line. */
    public EntityMeta(String entityName, String packageName, String markerInterface, String idType,
                      List<ViewMeta> views, List<EntityFieldMeta> allFields, String markerSourcePath) {
        this(entityName, packageName, markerInterface, idType, views, allFields, markerSourcePath, -1);
    }

    /** Back-compatible convenience for callers that predate the marker's source path. */
    public EntityMeta(String entityName, String packageName, String markerInterface, String idType,
                      List<ViewMeta> views, List<EntityFieldMeta> allFields) {
        this(entityName, packageName, markerInterface, idType, views, allFields, null, -1);
    }

    public String getEntityName() { return entityName; }
    public String getPackageName() { return packageName; }
    public String getMarkerInterface() { return markerInterface; }
    public String getIdType() { return idType; }
    public List<ViewMeta> getViews() { return views; }
    public List<EntityFieldMeta> getAllFields() { return allFields; }
    public String getMarkerSourcePath() { return markerSourcePath; }
    public int getMarkerLine() { return markerLine; }
}
