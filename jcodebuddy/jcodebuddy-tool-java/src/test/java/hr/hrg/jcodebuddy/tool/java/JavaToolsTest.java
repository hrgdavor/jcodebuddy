package hr.hrg.jcodebuddy.tool.java;

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

class JavaToolsTest {

    @Test
    void accessorAndConstructorPreviewWithoutWriting(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        String source = "class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        ToolProposal accessors = new AccessorTool().preview(new ToolRequest(root, file, 1, Map.of()));
        assertFalse(accessors.empty());
        assertTrue(accessors.files().get(0).afterContent().contains("getValue"));
        assertEquals(source, Files.readString(file));

        ToolProposal constructors = new ConstructorTool().preview(new ToolRequest(root, file, 1, Map.of()));
        assertFalse(constructors.empty());
        assertTrue(constructors.files().get(0).afterContent().contains("A("));
    }

    @Test
    void recordBuilderProposesBuilderForARecord(@TempDir Path root) throws Exception {
        Path file = root.resolve("R.java");
        Files.writeString(file, "record R(int value) {}\n");
        ToolProposal proposal = new RecordBuilderTool().preview(
                new ToolRequest(root, file, 1, Map.of()));
        assertEquals(1, proposal.files().size());
        assertTrue(proposal.files().get(0).afterContent().contains("Builder"));
    }

    @Test
    void renameUsesTheTreeCountAndSplicesText(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        String source = "class A {\n    private int value;\n    int read() { return value; }\n}\n";
        Files.writeString(file, source);
        ToolProposal proposal = new RenameTool().preview(
                new ToolRequest(root, file, 1, Map.of("from", "value", "to", "amount")));
        assertEquals(1, proposal.files().size());
        String after = proposal.files().get(0).afterContent();
        assertTrue(after.contains("private int amount"));
        assertTrue(after.contains("return amount"));
        assertFalse(after.contains("private int value"));
    }
}
