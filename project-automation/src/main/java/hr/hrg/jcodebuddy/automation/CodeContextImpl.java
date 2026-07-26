package hr.hrg.jcodebuddy.automation;

import hr.hrg.hipster.entity.tooling.meta.SourceMetadata;

import java.nio.file.Path;

public record CodeContextImpl(Path root, Path file, int line, String indent, TypeResolver typeResolver, SourceMetadata sourceMetadata)
        implements CodeContext {

    public CodeContextImpl(Path root, Path file, int line, String indent) {
        this(root, file, line, indent, TypeResolver.empty(), null);
    }

    public CodeContextImpl(Path root, Path file, int line) {
        this(root, file, line, "    ");
    }
}
