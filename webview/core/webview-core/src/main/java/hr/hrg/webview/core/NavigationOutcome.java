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

        /**
         * The file exists, but the place the link named is not in it — a declaration, a region or a JSON key
         * that is not there — so the host opened the file instead (contract § 1.1.1, 2026-10-11).
         *
         * <p><b>A success, deliberately.</b> It replaces the 404 this used to be: the reader gets the file even
         * though the link's place could not be resolved, and an already-open file keeps its caret. The reason
         * is separate from {@link #OK} so a log, an HTTP body or a caller can still tell "resolved exactly"
         * from "fell back to the file" — the difference a broken link needs to be diagnosable at all.
         */
        LOCATION_FALLBACK,
        /** The rate limit refused the request; no host was asked. */
        RATE_LIMITED
    }

    public static NavigationOutcome ok(String absolutePath) {
        return new NavigationOutcome(Reason.OK, absolutePath, "");
    }

    /** The file was opened, but not at the place the link named; see {@link Reason#LOCATION_FALLBACK}. */
    public static NavigationOutcome fallback(String absolutePath, String detail) {
        return new NavigationOutcome(Reason.LOCATION_FALLBACK,
                absolutePath == null ? "" : absolutePath, detail);
    }

    public static NavigationOutcome refused(Reason reason, String absolutePath, String detail) {
        return new NavigationOutcome(reason, absolutePath == null ? "" : absolutePath, detail);
    }

    /** True for the two reasons that mean the reader was given a file: {@link Reason#OK} and the fallback. */
    public boolean succeeded() {
        return reason == Reason.OK || reason == Reason.LOCATION_FALLBACK;
    }
}
