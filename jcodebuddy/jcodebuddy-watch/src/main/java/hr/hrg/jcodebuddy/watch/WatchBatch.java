package hr.hrg.jcodebuddy.watch;

import java.util.Set;

/**
 * One batch of file changes, as the fs-meta daemon client delivers it.
 *
 * <p>{@link FsMetaDaemonWatch} produces it from an fs-meta daemon subscription, where a protocol replay refusal
 * becomes {@code overflow} — the "the set may be incomplete, run a full pass" signal. This is the public batch type of
 * the module: the project-automation regeneration loop and the agent's {@code ProjectWatcher} both consume it. Paths
 * are relative to the watched root, POSIX-separated.</p>
 *
 * <p>There is no second producer. The daemon owns the batch boundary, so a client never starts a {@code WatchService}
 * and this type never has to describe two event sources.</p>
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
