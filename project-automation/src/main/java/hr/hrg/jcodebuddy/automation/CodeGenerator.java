package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;

public interface CodeGenerator<T> {

    String name();

    boolean isApplicable(CodeContext context);

    T generate(CodeContext context);
}
