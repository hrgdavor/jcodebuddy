package hr.hrg.jcodebuddy.tool;

/**
 * One generated type declaration a tool contributes to the project's marker module.
 *
 * <p>The source may be an annotation, a record, or a class/enum declaration that provides types a tool can
 * use. It deliberately does not describe configuration values: the project writes the record instances,
 * static fields or other configuration where it needs them, and the tool reads that source. The agent owns
 * the generated type file (with a generated-file header) and rewrites it when the tool's type declaration
 * changes, without touching the project's configuration.</p>
 *
 * @param fileName    the simple file name without {@code .java}
 * @param description one line for the generated-file header
 * @param source      the type declarations, without a package or imports
 */
public record ProjectMarker(String fileName, String description, String source) {

    public ProjectMarker {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("a marker file name is required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a marker source is required");
        }
        description = description == null ? "" : description;
    }
}
