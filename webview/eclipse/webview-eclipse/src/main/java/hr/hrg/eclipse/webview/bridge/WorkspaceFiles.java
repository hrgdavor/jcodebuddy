package hr.hrg.eclipse.webview.bridge;

import java.io.File;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

/**
 * The platform lookups the bridge needs: which workspace file an absolute path names, and which
 * project is open. Both read the platform state directly; no configuration is involved, so there
 * is nothing to persist.
 */
public final class WorkspaceFiles {

    private WorkspaceFiles() {
    }

    /**
     * The workspace file at this absolute path, or null when the workspace holds no such file. The
     * path is the navigator's resolved, forward-slashed spelling; the platform wants an OS string.
     */
    public static IFile find(String absolutePath) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IFile file = root.getFileForLocation(IPath.fromOSString(absolutePath));
        return file == null || !file.exists() ? null : file;
    }

    /**
     * The on-disk location of the project that is open — the active editor's file's project, or,
     * with no editor open, the page selection's — as a forward-slash path, or null when no project
     * is open at all.
     */
    public static String activeProjectRoot() {
        IWorkbenchPage page = activePage();
        if (page == null) {
            return null;
        }
        IEditorPart editor = page.getActiveEditor();
        if (editor != null && editor.getEditorInput() instanceof FileEditorInput fileInput) {
            return projectLocation(fileInput.getFile().getProject());
        }
        IStructuredSelection selection = (IStructuredSelection) page.getSelection();
        Object element = selection.getFirstElement();
        if (element instanceof IFile file) {
            return projectLocation(file.getProject());
        }
        if (element instanceof IProject project) {
            return projectLocation(project);
        }
        return null;
    }

    /**
     * The project's location as a forward-slash path. A location that is not absolute — a project
     * located relative to the workspace — is resolved against the workspace root instead. Public
     * because the view asks it which project a file belongs to before deciding bridge-versus-file
     * URL; the plugin identifies its running bridges by exactly this spelling.
     */
    public static String projectLocation(IProject project) {
        IPath location = project.getLocation();
        if (location != null && location.isAbsolute()) {
            return toForwardSlashes(location);
        }
        IPath rootLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (rootLocation == null) {
            return null;
        }
        return toForwardSlashes(rootLocation.append(new Path(project.getName())));
    }

    /** The platform's OS spelling, normalized to forward slashes for the navigator. */
    private static String toForwardSlashes(IPath path) {
        return path.toOSString().replace(File.separatorChar, '/');
    }

    private static IWorkbenchPage activePage() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getActivePage();
    }
}
