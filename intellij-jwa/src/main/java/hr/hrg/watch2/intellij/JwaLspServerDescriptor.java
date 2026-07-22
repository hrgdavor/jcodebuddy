package hr.hrg.watch2.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.intellij.ide.util.PropertiesComponent;
import java.io.File;

public class JwaLspServerDescriptor extends ProjectWideLspServerDescriptor {

    public JwaLspServerDescriptor(@NotNull Project project) {
        super(project, "JWA");
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return "java".equals(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() throws ExecutionException {
        Path jarPath = findSidecarJar();
        if (jarPath == null || !Files.exists(jarPath)) {
            throw new ExecutionException("JWA Sidecar JAR not found. Please build it first or check plugin installation.");
        }

        String javaPath = findJavaExecutable();

        return new GeneralCommandLine()
                .withExePath(javaPath)
                .withParameters("-cp", jarPath.toString(), "hr.hrg.watch2.sidecar.SidecarApp");
    }

    private String findJavaExecutable() {
        String isWindows = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        String javaBin = "java" + isWindows;

        // 1. Check IntelliJ Properties (Plugin Setting)
        String customPath = PropertiesComponent.getInstance().getValue("hr.hrg.watch2.javaPath");
        if (customPath != null && !customPath.isEmpty()) {
            File bin = new File(customPath, "bin/" + javaBin);
            if (bin.exists()) return bin.getAbsolutePath();
        }

        // 2. Check JAVA_HOME
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            File bin = new File(javaHome, "bin/" + javaBin);
            if (bin.exists()) return bin.getAbsolutePath();
        }

        // 3. Fallback to PATH
        return javaBin;
    }

    private Path findSidecarJar() {
        // 1. Try project-level property (Custom build per project)
        String projectJarPath = PropertiesComponent.getInstance(getProject()).getValue("hr.hrg.watch2.jarPath");
        if (projectJarPath != null && !projectJarPath.isEmpty()) {
            Path path = Paths.get(projectJarPath);
            if (Files.exists(path)) return path;
        }

        // 2. Try global property
        String globalJarPath = PropertiesComponent.getInstance().getValue("hr.hrg.watch2.jarPath");
        if (globalJarPath != null && !globalJarPath.isEmpty()) {
            Path path = Paths.get(globalJarPath);
            if (Files.exists(path)) return path;
        }

        // 3. Try bundled location (production)
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("hr.hrg.watch2.intellij"));
        if (plugin != null) {
            Path bundledPath = plugin.getPluginPath().resolve("sidecar/jwa-sidecar.jar");
            if (Files.exists(bundledPath)) return bundledPath;
        }

        // 4. Try relative path (development)
        String basePath = getProject().getBasePath();
        if (basePath != null) {
            Path devPath = Paths.get(basePath, "jwa-sidecar/target/jwa-sidecar.jar");
            if (Files.exists(devPath)) return devPath;
        }

        return null;
    }
}
