package hr.hrg.hipster.entity.tooling.meta;

/**
 * One Bean Validation constraint read off a view accessor (plan.dsflash § 12.3/7.9).
 *
 * <p>The <strong>source of truth is the constraint annotation on the view accessor itself</strong> —
 * {@code @NotNull}, {@code @Size}, {@code @Min}, … read with JavaParser exactly as
 * {@code @FieldSource} already is. Nothing is duplicated into {@code @FieldSource}, and no second
 * annotation style is invented: the author declares the constraint where a Bean Validation provider
 * would already look for it, and the generator carries it to the generated artifacts.</p>
 *
 * @param simpleName the annotation's simple name, e.g. {@code Size} — used for emission and for the
 *                   import, since the whole recognised set lives in
 *                   {@code jakarta.validation.constraints}
 * @param arguments  the annotation's argument list exactly as written, without the parentheses, or
 *                   {@code ""} for a marker annotation. Keeping the source text rather than a parsed
 *                   model is deliberate: the generator's job is to carry the constraint through, not
 *                   to re-implement Bean Validation's member grammar, and a re-emitted
 *                   {@code min = 1, max = 50} is what the provider already understands.
 */
public record FieldConstraint(String simpleName, String arguments) {

    public FieldConstraint {
        if (simpleName == null || simpleName.isBlank()) {
            throw new IllegalArgumentException("a constraint needs an annotation name");
        }
        arguments = arguments == null ? "" : arguments.trim();
    }

    /** The annotation exactly as it will appear in generated source, e.g. {@code @Size(min = 1)}. */
    public String annotation() {
        return arguments.isEmpty() ? "@" + simpleName : "@" + simpleName + "(" + arguments + ")";
    }

    /** The fully-qualified annotation type, which is also the import the emitted file needs. */
    public String qualifiedName() {
        return "Valid".equals(simpleName)
                ? "jakarta.validation.Valid"
                : "jakarta.validation.constraints." + simpleName;
    }

    @Override
    public String toString() {
        return annotation();
    }
}
