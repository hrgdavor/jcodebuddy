// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.server;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Project tool configuration: {@code .jcodebuddy/conf/agent.json}.
 *
 * <pre>{@code
 * { "tools": { "builder": { "activation": "explicit" } } }
 * }</pre>
 *
 * <p>Activation is {@code explicit} (only a marker/API request), {@code auto} (a source batch may
 * propose it) or {@code disabled}. Missing entries are explicit, so adding a tool never starts
 * generating without a project decision.</p>
 */
public final class AgentToolConfig {

    public static final String FILE = "agent.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> activation = new LinkedHashMap<>();
    private final Map<String, String> mode = new LinkedHashMap<>();
    private String annotationModule = "";
    private String annotationSourceRoot = "";
    private String annotationPackage = "";

    public static AgentToolConfig read(Path projectRoot) {
        AgentToolConfig config = new AgentToolConfig();
        if (projectRoot == null) return config;
        Path file = projectRoot.resolve(".jcodebuddy").resolve("conf").resolve(FILE);
        if (!Files.isRegularFile(file)) return config;
        try {
            Map<?, ?> root = MAPPER.readValue(Files.readString(file), Map.class);
            Object annotations = root.get("annotations");
            if (annotations instanceof Map<?, ?> map) {
                config.annotationModule = text(map.get("module"));
                config.annotationSourceRoot = text(map.get("sourceRoot"));
                config.annotationPackage = text(map.get("package"));
            }
            Object tools = root.get("tools");
            if (tools instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> settings) {
                        Object mode = settings.get("activation");
                        if (mode != null) {
                            config.activation.put(String.valueOf(entry.getKey()),
                                    String.valueOf(mode).toLowerCase());
                        }
                        Object applyMode = settings.get("mode");
                        if (applyMode != null) {
                            config.mode.put(String.valueOf(entry.getKey()),
                                    String.valueOf(applyMode).toLowerCase());
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException malformed) {
            // A malformed config must not make the agent unusable; explicit is the safe default.
        }
        return config;
    }

    public String activation(String toolId) {
        String mode = activation.get(toolId);
        return mode == null || mode.isBlank() ? "explicit" : mode;
    }

    public boolean auto(String toolId) {
        return "auto".equals(activation(toolId));
    }

    public boolean disabled(String toolId) {
        return "disabled".equals(activation(toolId)) || "off".equals(activation(toolId));
    }

    /** {@code review} keeps proposals pending; {@code active} applies them and leaves them revertible. */
    public String mode(String toolId) {
        String value = mode.get(toolId);
        return value == null || value.isBlank() ? "review" : value;
    }

    public boolean active(String toolId) {
        return "active".equals(mode(toolId)) || "apply".equals(mode(toolId))
                || "active".equals(activation(toolId));
    }

    /** The configured project-local marker annotation package root, or empty when unconfigured. */
    public String annotationPackage() {
        return annotationPackage;
    }

    /** The configured annotation source root, or empty when unconfigured. */
    public String annotationSourceRoot() {
        return annotationSourceRoot;
    }

    /** The configured annotation module, or empty when unconfigured. */
    public String annotationModule() {
        return annotationModule;
    }

    /**
     * Why tool annotations cannot be created, or an empty string when module/sourceRoot/package are set.
     * A missing annotation file is not a problem: {@link #ensureAnnotation} creates it.
     */
    public String annotationProblem() {
        if (annotationModule.isBlank() || annotationSourceRoot.isBlank() || annotationPackage.isBlank()) {
            return "tool annotations are not configured; set annotations.module, annotations.sourceRoot "
                    + "and annotations.package in .jcodebuddy/conf/agent.json";
        }
        return "";
    }

    /**
     * Ensure the project has {@code annotationName.java} in the configured annotation module/package. The
     * tool owns the annotation and creates it when absent, so a user does not have to know or maintain
     * annotation files. Returns an error only when the location is unconfigured or unsafe, or the write fails.
     */
    public String ensureAnnotation(Path projectRoot, String annotationName) {
        String problem = annotationProblem();
        if (!problem.isBlank()) return problem;
        String relative = annotationPackage.replace('.', '/') + "/" + annotationName + ".java";
        String sourceRootPath = annotationSourceRoot.replace('\\', '/').endsWith("/")
                ? annotationSourceRoot : annotationSourceRoot + "/";
        Path file = projectRoot.resolve(sourceRootPath).resolve(relative).normalize();
        if (!file.startsWith(projectRoot)) {
            return "the configured annotation module '" + annotationModule + "' resolves outside the project";
        }
        if (Files.isRegularFile(file)) return "";
        try {
            Files.createDirectories(file.getParent());
            String eol = System.lineSeparator();
            String source = "package " + annotationPackage + ";" + eol + eol
                    + "import java.lang.annotation.ElementType;" + eol
                    + "import java.lang.annotation.Retention;" + eol
                    + "import java.lang.annotation.RetentionPolicy;" + eol
                    + "import java.lang.annotation.Target;" + eol + eol
                    + "@Retention(RetentionPolicy.SOURCE)" + eol
                    + "@Target(ElementType.TYPE)" + eol
                    + "public @interface " + annotationName + " {" + eol + "}" + eol;
            Files.writeString(file, source);
            return "";
        } catch (IOException failed) {
            return "cannot create annotation " + annotationName + " at " + file + ": " + failed.getMessage();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
