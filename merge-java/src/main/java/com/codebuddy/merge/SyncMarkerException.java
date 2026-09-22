// {@link com.codebuddy.merge.SyncMarkerException} Raised when a recorded sync marker cannot name a base.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

/**
 * Raised when a last-sync marker is present but cannot name the base it claims.
 *
 * <h2>Why this is an error and not a fallback</h2>
 *
 * <p>Every other way of not knowing the base is handled softly. No marker means a
 * branch that has never synced, and the merge base is used instead; a path that
 * cannot be read is skipped. This one is different, because the marker <em>does</em>
 * name something and the danger is that the name is believed.
 *
 * <p>A marker field is a string that ends up in
 * {@link org.eclipse.jgit.lib.Repository#resolve}, which accepts a branch name, a
 * tag, {@code HEAD} and revision syntax such as {@code HEAD~3} - all of which mean
 * something different tomorrow than they mean today. A marker holding
 * {@code upstream} therefore makes the base the upstream <em>tip</em>, at which
 * point neither side has changed anything relative to the base and a file that
 * conflicts is reported clean. A false clean is the one answer this module must
 * never give: it says a branch is up to date while the merge is unresolved, and
 * everything downstream of that - the exit code, the reported resolutions - is
 * wrong with it.
 *
 * <p>So a marker that names something movable, or names a commit this repository
 * cannot read, fails the run with a message that says which file, which value, and
 * what to write instead. There is no flag to continue past it: continuing is the
 * behaviour being removed.
 *
 * @see LastSyncMarker#requireCommitId(java.nio.file.Path)
 */
public class SyncMarkerException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what is wrong with the marker, and what to write instead
     */
    public SyncMarkerException(String message) {
        super(message);
    }
}