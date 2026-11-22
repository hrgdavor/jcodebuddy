// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolsTest {

    @Test
    void previewStoresAProposalAndApplyGoesThroughTheCoordinator(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        String source = "package x;\npublic class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        AgentTools tools = new AgentTools(root);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> catalog = tools.list();
        assertTrue(catalog.stream().anyMatch(item -> "builder".equals(item.get("id"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = tools.preview(Map.of("tool", "builder",
                "file", "src/A.java", "line", 2));
        assertEquals("builder", proposal.get("tool"));
        String proposalId = String.valueOf(proposal.get("proposalId"));
        assertEquals(source, Files.readString(file));

        @SuppressWarnings("unchecked")
        Map<String, Object> applied = tools.apply(proposalId);
        assertEquals("applied", applied.get("status"));
        assertTrue(Files.readString(file).contains("ABuilder"));

        @SuppressWarnings("unchecked")
        Map<String, Object> rejected = tools.reject(proposalId, false);
        assertEquals("reverted", rejected.get("status"));
        assertEquals(source, Files.readString(file));
    }

    @Test
    void batchCanApplyAndRevertOneFileWhileKeepingTheProposal(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        String source = "class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        AgentTools tools = new AgentTools(root);
        Map<String, Object> proposal = tools.preview(Map.of("tool", "builder",
                "file", "src/A.java", "line", 1, "batchId", "b-test"));
        String proposalId = String.valueOf(proposal.get("proposalId"));
        List<Map<String, Object>> batches = tools.batches();
        assertEquals("b-test", batches.get(0).get("batchId"));

        Map<String, Object> applied = tools.applyFile(proposalId, "src/A.java");
        assertEquals("applied", applied.get("status"));
        assertTrue(Files.readString(file).contains("ABuilder"));
        Map<String, Object> reverted = tools.revertFile(proposalId, "src/A.java");
        assertEquals("REVERTED", reverted.get("status"));
        assertEquals(source, Files.readString(file));
    }

    @Test
    void permanentRefusalSkipsTheNextPreview(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {\n    private int value;\n}\n");
        AgentTools tools = new AgentTools(root);
        Map<String, Object> proposal = tools.preview(Map.of("tool", "builder",
                "file", "A.java", "line", 1));
        String proposalId = String.valueOf(proposal.get("proposalId"));
        assertEquals(Boolean.TRUE, tools.reject(proposalId, true).get("permanent"));
        Map<String, Object> refused = tools.preview(Map.of("tool", "builder",
                "file", "A.java", "line", 1));
        assertEquals("refused", refused.get("status"));
        assertTrue(Files.readString(root.resolve(".jcodebuddy/conf/tool-refusals.json"))
                .contains("builder:A.java"));
    }

    @Test
    void explicitReviewProposesButNeverChangesTheEditorBuffer(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"review\"}}}");
        Path file = root.resolve("A.java");
        String source = "class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertEquals(source, Files.readString(file));
        List<Map<String, Object>> batches = tools.batches();
        assertEquals(1, batches.size());
        assertEquals(Boolean.FALSE, batches.get(0).get("anyApplied"));
    }

    @Test
    void modeChangesTakeEffectWithoutRestartingTheAgent(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Path file = root.resolve("A.java");
        String source = "class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"review\"}}}");
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertTrue(Files.readString(file).equals(source), "review mode must not write");

        Files.writeString(conf, "{\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"active\"}}}");
        tools.onChanged(List.of(file));
        assertTrue(Files.readString(file).contains("ABuilder"), "the saved active mode applies on the next batch");
    }

    @Test
    void explicitToolProposesWhenWebviewIsAliveAndActiveModeApplies(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"active\"}}}");
        Path file = root.resolve("A.java");
        String source = "class A {\n    private int value;\n}\n";
        Files.writeString(file, source);
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertTrue(Files.readString(file).contains("ABuilder"));
        List<Map<String, Object>> batches = tools.batches();
        assertEquals(Boolean.TRUE, batches.get(0).get("allApplied"));
        tools.revertBatch(String.valueOf(batches.get(0).get("batchId")));
        assertEquals(source, Files.readString(file));
    }

    @Test
    void activationIsPerToolAndDisabledToolsRefusePreview(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"tools\":{\"builder\":{\"activation\":\"auto\"},"
                + "\"hipster-ioc\":{\"activation\":\"disabled\"}}}");
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        AgentTools tools = new AgentTools(root);
        List<Map<String, Object>> catalog = tools.list();
        Map<String, Object> builder = catalog.stream()
                .filter(item -> "builder".equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, Object> ioc = catalog.stream()
                .filter(item -> "hipster-ioc".equals(item.get("id"))).findFirst().orElseThrow();
        assertEquals("auto", builder.get("activation"));
        assertEquals("disabled", ioc.get("activation"));
        org.junit.jupiter.api.Assertions.assertThrows(hr.hrg.jcodebuddy.ipc.ToolException.class,
                () -> tools.preview(Map.of("tool", "hipster-ioc", "file", "A.java", "line", 1)));
    }
}
