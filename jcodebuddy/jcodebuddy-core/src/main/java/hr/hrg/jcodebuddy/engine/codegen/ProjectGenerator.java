package hr.hrg.jcodebuddy.engine.codegen;

/**
 * Something that generates code for a <strong>project</strong>, from its metadata.
 *
 * <p><strong>Metadata in, code out.</strong> A project-scoped generator is handed a {@link ProjectContext} — the
 * class index, the typed queries over it, and what to generate for — and never a single-file {@link CodeContext}.
 * The two are not shades of one thing: a generator that needs the project's type relations is not a file generator
 * with a bigger appetite, it is a different kind of program. hipster-ioc is the example that made the difference
 * visible: it cannot work on single files at all, because "who extends whom" is in no file, and its input is the
 * project's model rather than source text it parses itself.</p>
 *
 * <h2>The contract, stated so a caller can rely on it</h2>
 *
 * <ul>
 *   <li><strong>Not a {@link CodeGenerator}, and never both.</strong> This interface does not extend it and it does
 *       not extend this one, which is what makes "offer this generator to every file" impossible to write by
 *       accident: a caller holding a list of file-scoped generators cannot be handed a project-scoped one, and the
 *       compiler says so rather than the documentation.</li>
 *   <li><strong>A wrapper's kind is its delegate's.</strong> A generator that forwards to another implements the
 *       interface of the kind it delegates to, and exactly one of the two — a wrapper cannot be both, because
 *       nothing implements both. (The repository's one wrapper, {@code ActionToolAdapter}, was deleted in step
 *       3.0s; this is a shape to hold to, not a class to fix.)</li>
 *   <li><strong>{@link #generate} returns, it does not write.</strong> As with the file-scoped kind, turning the
 *       result into files is the caller's decision, which is what keeps a generator testable without a filesystem
 *       and reusable by a watch agent that applies it through an editor.</li>
 *   <li><strong>And the difference between a generator and a pass:</strong> a generator's kind is what it
 *       <em>reads</em>; a pass's kind is what it <em>writes</em>. hipster-ioc's generator returns text and has no
 *       side effects, while its pass writes the dependency graph and the generated files. A rule that called a
 *       thing's kind by what it touches would name the same generator two kinds depending on which half you looked
 *       at.</li>
 * </ul>
 *
 * @param <T> what this generator produces
 */
public interface ProjectGenerator<T> {

    /** A stable name for diagnostics — this is what a log line or a report calls the generator. */
    String name();

    /**
     * Whether this generator has anything to do with the project the context describes.
     *
     * <p>Still a cheap predicate asked before {@link #generate}, for the same reason as the file-scoped SPI's: a
     * caller holds a list of generators and tries them in order without knowing what any of them is for. Here the
     * predicate may consult the model — that is the point of the kind — but it must not generate, and it must not
     * treat an unanswered question as a negative one.</p>
     */
    boolean isApplicable(ProjectContext context);

    /** Produce the generated form. Only called when {@link #isApplicable} returned true. */
    T generate(ProjectContext context);
}
