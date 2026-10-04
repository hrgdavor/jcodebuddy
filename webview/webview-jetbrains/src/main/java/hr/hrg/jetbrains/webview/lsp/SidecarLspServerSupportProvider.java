package hr.hrg.jetbrains.webview.lsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Offers the sidecar as a language server to any Java file that is opened.
 *
 * <p>Merged in step 3.0q from the earlier {@code webview/intellij-jwa} attempt. The platform calls this for
 * every opened file, so the filter here is what keeps the sidecar from being started for a project that has no
 * Java in it: {@link SidecarLspServerDescriptor#isSupportedFile} repeats the check, and
 * {@code starter.ensureServerStarted} is what makes one server per project rather than one per file.</p>
 */
public class SidecarLspServerSupportProvider implements LspServerSupportProvider {

    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file,
                           @NotNull LspServerSupportProvider.LspServerStarter starter) {
        if ("java".equals(file.getExtension())) {
            starter.ensureServerStarted(new SidecarLspServerDescriptor(project));
        }
    }
}
