package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import hr.hrg.jetbrains.webview.settings.WebViewSettingsConfigurable;
import org.jetbrains.annotations.NotNull;

/**
 * The four toolbar buttons of the WebView Explorer tool window.
 *
 * <p>They were anonymous inner classes inside the tool-window factory; naming them keeps the factory
 * about layout and gives each button one job and one place to be changed.
 */
final class WebViewActions {

    private WebViewActions() {
    }

    /** Goes back in the browser history. */
    static final class Back extends DumbAwareAction {

        private final WebViewPanel panel;

        Back(@NotNull WebViewPanel panel) {
            super("Back", "Go back", AllIcons.Actions.Back);
            this.panel = panel;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            panel.goBack();
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabled(panel.canGoBack());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** Goes forward in the browser history. */
    static final class Forward extends DumbAwareAction {

        private final WebViewPanel panel;

        Forward(@NotNull WebViewPanel panel) {
            super("Forward", "Go forward", AllIcons.Actions.Forward);
            this.panel = panel;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            panel.goForward();
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            event.getPresentation().setEnabled(panel.canGoForward());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** Reloads the current page. */
    static final class Refresh extends DumbAwareAction {

        private final WebViewPanel panel;

        Refresh(@NotNull WebViewPanel panel) {
            super("Refresh", "Reload the page", AllIcons.Actions.Refresh);
            this.panel = panel;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            panel.reload();
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    /** Opens this plugin's project settings. */
    static final class Settings extends DumbAwareAction {

        private final Project project;

        Settings(@NotNull Project project) {
            super("Settings", "Open WebView Explorer settings", AllIcons.General.Settings);
            this.project = project;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, WebViewSettingsConfigurable.class);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
