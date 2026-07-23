package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;

public record CodeContextImpl(Path root, Path file, int line, String indent, TypeResolver typeResolver)
        implements CodeContext {

    public CodeContextImpl(Path root, Path file, int line, String indent) {
        this(root, file, line, indent, TypeResolver.empty());
    }

    public CodeContextImpl(Path root, Path file, int line) {
        this(root, file, line, "    ");
    }
}
