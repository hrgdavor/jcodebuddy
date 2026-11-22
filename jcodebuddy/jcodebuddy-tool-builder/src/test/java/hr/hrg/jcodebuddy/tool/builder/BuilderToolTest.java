package hr.hrg.jcodebuddy.tool.builder;

import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderToolTest {

    @Test
    void previewsButDoesNotWrite(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        String source = "package x;\npublic class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        ToolProposal proposal = new BuilderTool("    ").preview(
                new ToolRequest(root, file, 2, Map.of()));
        assertFalse(proposal.empty());
        assertEquals(source, Files.readString(file));
        FileProposal change = proposal.files().get(0);
        assertTrue(change.afterContent().contains("ABuilder"));
        assertEquals(source, change.beforeContent());
    }

    @Test
    void refusesAFileWithoutAClass(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "not java\n");
        org.junit.jupiter.api.Assertions.assertThrows(hr.hrg.jcodebuddy.tool.ToolRefusal.class,
                () -> new BuilderTool().preview(new ToolRequest(root, file, 1, Map.of())));
    }
}
