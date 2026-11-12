package hr.hrg.jetbrains.jcodebuddy.incremental;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import javax.swing.JPanel;

/**
 * The <b>JCodeBuddy Build</b> tool window: a readable, copyable log of source-root discovery, module
 * classpaths, daemon attachment, batches, compiles and failures. The same messages also go to
 * IntelliJ's {@code idea.log}.
 */
public final class JcodeBuddyLogToolWindowFactory implements ToolWindowFactory, DumbAware {

    static final String ID = "JCodeBuddy Build";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        IncrementalLogService logs = IncrementalLogService.getInstance(project);
        IncrementalBuildService service = project.getService(IncrementalBuildService.class);
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        area.setText(String.join("\n", logs.snapshot()));
        Consumer<String> listener = line -> ApplicationManager.getApplication().invokeLater(() -> {
            area.append(line);
            area.append("\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
        logs.addListener(listener);

        JButton rebuild = new JButton("Rebuild");
        rebuild.setToolTipText("Compile every source root again");
        rebuild.addActionListener(event -> service.rebuild());
        JButton reconnect = new JButton("Reconnect fs-meta");
        reconnect.setToolTipText("Drop the current fs-meta subscription and retry daemon discovery");
        reconnect.addActionListener(event -> service.reconnectFsMeta());
        JButton openLog = new JButton("Open Log Folder");
        openLog.setToolTipText("Open " + logs.file().getParent() + " in the file explorer");
        openLog.addActionListener(event -> openLogFolder(project, logs));
        JButton clearLog = new JButton("Clear Log");
        clearLog.setToolTipText("Clear the in-memory and file build log");
        clearLog.addActionListener(event -> {
            logs.clear();
            area.setText("");
            area.setCaretPosition(0);
        });
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.add(rebuild);
        toolbar.add(reconnect);
        toolbar.add(openLog);
        toolbar.add(clearLog);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        Content content = ContentFactory.getInstance().createContent(panel, "Build log", false);
        Disposable disposable = Disposer.newDisposable("JCodeBuddy Build log");
        content.setDisposer(disposable);
        Disposer.register(disposable, new Disposable() {
            @Override
            public void dispose() {
                logs.removeListener(listener);
            }
        });
        toolWindow.getContentManager().addContent(content);
    }

    private static void openLogFolder(Project project, IncrementalLogService logs) {
        logs.ensureDirectory();
        Path folder = logs.file().getParent();
        if (folder == null) {
            folder = logs.file().toAbsolutePath().getParent();
        }
        try {
            Files.createDirectories(folder);
            if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Messages.showErrorDialog(project, "The system file explorer is not available.",
                        "Open JCodeBuddy Log");
                return;
            }
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | UnsupportedOperationException failed) {
            Messages.showErrorDialog(project, "Cannot open " + folder + ": " + failed,
                    "Open JCodeBuddy Log");
        }
    }
}
