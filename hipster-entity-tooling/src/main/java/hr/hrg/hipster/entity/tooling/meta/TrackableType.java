package hr.hrg.hipster.entity.tooling.meta;

/**
 * A view the generator can wire into a deep-change walk (plan.dsflash § 11/6.5).
 *
 * <h3>What makes a type trackable</h3>
 * <p>Its <strong>generation level</strong> — {@code BUILDER_TRACKED} or {@code BUILDER_ALL} — because
 * that is what makes the generator emit a {@code <View>BuilderTracking} that implements
 * {@code ViewChangeTracking}, which is what a nested value has to be at runtime for the walk to find
 * anything.</p>
 *
 * <p>The tempting alternative is "the field's interface declares {@code ViewChangeTracking}", and it
 * is <strong>wrong here</strong>: G8 rule 1 excludes any interface extending a framework surface
 * ({@code ViewReader}, {@code ViewWriter}, {@code ViewChangeTracking}) from discovery, because such a
 * type is supposed to be generated output rather than a view. A rule phrased that way could therefore
 * never match a generated view — it would only ever match hand-written fixtures, which is exactly how
 * the deep-tracking tests in {@code hipster-entity-test} are built and exactly why they cannot be
 * generator targets.</p>
 *
 * <p>The consequence is that the generated call needs a cast: the field's declared type is the plain
 * view interface, so the walk is reached through
 * {@code (ViewChangeTracking<<View>_, ?>) value}. That is ordinary, navigable Java — the cast is
 * checked by the compiler and the target type is named in full — and it is safe by construction for
 * values the generated builders produced, which is the only way a tracking value comes to exist. The
 * type argument is the element's own field enum, which is what makes {@code changedValues()} usable: with a
 * wildcard element type its result could not be widened into the {@code List<FieldChange<?>>} a
 * {@code ListDelta} carries.</p>
 *
 * @param simpleName        the interface's simple name, as written in the field's declared type
 * @param qualifiedName     the interface's fully-qualified name, used in the emitted cast
 * @param enumQualifiedName the field enum's fully-qualified name, used as the cast's type argument
 * @param identifiable      whether the interface also extends {@code Identifiable}, which is what
 *                          lets a {@code ListDelta} carry an identity instead of a bare index
 *                          (DEC-017); it is also what makes {@code value.id()} compile
 */
public record TrackableType(String simpleName, String qualifiedName, String enumQualifiedName,
                            boolean identifiable) {
}
