// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.util.List;
import java.util.Map;

/**
 * Result containing surgical edits and relationship metadata.
 */
public record TransformationResult(
        List<CodeEdit> edits,
        Map<String, String> metadata) {
    public static TransformationResult empty() {
        return new TransformationResult(List.of(), Map.of());
    }
}
