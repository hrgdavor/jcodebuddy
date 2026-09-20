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
 */
public record ViewMeta(
        String name,
        List<String> extendsTypes,
        GenLevel gen,
        String discriminatorField,
        List<String> addons,
        List<Property> properties,
        int lineNumber) {

    public ViewMeta {
        if (gen == null) {
            gen = GenLevel.META;
        }
        if (discriminatorField == null) {
            discriminatorField = "";
        }
        addons = addons == null ? List.of() : List.copyOf(addons);
    }

    /** Back-compatible convenience for callers that only have a name and properties. */
    public ViewMeta(String name, List<String> extendsTypes, List<Property> properties, int lineNumber) {
        this(name, extendsTypes, GenLevel.META, "", List.of(), properties, lineNumber);
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
}
