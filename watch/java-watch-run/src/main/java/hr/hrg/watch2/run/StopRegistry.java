// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static registry that the <em>target application</em> uses to publish its
 * {@link Stoppable} objects, and that the daemon uses to call {@link #stopAll()}
 * before each reload cycle.
 *
 * <p>Because each reload uses a fresh {@link java.net.URLClassLoader}, the static
 * field here belongs to the <em>daemon's</em> class loader and therefore survives
 * across reloads.  The target application must reference this class through the
 * boot/system class loader (i.e. it must be on the original classpath, not inside
 * the reloaded {@code bin/} directory).
 *
 * <p>Thread safety: all mutating operations are {@code synchronized}.
 */
public final class StopRegistry {
    private static final Logger log = LoggerFactory.getLogger(StopRegistry.class);

    /** Guards {@link #stoppables}. */
    private static final Object LOCK = new Object();
    private static final List<Stoppable> stoppables = new ArrayList<>();

    private StopRegistry() {}

    /**
     * Register a {@link Stoppable} that should be notified on the next reload.
     * Safe to call from any thread in the target application.
     *
     * @param s the stoppable to register; {@code null} values are ignored
     */
    public static void register(Stoppable s) {
        if (s == null) return;
        synchronized (LOCK) {
            stoppables.add(s);
        }
        log.debug("Registered stoppable: {}", s.getClass().getName());
    }

    /**
     * Called by the daemon before each reload.  Invokes {@link Stoppable#stop()}
     * on every registered object in registration order, then clears the registry.
     * Exceptions thrown by individual handlers are caught and logged so that the
     * remaining handlers still run.
     */
    public static void stopAll() {
        List<Stoppable> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(stoppables);
            stoppables.clear();
        }
        log.info("Stopping {} registered stoppable(s)...", snapshot.size());
        for (Stoppable s : snapshot) {
            try {
                s.stop();
            } catch (Exception e) {
                log.error("Error stopping {}: {}", s.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /** Returns an unmodifiable snapshot of currently registered stoppables (for testing). */
    public static List<Stoppable> getRegistered() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(stoppables));
        }
    }
}
