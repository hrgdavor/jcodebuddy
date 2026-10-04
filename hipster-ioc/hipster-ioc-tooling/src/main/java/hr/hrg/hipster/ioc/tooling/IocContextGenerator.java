package hr.hrg.hipster.ioc.tooling;

import hr.hrg.hipster.entity.tooling.CooperativeCodegen;
import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.codegen.CodeGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The hipster-ioc context generator, driven by the metadata model (DEC-036, plan step 3.0e part two).
 *
 * <p><strong>It is no longer a {@code CodeGenerator}.</strong> The per-file SPI asks "is this file yours, and what
 * would you write for it", which forces a generator to read the file it is offered — and this one does not need
 * to, because everything it renders is in the model: the context's beans are its methods, its module is the type a
 * relation names, its factories are that module's {@code default} methods, and the parent of a
 * {@code ChildContext<P>} comes from slicing the declaring file at the relation's range. So the pass hands it a row
 * and an index, and it parses nothing. What it still reads is the file it <em>writes</em>, because cooperative
 * codegen has to recognise its own previous output and preserve a developer's edits (DEC-020) — that is a
 * different question from where the facts come from, and conflating the two is what this split undoes.</p>
 *
 * <p>Three refusals, all of them deliberate, and each reported in DEC-022's format rather than silently
 * skipped: a dependency cycle (marked or not, see {@link DependencyOrder}); a {@code Supplier<Bean>} or
 * {@code DynamicResource<Bean>} bean with no factory, because laziness is opt-in and "generate something that
 * looks lazy" is how a generator lies (DEC-036 § 8); and a context whose {@code impl()} names a class, because an
 * implementation already exists and this generator must not compete with it (DEC-036 § 7).</p>
 *
 * <p>When it does generate, the text goes through {@link CooperativeCodegen#reconcileMembers}: members the
 * developer added are carried through verbatim, and {@code enabled:false} in the header freezes the file
 * (DEC-020, DEC-021 § 6). That is why this generator never writes to the file itself — the reconciliation is
 * the only place that decides what the file should become.</p>
 */
public final class IocContextGenerator {

    /** The generator's stable name, as a log line or a report calls it. */
    public static final String NAME = "hipster-ioc";

    /**
     * What one run produced.
     *
     * @param sourceFile  the context interface the model describes (its path, from the row)
     * @param implFile    the file that would be written, or {@code null} when nothing is generated
     * @param source      the text for {@code implFile}; for a refused context this is the file as it is now
     * @param divergences what the run reported, in DEC-022's format
     * @param refused     whether the generator declined to emit anything
     */
    public record GeneratedContext(Path sourceFile, Path implFile, String source, List<String> divergences,
                                   boolean refused) {

        public GeneratedContext {
            divergences = List.copyOf(divergences);
        }
    }

    /**
     * Generate one context from the model.
     *
     * @param contextRow the {@code @HipsterContext} row, from {@code index}
     * @param index      the index the row came from — the reader's only source of facts
     * @param moduleRoot the module the row's path is relative to, which is where the implementation is written
     * @param indent     one indentation step for the generated text
     */
    public GeneratedContext generate(ClassRecord contextRow, ClassIndex index, Path moduleRoot, String indent) {
        Path sourceFile = moduleRoot.resolve(contextRow.path());
        DivergenceReporter divergences = new DivergenceReporter();
        Optional<IocModel.Context> read = ContextReader.read(contextRow, index, divergences);
        if (read.isEmpty()) {
            return new GeneratedContext(sourceFile, null, currentText(sourceFile), divergences.entries(), true);
        }
        IocModel.Context model = read.get();

        if (model.hasImplementation()) {
            divergences.report("context_implementation_present", model.qualifiedName(),
                    "the @HipsterContext annotation names an implementation, so one already exists",
                    "@HipsterContext(impl = ...)", "no generated implementation",
                    "nothing to do: the named class is the implementation. Remove impl() to let this "
                            + "generator emit one");
            return new GeneratedContext(sourceFile, null, currentText(sourceFile), divergences.entries(), true);
        }

        DependencyOrder.Ordered ordered = DependencyOrder.sort(model, divergences);
        if (ordered.refused()) {
            return new GeneratedContext(sourceFile, null, currentText(sourceFile), divergences.entries(), true);
        }

        if (lazyBeanWithoutFactory(model, ordered, divergences)) {
            return new GeneratedContext(sourceFile, null, currentText(sourceFile), divergences.entries(), true);
        }

        Path implFile = implFileFor(sourceFile, model);
        String canonical = ContextSource.render(model, ordered.beans(), ordered.extraParameters(), indent);
        CooperativeCodegen.Reconciled reconciled;
        try {
            reconciled = CooperativeCodegen.reconcileMembers(
                    implFile, model.implSimpleName(), canonical, CooperativeCodegen.Reconciliation.ALL, Set.of());
        } catch (IOException unreadable) {
            // Reading the file this generator OWNS is the one read it still does — cooperative codegen has to
            // recognise its own previous output (DEC-020) — and a failure there is this context's problem, not
            // the run's: report it and refuse this one rather than aborting every other context.
            divergences.report("implementation_not_readable", String.valueOf(implFile.getFileName()),
                    "the implementation file could not be read to reconcile against: " + unreadable.getMessage(),
                    "the file as it is on disk", "a readable or absent implementation file",
                    "fix the file's permissions or contents and re-run");
            return new GeneratedContext(sourceFile, null, currentText(sourceFile), divergences.entries(), true);
        }

        List<String> all = new ArrayList<>(divergences.entries());
        all.addAll(reconciled.divergences());
        return new GeneratedContext(sourceFile, implFile, reconciled.source(), all, false);
    }

    /**
     * Whether a bean the generator cannot construct is one it must not guess at.
     *
     * <p>{@code Supplier<X>} and {@code DynamicResource<X>} say "this bean is resolved later", which needs a
     * factory that knows how; without one, {@code new Supplier<X>()} is not even valid Java. Reporting beats
     * emitting code that cannot compile, and beats inventing a supplier that captures a bean nobody built.</p>
     */
    private static boolean lazyBeanWithoutFactory(IocModel.Context model, DependencyOrder.Ordered ordered,
                                                  DivergenceReporter divergences) {
        boolean refused = false;
        for (IocModel.Bean bean : ordered.beans()) {
            String type = bean.typeText().trim();
            boolean lazy = type.startsWith("Supplier<") || type.startsWith("DynamicResource<");
            if (lazy && !model.factories().containsKey(bean.name())) {
                divergences.report("lazy_bean_needs_factory", model.qualifiedName() + "." + bean.name(),
                        "the bean's type defers its value, and laziness is opt-in in this API (DEC-036 § 8)",
                        type + " " + bean.name() + "()", "a default build" + bean.capitalized()
                                + "(...) on the module interface",
                        "add the factory method that decides when and how the value is produced");
                refused = true;
            }
        }
        return refused;
    }

    /** Where the implementation goes: the interface's own package, beside it (DEC-036 § 1). */
    public static Path implFileFor(Path sourceFile, IocModel.Context model) {
        Path directory = sourceFile.getParent();
        if (directory == null) {
            directory = Path.of(".");
        }
        return directory.resolve(model.implSimpleName() + ".java");
    }

    private static String currentText(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
