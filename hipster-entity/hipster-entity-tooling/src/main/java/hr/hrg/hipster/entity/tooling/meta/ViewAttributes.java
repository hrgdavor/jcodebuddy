package hr.hrg.hipster.entity.tooling.meta;

import hr.hrg.hipster.entity.api.GenLevel;

import java.util.List;

/**
 * The three attributes of {@code @View}, in the form the rest of the tooling consumes
 * (plan.dsflash § 8.1/3.1).
 *
 * <p>The previous shape of this record — {@code (Boolean read, Boolean write)} — described
 * annotation members that do not exist on the real {@code @View}
 * ({@code {GenLevel gen(); String discriminatorField(); Class<?>[] addons();}}), which is why
 * {@code @View(gen = GenLevel.BUILDER_ALL)} used to produce byte-identical output to a bare
 * {@code @View}.</p>
 *
 * @param gen                the requested generation level; {@link GenLevel#DEFAULT} when absent.
 *                           {@code DEFAULT} is resolved into a concrete level by
 *                           {@code GenLevelResolver} — never here.
 * @param discriminatorField the polymorphic discriminator field name, {@code ""} when absent
 * @param addons             simple names of addon interfaces, in declaration order
 */
public record ViewAttributes(GenLevel gen, String discriminatorField, List<String> addons) {

    public ViewAttributes {
        if (gen == null) {
            gen = GenLevel.DEFAULT;
        }
        if (discriminatorField == null) {
            discriminatorField = "";
        }
        addons = addons == null ? List.of() : List.copyOf(addons);
    }

    /** The all-defaults attributes: what a bare {@code @View} or a missing annotation means. */
    public static ViewAttributes defaults() {
        return new ViewAttributes(GenLevel.DEFAULT, "", List.of());
    }
}
