package hr.hrg.webview.core;

import java.util.Set;

/**
 * The host that drives nothing: a headless sidecar with no editor attached.
 *
 * <p>It exists so the "no editor" case is a host rather than a null check scattered through the HTTP
 * surface. It reports no capabilities and refuses every call, which is exactly what a page must see so
 * that it falls back to the clipboard instead of rendering a link that silently does nothing (contract
 * § 4: "never render a link that does nothing").
 *
 * <p>A headless host is not a degraded host: everything a page can do *without* an editor — reads,
 * writes through the edit API, file-change events — is implemented by the sidecar itself and does not
 * pass through this interface at all.
 */
public final class NullHost implements EditorHost {

    public static final NullHost INSTANCE = new NullHost();

    private NullHost() {
    }

    @Override
    public String name() {
        return "null";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Set<String> capabilities() {
        return Set.of();
    }

    @Override
    public boolean openFileAt(String absolutePath, int line, int column) {
        return false;
    }
}
