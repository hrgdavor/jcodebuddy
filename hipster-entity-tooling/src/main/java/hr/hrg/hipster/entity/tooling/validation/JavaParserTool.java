package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;

public class JavaParserTool {

    public static ClassOrInterfaceDeclaration findFirstInterface(Path javaFile) throws Exception {
        String source = Files.readString(javaFile);
        ParseResult<CompilationUnit> result = new JavaParser().parse(source);
        CompilationUnit cu = result.getResult().orElseThrow(() -> new IllegalStateException("No compilation unit parsed for " + javaFile));
        return cu.findFirst(ClassOrInterfaceDeclaration.class, ClassOrInterfaceDeclaration::isInterface)
                .orElseThrow(() -> new IllegalStateException("No interface found in " + javaFile));
    }
}
