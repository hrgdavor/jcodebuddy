// {@link com.codebuddy.merge.AnalysisLevel} The evidence a resolution rests on, ordered weakest to strongest.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

/**
 * How strong the evidence behind a {@link ConflictResolution} is, as an ordered scale.
 *
 * <h2>Why a scale rather than more prose</h2>
 *
 * <p>A resolution already carries {@link ConflictResolution#getWarnings() warnings}, which say what
 * was <em>missing</em> from its basis as free text. That answers "should a reviewer be suspicious",
 * but it cannot answer "which of these two answers rests on better evidence" — and that question is
 * asked whenever several conflicts claim one block and disagree, which is where a block is left
 * unapplied because the weakest claim vetoed the strongest analysis.
 *
 * <p>So the level is the machine-comparable half of the same idea: a warning is prose about a gap,
 * a level is a position on a scale. They are not alternatives — a resolution can be at
 * {@link #PLATFORM_TYPES} and still warn that the project's own types were not on the classpath.
 *
 * <h2>The scale</h2>
 *
 * <p>Ordered by <em>what the answer was checked against</em>, each level strictly containing what the
 * one below it knows:
 *
 * <ol>
 *   <li>{@link #TEXT_LOCAL} — the conflicting sides compared as text. Line sets, one line parsed with
 *       a regular expression, the first declared name. Nothing outside the block is read, so an answer
 *       at this level cannot see that the same code appears elsewhere, that a member already exists,
 *       or what any type is. It is the weakest evidence there is, and a resolver that reads nothing
 *       else must say so rather than sound confident.</li>
 *   <li>{@link #TEXT_FILE} — a text region of the file beyond the conflicting lines: the import block,
 *       the constant block, the package declaration, the declared names. Still text — no type is
 *       resolved and no member is modelled — but the answer is placed in the file rather than in the
 *       hunk, which is what makes "where does this line belong" answerable at all.</li>
 *   <li>{@link #STRUCTURE} — Java structure recognised: declarations and their parameter lists, the set
 *       of members, the statements of a body. This is the level that can tell two <em>distinct</em>
 *       members apart where a line comparison sees only "both sides changed this place", and it is the
 *       first level whose answers do not depend on spelling or formatting.</li>
 *   <li>{@link #PLATFORM_TYPES} — the language's and the JDK's own semantics: the JLS 5.1.2 primitive
 *       conversions, a built-in table of true JDK supertype chains, or types actually resolved by javac
 *       when only the platform is on the classpath. Authoritative about the platform, and blind to the
 *       project under merge — a project type at this level is unresolved, not decided.</li>
 *   <li>{@link #PROJECT_TYPES} — types resolved by javac against a classpath that carries the project's
 *       own entries, so a project's types are known rather than {@code JavaType.Unknown}. This is the
 *       strongest evidence available, and the only level at which a question about the project's own
 *       hierarchies can be answered rather than escalated.</li>
 * </ol>
 *
 * <h2>What the level is not</h2>
 *
 * <p>It is not a permission. A high level does not let a resolution be applied — that is still
 * {@link ConflictResolution.ResolutionKind} plus the application rule, and a {@code REVIEW} or
 * {@code MANUAL} answer is never applied whatever its level. The level decides only which claims
 * <em>survive</em> when several disagree, and only where the stronger answer already accounts for what
 * the weaker one protects (see {@link MergeFileTool}).
 *
 * <p>It is also not a property of a resolver alone. A resolver declares the strongest level it can
 * reach ({@link ConflictResolver#maxAnalysisLevel()}), while each resolution records the level it
 * actually used — {@link TypeChangeConflictResolver} answers at {@link #PLATFORM_TYPES} without a
 * classpath and at {@link #PROJECT_TYPES} with one, and the record is what a reviewer reads.
 */
public enum AnalysisLevel {

    /** The block's own text: line sets, a single parsed line. Nothing outside the block. */
    TEXT_LOCAL(1),

    /** Text of a region of the file beyond the hunk: imports, constants, package, declared names. */
    TEXT_FILE(2),

    /** Java structure recognised: declarations, member sets, parameter lists, body statements. */
    STRUCTURE(3),

    /** The language's and the JDK's semantics, or types resolved against the platform alone. */
    PLATFORM_TYPES(4),

    /** Types resolved by javac against a classpath carrying the project's own entries. */
    PROJECT_TYPES(5);

    /**
     * Position on the scale, stated per constant rather than taken from the declaration order.
     *
     * <p>An explicit number means reordering the constants — for readability, or when a level is
     * inserted between two others — cannot silently change what outranks what.
     */
    private final int strength;

    AnalysisLevel(int strength) {
        this.strength = strength;
    }

    /** This level's position on the scale; higher is stronger evidence. */
    public int strength() {
        return strength;
    }

    /**
     * True when this level rests on strictly stronger evidence than {@code other}.
     *
     * <p>Strict, because equal evidence must not decide anything: two answers that read the same kind
     * of thing disagreeing is exactly the case a human has to settle, and a scale that let one win
     * would be inventing a tiebreak it does not have.
     */
    public boolean isStrongerThan(AnalysisLevel other) {
        return other != null && strength > other.strength;
    }

    /** True when this level is at least as strong as {@code other}. */
    public boolean isAtLeast(AnalysisLevel other) {
        return other == null || strength >= other.strength;
    }
}