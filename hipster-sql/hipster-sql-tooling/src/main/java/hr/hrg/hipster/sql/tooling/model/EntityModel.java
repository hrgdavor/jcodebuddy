package hr.hrg.hipster.sql.tooling.model;

import java.util.List;

/**
 * One {@code @HipsterEntity} interface as the generator sees it.
 *
 * <p>This is the seed of the full model: the first vertical slice needs the entity's identity,
 * table and accessors to emit a Visitor. The remaining fields described in the plan (options,
 * artifacts, ordinal ledger, locations) are added alongside this record rather than by widening it
 * with nullable columns; a field that is not known yet is absent, not {@code null}.</p>
 *
 * @param packageName the entity's package, or {@code ""} for the default package
 * @param simpleName  the entity's simple name
 * @param tableName   the table from {@code @HipsterEntity(table=...)}, or the simple name
 * @param sourcePath  the entity source's path, relative to the scanned source root, forward slashes
 * @param supertypes  the entity's {@code extends} clause as written, in declaration order
 * @param imports     single-type imports of the entity's file, simple name to fully qualified name
 * @param properties  the accessors the Visitor will expose, in declaration order
 * @param skipped     accessors excluded from columns by {@code @HipsterColumn(skip = true)}; the
 *                    generated classes still implement them, but they have no ordinal
 */
public record EntityModel(
        String packageName,
        String simpleName,
        String tableName,
        String sourcePath,
        List<String> supertypes,
        java.util.Map<String, String> imports,
        List<PropertyModel> properties,
        List<PropertyModel> skipped) {

    public EntityModel {
        supertypes = List.copyOf(supertypes);
        imports = java.util.Map.copyOf(imports);
        properties = List.copyOf(properties);
        skipped = List.copyOf(skipped);
    }

    /** The entity's fully qualified name, the key every document and diagnostic uses. */
    public String fqn() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /** The generated visitor's simple name. */
    public String visitorName() {
        return simpleName + "Visitor";
    }

    /** The first {@code @Id} property, or {@code null} when the entity has none. */
    public PropertyModel primary() {
        return properties.stream().filter(PropertyModel::primary).findFirst().orElse(null);
    }
}
