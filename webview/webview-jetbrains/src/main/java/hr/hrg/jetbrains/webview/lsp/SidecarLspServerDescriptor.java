package hr.hrg.jetbrains.webview.lsp;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Runs {@code webview/jwa-sidecar} as this project's Java language server.
 *
 * <p>Merged in step 3.0q from the earlier {@code webview/intellij-jwa} attempt, which is why the plugin now
 * declares {@code com.intellij.modules.java} — see the F8 note in {@code META-INF/plugin.xml}: a module the
 * plugin needs at runtime has to be declared there and not only on the Gradle compile classpath.</p>
 *
 * <p>The discovery rules are in {@link SidecarLaunch} and are unit-tested; what is here is the platform half:
 * reading the two configured values, asking the plugin for its own directory, and building the command line.
 * The two knobs are {@code PropertiesComponent} values under this plugin's namespace —
 * {@code webview.explorer.sidecarJavaHome} and {@code webview.explorer.sidecarJarPath} — which is the same kind
 * of setting the earlier attempt used, and they are deliberately not in the settings UI yet: the old attempt
 * had no UI either, and a half-filled settings page would be worse than a documented property.</p>
 */
public class SidecarLspServerDescriptor extends ProjectWideLspServerDescriptor {

    /** The project service name shown by the IDE in the language server's log and status. */
    public static final String NAME = "JWA Sidecar";

    private static final String JAVA_HOME_KEY = "webview.explorer.sidecarJavaHome";
    private static final String JAR_PATH_KEY = "webview.explorer.sidecarJarPath";

    public SidecarLspServerDescriptor(@NotNull Project project) {
        super(project, NAME);
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        return "java".equals(file.getExtension());
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() throws ExecutionException {
        // `SidecarLaunch.Exists` answers about a String path, so the filesystem check is wrapped rather than
        // handed over as a method reference: `Files.exists` takes a `Path` (the compiler caught that here).
        SidecarLaunch.Exists exists = candidate -> Files.exists(Path.of(candidate));

        PropertiesComponent properties = PropertiesComponent.getInstance();
        String javaExecutable = SidecarLaunch.javaExecutable(
                PropertiesComponent.getInstance(getProject()).getValue(JAVA_HOME_KEY),
                System.getenv("JAVA_HOME"),
                System.getProperty("os.name"),
                exists);

        List<String> candidates = SidecarLaunch.jarCandidates(
                PropertiesComponent.getInstance(getProject()).getValue(JAR_PATH_KEY),
                properties.getValue(JAR_PATH_KEY),
                bundledJar(),
                getProject().getBasePath());
        Optional<String> jar = SidecarLaunch.firstExisting(candidates, exists);
        if (jar.isEmpty()) {
            throw new ExecutionException(NAME + ": no sidecar JAR found. Build it with "
                    + "`bun scripts/mvn-jdk25.js -pl webview/jwa-sidecar -am package`, or set the "
                    + JAR_PATH_KEY + " property. Looked in: " + String.join(", ", candidates));
        }

        return new GeneralCommandLine()
                .withExePath(javaExecutable)
                .withParameters("-cp", jar.get(), SidecarLaunch.SIDECAR_MAIN_CLASS);
    }

    /**
     * The JAR bundled inside the installed plugin, or null when the plugin's own directory cannot be resolved
     * (a unit test, say) — in which case the other candidates are still tried.
     */
    private String bundledJar() {
        var plugin = PluginManagerCore.getPlugin(PluginId.getId("hr.hrg.jetbrains.webview"));
        if (plugin == null) {
            return null;
        }
        Path bundled = plugin.getPluginPath().resolve("sidecar").resolve(SidecarLaunch.SIDECAR_JAR_NAME);
        return bundled.toString();
    }
}
