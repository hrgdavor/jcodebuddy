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
 * @param dto                whether the view is a read projection ({@code @View(dto = true)}),
 *                           the marker the projection pass reads (DEC-003 / DEC-007). Two-valued
 *                           and defaulted, never {@code null}: "not a DTO" is the answer for every
 *                           view that predates the marker, so a missing annotation member cannot be
 *                           told apart from an explicit {@code false} — and does not need to be.
 */
public record ViewAttributes(GenLevel gen, String discriminatorField, List<String> addons, boolean dto) {

    public ViewAttributes {
        if (gen == null) {
            gen = GenLevel.DEFAULT;
        }
        if (discriminatorField == null) {
            discriminatorField = "";
        }
        addons = addons == null ? List.of() : List.copyOf(addons);
    }

    /**
     * The three attributes that predate the DTO marker, for callers that have no opinion about it.
     *
     * <p>Kept so a call site that only knows about {@code gen}/{@code discriminatorField}/{@code addons}
     * does not have to spell out a fourth argument it never reads. It is not a second meaning: the
     * canonical constructor's {@code dto} component is {@code false} for exactly the same reason.</p>
     */
    public ViewAttributes(GenLevel gen, String discriminatorField, List<String> addons) {
        this(gen, discriminatorField, addons, false);
    }

    /** The all-defaults attributes: what a bare {@code @View} or a missing annotation means. */
    public static ViewAttributes defaults() {
        return new ViewAttributes(GenLevel.DEFAULT, "", List.of(), false);
    }
}
