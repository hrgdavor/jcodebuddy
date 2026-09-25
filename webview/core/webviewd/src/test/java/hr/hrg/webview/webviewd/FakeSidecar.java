package hr.hrg.webview.webviewd;

import java.util.ArrayList;
import java.util.List;

/**
 * A sidecar that answers from a script and records what it was asked to do.
 *
 * <p>Shared by {@link LspHostTest} (what the adapter forwards and when it refuses) and
 * {@link WebviewServerTest} (what {@code /health} and the manifest then say), because both need the same
 * thing: a {@link SidecarClient} whose answers the test controls and whose calls it can inspect.
 */
final class FakeSidecar implements SidecarClient {

    /** What {@code /health} answers; null means "the process is not there". */
    String healthBody;
    boolean acceptJump = true;
    boolean acceptEdit = true;
    int healthCalls;
    final List<String> jumps = new ArrayList<>();
    final List<String> edits = new ArrayList<>();

    /** The shared health document with the given capability list — the only part these tests vary. */
    static String health(String capabilities) {
        return "{\"plugin\":\"hr.hrg.watch2.sidecar\",\"port\":7979,\"allowedOrigins\":0,"
                + "\"tokenRequired\":true,\"bridgeVersion\":1,\"capabilities\":[" + capabilities + "]}";
    }

    /** A sidecar with an attached editor, which is the state the spike needs. */
    static FakeSidecar withEditorAttached() {
        FakeSidecar sidecar = new FakeSidecar();
        sidecar.healthBody = health("\"edit\",\"open\",\"select\"");
        return sidecar;
    }

    @Override
    public String describe() {
        return "http://127.0.0.1:7979 (fake sidecar)";
    }

    @Override
    public String health() {
        healthCalls++;
        return healthBody;
    }

    @Override
    public boolean jump(String absolutePath, int line, int column) {
        jumps.add(absolutePath + ":" + line + ":" + column);
        return acceptJump;
    }

    @Override
    public boolean applyEdit(String absolutePath, java.util.List<hr.hrg.webview.core.TextEdit> edits) {
        edits.forEach(edit -> this.edits.add(absolutePath + ":" + edit.startLine() + ":" + edit.startColumn()
                + "->" + edit.newText()));
        return acceptEdit;
    }
}
