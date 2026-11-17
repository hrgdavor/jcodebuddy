package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Reads a {@code package-info.java} into a {@link PackageRecord}. */
public final class PackageExtractor {

    private PackageExtractor() {
    }

    public static PackageRecord of(Path moduleRoot, Path packageInfo) throws IOException {
        SourceReader.Read read = SourceReader.read(packageInfo);
        if (!read.readable()) {
            throw new IOException("unreadable package-info: " + packageInfo);
        }
        J.CompilationUnit unit = read.unit();
        J.Package declaration = unit.getPackageDeclaration();
        List<TypeAnnotation> annotations = declaration == null
                ? List.of()
                : TypeFacts.annotationsOf(declaration.getAnnotations());
        String path = moduleRoot.relativize(packageInfo).toString().replace('\\', '/');
        return new PackageRecord(TreeQueries.packageName(unit), path, ContentHash.of(packageInfo),
                declaration == null ? -1 : 1, annotations, null);
    }
}
