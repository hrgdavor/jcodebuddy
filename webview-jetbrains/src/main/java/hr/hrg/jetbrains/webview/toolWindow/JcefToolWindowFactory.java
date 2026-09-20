package hr.hrg.jetbrains.webview.toolWindow;

import com.intellij.ide.BrowserUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import hr.hrg.jetbrains.webview.services.PluginStateService;
import hr.hrg.jetbrains.webview.settings.WebViewSettingsConfigurable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;

public class JcefToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        if (!JBCefApp.isSupported()) {
            JLabel label = new JLabel("JCEF is not supported on this runtime.");
            label.setHorizontalAlignment(SwingConstants.CENTER);
            Content content = ContentFactory.getInstance().createContent(label, "", false);
            toolWindow.getContentManager().addContent(content);
            return;
        }

        JBCefBrowser browser = JBCefBrowser.createBuilder()
                .setOffScreenRendering(false)
                .build();
        browser.getComponent().putClientProperty("JBCefBrowser.devTools", true);
        browser.getComponent().putClientProperty("JBCefBrowser", browser);

        new JcefBridgeNew(project, browser);

        // Address Bar
        JBTextField addressBar = new JBTextField();
        addressBar.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    String url = addressBar.getText();
                    if (!url.isEmpty()) {
                        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
                            // Try to see if it's a local file
                            File file = new File(url);
                            if (file.exists()) {
                                url = file.toURI().toString();
                            } else if (!url.contains("://")) {
                                url = "https://" + url;
                            }
                        }
                        browser.loadURL(url);
                    }
                }
            }
        });

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain()) {
                    String url = cefBrowser.getURL();
                    SwingUtilities.invokeLater(() -> addressBar.setText(url));
                    PluginStateService.getInstance(project).setLastUrl(url);
                }
            }
        }, browser.getCefBrowser());

        // Actions
        DefaultActionGroup actionGroup = new DefaultActionGroup();

        actionGroup.add(new DumbAwareAction("Back", "Go back", AllIcons.Actions.Back) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (browser.getCefBrowser().canGoBack()) {
                    browser.getCefBrowser().goBack();
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(browser.getCefBrowser().canGoBack());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        actionGroup.add(new DumbAwareAction("Forward", "Go forward", AllIcons.Actions.Forward) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (browser.getCefBrowser().canGoForward()) {
                    browser.getCefBrowser().goForward();
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(browser.getCefBrowser().canGoForward());
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });

        actionGroup.add(new DumbAwareAction("Refresh", "Reload the page", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                browser.getCefBrowser().reload();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });

        actionGroup.add(new DumbAwareAction("Settings", "Open settings", AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, WebViewSettingsConfigurable.class);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }
        });

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("JcefToolWindowToolbar", actionGroup,
                true);
        toolbar.setTargetComponent(addressBar);

        // Toolbar Panel
        JPanel toolbarPanel = new JPanel(new BorderLayout());
        toolbarPanel.add(toolbar.getComponent(), BorderLayout.WEST);
        toolbarPanel.add(addressBar, BorderLayout.CENTER);

        SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true, true);
        panel.putClientProperty("JBCefBrowser", browser);
        panel.setToolbar(toolbarPanel);
        panel.setContent(browser.getComponent());

        // Initial load
        String lastUrl = PluginStateService.getInstance(project).getLastUrl();
        if (lastUrl != null && !lastUrl.isEmpty()) {
            browser.loadURL(lastUrl);
        } else {
            loadContent(project, browser);
        }

        // Add to tool window
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(browser);
        toolWindow.getContentManager().addContent(content);
    }

    private void loadContent(Project project, JBCefBrowser browser) {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <title>WebView Explorer</title>\n" +
                "    <style>\n" +
                "        body { font-family: sans-serif; padding: 20px; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <h2>WebView Explorer</h2>\n" +
                "    <p>WebView Explorer. Use context menu to open a html file here. The file can use javascript to trigger opening a file</p>\n"
                +
                "</body>\n" +
                "</html>";
        browser.loadHTML(html);
    }

    public static void reloadWithFile(Project project, String url) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("WebView Explorer");
        if (toolWindow == null)
            return;
        Content content = toolWindow.getContentManager().getContent(0);
        if (content == null)
            return;
        JComponent component = content.getComponent();
        if (component == null)
            return;
        JBCefBrowser browser = (JBCefBrowser) component.getClientProperty("JBCefBrowser");
        if (browser != null) {
            PluginStateService.getInstance(project).setLastUrl(url);
            browser.loadURL(url);
            toolWindow.activate(null);
        }
    }
}
