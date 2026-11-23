package hr.hrg.jcodebuddy.tool.entity;

import hr.hrg.hipster.entity.tooling.EntityMetadataGenerator;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.ProjectMarker;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * hipster-entity as a proposal tool. It uses the entity generator's temporary-source preview mode, so a
 * preview cannot write the real module or its metadata. Each differing generated Java file becomes a
 * coordinator proposal.
 */
public final class EntityTool implements Tool {

    public static final String ID = "hipster-entity";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "hipster-entity generated source";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<ProjectMarker> projectMarkers() {
        return List.of(new ProjectMarker("GenerateEntity", "Entity view builder opt-in marker",
                "@Retention(RetentionPolicy.SOURCE)\n"
                        + "@Target(ElementType.TYPE)\n"
                        + "public @interface GenerateEntity {\n"
                        + "    Variant variant() default Variant.BUILDER;\n"
                        + "    enum Variant { BUILDER, TRACKING, BOTH }\n"
                        + "}\n"));
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        Path moduleRoot = moduleRoot(request);
        Path sourceRoot = moduleRoot.resolve(request.string("sourceRoot", "src/main/java"));
        if (!Files.isDirectory(sourceRoot)) {
            throw new ToolRefusal("no source root at " + sourceRoot);
        }
        try {
            Map<String, String> generated = EntityMetadataGenerator.renderGeneratedSource(sourceRoot);
            List<FileProposal> files = new ArrayList<>();
            for (Map.Entry<String, String> entry : generated.entrySet()) {
                Path path = sourceRoot.resolve(entry.getKey()).toAbsolutePath().normalize();
                String before = Files.isRegularFile(path) ? Files.readString(path) : "";
                if (before.equals(entry.getValue())) continue;
                String key = WriteCoordinator.hash(ID + ":" + path + ":" + WriteCoordinator.hash(before));
                files.add(FileProposal.change(path, WriteCoordinator.hash(before), before,
                        entry.getValue(), key).owned("hipster-entity-tooling"));
            }
            return new ToolProposal(ID, title(), version(), files,
                    Map.of("generatedFiles", generated.size()),
                    "Entity generation: " + files.size() + " changed file"
                            + (files.size() == 1 ? "" : "s"),
                    files.stream().map(file -> "Updated " + request.root().relativize(file.path())
                            .toString().replace('\\', '/')).toList());
        } catch (IOException failed) {
            throw new ToolRefusal("hipster-entity preview failed: " + failed.getMessage(), failed);
        }
    }

    private static Path moduleRoot(ToolRequest request) throws ToolRefusal {
        String explicit = request.string("moduleRoot", null);
        if (explicit != null && !explicit.isBlank()) {
            return request.root().resolve(explicit).toAbsolutePath().normalize();
        }
        Path candidate = request.file() == null ? request.root() : request.file();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate.toAbsolutePath().normalize();
            }
            candidate = candidate.getParent();
        }
        throw new ToolRefusal("no pom.xml above " + request.file() + " (pass moduleRoot explicitly)");
    }
}
