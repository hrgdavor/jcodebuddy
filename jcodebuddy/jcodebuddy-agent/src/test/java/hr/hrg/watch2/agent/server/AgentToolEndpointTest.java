// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.ipc.ToolClient;
import hr.hrg.jcodebuddy.ipc.ToolEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolEndpointTest {

    @Test
    void theAgentPublishesItsVerbsOnTheToolSocket(@TempDir Path root) throws Exception {
        AgentMethods methods = new AgentMethods(root);
        ToolEndpoint endpoint = new ToolEndpoint(root, AgentMethods.TOOL, "JCodeBuddy Agent",
                "JCodeBuddy Agent", "", methods);
        endpoint.start();
        try (ToolClient client = ToolClient.connect(endpoint.descriptor())) {
            assertTrue(client.capabilities().contains("agent.status"));
            assertTrue(client.capabilities().contains("tool.batches"));
            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) client.call("agent.status", Map.of());
            assertEquals("running", status.get("status"));
            assertTrue(((java.util.List<?>) client.call("tool.list", Map.of())).size() >= 6);
        } finally {
            endpoint.stop();
        }
    }
}
