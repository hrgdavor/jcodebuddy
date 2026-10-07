// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5.
// @derived none — this file is this module's own record of that revision, not a translation of upstream code.
// {enabled:true, blockMarker: "implicit"} The one home of the pinned upstream revision for the JetBrains port.
package com.codebuddy.merge.jetbrains;

/**
 * The pinned upstream revision, in one place.
 *
 * <p>The port is a citation of a <b>specific</b> revision, and a citation that lives in several files
 * drifts. Upstream's {@code master} moves, so a reference without a commit hash is not reproducible, and
 * a reference with <em>different</em> hashes in different files is worse than one with none — it looks
 * checked. So the hash, the repository and the tag lines that make up a derived file's header are
 * declared here once, and:
 *
 * <ul>
 *   <li>{@code JetBrainsAttributionTest} fails if a derived file's recorded commit differs from
 *       {@link #PINNED_COMMIT}, or if it omits any line of {@link #requiredHeaderLines()};</li>
 *   <li>{@code scripts/verify-jetbrains-sources.js} fails if a real checkout of the pinned revision does
 *       not contain a file a derived header names.</li>
 * </ul>
 *
 * <p>This file itself carries the header with {@code @derived none}, which is how the attribution rule
 * distinguishes <em>a file derived from upstream</em> from <em>this module's own bookkeeping about
 * upstream</em>. It is the only such file, and the marker is explicit rather than implied by a missing
 * path, because a missing path is otherwise indistinguishable from an omission.
 *
 * <p>{@link #PINNED_COMMIT} is repeated literally in
 * <a href="../../../../../../../THIRD_PARTY_NOTICES.md">THIRD_PARTY_NOTICES.md</a> and in
 * <a href="../../../../../../docs/JETBRAINS_PORT.md">docs/JETBRAINS_PORT.md</a> § 12, because a Markdown
 * document cannot read a Java constant. Those are the two copies, and both are asserted.
 */
public final class JetBrainsProvenance {

    /**
     * The marker meaning "this file is not a translation of upstream code".
     *
     * <p>A file carrying it must name no upstream path; every other file carrying an {@code @derived}
     * notice must name one. Two rules rather than one, because the two cases genuinely differ.
     */
    public static final String NOT_DERIVED_MARKER = "@derived none";

    private JetBrainsProvenance() {
    }

    /** The upstream repository whose code this package derives from. */
    public static final String UPSTREAM_REPOSITORY = "https://github.com/JetBrains/intellij-community";

    /**
     * The upstream revision every derived file was read at.
     *
     * <p>Not a branch and not a tag: a moving reference would make the port unreproducible, and the whole
     * point of recording it is that a reader can retrieve the same bytes.
     */
    public static final String PINNED_COMMIT = "9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5";

    /** The upstream project, as the attribution notices name it. */
    public static final String UPSTREAM_NAME = "JetBrains/intellij-community";

    /** The file that carries the attribution obligations, relative to the module root. */
    public static final String NOTICES_FILE = "THIRD_PARTY_NOTICES.md";

    /**
     * The lines every derived file must open with, in this order.
     *
     * <p>Returned as fragments rather than whole lines because a file may add its own detail — DEC-021's
     * class-file header convention allows a one-line description after the {@code @derived} marker — so
     * the test asserts each fragment is <em>present</em> and in order, not that the lines are
     * byte-identical.
     *
     * <p><b>The pinned commit is deliberately not one of these fragments.</b> It is checked separately,
     * against {@link #PINNED_COMMIT}, and keeping the two apart is what makes the failures name the right
     * defect: a file citing another revision must be reported as <em>citing another revision</em>, not as
     * missing a line — which is what happened when the hash was baked into the fragment it was supposed to
     * match.
     */
    public static java.util.List<String> requiredHeaderLines() {
        return java.util.List.of(
            "Licensed under the Apache License 2.0",
            "Copyright (C) JetBrains s.r.o.",
            "Derived from " + UPSTREAM_NAME + " at commit ",
            "@derived ");
    }
}
