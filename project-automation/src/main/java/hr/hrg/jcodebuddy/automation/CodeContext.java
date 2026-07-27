package hr.hrg.jcodebuddy.automation;

import hr.hrg.hipster.entity.tooling.meta.SourceMetadata;

import java.nio.file.Path;

public interface CodeContext {

    Path getRootPath();

    Path getFilePath();

    int getLine();

    String getIndent();

    TypeResolver getTypeResolver();

    SourceMetadata getSourceMetadata();
}
