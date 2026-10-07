// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/ComparisonMergeUtil.kt).
// @derived Translated from Kotlin to Java, reduced to the algorithm, and stripped of the IntelliJ Platform dependency.
// {enabled:true, blockMarker: "implicit"} The comparison primitives of the JetBrains merge and diff port (step 4.8).
package com.codebuddy.merge.jetbrains.text;

/**
 * The comparison tier: what changed, where, and down to which word.
 *
 * <p>This module had no diff algorithm before this tier. Conflict detection decided what changed with
 * {@code line.trim()} inside a {@code LinkedHashSet} — a set-membership test, not a comparison — so it
 * could not tell "both sides replaced this region" from "both sides inserted here", could not locate a
 * change, and could not see below the line. Upstream's engine is two passes, a line diff and then a word
 * diff over the changed blocks, and this tier is both.
 *
 * <h2>What belongs here</h2>
 *
 * <ul>
 *   <li>{@code ComparisonPolicy} — {@code DEFAULT}, {@code TRIM_WHITESPACES}, {@code IGNORE_WHITESPACES}.
 *       The first two are <b>different operations</b>, and conflating them is exactly what
 *       {@code line.trim()} did: {@code TRIM_WHITESPACES} ignores a line's leading and trailing
 *       whitespace, {@code IGNORE_WHITESPACES} ignores whitespace throughout.</li>
 *   <li>The line diff, producing ordered change ranges, and the word diff over a changed block,
 *       producing inner fragments.</li>
 * </ul>
 *
 * <h2>What may not belong here</h2>
 *
 * <p><b>Nothing from this module's merge model.</b> No {@code Conflict}, no {@code ConflictResolution},
 * no {@code ConflictType} — this tier compiles and tests with no merge model at all, which is what makes
 * the transcribed upstream vectors runnable against it. {@code JetBrainsTierIsolationTest} enforces it.
 *
 * <p>Nor may the policy or a size bound be a <b>process-global switch</b>. Upstream's {@code DiffConfig}
 * is mutable global state; a library must not have one, so both are parameters here.
 */
