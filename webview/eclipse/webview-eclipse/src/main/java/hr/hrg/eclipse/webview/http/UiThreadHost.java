package hr.hrg.eclipse.webview.http;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

import hr.hrg.eclipse.webview.bridge.EclipseEditorHost;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.TextEdit;
import hr.hrg.webview.core.TextRange;

/**
 * The editor host as the HTTP transport sees it: every call is handed to the SWT display thread
 * before it touches the workbench, and the caller waits for the real answer (plan R19: an
 * implementation that must run on a UI thread is responsible for marshalling — the core's
 * {@link EditorHost} contract is explicit that it does not marshal for anyone).
 *
 * <p>The wrapping is a separate class rather than code inside {@link EclipseEditorHost} because the
 * two callers differ: the injected bridge already arrives on the display thread — the browser
 * delivers page messages there — while {@code EclipseHttpBridge} answers requests on its own worker
 * pool. A call that is <em>already</em> on the UI thread runs inline here, so the same wrapped
 * instance serves both paths without adding a round trip to the one that needs none.
 *
 * <p>Blocking is safe in this direction only: the display thread never waits on an HTTP worker
 * (a page load is an asynchronous {@code setUrl}), so a worker waiting on the display cannot close
 * the loop the platform's deadlock rule (R10) is about.
 */
public final class UiThreadHost implements EditorHost {

    private final EclipseEditorHost delegate;
    private final Display display;

    public UiThreadHost(EclipseEditorHost delegate, Display display) {
        this.delegate = delegate;
        this.display = display;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public Set<String> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public String lineNavigation() {
        return delegate.lineNavigation();
    }

    @Override
    public String lineNavigationNote() {
        return delegate.lineNavigationNote();
    }

    @Override
    public boolean openFileAt(String absolutePath, int line, int column) {
        return onUiThread(() -> delegate.openFileAt(absolutePath, line, column));
    }

    @Override
    public boolean reveal(String absolutePath) {
        return onUiThread(() -> delegate.reveal(absolutePath));
    }

    @Override
    public boolean select(String absolutePath, TextRange range) {
        return onUiThread(() -> delegate.select(absolutePath, range));
    }

    @Override
    public boolean applyEdit(String absolutePath, List<TextEdit> edits) {
        return onUiThread(() -> delegate.applyEdit(absolutePath, edits));
    }

    /**
     * Runs one editor action on the display thread and reports its answer. A disposed or dying
     * workbench answers false rather than throwing: an HTTP caller should get a 404, not a stack
     * trace out of the executor.
     */
    private boolean onUiThread(BooleanSupplier action) {
        if (display.isDisposed()) {
            return false;
        }
        if (display.getThread() == Thread.currentThread()) {
            return action.getAsBoolean();
        }
        final boolean[] result = {false};
        try {
            display.syncExec(() -> result[0] = action.getAsBoolean());
        } catch (SWTException e) {
            // The workbench closed between the check and the dispatch. Refused, like any host that
            // could not act.
            return false;
        }
        return result[0];
    }
}
