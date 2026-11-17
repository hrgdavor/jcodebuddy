package hr.hrg.jcodebuddy.engine.index;

import java.util.List;

/**
 * One package (or module) declaration: a fact a type-only class index cannot carry.
 *
 * <p>{@code package-info.java} declares no type, so it has no {@link ClassRecord}; its package annotations are
 * nevertheless what a generic consumer reads to configure all types in the package. This record keeps those
 * annotations as written, with the file identity needed to slice them, and never interprets them.</p>
 *
 * @param name        the package name; empty for the default package
 * @param path        the declaring file, module-relative
 * @param checksum    the file's LF-normalised content hash
 * @param line        1-based line of the package declaration
 * @param annotations the package annotations as written
 * @param module      the module name when this is {@code module-info.java}, otherwise {@code null}
 */
public record PackageRecord(String name, String path, String checksum, int line,
                            List<TypeAnnotation> annotations, String module) {

    public PackageRecord {
        name = name == null ? "" : name;
        path = path == null ? "" : path;
        checksum = checksum == null ? "" : checksum;
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
