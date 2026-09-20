package hr.hrg.jetbrains.webview.toolWindow;

import hr.hrg.jetbrains.webview.services.HttpBridgeService;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JcefBridgeNew {
    private final Project project;
    private final JBCefBrowserBase browser;
    private final JBCefJSQuery jsQuery;

    public JcefBridgeNew(Project project, JBCefBrowserBase browser) {
        this.project = project;
        this.browser = browser;
        this.jsQuery = JBCefJSQuery.create(browser);

        jsQuery.addHandler(msg -> {
            handleMessage(msg);
            return new JBCefJSQuery.Response("OK");
        });

        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                String code = "window.openFile = function(path, line, col) { " +
                        "  var msg = JSON.stringify({filePath: path, line: line || 1, column: col || 1});" +
                        "  " + jsQuery.inject("msg") +
                        "};";
                browser.executeJavaScript(code, browser.getURL(), 0);
            }
        }, browser.getCefBrowser());
    }

    private void handleMessage(String msg) {
        try {
            Pattern pathPattern = Pattern.compile("\"filePath\":\\s*\"(.*?)\"");
            Pattern linePattern = Pattern.compile("\"line\":\\s*\"?(\\d+)\"?");
            Pattern columnPattern = Pattern.compile("\"column\":\\s*\"?(\\d+)\"?");

            Matcher pathMatch = pathPattern.matcher(msg);
            Matcher lineMatch = linePattern.matcher(msg);
            Matcher columnMatch = columnPattern.matcher(msg);

            if (pathMatch.find()) {
                String filePath = pathMatch.group(1);
                int line = lineMatch.find() ? Integer.parseInt(lineMatch.group(1)) : 1;
                int column = columnMatch.find() ? Integer.parseInt(columnMatch.group(1)) : 1;

                System.out.println("[JcefBridge] Parsed: file=" + filePath + ", line=" + line + ", col=" + column);

                HttpBridgeService.getInstance(project).navigateToFile(filePath, line, column);
            } else {
                System.out.println("[JcefBridge] Could not parse filePath in message: " + msg);
            }
        } catch (Exception e) {
            System.out.println("[JcefBridge] Error handling message: " + e.getMessage());
        }
    }
}
