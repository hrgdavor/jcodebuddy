package hr.hrg.webview.core;

/**
 * What happened to one navigation request, in enough detail that a transport can answer with the right
 * status without guessing.
 *
 * <p>Before this existed, the JetBrains host answered {@code 404} for both "no such file" and "the rate
 * limit refused you", and the VS Code host answered {@code 200} before it had even tried. Neither is
 * wrong enough to break a page, but neither lets a page or a log tell the difference between a broken
 * link and a limit. {@link Reason} carries that difference as data.
 */
public record NavigationOutcome(Reason reason, String absolutePath, String detail) {

    public enum Reason {
        /** The host was asked and acted. */
        OK,
        /** The path was empty or not a path at all. */
        INVALID_PATH,
        /** The path resolved outside the project and this host only serves its own project. */
        OUTSIDE_PROJECT,
        /** No host is attached, or the attached host reports itself unavailable. */
        NO_HOST,
        /** The host was asked and refused — it could not find the file, most often. */
        HOST_REFUSED,
        /** The rate limit refused the request; no host was asked. */
        RATE_LIMITED
    }

    public static NavigationOutcome ok(String absolutePath) {
        return new NavigationOutcome(Reason.OK, absolutePath, "");
    }

    public static NavigationOutcome refused(Reason reason, String absolutePath, String detail) {
        return new NavigationOutcome(reason, absolutePath == null ? "" : absolutePath, detail);
    }

    public boolean succeeded() {
        return reason == Reason.OK;
    }
}
