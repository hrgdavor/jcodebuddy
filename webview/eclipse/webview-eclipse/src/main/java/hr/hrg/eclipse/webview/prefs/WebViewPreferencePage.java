package hr.hrg.eclipse.webview.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import hr.hrg.eclipse.webview.WebViewPlugin;
import hr.hrg.webview.core.HostConfig;

/**
 * The preference page: two booleans and two texts, one row each. It is the place where the defaults
 * are declared, so the FieldEditors' {@code load()} sees them on first use; the reader in
 * {@link WebViewPreferences} never writes a default itself.
 *
 * <p>A changed port or token is not a note for later: the page tells the plugin to retire the
 * running bridges when either one moved, so the next request re-claims under the new answer instead
 * of leaving the reader at a socket that no preference asked for. Published descriptors survive the
 * retirement (DEC-033) — the next start asks for the same port and finds or moves it exactly as a
 * workbench restart would.
 */
public class WebViewPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private BooleanFieldEditor bridgeEditor;
    private BooleanFieldEditor splashEditor;
    private StringFieldEditor portEditor;
    private StringFieldEditor tokenEditor;

    private String initialPort;
    private String initialToken;

    @Override
    public void init(IWorkbench workbench) {
        IPreferenceStore store = workbench.getPreferenceStore();
        setPreferenceStore(store);
        store.setDefault(WebViewPreferences.BRIDGE_ENABLED_KEY, true);
        store.setDefault(WebViewPreferences.SPLASH_ON_OPEN_KEY, true);
        store.setDefault(WebViewPreferences.PORT_KEY, "");
        store.setDefault(WebViewPreferences.TOKEN_KEY, "");
    }

    /**
     * The page's widget: one row per setting, and the composite itself is what the dialog
     * places, because in this train {@code PreferencePage.createContents} hands back the
     * top-level control rather than building it in place.
     */
    @Override
    protected Control createContents(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout gridLayout = new GridLayout(1, false);
        gridLayout.marginWidth = 0;
        gridLayout.marginHeight = 0;
        composite.setLayout(gridLayout);
        bridgeEditor = new BooleanFieldEditor(
                WebViewPreferences.BRIDGE_ENABLED_KEY,
                "Inject the bridge script into pages",
                composite);
        bridgeEditor.setPreferenceStore(getPreferenceStore());
        splashEditor = new BooleanFieldEditor(
                WebViewPreferences.SPLASH_ON_OPEN_KEY,
                "Show the splash page when the view opens",
                composite);
        splashEditor.setPreferenceStore(getPreferenceStore());
        portEditor = new StringFieldEditor(
                WebViewPreferences.PORT_KEY,
                "HTTP bridge port (empty leaves the bridge off):",
                composite);
        portEditor.setPreferenceStore(getPreferenceStore());
        tokenEditor = new StringFieldEditor(
                WebViewPreferences.TOKEN_KEY,
                "Bridge token (empty generates one in .jcodebuddy/webview/token):",
                composite);
        tokenEditor.setPreferenceStore(getPreferenceStore());
        bridgeEditor.load();
        splashEditor.load();
        portEditor.load();
        tokenEditor.load();
        bridgeEditor.fillIntoGrid(composite, 1);
        splashEditor.fillIntoGrid(composite, 1);
        portEditor.fillIntoGrid(composite, 1);
        tokenEditor.fillIntoGrid(composite, 1);
        initialPort = getPreferenceStore().getString(WebViewPreferences.PORT_KEY);
        initialToken = getPreferenceStore().getString(WebViewPreferences.TOKEN_KEY);
        return composite;
    }

    /**
     * Stores every value and reports that the page is valid, as the platform's {@code performOk}
     * contract in this train requires. A port the bridge cannot use is refused the same way the
     * reader would: the page names the problem, the value stays as it typed, and the bridge stays
     * off until the text is one it can use.
     */
    @Override
    public boolean performOk() {
        bridgeEditor.store();
        splashEditor.store();
        portEditor.store();
        tokenEditor.store();
        HostConfig.PortPreference port = WebViewPreferences.bridgePort();
        setErrorMessage(port.problem().isEmpty() ? null : port.problem());
        String nowPort = getPreferenceStore().getString(WebViewPreferences.PORT_KEY);
        String nowToken = getPreferenceStore().getString(WebViewPreferences.TOKEN_KEY);
        if (!nowPort.equals(initialPort) || !nowToken.equals(initialToken)) {
            WebViewPlugin plugin = WebViewPlugin.getInstance();
            if (plugin != null) {
                plugin.restartBridges();
            }
            initialPort = nowPort;
            initialToken = nowToken;
        }
        return true;
    }
}
