package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;

public interface CodeContext {

    Path getRootPath();

    Path getFilePath();

    int getLine();

    String getIndent();

    TypeResolver getTypeResolver();
}
