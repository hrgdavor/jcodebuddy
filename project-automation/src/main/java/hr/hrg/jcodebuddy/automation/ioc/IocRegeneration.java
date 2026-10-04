package hr.hrg.jcodebuddy.automation.ioc;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.hipster.ioc.tooling.ContextReader;
import hr.hrg.hipster.ioc.tooling.IocGeneration;
import hr.hrg.hipster.ioc.tooling.IocProjectGenerator;
import hr.hrg.jcodebuddy.engine.codegen.ProjectContext;
import hr.hrg.jcodebuddy.engine.codegen.ProjectGenerator;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.query.MetadataQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The dev-time pass that drives the hipster-ioc generator (DEC-036 § 11, plan step 3.9).
 *
 * <h3>What the pass owns, and what the generator owns</h3>
 * <p>The charter's rule § 2.8 draws the line and this class is the reason it had to be drawn: <strong>a generator's
 * kind is what it reads; a pass's kind is what it writes.</strong> So this pass builds the model (the one step that
 * reads sources — {@link IocGeneration#index}), hands it to each {@link ProjectGenerator} as a
 * {@link ProjectContext}, and then <em>writes</em> what they return. The generator never touches the filesystem, and
 * the pass never decides what code should say.</p>
 *
 * <h3>Why it lives in {@code project-automation} and not with the generator</h3>
 * <p>AGENTS.md § 1.1: a {@code project-automation} module is a project's own dev-time assistant, and the dependency
 * runs one way only — the pass may depend on the generator, never the reverse. {@code hipster-ioc-tooling} does not
 * know this class exists, which is exactly why the same generator is also usable from {@code bun scripts/ioc-gen.js}
 * and from the watch half beside this one.</p>
 *
 * <h3>Idempotence, because the watch half runs it on every save</h3>
 * <p>A second run over an unchanged tree writes nothing: {@link IocGeneration#write} writes only a file whose content
 * differs, and the generator reconciles cooperatively against what is already there (DEC-020). The plan named this as
 * 3.9's hazard — "a watch-mode pass regenerates on save and would otherwise rewrite a prototype's output
 * repeatedly" — and the answer is that the emitted shape is stable, not that the watcher suppresses the pass.</p>
 */
public final class IocRegeneration {

    /**
     * What a run produced.
     *
     * @param contextsRead how many context interfaces the model held
     * @param filesWritten how many implementation files were actually written (an unchanged file is not one)
     * @param refused      how many contexts the generator declined, each with a diagnostic
     * @param divergences  every diagnostic, in DEC-022's format
     * @param graphFile    where the dependency graph was written
     */
    public record Pass(int contextsRead, int filesWritten, int refused, List<String> divergences,
                       Path graphFile) {

        public Pass {
            divergences = List.copyOf(divergences);
        }

        /** Whether this pass wrote anything — what a watcher logs as "acted" rather than "nothing to do". */
        public boolean wrote() {
            return filesWritten > 0;
        }
    }

    private final List<ProjectGenerator<?>> generators;

    /** The default pass: the hipster-ioc context generator, and nothing else. */
    public IocRegeneration() {
        this(List.of(new IocProjectGenerator()));
    }

    /**
     * @param generators the project-scoped generators this pass drives, in order. A file-scoped
     *                   {@code CodeGenerator} cannot be passed here, and the compiler is what says so (step 7.8)
     */
    public IocRegeneration(List<ProjectGenerator<?>> generators) {
        this.generators = List.copyOf(generators);
    }

    /** The generators this pass drives, so a caller can log what a run will do before it does it. */
    public List<ProjectGenerator<?>> generators() {
        return generators;
    }

    /**
     * One pass over {@code sourceRoot}: build the model, render with every applicable generator, write the result.
     *
     * @param sourceRoot the tree to read and to write generated implementations into
     * @param moduleRoot the module whose {@code .jcodebuddy/} receives the derived graph (DEC-026)
     */
    public Pass run(Path sourceRoot, Path moduleRoot) throws IOException {
        DivergenceReporter reporter = new DivergenceReporter();
        ClassIndex index = IocGeneration.index(sourceRoot, moduleRoot, reporter.entries());
        ProjectContext context = new ProjectContext(sourceRoot, moduleRoot, index,
                MetadataQuery.over(index), reporter, List.of());

        int contextsRead = 0;
        int written = 0;
        int refused = 0;
        Path graphFile = moduleRoot.resolve(IocGeneration.GRAPH_PATH);
        for (ProjectGenerator<?> generator : generators) {
            if (!generator.isApplicable(context)) {
                continue;
            }
            Object rendered;
            try {
                rendered = generator.generate(context);
            } catch (UncheckedIOException failure) {
                // The pass owns what a failure means for a run: report it and leave the tree alone rather than
                // taking the pass down, which is the same choice the entity watcher makes.
                reporter.report("watcher_pass_failed", sourceRoot.toString(),
                        "a project-scoped generator could not read the tree it was handed",
                        failure.getCause().getClass().getSimpleName() + ": " + failure.getCause().getMessage(),
                        "a readable source tree",
                        "fix the source or the path that broke the pass; the run stops here and the tree is intact");
                continue;
            }
            if (rendered instanceof IocGeneration.Rendered result) {
                // The pass's half: the generator returned, this writes.
                IocGeneration.Result saved = IocGeneration.write(result);
                contextsRead += saved.contextsRead();
                written += saved.filesWritten();
                refused += saved.refused();
                graphFile = saved.graphFile();
                for (String divergence : saved.divergences()) {
                    reporter.add(divergence);
                }
            }
        }
        return new Pass(contextsRead, written, refused, reporter.entries(), graphFile);
    }

    /** The whole run for a caller that wants one line: build the model, render, write. */
    public static Pass of(Path sourceRoot, Path moduleRoot) throws IOException {
        return new IocRegeneration().run(sourceRoot, moduleRoot);
    }

    /**
     * A run over generators a caller assembled, for the composition case and for tests.
     *
     * <p>It exists so a caller can state which generators a pass drives instead of relying on a static — which is how
     * the entity generator is configured, and the reason its watcher has to take ownership of those statics for the
     * duration of a pass.</p>
     */
    public static Pass of(Path sourceRoot, Path moduleRoot, List<ProjectGenerator<?>> generators)
            throws IOException {
        return new IocRegeneration(generators).run(sourceRoot, moduleRoot);
    }

    /** The contexts the model holds, without rendering: a caller that wants to check before anything is written. */
    public static List<ClassRecord> contextsOf(Path sourceRoot, Path moduleRoot) throws IOException {
        ClassIndex index = IocGeneration.index(sourceRoot, moduleRoot, new DivergenceReporter().entries());
        return ContextReader.contextsIn(index);
    }
}
