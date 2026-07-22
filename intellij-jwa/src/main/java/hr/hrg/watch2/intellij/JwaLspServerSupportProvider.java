package hr.hrg.watch2.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

public class JwaLspServerSupportProvider implements LspServerSupportProvider {
    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        if ("java".equals(file.getExtension())) {
            starter.ensureServerStarted(new JwaLspServerDescriptor(project));
        }
    }
}
