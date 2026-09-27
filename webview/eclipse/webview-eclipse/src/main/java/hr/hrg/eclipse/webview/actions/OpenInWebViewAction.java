package hr.hrg.eclipse.webview.actions;

import hr.hrg.eclipse.webview.view.WebViewPart;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.core.commands.IHandlerListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

/**
 * The handler behind the "Open in WebView" command: it takes the selection the menu contributed —
 * a resource from the navigator, or the active editor's file — and, when it is an HTML file, opens
 * (or shows) the view and loads the file into it. Anything that is not an HTML file is left
 * alone; the menu's visibility already keeps the command out of the menu for such files, and the
 * guard here keeps the keyboard shortcut honest as well.
 */
public class OpenInWebViewAction implements IHandler {

    /**
     * Runs the command. This train's {@code IHandler} returns the command's result, which the
     * command has none, and its listener and lifecycle methods are no-ops: the handler holds no
     * state of its own.
     */
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }
        IFile file = fileOf(event, page);
        if (file == null || !isHtml(file.getName())) {
            return null;
        }
        WebViewPart view = (WebViewPart) page.findView(WebViewPart.ID);
        if (view == null) {
            try {
                view = (WebViewPart) page.showView(WebViewPart.ID);
            } catch (PartInitException e) {
                return null;
            }
        }
        view.loadFile(file);
        IWorkbenchPart part = page.getActivePart();
        if (part == null || !(part instanceof WebViewPart)) {
            page.activate(view);
        }
        return null;
    }

    /**
     * The file the command acts on: the menu's element parameter, or — when the command runs
     * without one, as a keyboard shortcut does — the page selection's file, or the active
     * editor's file.
     */
    private IFile fileOf(ExecutionEvent event, IWorkbenchPage page) throws ExecutionException {
        Object parameter = event.getObjectParameterForExecution("element");
        if (parameter instanceof IAdaptable adaptable) {
            Object resource = adaptable.getAdapter(IFile.class);
            if (resource instanceof IFile file) {
                return file;
            }
        }
        Object selection = page.getSelection();
        if (selection instanceof IFile file) {
            return file;
        }
        IEditorPart editor = page.getActiveEditor();
        if (editor != null && editor.getEditorInput() instanceof FileEditorInput fileInput) {
            return fileInput.getFile();
        }
        return null;
    }

    /** Only HTML files: .html and .htm, case-insensitive. */
    private static boolean isHtml(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm");
    }

    @Override
    public void addHandlerListener(IHandlerListener listener) {
        // No state to notify.
    }

    @Override
    public void removeHandlerListener(IHandlerListener listener) {
        // No state to notify.
    }

    @Override
    public void dispose() {
        // Nothing to release.
    }

    /**
     * The command is always enabled; the menu's visibility (and the {@code fileOf} guard here)
     * is what keeps it from acting on a non-HTML file.
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isHandled() {
        return true;
    }
}
