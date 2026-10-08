// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.api;

/**
 * One annotation a field carries, exposed as <b>metadata</b> on the generated view enum (DEC-047, plan step 6.1).
 *
 * <p>A generated view enum's constant is the field's metadata — it already answers {@code column()},
 * {@code fieldKind()} and the rest through {@link FieldDef} — and a field's annotations are part of what a factory
 * needs to know: an XML or JSON reader that fills a view wants the constraints the developer wrote on the accessor,
 * without reading the interface source and without reflecting over it.
 *
 * @param type      the annotation's <b>qualified name</b> ({@code jakarta.validation.constraints.Size}), so a
 *                  consumer resolves it without the declaring file's import table — the generator resolved that at
 *                  parse time, and re-resolving it here would be a second, weaker answer
 * @param arguments the source text between the annotation's parentheses, exactly as written
 *                  ({@code min = 1, max = 64}), or an empty string when it has none
 */
public record FieldAnnotation(String type, String arguments) {

    public FieldAnnotation {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("a FieldAnnotation needs the annotation's qualified name");
        }
        arguments = arguments == null ? "" : arguments;
    }

    /** The annotation as it would be written, for a message or a log line. */
    public String annotation() {
        return arguments.isEmpty() ? "@" + type : "@" + type + "(" + arguments + ")";
    }

    /** The annotation's simple name, for the common case of matching a well-known constraint. */
    public String simpleName() {
        int lastDot = type.lastIndexOf('.');
        return lastDot < 0 ? type : type.substring(lastDot + 1);
    }
}
