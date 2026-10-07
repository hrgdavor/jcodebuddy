// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/MergeResolveUtil.kt).
// @derived Translated from Kotlin to Java, reduced to the algorithm, and stripped of the IntelliJ Platform dependency.
// {enabled:true, blockMarker: "implicit"} The merge tier of the JetBrains port — SAFE results only (step 4.9).
package com.codebuddy.merge.jetbrains.merge;

/**
 * The merge tier: which changes compose, which conflict, and what the composed text is.
 *
 * <p>It depends on {@link com.codebuddy.merge.jetbrains.text} and on nothing else. In particular it does
 * <b>not</b> import this module's merge model, so every rule here can be tested against upstream's
 * vectors with no {@code Conflict} in sight. {@code JetBrainsTierIsolationTest} enforces that.
 *
 * <h2>Only the SAFE half lives here</h2>
 *
 * <p>Upstream's eight resolution steps were classified before any of them was ported, on one axis: does
 * the input <b>determine</b> the answer, or is the answer merely plausible? See
 * {@code docs/JETBRAINS_PORT.md} § 5. This tier holds the first kind and only the first:
 *
 * <ul>
 *   <li>the merge-range builder — bookkeeping over two two-way diffs, which decides nothing about
 *       content;</li>
 *   <li>the ignored-change re-emission, <b>required</b> rather than optional: it is what makes a
 *       whitespace-ignoring merge still <i>show</i> the whitespace difference instead of silently
 *       normalising formatting;</li>
 *   <li>the simple resolve pass, whose every appended region is chosen by <i>is-unchanged</i>,
 *       <i>only-left-changed</i> or <i>only-right-changed</i>;</li>
 *   <li><b>and the refusal itself</b> — the shape that must survive the port. Two differing insertions
 *       at one point have no correct order, and this tier returns nothing rather than guessing.</li>
 * </ul>
 *
 * <h2>What is deliberately absent, and why</h2>
 *
 * <p>The greedy pass and its unconditional deletion application, and the whitespace retry, are
 * <b>SUGGESTION</b>-class: their result is useful but not mechanically forced. They are not automatic
 * answers here, because upstream may trade correctness for resolve-rate only on the strength of a
 * person reviewing and undoing every result — and this module can write its resolutions to disk with
 * nobody watching. They arrive on the suggestion channel instead, in steps 4.14–4.17.
 */
