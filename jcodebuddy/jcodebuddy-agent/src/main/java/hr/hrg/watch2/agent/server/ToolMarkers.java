package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.tool.ProjectMarker;
import hr.hrg.jcodebuddy.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the tool-owned marker sources into the project's configured annotation module.
 *
 * <p>The generated file carries DEC-035's generated-file header and the tool version, so a tool that adds a
 * type or changes a record/annotation declaration causes a rewrite on the next start. The mechanism is
 * intentionally independent of annotations: a {@link ProjectMarker} may contain an annotation, a record or
 * another type. Configuration values — record instances and static fields — are written by the project
 * where they are needed and are never generated or overwritten here.</p>
 */
final class ToolMarkers {

    private ToolMarkers() {
    }

    static String ensure(Path projectRoot, AgentToolConfig config, Tool tool) {
        String problem = config.annotationProblem();
        if (!problem.isBlank()) return problem;
        String packageName = config.annotationPackage();
        String relativeRoot = config.annotationSourceRoot().replace('\\', '/');
        Path sourceRoot = projectRoot.resolve(relativeRoot).normalize();
        if (!sourceRoot.startsWith(projectRoot)) {
            return "the configured annotation source root resolves outside the project";
        }
        for (ProjectMarker marker : tool.projectMarkers()) {
            String relative = packageName.replace('.', '/') + "/" + marker.fileName() + ".java";
            Path file = sourceRoot.resolve(relative).normalize();
            if (!file.startsWith(projectRoot)) {
                return "marker " + marker.fileName() + " resolves outside the project";
            }
            String eol = System.lineSeparator();
            String source = "// @generated file " + tool.getClass().getName() + " \u2014 " + marker.description() + eol
                    + "// {enabled:true, blockMarker:\"implicit\", markerVersion:\"" + tool.version() + "\"}" + eol
                    + "package " + packageName + ";" + eol + eol
                    + "import java.lang.annotation.ElementType;" + eol
                    + "import java.lang.annotation.Retention;" + eol
                    + "import java.lang.annotation.RetentionPolicy;" + eol
                    + "import java.lang.annotation.Target;" + eol + eol
                    + marker.source().replace("\r\n", "\n").replace("\r", "\n")
                            .replace("\n", eol) + eol;
            try {
                if (Files.isRegularFile(file) && source.equals(Files.readString(file))) continue;
                Files.createDirectories(file.getParent());
                Files.writeString(file, source);
            } catch (IOException failed) {
                return "cannot update tool marker " + file + ": " + failed.getMessage();
            }
        }
        return "";
    }
}
