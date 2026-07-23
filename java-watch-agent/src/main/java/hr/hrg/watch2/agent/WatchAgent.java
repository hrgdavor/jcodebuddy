// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent;

import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import hr.hrg.watch2.agent.config.AgentConfig;
import hr.hrg.watch2.agent.server.CommandServer;
import hr.hrg.watch2.agent.tools.*;
import hr.hrg.watch2.agent.ui.*;

public class WatchAgent {
    public static void main(String[] args) throws Exception {
        System.out.println("Java Watch Agent Initializing...");

        Path root = Paths.get(".").toAbsolutePath().normalize();
        File configFile = root.resolve(".watch_agent.conf").toFile();

        ObjectMapper mapper = new ObjectMapper();
        AgentConfig config;
        if (configFile.exists()) {
            config = mapper.readValue(configFile, AgentConfig.class);
        } else {
            System.out.println("No .watch_agent.conf found, using default empty configuration.");
            config = new AgentConfig();
        }

        ToolRegistry globalRegistry = new ToolRegistry();
        globalRegistry.register(new HelloTool());
        globalRegistry.register(new BuilderGenerator());
        globalRegistry.register(new AccessorGenerator("getters", true, false));
        globalRegistry.register(new AccessorGenerator("setters", false, true));
        globalRegistry.register(new AccessorGenerator("accessors", true, true));
        globalRegistry.register(new ConstructorGenerator());
        globalRegistry.register(new RecordBuilderGenerator());
        if (config.getToolSets().isEmpty()) {
            AgentConfig.ToolSet defaultSet = new AgentConfig.ToolSet();
            defaultSet.setName("java");
            defaultSet.setInclude(List.of("src/main/java/**/*", "src/main/resources/**/*"));
            defaultSet.setTools(
                    List.of("hello", "builder", "getters", "setters", "accessors", "constructor", "record_builder"));
            config.getToolSets().add(defaultSet);
            System.out.println("No toolsets defined, added default 'java' toolset.");
        }

        boolean saveNeeded = false;
        if (config.isWebEnabled() && (config.getWebPassword() == null || config.getWebPassword().isEmpty())) {
            config.setWebPassword(generateRandomPassword(16));
            System.out.println("Generated web password: " + config.getWebPassword());
            saveNeeded = true;
        }

        if (saveNeeded || config.getToolSets().size() == 1 && "java".equals(config.getToolSets().get(0).getName())) {
            // we should save if password was generated OR if we just added the default
            // toolset (and it's the only one)
            // however, to be safe, I'll just check if the file didn't exist or we added
            // things.
            // Actually, if configFile didn't exist, we definitely want to save.
            if (!configFile.exists() || saveNeeded) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, config);
            }
        }

        List<ToolSetAgent> agents = new ArrayList<>();
        PendingActionManager manager = new PendingActionManager();
        InteractiveSession session = new InteractiveSession(agents, config, manager);

        for (AgentConfig.ToolSet setConfig : config.getToolSets()) {
            ToolSetAgent agent = new ToolSetAgent(root, setConfig, globalRegistry);
            agent.setActionCallback(session::addAction);
            agent.load();
            System.out.println("Initialized ToolSet: " + setConfig.getName());
            agents.add(agent);
        }

        // Initial scan for all agents
        System.out.println("Performing initial scan...");
        for (ToolSetAgent agent : agents) {
            agent.scan();
        }

        ProjectWatcher watcher = new ProjectWatcher(root, agents);
        watcher.start();

        CommandServer apiServer = null;
        if (config.isWebEnabled()) {
            System.out.println("Starting Command Server on port " + config.getWebPort() + "...");
            apiServer = new CommandServer(config.getWebPort(), root, agents, manager, config.getWebUser(),
                    config.getWebPassword(),
                    config.isApplyFirst());
            apiServer.start();
        } else {
            System.out.println("Web interface is disabled in configuration.");
        }

        session.startLoop();

        if (apiServer != null)
            apiServer.stop();
        watcher.stop();
        System.out.println("Agent closed.");
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
