// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import hr.hrg.jcodebuddy.ipc.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMethodsTest {

    @Test
    void statusAndToolCatalogAreUsableWithoutAProjectDaemon(@TempDir Path root) throws Exception {
        AgentMethods methods = new AgentMethods(root);
        assertTrue(methods.capabilities().contains("agent.status"));
        assertTrue(methods.capabilities().contains("tool.batches"));

        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) methods.call("agent.status", Map.of());
        assertEquals("running", status.get("status"));
        assertEquals(root.toString().replace('\\', '/'), status.get("root"));
        assertEquals(0, status.get("batches"));
        assertFalse((Boolean) status.get("watching"));

        List<?> tools = (List<?>) methods.call("tool.list", Map.of());
        assertTrue(tools.stream().anyMatch((tool) -> "builder".equals(((Map<?, ?>) tool).get("id"))));
    }

    @Test
    void aMissingBatchIdIsAnInvalidParamsRefusal(@TempDir Path root) {
        AgentMethods methods = new AgentMethods(root);
        ToolException refused = org.junit.jupiter.api.Assertions.assertThrows(ToolException.class,
                () -> methods.call("tool.applyBatch", Map.of()));
        assertEquals(ToolException.INVALID_PARAMS, refused.code());
    }

    @Test
    void anUnknownMethodUsesTheToolRefusalCode(@TempDir Path root) {
        AgentMethods methods = new AgentMethods(root);
        ToolException refused = org.junit.jupiter.api.Assertions.assertThrows(ToolException.class,
                () -> methods.call("agent.nope", Map.of()));
        assertEquals(ToolException.METHOD_NOT_FOUND, refused.code());
    }
}
