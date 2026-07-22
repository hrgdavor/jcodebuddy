package hr.hrg.jswa.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class JswaLspServerDescriptor extends ProjectWideLspServerDescriptor {

    public JswaLspServerDescriptor(@NotNull Project project) {
        super(project, "JSWA");
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        String ext = file.getExtension();
        return "js".equals(ext) || "ts".equals(ext) || "jsx".equals(ext) || "tsx".equals(ext);
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() throws ExecutionException {
        String bunPath = findBunExecutable();
        Path serverScript = findServerScript();

        if (serverScript == null || !Files.exists(serverScript)) {
            throw new ExecutionException("JSWA Sidecar script not found.");
        }

        return new GeneralCommandLine()
                .withExePath(bunPath)
                .withParameters("run", serverScript.toString());
    }

    private Path findServerScript() {
        // 1. Try project-level property (Custom build per project)
        String projectScriptPath = PropertiesComponent.getInstance(getProject()).getValue("hr.hrg.jswa.scriptPath");
        if (projectScriptPath != null && !projectScriptPath.isEmpty()) {
            Path path = Paths.get(projectScriptPath);
            if (Files.exists(path)) return path;
        }

        // 2. Try global property
        String globalScriptPath = PropertiesComponent.getInstance().getValue("hr.hrg.jswa.scriptPath");
        if (globalScriptPath != null && !globalScriptPath.isEmpty()) {
            Path path = Paths.get(globalScriptPath);
            if (Files.exists(path)) return path;
        }

        // 3. Try bundled location (production)
        // Note: For Bun/TS, we might bundle the index.ts directly in the plugin resources
        var plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("hr.hrg.jswa.intellij"));
        if (plugin != null) {
            Path bundledPath = plugin.getPluginPath().resolve("sidecar/index.ts");
            if (Files.exists(bundledPath)) return bundledPath;
        }

        // 4. Try relative path (development)
        String basePath = getProject().getBasePath();
        if (basePath != null) {
            Path devPath = Paths.get(basePath, "../jswa-core/index.ts").normalize();
            if (Files.exists(devPath)) return devPath;
        }

        return null;
    }

    private String findBunExecutable() {
        String isWindows = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        String bunBin = "bun" + isWindows;

        // 1. Check IntelliJ Properties (Plugin Setting)
        String customPath = PropertiesComponent.getInstance().getValue("hr.hrg.jswa.bunPath");
        if (customPath != null && !customPath.isEmpty()) {
            File bin = new File(customPath);
            if (bin.exists()) return bin.getAbsolutePath();
            // If it's a directory, check for bun inside
            File binInDir = new File(customPath, bunBin);
            if (binInDir.exists()) return binInDir.getAbsolutePath();
        }

        // 2. Fallback to PATH
        return bunBin;
    }
}
