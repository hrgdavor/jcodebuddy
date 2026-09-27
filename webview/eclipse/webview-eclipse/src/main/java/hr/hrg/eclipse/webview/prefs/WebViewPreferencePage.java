package hr.hrg.eclipse.webview.prefs;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * The preference page: two booleans, one row each. It is the place where the defaults are
 * declared, so the FieldEditors' {@code load()} sees them on first use; the reader in
 * {@link WebViewPreferences} never writes a default itself.
 */
public class WebViewPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private BooleanFieldEditor bridgeEditor;
    private BooleanFieldEditor splashEditor;

    @Override
    public void init(IWorkbench workbench) {
        IPreferenceStore store = workbench.getPreferenceStore();
        setPreferenceStore(store);
        store.setDefault(WebViewPreferences.BRIDGE_ENABLED_KEY, true);
        store.setDefault(WebViewPreferences.SPLASH_ON_OPEN_KEY, true);
    }

    /**
     * The page's widget: one row per boolean, and the composite itself is what the dialog
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
        bridgeEditor.load();
        splashEditor.load();
        bridgeEditor.fillIntoGrid(composite, 1);
        splashEditor.fillIntoGrid(composite, 1);
        return composite;
    }

    /**
     * Stores both values and reports that the page is valid, as the platform's
     * {@code performOk} contract in this train requires.
     */
    @Override
    public boolean performOk() {
        bridgeEditor.store();
        splashEditor.store();
        return true;
    }
}
