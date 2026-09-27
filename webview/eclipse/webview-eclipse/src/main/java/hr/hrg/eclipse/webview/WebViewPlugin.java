package hr.hrg.eclipse.webview;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

/**
 * The bundle's activator. It exists because the platform's plugin.xml names it, and because the
 * other classes need a static handle to the plugin; it carries no logic of its own — the view,
 * the bridge and the command each do their own work.
 */
public class WebViewPlugin extends Plugin {

    /** The bundle's symbolic name, which plugin.xml carries as its id. */
    public static final String PLUGIN_ID = "hr.hrg.eclipse.webview";

    private static WebViewPlugin instance;

    /**
     * The platform's activator methods declare checked exceptions on this train, so the overrides
     * carry them as well.
     */
    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    /** The running plugin, or null when the platform is not up. */
    public static WebViewPlugin getInstance() {
        return instance;
    }
}
