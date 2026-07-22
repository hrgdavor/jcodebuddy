// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.nio.file.Path;
import java.util.Set;

/**
 * Immutable snapshot of a batch of file-system changes delivered by
 * {@link BatchedFileWatcher} after a single debounce window expires.
 *
 * <p>Files are exposed as unmodifiable {@link Set}s of absolute {@link Path}s.
 *
 * <p>When the underlying OS event queue overflows (see
 * {@code DirectoryChangeEvent.EventType.OVERFLOW}), the watcher sets
 * {@link #fullRecompile()} to {@code true}.  Consumers should treat this as a
 * signal to recompile the entire source tree rather than relying on the (possibly
 * incomplete) sets of {@link #changed()} and {@link #deleted()} paths.
 */
public final class ChangeSet {

    private final Set<Path> changed;
    private final Set<Path> deleted;
    private final boolean   fullRecompile;
    private final long      firstEventMs;

    /**
     * Constructs a normal (non-overflow) change set.
     *
     * @param changed      files that were created or modified
     * @param deleted      files that were deleted
     * @param firstEventMs time of the first event in this batch
     */
    public ChangeSet(Set<Path> changed, Set<Path> deleted, long firstEventMs) {
        this(changed, deleted, false, firstEventMs);
    }

    /**
     * Full constructor including the overflow flag.
     *
     * @param changed       files that were created or modified (may be empty if fullRecompile)
     * @param deleted       files that were deleted
     * @param fullRecompile {@code true} when an OS event-queue overflow occurred
     * @param firstEventMs  time of the first event in this batch
     */
    public ChangeSet(Set<Path> changed, Set<Path> deleted, boolean fullRecompile, long firstEventMs) {
        this.changed       = Set.copyOf(changed);
        this.deleted       = Set.copyOf(deleted);
        this.fullRecompile = fullRecompile;
        this.firstEventMs  = firstEventMs;
    }

    /** Files created or modified since the last batch. Never {@code null}. */
    public Set<Path> changed() { return changed; }

    /** Files deleted since the last batch. Never {@code null}. */
    public Set<Path> deleted() { return deleted; }

    /**
     * {@code true} if an OS event-queue overflow was detected.  Consumers should
     * perform a full source-tree recompile rather than trusting the partial
     * {@link #changed()} / {@link #deleted()} sets.
     */
    public boolean fullRecompile() { return fullRecompile; }

    /** {@code true} if there are no changes and no deletion. */
    public boolean isEmpty() { return !fullRecompile && changed.isEmpty() && deleted.isEmpty(); }

    /** Total number of files in {@link #changed()} plus {@link #deleted()}. */
    public int totalSize() { return changed.size() + deleted.size(); }

    /** The system timestamp when the first event in this batch arrived. */
    public long firstEventMs() { return firstEventMs; }

    @Override
    public String toString() {
        return "ChangeSet{changed=" + changed.size()
                + ", deleted=" + deleted.size()
                + ", fullRecompile=" + fullRecompile
                + ", firstEventMs=" + firstEventMs + '}';
    }
}
