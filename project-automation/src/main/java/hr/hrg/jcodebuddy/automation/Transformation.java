package hr.hrg.jcodebuddy.automation;

/**
 * One source-to-source transformation the automation engine can run over a file.
 *
 * <h3>Why text in, text out</h3>
 * <p>A transformation is a function from a source file's text to the text that should replace it. That
 * is the shape every emitter in this repository already has (the entity generators, the field-enum
 * emitter, {@code jwa-builder}'s record processor): they read a tree for <em>facts</em> and produce
 * <em>text</em>. Keeping the interface at the text boundary means a transformation can be a tree
 * rewrite, a template expansion or a plain string edit without the engine knowing which — and it keeps
 * the engine free of the one thing an LST cannot do, which is hand a mutated tree back to a caller.</p>
 *
 * <h3>Why it throws</h3>
 * <p>{@link TransformationException} is part of the contract rather than an implementation detail. A
 * transformation that cannot do its job must say so: the alternative — returning the input unchanged —
 * is indistinguishable from success at the call site, and the engine would report a file as transformed
 * when nothing happened. Phase 6 of the rewrite-migration plan is a long record of that failure mode
 * ({@code MIGRATION-CAVEATS.md} § 1.1: a parser that recovers silently made a whole generation pass
 * produce nothing, with no error anywhere).</p>
 */
public interface Transformation {

    /** The name this transformation is registered and invoked under; never {@code null} or empty. */
    String getName();

    /** One line a report can print, so a registered transformation is self-describing. */
    String getDescription();

    /**
     * {@code source} with this transformation applied.
     *
     * @param source the file's text, never {@code null}
     * @return the text that should replace it; returning {@code source} unchanged is a legal no-op and
     *         the engine reports it as "unchanged" rather than as a transformation that ran
     * @throws TransformationException when the transformation cannot be applied, so the engine records a
     *                                 failure instead of a success with identical output
     */
    String apply(String source) throws TransformationException;
}
