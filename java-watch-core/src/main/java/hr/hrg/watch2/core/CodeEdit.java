// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

/**
 * Data model for a surgical code edit.
 * Coordinates are 1-based (standard for humans and many IDEs).
 */
public record CodeEdit(
        String uri,
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        String newText) {
}
