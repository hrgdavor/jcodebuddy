// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent;

import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import hr.hrg.watch2.agent.config.AgentConfig;
import hr.hrg.watch2.agent.server.AgentMethods;
import hr.hrg.watch2.agent.server.AgentPageInstaller;
import hr.hrg.watch2.agent.server.CommandServer;
import hr.hrg.jcodebuddy.ipc.ToolEndpoint;

public class WatchAgent {
    public static void main(String[] args) throws Exception {
        System.out.println("JCodeBuddy Agent initializing...");

        Path root = Paths.get(".").toAbsolutePath().normalize();
        File configFile = root.resolve(".watch_agent.conf").toFile();
        ObjectMapper mapper = new ObjectMapper();
        AgentConfig config;
        if (configFile.exists()) {
            config = mapper.readValue(configFile, AgentConfig.class);
        } else {
            config = new AgentConfig();
        }

        boolean saveNeeded = false;
        if (config.isWebEnabled() && (config.getWebPassword() == null || config.getWebPassword().isEmpty())) {
            config.setWebPassword(generateRandomPassword(16));
            System.out.println("Generated web password: " + config.getWebPassword());
            saveNeeded = true;
        }
        if (!configFile.exists() || saveNeeded) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, config);
        }

        AgentMethods methods = new AgentMethods(root);
        ProjectWatcher watcher = new ProjectWatcher(root, methods.tools()::onChanged);
        methods.setWatcher(watcher);
        watcher.start();

        ToolEndpoint toolEndpoint = new ToolEndpoint(root, AgentMethods.TOOL, "JCodeBuddy Agent",
                "JCodeBuddy Agent", "", methods);
        try {
            toolEndpoint.start();
            System.out.println("Agent tool socket: " + toolEndpoint.descriptor().socket());
        } catch (Exception alreadyServing) {
            System.out.println("Agent tool socket not started: " + alreadyServing.getMessage());
            toolEndpoint = null;
        }
        try {
            AgentPageInstaller.install(root);
        } catch (RuntimeException installFailure) {
            System.out.println("Agent dashboard not installed: " + installFailure.getMessage());
        }

        AtomicReference<CommandServer> apiServer = new AtomicReference<>();
        if (config.isWebEnabled()) {
            System.out.println("Starting Command Server on port " + config.getWebPort() + "...");
            CommandServer server = new CommandServer(config.getWebPort(), root, config.getWebUser(),
                    config.getWebPassword(), methods);
            server.start();
            apiServer.set(server);
        } else {
            System.out.println("Web interface is disabled in configuration.");
        }

        final ToolEndpoint endpoint = toolEndpoint;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CommandServer server = apiServer.get();
            if (server != null) server.stop();
            if (endpoint != null) endpoint.stop();
            watcher.stop();
            System.out.println("Agent closed.");
        }, "jcodebuddy-agent-shutdown"));

        System.out.println("Agent running; press Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }

    private static String generateRandomPassword(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
