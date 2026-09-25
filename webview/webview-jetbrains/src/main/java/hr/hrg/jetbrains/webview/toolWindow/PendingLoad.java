package hr.hrg.jetbrains.webview.toolWindow;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Decides which URL the tool window shows first.
 *
 * <p>The rule is that a URL requested <b>before</b> the tool window existed wins over the default page,
 * and that the default page only fills a panel that has shown nothing. Without an owner for that rule,
 * the factory's "restore the last URL, or show the splash page" step ran after the parked URL had
 * already been delivered and overwrote it — which is why "Open in WebView Explorer" on a file left the
 * splash page on screen and never rendered the file.
 *
 * <p>Pure logic: it holds a URL and knows how to hand it to a panel, and nothing about browsers,
 * projects or services, so the ordering can be tested directly.
 */
public final class PendingLoad {

    /** True once some URL has been handed to a panel. */
    private boolean everLoaded;

    /** The URL waiting for a panel to exist, or null. */
    private String parked;

    /**
     * Requests a load.
     *
     * @return true when the URL was accepted (it will be delivered now if a panel already loaded
     *         something, or when the panel is created)
     */
    public synchronized boolean request(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        parked = url;
        return true;
    }

    /**
     * Hands the parked URL to {@code panelLoader}.
     *
     * @return true when a parked URL was delivered, so the caller must not apply its own default page
     */
    public synchronized boolean deliverTo(@NotNull Consumer<String> panelLoader) {
        String url = parked;
        if (url == null) {
            return false;
        }
        parked = null;
        panelLoader.accept(url);
        return true;
    }

    /** Records that a panel has now been given a URL. */
    synchronized void markLoaded() {
        everLoaded = true;
    }

    /** True once a panel has been given a URL. */
    public synchronized boolean isLoaded() {
        return everLoaded;
    }

    /** True when a URL is waiting for a panel to be created. */
    public synchronized boolean isWaiting() {
        return parked != null;
    }

    /** The waiting URL, or null. */
    public synchronized @Nullable String waitingUrl() {
        return parked;
    }
}
