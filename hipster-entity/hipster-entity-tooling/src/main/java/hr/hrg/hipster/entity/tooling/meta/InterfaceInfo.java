package hr.hrg.hipster.entity.tooling.meta;

import java.util.List;

import org.openrewrite.java.tree.J;

/**
 * A discovered interface in the tooling's metadata model.
 *
 * @param packageName            declaring package
 * @param name                   simple interface name
 * @param extendsTypes           declared supertypes, as written in source
 * @param properties             the interface's own declared field accessors, in declaration order
 * @param view                   the parsed {@code @View} attributes, or {@code null} when absent
 * @param entityBaseIdType       the {@code EntityBase<Id>} type argument, or {@code null} when the
 *                               interface is not an entity marker
 * @param lineNumber             source line of the declaration, {@code -1} when unknown
 * @param nestedRecordComponents the component names of a nested {@code record}, or {@code null}
 *                               when the interface declares none. Read from the parsed source
 *                               because {@code GenLevel.DEFAULT}'s resolution (§ 4.5/G1 rule 1) and
 *                               the {@code RECORD} level's "do not emit a second record" rule
 *                               (§ 8.4/3.10) both compare this list against the field enum order.
 * @param publicType             whether the declaration is {@code public}. A non-public interface
 *                               cannot carry a public generated type, so it is never a generation
 *                               target — the example's package-private documentation sample is the
 *                               live case.
 * @param declaration            the parsed declaration, or {@code null} when it was not retained.
 *                               Carried so the generator can read a method BODY where a decision
 *                               needs one — specifically a polymorphic view's
 *                               {@code default <field>() { return "…"; }}, which declares the
 *                               discriminator value the emitted {@code META} must supply (§ 9/4.9).
 * @param sourcePath             the file that declares this interface, <strong>relative to the module
 *                               root</strong> (e.g. {@code src/main/java/a/b/Person.java}), or
 *                               {@code null} when the pass did not resolve one. Module-relative, not
 *                               project-relative: a multi-module build resolves a path against the
 *                               module that holds the source, which is also where that module's
 *                               `.jcodebuddy/` output lives (DEC-026/DEC-027).
 */
public record InterfaceInfo(
        String packageName,
        String name,
        List<String> extendsTypes,
        List<Property> properties,
        ViewAttributes view,
        String entityBaseIdType,
        int lineNumber,
        List<String> nestedRecordComponents,
        boolean publicType,
        org.openrewrite.java.tree.J.ClassDeclaration declaration,
        String sourcePath
) {
    public InterfaceInfo {
        nestedRecordComponents = nestedRecordComponents == null ? null : List.copyOf(nestedRecordComponents);
    }

    /** Back-compatible constructor for callers that collect neither the record nor the declaration. */
    public InterfaceInfo(String packageName, String name, List<String> extendsTypes, List<Property> properties,
                         ViewAttributes view, String entityBaseIdType, int lineNumber) {
        this(packageName, name, extendsTypes, properties, view, entityBaseIdType, lineNumber, null, true,
                null, null);
    }

    /** Back-compatible constructor for callers that predate the source path. */
    public InterfaceInfo(String packageName, String name, List<String> extendsTypes, List<Property> properties,
                         ViewAttributes view, String entityBaseIdType, int lineNumber,
                         List<String> nestedRecordComponents, boolean publicType,
                         org.openrewrite.java.tree.J.ClassDeclaration declaration) {
        this(packageName, name, extendsTypes, properties, view, entityBaseIdType, lineNumber,
                nestedRecordComponents, publicType, declaration, null);
    }
    /** The declaring file as a module-relative path, or {@code null} when the pass did not resolve it. */
    public String getSourcePath() {
        return sourcePath;
    }

    public boolean isMarkerEntity() {
        return entityBaseIdType != null;
    }

    /** Alias kept so `isPublic()` reads naturally at the call sites in the generator. */
    public boolean isPublic() {
        return publicType;
    }
}
