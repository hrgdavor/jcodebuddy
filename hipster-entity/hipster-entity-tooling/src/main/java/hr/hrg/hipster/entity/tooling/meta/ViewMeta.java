package hr.hrg.hipster.entity.tooling.meta;

import hr.hrg.hipster.entity.api.GenLevel;

import java.util.List;

/**
 * A discovered view in the tooling's metadata model.
 *
 * <p>The {@code gen}/{@code discriminatorField}/{@code addons} triple replaces the previous
 * {@code (Boolean read, Boolean write)} pair, which described annotation members the real
 * {@code @View} does not have (plan.dsflash § 8.1/3.1).</p>
 *
 * @param name               simple interface name
 * @param extendsTypes       declared supertypes, as written in source
 * @param gen                the resolved generation level (never {@link GenLevel#DEFAULT} once
 *                           {@code GenLevelResolver} has run)
 * @param discriminatorField polymorphic discriminator field name, {@code ""} when absent
 * @param addons             simple names of addon interfaces, in declaration order
 * @param properties         the view's own declared field accessors, in declaration order
 * @param lineNumber         source line of the declaration, {@code -1} when unknown
 * @param sourcePath         the file that declares this view, <strong>relative to the module
 *                           root</strong> (e.g. {@code src/main/java/a/b/PersonSummary.java}), or
 *                           {@code null} when the pass did not resolve one. A view may be declared in a
 *                           different package — even a different source root — than the marker that
 *                           claims it (the example's {@code person.entity.PersonAuditable} belongs to
 *                           the {@code example.Auditable} marker), which is exactly why the path is
 *                           recorded rather than reconstructed from the marker's package.
 * @param artifacts          the types that belong to this view: its own file and the nested types it
 *                           declares, the generated siblings the pass emitted, and the foreign declaring
 *                           interfaces its fields reference ({@code own = false}). Empty until the
 *                           emission loop has run for this view, because the inventory describes what the
 *                           pass <em>produced</em> — see {@link #withDetails}. The JSON writes it as
 *                           {@code views[].artifacts[]}
 * @param fields             one entry per field of the view, in DEC-023 ledger order, each carrying every
 *                           location the pass recorded for it. Empty for the same reason and at the same
 *                           time as {@code artifacts}. The JSON writes it as {@code views[].fields[]}
 */
public record ViewMeta(
        String name,
        List<String> extendsTypes,
        GenLevel gen,
        String discriminatorField,
        List<String> addons,
        List<Property> properties,
        int lineNumber,
        String sourcePath,
        List<ArtifactMeta> artifacts,
        List<ViewFieldMeta> fields) {

    public ViewMeta {
        if (gen == null) {
            gen = GenLevel.META;
        }
        if (discriminatorField == null) {
            discriminatorField = "";
        }
        addons = addons == null ? List.of() : List.copyOf(addons);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** Back-compatible convenience for callers that only have a name and properties. */
    public ViewMeta(String name, List<String> extendsTypes, List<Property> properties, int lineNumber) {
        this(name, extendsTypes, GenLevel.META, "", List.of(), properties, lineNumber, null,
                List.of(), List.of());
    }

    /** Back-compatible convenience for callers that predate the source path. */
    public ViewMeta(String name, List<String> extendsTypes, GenLevel gen, String discriminatorField,
                    List<String> addons, List<Property> properties, int lineNumber) {
        this(name, extendsTypes, gen, discriminatorField, addons, properties, lineNumber, null,
                List.of(), List.of());
    }

    /**
     * The discovery result for callers that predate the artifact inventory.
     *
     * <p>This is the constructor the marker loop uses: a view is discovered before anything is emitted,
     * so its inventory cannot exist yet.</p>
     */
    public ViewMeta(String name, List<String> extendsTypes, GenLevel gen, String discriminatorField,
                    List<String> addons, List<Property> properties, int lineNumber, String sourcePath) {
        this(name, extendsTypes, gen, discriminatorField, addons, properties, lineNumber, sourcePath,
                List.of(), List.of());
    }

    /**
     * The same view, with the detail the emission loop produced (DEC-028 § 4.5).
     *
     * <p>The artifact inventory and the location map can only be built <em>after</em> the emitters have
     * run for this view — the files must exist before they can be read back — while the view itself is
     * discovered before any of them run. Re-listing the other eight components at the call site would
     * mean a new component added to this record breaks the one place that assembles the final
     * {@code views} list, so the copy lives here beside the record it copies.</p>
     */
    public ViewMeta withDetails(List<ArtifactMeta> artifacts, List<ViewFieldMeta> fields) {
        return new ViewMeta(name, extendsTypes, gen, discriminatorField, addons, properties, lineNumber,
                sourcePath, artifacts, fields);
    }

    /** The level as it appears in the metadata JSON. */
    public String genName() {
        return gen.name();
    }

    // JavaBean-style accessors kept for the existing JSON/reflection call sites.
    public String getName() {
        return name;
    }

    public List<String> getExtendsTypes() {
        return extendsTypes;
    }

    public GenLevel getGen() {
        return gen;
    }

    public String getDiscriminatorField() {
        return discriminatorField;
    }

    public List<String> getAddons() {
        return addons;
    }

    public List<Property> getProperties() {
        return properties;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    /** The declaring file as a module-relative path, or {@code null} when the pass did not resolve it. */
    public String getSourcePath() {
        return sourcePath;
    }

    /** The types that belong to this view, empty until {@link #withDetails} has been applied. */
    public List<ArtifactMeta> getArtifacts() {
        return artifacts;
    }

    /** Every field of this view with all its locations, empty until {@link #withDetails} has been applied. */
    public List<ViewFieldMeta> getFields() {
        return fields;
    }
}
