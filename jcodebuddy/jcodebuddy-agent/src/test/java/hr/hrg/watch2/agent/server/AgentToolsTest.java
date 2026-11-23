// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.java.RecordBuilderTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"review\"}}}");
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
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"review\"}}}");
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertTrue(Files.readString(file).equals(source), "review mode must not write");

        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"active\"}}}");
        tools.onChanged(List.of(file));
        assertTrue(Files.readString(file).contains("ABuilder"), "the saved active mode applies on the next batch");
    }

    @Test
    void pendingBatchIsDiscardedWhenFieldIsAddedThenRemoved(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{"
                + "\"builder\":{\"activation\":\"disabled\"},"
                + "\"record-builder\":{\"activation\":\"explicit\",\"mode\":\"review\"},"
                + "\"accessors\":{\"activation\":\"disabled\"},"
                + "\"constructor\":{\"activation\":\"disabled\"},"
                + "\"rename\":{\"activation\":\"disabled\"},"
                + "\"hipster-ioc\":{\"activation\":\"disabled\"},"
                + "\"hipster-entity\":{\"activation\":\"disabled\"}}}");
        writeAnnotation(root);

        Path file = root.resolve("R.java");
        Files.writeString(file, "@GenerateBuilder record R(int value) {}\n");
        String baseline = new RecordBuilderTool().preview(new ToolRequest(root, file, 1, Map.of()))
                .files().get(0).afterContent();
        Files.writeString(file, baseline);

        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        String withNote = baseline.replace("record R(int value)", "record R(int value, String note)");
        Files.writeString(file, withNote);
        tools.onChanged(List.of(file));
        assertEquals(1, tools.batches().size(), "the added field must create a pending batch");
        assertEquals(withNote, Files.readString(file), "review mode must not write the field");

        Files.writeString(file, baseline);
        tools.onChanged(List.of(file));
        assertTrue(tools.batches().isEmpty(), "removing the field must discard the pending batch");
        assertEquals(baseline, Files.readString(file), "undo must leave no generated replacement");
    }

    @Test
    void pendingBatchIsReplacedByANewerChangeAndUndoLeavesNoBatch(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{"
                + "\"builder\":{\"activation\":\"disabled\"},"
                + "\"record-builder\":{\"activation\":\"explicit\",\"mode\":\"review\"},"
                + "\"accessors\":{\"activation\":\"disabled\"},"
                + "\"constructor\":{\"activation\":\"disabled\"},"
                + "\"rename\":{\"activation\":\"disabled\"},"
                + "\"hipster-ioc\":{\"activation\":\"disabled\"},"
                + "\"hipster-entity\":{\"activation\":\"disabled\"}}}");
        writeAnnotation(root);

        Path file = root.resolve("R.java");
        Files.writeString(file, "@GenerateBuilder record R(int value) {}\n");
        ToolProposal baselineProposal = new RecordBuilderTool().preview(
                new ToolRequest(root, file, 1, Map.of()));
        String baseline = baselineProposal.files().get(0).afterContent();
        Files.writeString(file, baseline);

        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertTrue(tools.batches().isEmpty(), "a generated builder with unchanged components is not a batch");

        String withNote = baseline.replace("record R(int value)", "record R(int value, String note)");
        Files.writeString(file, withNote);
        tools.onChanged(List.of(file));
        List<Map<String, Object>> first = tools.batches();
        assertEquals(1, first.size());
        String firstBatch = String.valueOf(first.get(0).get("batchId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstProposals = (List<Map<String, Object>>) first.get(0).get("proposals");
        String firstProposal = String.valueOf(firstProposals.get(0).get("proposalId"));
        assertEquals(withNote, Files.readString(file), "review mode must not write");

        String withExtra = withNote.replace("String note)", "String note, String extra)");
        Files.writeString(file, withExtra);
        tools.onChanged(List.of(file));
        List<Map<String, Object>> second = tools.batches();
        assertEquals(1, second.size(), "the stale pending batch must be discarded");
        String secondBatch = String.valueOf(second.get(0).get("batchId"));
        assertNotEquals(firstBatch, secondBatch, "the replacement must be a new batch");
        assertThrows(hr.hrg.jcodebuddy.ipc.ToolException.class, () -> tools.apply(firstProposal),
                "the discarded proposal must no longer be applicable");

        Files.writeString(file, baseline);
        tools.onChanged(List.of(file));
        assertTrue(tools.batches().isEmpty(), "undoing the edit leaves nothing to propose");
        assertEquals(baseline, Files.readString(file));
    }

    @Test
    void unannotatedRecordDoesNotGetAnAutomaticBuilder(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{"
                + "\"builder\":{\"activation\":\"disabled\"},"
                + "\"record-builder\":{\"activation\":\"explicit\",\"mode\":\"review\"},"
                + "\"record-interface-sync\":{\"activation\":\"disabled\"}"
                + "}}");
        writeAnnotation(root);
        Path file = root.resolve("R.java");
        String source = "record R(int value) {}\n";
        Files.writeString(file, source);
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertTrue(tools.batches().isEmpty(),
                "a record without its configured annotation must not be completed automatically");
        assertEquals(source, Files.readString(file));
    }

    @Test
    void recordToolFailsWhenItsAnnotationModuleIsNotConfigured(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"tools\":{\"record-builder\":{\"activation\":\"explicit\"}}}");
        Path file = root.resolve("R.java");
        Files.writeString(file, "@GenerateBuilder record R(int value) {}\n");
        AgentTools tools = new AgentTools(root);
        org.junit.jupiter.api.Assertions.assertThrows(hr.hrg.jcodebuddy.ipc.ToolException.class,
                () -> tools.preview(Map.of("tool", "record-builder", "file", "R.java", "line", 1)),
                "record tooling must refuse an unconfigured annotation location");
    }

    @Test
    void configuredAnnotationFilesAreCreatedAutomatically(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\","
                + "\"package\":\"test.gen\"},\"tools\":{\"record-builder\":{\"activation\":\"explicit\"}}}");
        new AgentTools(root);
        assertTrue(Files.isRegularFile(root.resolve(
                        "src/main/java/test/gen/GenerateBuilder.java")),
                "the builder annotation is created in the configured package");
        assertTrue(Files.isRegularFile(root.resolve(
                        "src/main/java/test/gen/GenerateRecordInterfaceSync.java")),
                "each tool creates its own annotation");
    }

    @Test
    void markedInterfaceWithoutRecordProposesTheRecord(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\","
                + "\"package\":\"test.gen\"},\"tools\":{"
                + "\"builder\":{\"activation\":\"disabled\"},"
                + "\"record-builder\":{\"activation\":\"disabled\"},"
                + "\"record-interface-sync\":{\"activation\":\"explicit\",\"mode\":\"review\"}"
                + "}}");
        writeAnnotation(root);
        Path file = root.resolve("Order.java");
        Files.writeString(file, "import test.gen.GenerateRecordInterfaceSync;\n"
                + "@GenerateRecordInterfaceSync\n"
                + "public interface Order extends Identifiable<Long> {\n"
                + "    long customerId();\n"
                + "    String item();\n"
                + "}\n");
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        List<Map<String, Object>> batches = tools.batches();
        assertEquals(1, batches.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) batches.get(0).get("proposals");
        assertEquals("record-interface-sync", proposals.get(0).get("tool"));
    }

    @Test
    void unmarkedRecordInterfaceMismatchIsNotSynced(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{"
                + "\"builder\":{\"activation\":\"disabled\"},"
                + "\"record-builder\":{\"activation\":\"disabled\"},"
                + "\"record-interface-sync\":{\"activation\":\"explicit\",\"mode\":\"review\"}"
                + "}}");
        writeAnnotation(root);
        Path file = root.resolve("I.java");
        Files.writeString(file, "interface I {\n    int a();\n}\nrecord R(int a, String b) implements I {\n}\n");
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        assertTrue(tools.batches().isEmpty(), "an unmarked record/interface pair must not be changed");
    }

    @Test
    void recordInterfaceMismatchIsProposedAutomatically(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{"
                + "\"builder\":{\"activation\":\"disabled\"},"
                + "\"record-builder\":{\"activation\":\"disabled\"},"
                + "\"record-interface-sync\":{\"activation\":\"explicit\",\"mode\":\"review\"}"
                + "}}");
        writeAnnotation(root);
        Path file = root.resolve("I.java");
        String source = "interface I {\n    int a();\n}\n@GenerateRecordInterfaceSync record R(int a, String b) implements I {\n}\n";
        Files.writeString(file, source);
        AgentTools tools = new AgentTools(root);
        tools.touchWebview();
        tools.onChanged(List.of(file));
        List<Map<String, Object>> batches = tools.batches();
        assertEquals(1, batches.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposals = (List<Map<String, Object>>) batches.get(0).get("proposals");
        assertEquals("record-interface-sync", proposals.get(0).get("tool"));
        assertEquals(source, Files.readString(file), "preview must not write");
    }

    @Test
    void explicitToolProposesWhenWebviewIsAliveAndActiveModeApplies(@TempDir Path root) throws Exception {
        Path conf = root.resolve(".jcodebuddy").resolve("conf").resolve("agent.json");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{\"builder\":{\"activation\":\"explicit\",\"mode\":\"active\"}}}");
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
        Files.writeString(conf, "{\"annotations\":{\"module\":\"core\",\"sourceRoot\":\"src/main/java\",\"package\":\"test.gen\"},\"tools\":{\"builder\":{\"activation\":\"auto\"},"
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
    private static void writeAnnotation(Path root) throws Exception {
        Path file = root.resolve("src/main/java/test/gen/GenerateBuilder.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package test.gen;\n\npublic @interface GenerateBuilder {}\n");
        Path sync = root.resolve("src/main/java/test/gen/GenerateRecordInterfaceSync.java");
        Files.writeString(sync, "package test.gen;\n\npublic @interface GenerateRecordInterfaceSync {}\n");
    }

}
