package hr.hrg.jcodebuddy.watch;

import java.util.Set;

/**
 * One debounced batch from {@link FileWatch}.
 *
 * <p>Paths are relative to the watched root, POSIX-separated. {@code overflow} is the OS queue-overflow signal: the
 * event list may be incomplete, so a consumer must treat it as a full recompile request.</p>
 */
public record WatchBatch(Set<String> changed, Set<String> deleted, boolean overflow) {

    public WatchBatch {
        changed = Set.copyOf(changed);
        deleted = Set.copyOf(deleted);
    }

    /** Whether this batch carries no paths and no overflow signal. */
    public boolean isEmpty() {
        return !overflow && changed.isEmpty() && deleted.isEmpty();
    }
}
