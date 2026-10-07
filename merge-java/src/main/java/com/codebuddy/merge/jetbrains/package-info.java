// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/ComparisonMergeUtil.kt).
// @derived Translated from Kotlin to Java, reduced to the algorithm, and stripped of the IntelliJ Platform dependency.
// {enabled:true, blockMarker: "implicit"} The JetBrains merge and diff port (unified plan steps 4.7–4.17).
package com.codebuddy.merge.jetbrains;

/**
 * The JetBrains merge and diff port, in three tiers.
 *
 * <p>Everything here is <b>derived</b> from the IntelliJ Platform — Apache License 2.0, Copyright (C)
 * JetBrains s.r.o. — as recorded per file and in
 * <a href="../../../../../../../THIRD_PARTY_NOTICES.md">THIRD_PARTY_NOTICES.md</a>. The engineering record
 * of what was taken, what was refused and why is
 * <a href="../../../../../../docs/JETBRAINS_PORT.md">docs/JETBRAINS_PORT.md</a>.
 *
 * <h2>The tiers, and what each may know</h2>
 *
 * <p>The tiers exist to keep the ported algorithms testable without this module's merge model, which is
 * how upstream's own tests are written and what makes their vectors portable:
 *
 * <ol>
 *   <li>{@link com.codebuddy.merge.jetbrains.text} — comparison. The line and word search and the three
 *       whitespace policies. <b>Depends on the JDK only.</b></li>
 *   <li>{@link com.codebuddy.merge.jetbrains.merge} — composition. Merge ranges, the conflict-shape
 *       decision table, and the word-level resolve passes. <b>Depends on {@code text} only</b>, and
 *       imports no type of this module — no {@code Conflict}, no {@code ConflictResolution}.</li>
 *   <li>the adapter — the {@code ConflictResolver} implementations that bridge the merge tier into this
 *       module's model. This is the only tier that may know either side.</li>
 * </ol>
 *
 * <p><b>The second tier's constraint is asserted, not assumed:</b>
 * {@code JetBrainsTierIsolationTest} fails if a class in {@code text} or {@code merge} references
 * {@code com.codebuddy.merge} outside its own package. That is what makes "the algorithms are
 * independently testable" a property rather than an intention.
 *
 * <h2>What is not here</h2>
 *
 * <p>The port deliberately does not include the IntelliJ platform's editor, PSI, undo or document
 * layers, or its PSI-backed semantic resolver — see {@code docs/JETBRAINS_PORT.md} § 8. This module has
 * its own equivalents, and its own verification gate ({@code ResolutionVerifier}), which upstream does
 * not have at all.
 */
