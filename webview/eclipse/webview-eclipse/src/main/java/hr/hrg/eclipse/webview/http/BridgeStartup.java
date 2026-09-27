package hr.hrg.eclipse.webview.http;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.RateLimiter;

/**
 * The slow half of starting a bridge, kept off the UI thread (plan R19): claiming a port walks the
 * file system and probes sockets, and a display thread waiting on that is how a frozen workbench is
 * reported. The job therefore runs on the platform's job manager and hands the answer back with
 * {@code Display.asyncExec}, never {@code syncExec} from the UI side.
 *
 * <p>One job serves every caller that asks about the same project while it runs: the second caller
 * hangs its continuation on the running job instead of starting a second claim that would race the
 * first for the same port (the plan's first-bind-wins rule, expressed as a queue rather than as two
 * servers). Every answer is delivered on the UI thread — {@code onSettled} first, the plugin's
 * bookkeeping, whose return value is what the waiting continuations then receive, so a bridge the
 * plugin refused to register (one made stale by a preference change mid-flight) is refused to the
 * callers as well, and none of them loads a page from a socket that is already closed.
 */
public final class BridgeStartup extends Job {

    private final Path project;
    private final Integer explicitPort;
    private final String tokenOverride;
    private final String ideName;
    private final EditorHost editorHost;
    private final RateLimiter limiter;
    private final Function<EclipseHttpBridge, EclipseHttpBridge> onSettled;

    private final List<Consumer<EclipseHttpBridge>> waiting = new ArrayList<>();
    private boolean settled;
    private EclipseHttpBridge result;

    /**
     * @param explicitPort the port preference at the time of the request, already validated (null when
     *                     the user named nothing — the resolution order continues into {@code HostConfig})
     * @param onSettled    the plugin's registration hook, run on the UI thread with the started bridge;
     *                     its answer is what every waiting continuation is given
     */
    public BridgeStartup(Path project, Integer explicitPort, String tokenOverride, String ideName,
                         EditorHost editorHost, RateLimiter limiter,
                         Function<EclipseHttpBridge, EclipseHttpBridge> onSettled) {
        super("JCodeBuddy webview bridge");
        this.project = project;
        this.explicitPort = explicitPort;
        this.tokenOverride = tokenOverride;
        this.ideName = ideName;
        this.editorHost = editorHost;
        this.limiter = limiter;
        this.onSettled = onSettled;
        setSystem(true);
        // No family, no rule: the plugin's per-project map is what keeps two jobs for one project
        // from existing, and a bridge for another project is meant to run beside this one (E7).
    }

    /**
     * Attaches one more caller to this start attempt. A caller that arrives after the job settled is
     * answered on the UI thread right away — the continuation never runs inline here, because the
     * caller may be the job's own worker thread.
     */
    public void addWaiting(Consumer<EclipseHttpBridge> continuation) {
        boolean alreadySettled;
        EclipseHttpBridge answer;
        synchronized (waiting) {
            alreadySettled = settled;
            answer = result;
            if (!alreadySettled) {
                waiting.add(continuation);
            }
        }
        if (alreadySettled) {
            Deliver.onUi(() -> continuation.accept(answer));
        }
    }

    /**
     * Ends a job that a restart cancelled before its thread ever started. A cancelled-never-run job
     * would otherwise leave its waiting callers holding continuations nothing will deliver: they are
     * answered with null — no bridge — and the view falls back the same way it does when the bridge
     * is off.
     */
    public void abandon() {
        List<Consumer<EclipseHttpBridge>> aboutToDeliver;
        synchronized (waiting) {
            if (settled) {
                return;
            }
            settled = true;
            result = null;
            aboutToDeliver = new ArrayList<>(waiting);
            waiting.clear();
        }
        Deliver.onUi(() -> {
            for (Consumer<EclipseHttpBridge> continuation : aboutToDeliver) {
                continuation.accept(null);
            }
        });
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        EclipseHttpBridge started = null;
        String note;
        try {
            EclipseHttpBridge.Outcome outcome = EclipseHttpBridge.start(project, explicitPort, editorHost,
                    limiter, tokenOverride, ideName);
            started = outcome.bridge();
            note = outcome.note();
        } catch (IOException | RuntimeException e) {
            note = "could not start the bridge for " + project + ": " + e;
        }
        if (note != null && !note.isEmpty()) {
            System.err.println("webview-eclipse: " + note);
        }

        // The registration and the drain happen together on the UI thread: ensureBridge queues
        // continuations there too, so serializing the settle there closes the window in which a
        // caller could queue behind a job that has already run.
        final EclipseHttpBridge startedBridge = started;
        Deliver.onUi(() -> {
            EclipseHttpBridge answer = onSettled.apply(startedBridge);
            List<Consumer<EclipseHttpBridge>> aboutToDeliver;
            synchronized (waiting) {
                settled = true;
                result = answer;
                aboutToDeliver = new ArrayList<>(waiting);
                waiting.clear();
            }
            for (Consumer<EclipseHttpBridge> continuation : aboutToDeliver) {
                continuation.accept(answer);
            }
        });
        return Status.OK_STATUS;
    }

    /** A tiny seam so the plugin and this job hand work to the display thread the same way. */
    static final class Deliver {
        private Deliver() {
        }

        static void onUi(Runnable work) {
            Display display = Display.getCurrent() == null ? Display.getDefault() : Display.getCurrent();
            if (display.isDisposed()) {
                return;
            }
            display.asyncExec(work);
        }
    }
}
