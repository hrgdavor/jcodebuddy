package hr.hrg.hipster.ioc.tooling;

import hr.hrg.hipster.entity.tooling.CooperativeCodegen;
import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.jcodebuddy.codegen.CodeContext;
import hr.hrg.jcodebuddy.codegen.CodeGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The hipster-ioc context generator, as a JCodeBuddy {@link CodeGenerator} (DEC-036).
 *
 * <p>It answers the SPI's two questions the way the SPI intends: {@link #isApplicable} is a substring test
 * on the file's text, not a parse, so offering this generator to every file in a tree costs one read; and
 * {@link #generate} returns the file's text rather than writing it, so the caller decides whether that text
 * reaches the disk. A watch agent can hand the same text to an editor, and a command can write it — the
 * generator does not know which is happening.</p>
 *
 * <p>Three refusals, all of them deliberate, and each reported in DEC-022's format rather than silently
 * skipped:</p>
 *
 * <ul>
 *   <li>a dependency cycle (marked or not) — see {@link DependencyOrder};</li>
 *   <li>a {@code Supplier<Bean>} or {@code DynamicResource<Bean>} bean with no factory, because laziness is
 *       opt-in and "generate something that looks lazy" is how a generator lies (DEC-036 § 8);</li>
 *   <li>a context whose {@code impl()} names a class, because an implementation already exists and this
 *       generator must not compete with it (DEC-036 § 7).</li>
 * </ul>
 *
 * <p>When it does generate, the text goes through {@link CooperativeCodegen#reconcileMembers}: members the
 * developer added are carried through verbatim, and {@code enabled:false} in the header freezes the file
 * (DEC-020, DEC-021 § 6). That is why this generator never writes to the file itself — the reconciliation is
 * the only place that decides what the file should become.</p>
 */
public final class IocContextGenerator implements CodeGenerator<IocContextGenerator.GeneratedContext> {

    /** The generator's stable name, as a log line or a report calls it. */
    public static final String NAME = "hipster-ioc";

    /** The substring {@link #isApplicable} looks for: cheap, and a false positive only costs a parse. */
    private static final String MARKER = "@" + ContextReader.CONTEXT_ANNOTATION;

    /**
     * What one run produced.
     *
     * @param sourceFile  the context interface that was read
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

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isApplicable(CodeContext context) {
        Path file = context.getFilePath();
        if (file == null || !Files.isRegularFile(file) || !file.toString().endsWith(".java")) {
            return false;
        }
        try {
            return Files.readString(file).contains(MARKER);
        } catch (IOException e) {
            // Unreadable is not "not applicable", it is "nothing can be decided here" — and the caller's own
            // pass reports unreadable files. Answering false keeps the SPI's contract (a predicate) honest.
            return false;
        }
    }

    @Override
    public GeneratedContext generate(CodeContext context) {
        Path sourceFile = context.getFilePath();
        DivergenceReporter divergences = new DivergenceReporter();
        try {
            Optional<IocModel.Context> read = ContextReader.read(sourceFile, divergences);
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
            String canonical = ContextSource.render(model, ordered.beans(), ordered.extraParameters(),
                    context.getIndent());
            CooperativeCodegen.Reconciled reconciled = CooperativeCodegen.reconcileMembers(
                    implFile, model.implSimpleName(), canonical, CooperativeCodegen.Reconciliation.ALL, Set.of());

            List<String> all = new ArrayList<>(divergences.entries());
            all.addAll(reconciled.divergences());
            return new GeneratedContext(sourceFile, implFile, reconciled.source(), all, false);
        } catch (IOException e) {
            divergences.report("source_not_parsed", String.valueOf(sourceFile.getFileName()),
                    "reading or reconciling the file failed: " + e.getMessage(),
                    "the file as it is on disk", "a generated implementation", "fix the file and re-run");
            return new GeneratedContext(sourceFile, null, currentText(sourceFile), divergences.entries(), true);
        }
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
