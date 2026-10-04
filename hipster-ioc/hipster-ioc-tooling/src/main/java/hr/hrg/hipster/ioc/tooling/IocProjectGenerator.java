package hr.hrg.hipster.ioc.tooling;

import hr.hrg.jcodebuddy.engine.codegen.ProjectContext;
import hr.hrg.jcodebuddy.engine.codegen.ProjectGenerator;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;

import java.io.IOException;
import java.util.List;

/**
 * The hipster-ioc context generator as the engine's **project-scoped** kind (steps 7.8 and 3.9).
 *
 * <p>Metadata in, code out: it is handed the dev-time pass's {@link ProjectContext} — the class index the pass
 * already built, and the roots to resolve against — and returns what should exist. It writes nothing, and it parses
 * nothing. That is what makes it reusable by the two halves of DEC-036 § 11: the pass applies the result to disk,
 * and a watch agent can apply the same result through an editor without either of them owning the other's job.</p>
 *
 * <p>Why it is not a {@code CodeGenerator}: a context's module interface is the supertype named in a
 * <em>relation</em>, and "who extends whom" is in no single file. The prototype learned that the hard way — its
 * first implementation looked for the module inside the context's own compilation unit, found nothing, and reported
 * **no factories**, producing an empty-wiring implementation rather than an error, because a file it never read looks
 * exactly like a file with nothing in it (step 3.2). That is why a type the model cannot resolve is reported here
 * rather than inferred as absent.</p>
 */
public final class IocProjectGenerator implements ProjectGenerator<IocGeneration.Rendered> {

    /** One indentation step for generated code; the project's own style is four spaces everywhere it matters. */
    public static final String DEFAULT_INDENT = "    ";

    private final String indent;

    public IocProjectGenerator() {
        this(DEFAULT_INDENT);
    }

    public IocProjectGenerator(String indent) {
        this.indent = indent == null || indent.isEmpty() ? DEFAULT_INDENT : indent;
    }

    @Override
    public String name() {
        return IocContextGenerator.NAME;
    }

    /**
     * Whether the model holds a context at all.
     *
     * <p>Asked of the index rather than of the filesystem: the pass hands over a model it already built, so the
     * answer costs a walk over rows — which is the whole reason this kind may be offered a project and the
     * file-scoped kind may not be offered a file it has not read.</p>
     */
    @Override
    public boolean isApplicable(ProjectContext context) {
        return !ContextReader.contextsIn(context.index()).isEmpty();
    }

    @Override
    public IocGeneration.Rendered generate(ProjectContext context) {
        try {
            // The model is the pass's (it built it), so the generator answers only the generation question.
            return IocGeneration.render(context.index(), context.sourceRoot(), context.moduleRoot(), indent);
        } catch (IOException failure) {
            // A generator returns; it does not decide what a failure means for a run. The pass reports it — in the
            // generator's own diagnostics and, if it prefers, as its own kind — because a pass that keeps going
            // after a bad tree is a policy, and policies belong to the caller.
            throw new java.io.UncheckedIOException("the context generator could not read the tree it was handed: "
                    + context.sourceRoot(), failure);
        }
    }

    /**
     * The contexts this generator will generate for, for a caller that wants to log or check before running.
     *
     * <p>Exposed because a pass deciding whether to write anything at all should be able to ask the model the same
     * question {@link #isApplicable} asks, without rendering to find out.</p>
     */
    public static List<ClassRecord> contextsOf(ProjectContext context) {
        return ContextReader.contextsIn(context.index());
    }
}
