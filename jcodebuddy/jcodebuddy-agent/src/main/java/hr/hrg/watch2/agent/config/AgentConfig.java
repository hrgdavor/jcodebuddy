// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration for the Java Watch Agent.
 */
public class AgentConfig {
    private List<ToolSet> toolSets = new ArrayList<>();
    private String editorCommand; // e.g., "code --goto %f:%l" or "idea --line %l %f"
    private boolean webEnabled = true;
    private int webPort = 6666;
    private String webUser = "java_watch_agent";
    private String webPassword;
    private boolean applyFirst = false;

    public boolean isApplyFirst() {
        return applyFirst;
    }

    public void setApplyFirst(boolean applyFirst) {
        this.applyFirst = applyFirst;
    }

    public List<ToolSet> getToolSets() {
        return toolSets;
    }

    public void setToolSets(List<ToolSet> toolSets) {
        this.toolSets = toolSets;
    }

    public String getEditorCommand() {
        return editorCommand;
    }

    public void setEditorCommand(String editorCommand) {
        this.editorCommand = editorCommand;
    }

    public boolean isWebEnabled() {
        return webEnabled;
    }

    public void setWebEnabled(boolean webEnabled) {
        this.webEnabled = webEnabled;
    }

    public int getWebPort() {
        return webPort;
    }

    public void setWebPort(int webPort) {
        this.webPort = webPort;
    }

    public String getWebPassword() {
        return webPassword;
    }

    public void setWebPassword(String webPassword) {
        this.webPassword = webPassword;
    }

    public String getWebUser() {
        return webUser;
    }

    public void setWebUser(String webUser) {
        this.webUser = webUser;
    }

    public static class ToolSet {
        private String name;
        private List<String> include = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();
        private List<String> tools = new ArrayList<>();
        private String indent = "4s"; // Default to 4 spaces

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getInclude() {
            return include;
        }

        public void setInclude(List<String> include) {
            this.include = include;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude;
        }

        public List<String> getTools() {
            return tools;
        }

        public void setTools(List<String> tools) {
            this.tools = tools;
        }

        public String getIndent() {
            return indent;
        }

        public void setIndent(String indent) {
            this.indent = indent;
        }

        public String getResolvedIndentString() {
            if ("tab".equalsIgnoreCase(indent))
                return "\t";
            if (indent != null && indent.endsWith("s")) {
                try {
                    int count = Integer.parseInt(indent.substring(0, indent.length() - 1));
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < count; i++)
                        sb.append(" ");
                    return sb.toString();
                } catch (NumberFormatException e) {
                    // Ignore, fallback below
                }
            }
            return "    "; // Default to 4 spaces
        }
    }
}
