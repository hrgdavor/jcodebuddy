// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/fragments/DiffFragment.kt).
// @derived Translated from Kotlin to Java as a record, with the "inside one line pair" contract written out.
// {enabled:true, blockMarker: "implicit"} One change inside a pair of lines, in character offsets.
package com.codebuddy.merge.jetbrains.text;

/**
 * One change <b>inside a pair of lines</b>, in character offsets from the start of each line.
 *
 * <p>This is the second pass's unit. The first pass says "these two lines differ"; this says <em>which
 * characters of them</em>, which is what lets a merge resolve two edits to different words of one line
 * instead of escalating the whole line to a human.
 *
 * <p>Offsets are relative to the line, not to the file, because a fragment is only meaningful together
 * with the line pair it came from — a caller that wanted file offsets would have to add the line's start,
 * and a fragment that carried them would be wrong the moment the line moved.
 *
 * @param start1 inclusive character offset in the first line
 * @param end1   exclusive character offset in the first line
 * @param start2 inclusive character offset in the second line
 * @param end2   exclusive character offset in the second line
 */
public record WordFragment(int start1, int end1, int start2, int end2) {

    public WordFragment {
        if (start1 < 0 || start2 < 0 || end1 < start1 || end2 < start2) {
            throw new IllegalArgumentException(
                "not a range: " + start1 + ", " + end1 + ", " + start2 + ", " + end2);
        }
        if (start1 == end1 && start2 == end2) {
            throw new IllegalArgumentException("a fragment with both sides empty is not a change");
        }
    }

    /** True when the first line lost characters and the second gained none. */
    public boolean isDeletion() {
        return start1 < end1 && start2 == end2;
    }

    /** True when the second line gained characters and the first lost none. */
    public boolean isInsertion() {
        return start1 == end1 && start2 < end2;
    }

    /** True when both sides are non-empty. */
    public boolean isReplacement() {
        return start1 < end1 && start2 < end2;
    }
}
