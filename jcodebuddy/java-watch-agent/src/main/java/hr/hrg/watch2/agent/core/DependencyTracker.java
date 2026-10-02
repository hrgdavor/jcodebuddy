// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks dependencies between files (e.g., via // @watch: markers).
 */
public class DependencyTracker {
    private final Map<Path, Set<Path>> dependsOn = new ConcurrentHashMap<>();
    private final Map<Path, Set<Path>> dependentsOf = new ConcurrentHashMap<>();

    public void setDependencies(Path file, List<Path> targets) {
        // Clear old dependencies
        Set<Path> oldTargets = dependsOn.getOrDefault(file, Collections.emptySet());
        for (Path target : oldTargets) {
            dependentsOf.getOrDefault(target, Collections.emptySet()).remove(file);
        }

        Set<Path> newTargets = new HashSet<>(targets);
        dependsOn.put(file, newTargets);
        for (Path target : newTargets) {
            dependentsOf.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(file);
        }
    }

    public Set<Path> getDependents(Path target) {
        return dependentsOf.getOrDefault(target, Collections.emptySet());
    }
}
