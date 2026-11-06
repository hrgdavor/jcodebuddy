package hr.hrg.jcodebuddy.watch;

import java.util.Set;

/**
 * One batch of file changes, as either event source delivers it.
 *
 * <p>{@link FileWatch} produces it from a debounced {@code WatchService} queue (the agent's path), and the
 * project-automation loop produces it from an fs-meta daemon subscription, where a protocol replay refusal becomes
 * {@code overflow} — the same "the set may be incomplete, run a full pass" signal. Paths are relative to the watched
 * root, POSIX-separated.</p>
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
