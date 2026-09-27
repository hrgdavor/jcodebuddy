package hr.hrg.eclipse.webview.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * What the view shows when the browser engine is not usable: the WebView2 state and the problem
 * the platform reported, so the user sees why the view is empty and what to install rather than
 * staring at a dead pane.
 */
public class UnsupportedEnginePanel extends Composite {

    public UnsupportedEnginePanel(Composite parent, BrowserFactory.Engine engine) {
        super(parent, SWT.NONE);
        setLayout(new FillLayout());
        String problem = engine.problem() == null ? "unknown" : engine.problem();
        Label message = new Label(this, SWT.WRAP);
        message.setText("The JCodeBuddy WebView needs the Microsoft Edge WebView2 runtime, which\n"
                + "this machine does not provide. Install it from the Microsoft site, then restart\n"
                + "Eclipse.\n\n"
                + "The platform reported: " + problem);
    }
}
