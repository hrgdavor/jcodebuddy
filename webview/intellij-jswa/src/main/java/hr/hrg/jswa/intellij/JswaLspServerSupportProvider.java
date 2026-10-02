package hr.hrg.jswa.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspServerSupportProvider;
import org.jetbrains.annotations.NotNull;

public class JswaLspServerSupportProvider implements LspServerSupportProvider {
    @Override
    public void fileOpened(@NotNull Project project, @NotNull VirtualFile file, @NotNull LspServerStarter starter) {
        String ext = file.getExtension();
        if ("js".equals(ext) || "ts".equals(ext) || "jsx".equals(ext) || "tsx".equals(ext)) {
            starter.ensureServerStarted(new JswaLspServerDescriptor(project));
        }
    }
}
