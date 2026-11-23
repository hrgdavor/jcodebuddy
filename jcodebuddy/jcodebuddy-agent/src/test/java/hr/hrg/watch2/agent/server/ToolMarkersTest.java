package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.tool.ProjectMarker;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMarkersTest {

    @Test
    void writesGeneratedRecordTypesAndLeavesUserConfigurationAlone(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\","
                + "\"sourceRoot\":\"src/main/java\",\"package\":\"demo.markers\"}}");
        Path userConfig = root.resolve("src/main/java/demo/markers/UserConfig.java");
        Files.createDirectories(userConfig.getParent());
        String configuration = "package demo.markers;\n\n"
                + "final class UserConfig {\n"
                + "    static final MarkerApi.Descriptor CONFIG = new MarkerApi.Descriptor(\"configured\");\n"
                + "}\n";
        Files.writeString(userConfig, configuration);
        AgentToolConfig config = AgentToolConfig.read(root);

        ToolMarkers.ensure(root, config, new MarkerTool(
                "public final class MarkerApi {\n"
                        + "    public record Descriptor(String value) {}\n"
                        + "}\n"));
        Path marker = root.resolve("src/main/java/demo/markers/MarkerApi.java");
        String first = Files.readString(marker);
        assertTrue(first.contains("// @generated file"), first);
        assertTrue(first.contains("markerVersion:\"1\""), first);
        assertTrue(first.contains("public record Descriptor"), first);
        assertTrue(Files.readString(userConfig).equals(configuration),
                "a user's configured record instance is not generated source");

        ToolMarkers.ensure(root, config, new MarkerTool(
                "public final class MarkerApi {\n"
                        + "    public record Descriptor(String value) {}\n"
                        + "    public record MarkerRule(String name, int priority) {}\n"
                        + "}\n"));
        String second = Files.readString(marker);
        assertTrue(second.contains("public record MarkerRule"), second);
        assertTrue(Files.readString(userConfig).equals(configuration),
                "adding a generated type must not rewrite existing user configuration");
    }

    private static final class MarkerTool implements Tool {
        private final String source;

        private MarkerTool(String source) {
            this.source = source;
        }

        @Override
        public String id() {
            return "marker-test";
        }

        @Override
        public String title() {
            return "Marker test";
        }

        @Override
        public List<ProjectMarker> projectMarkers() {
            return List.of(new ProjectMarker("MarkerApi", "Test marker", source));
        }

        @Override
        public ToolProposal preview(ToolRequest request) {
            return new ToolProposal(id(), title(), version(), List.of(), java.util.Map.of());
        }
    }
}
