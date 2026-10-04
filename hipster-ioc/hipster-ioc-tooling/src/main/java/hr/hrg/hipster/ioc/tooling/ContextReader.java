package hr.hrg.hipster.ioc.tooling;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.MemberParameter;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.SourceSlice;
import hr.hrg.jcodebuddy.engine.index.TypeAnnotation;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a hipster-ioc context out of the <strong>metadata model</strong> rather than out of source text
 * (DEC-036, plan step 3.0e part two).
 *
 * <p>Before this rewrite the reader parsed the context interface, then hunted for the module interface by
 * <em>file name</em> beside it, and pulled the parent type out of a supertype's text. All three are facts the
 * engine already holds: the context is a row whose annotations carry {@code @HipsterContext}, its beans are its
 * no-argument methods, the module is the type one of its relations names, and the parent of a
 * {@code ChildContext<P>} is recovered by slicing the declaring file at the relation's range — which is what the
 * ranges exist for (DEC-040 D2/D6). So this class now reads the model, and the generator it serves parses
 * nothing.</p>
 *
 * <p><strong>What it still reports rather than guesses.</strong> Two things the model states honestly and this
 * reader passes on instead of papering over: a file whose imports were <em>not recorded</em> in the index (a
 * caller that built rows from facts rather than from a parse), and a module interface that is named but absent
 * from the index. Each is a divergence in DEC-022's format, because a generated context missing an import
 * compiles into an error the reader cannot see from here.</p>
 *
 * <p><strong>Annotation arguments are text in this model</strong> (DEC-029's "as written" rule), so
 * {@code @HipsterContext(dependencies = {A.class})} arrives as one argument string and this reader takes the
 * attribute apart itself. That is the honest level: the model records what the source says, and a consumer that
 * wants structure from it says so in its own code rather than the engine storing a second interpretation.</p>
 */
public final class ContextReader {

    /** The marker annotation DEC-036 generates for. */
    public static final String CONTEXT_ANNOTATION = "HipsterContext";

    /** The prefix of a module-interface factory method: {@code buildMapper} builds the {@code mapper} bean. */
    private static final String FACTORY_PREFIX = "build";

    /**
     * The prefix of an initialisation hook: {@code default void initFoo(Foo foo)}. See
     * {@link #initHooksOf} for the rules that make a method a hook rather than a method that merely starts
     * with {@code init}.
     */
    private static final String INIT_PREFIX = "init";

    private ContextReader() {
    }

    /**
     * Every context in an index: the rows carrying the marker annotation, in FQN order.
     *
     * <p>The model's answer to "which files hold a context" — and it is asked of the index rather than of the
     * tree, which is what lets a caller run the generator from committed metadata with no sources at hand.</p>
     */
    public static List<ClassRecord> contextsIn(ClassIndex index) {
        List<ClassRecord> contexts = new ArrayList<>();
        for (ClassRecord row : index.rows()) {
            if (markerOf(row) != null) {
                contexts.add(row);
            }
        }
        return List.copyOf(contexts);
    }

    /**
     * One context, read from the model, or empty when {@code context} carries no marker annotation.
     *
     * @param context      the row of the {@code @HipsterContext} interface
     * @param index        the index the row came from — where its module interface, its imports and the file its
     *                     ranges point into are found
     * @param divergences  where a problem is reported, in DEC-022's format
     */
    public static Optional<IocModel.Context> read(ClassRecord context, ClassIndex index,
                                                  DivergenceReporter divergences) {
        if (context == null || index == null) {
            return Optional.empty();
        }
        TypeAnnotation marker = markerOf(context);
        if (marker == null) {
            // Not a context: not a problem, and not reported — a caller that hands over every row in an index is
            // asking "is this one?", and the answer is no.
            return Optional.empty();
        }

        List<IocModel.Bean> beans = beansOf(context);
        ClassRecord module = moduleOf(context, index, divergences);
        Map<String, IocModel.Factory> factories = module == null
                ? Map.of() : factoriesOf(module, beans);
        Map<String, String> initHooks = module == null
                ? Map.of() : initHooksOf(module, beans, divergences);

        return Optional.of(new IocModel.Context(packageOf(context.fqn()), simpleNameOfFqn(context.fqn()),
                beans, factories, initHooks,
                classValues(marker, "dependencies"),
                parentTypeOf(context, index, divergences),
                hasImplementation(marker),
                importLines(index, context, module, divergences)));
    }

    /** The {@code @HipsterContext} annotation on a row, or {@code null} when it carries none. */
    private static TypeAnnotation markerOf(ClassRecord row) {
        for (TypeAnnotation annotation : row.annotations()) {
            if (CONTEXT_ANNOTATION.equals(annotation.simpleName())) {
                return annotation;
            }
        }
        return null;
    }

    /** Every abstract no-arg, non-void accessor: DEC-036 § 2's definition of a bean. */
    private static List<IocModel.Bean> beansOf(ClassRecord context) {
        List<IocModel.Bean> beans = new ArrayList<>();
        for (MemberRecord member : context.members()) {
            if (member.kind() != MemberRecord.Kind.METHOD
                    || member.modifiers().contains("default")
                    || member.modifiers().contains("static")
                    || member.type().isEmpty()
                    || "void".equals(member.type())
                    || !member.parameters().isEmpty()) {
                continue;
            }
            beans.add(new IocModel.Bean(member.name(), member.type()));
        }
        return beans;
    }

    /**
     * The module interface: the type the context's supertype relation names.
     *
     * <p>Found <strong>in the index</strong> rather than beside the file, which is both simpler and more correct:
     * a module in another package is found now, and one that is absent is reported instead of silently yielding
     * no factories. {@code ChildContext} is excluded because it is the API's own parent marker, not a module.</p>
     */
    private static ClassRecord moduleOf(ClassRecord context, ClassIndex index, DivergenceReporter divergences) {
        for (TypeRelation relation : context.relations()) {
            String name = simpleNameOf(relation.name());
            if ("ChildContext".equals(name)) {
                continue;
            }
            ClassRecord samePackage = index.row(packageOf(context.fqn()) + "." + name);
            if (samePackage != null) {
                return samePackage;
            }
            // A module written without its package (or imported from elsewhere): find it by simple name, and say
            // so when more than one candidate exists rather than picking by iteration order.
            List<ClassRecord> candidates = new ArrayList<>();
            for (ClassRecord row : index.rows()) {
                if (simpleNameOfFqn(row.fqn()).equals(name)) {
                    candidates.add(row);
                }
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            if (candidates.size() > 1) {
                divergences.report("module_interface_ambiguous", context.fqn() + " -> " + name,
                        "the context names " + name + " as its module and the index holds several types with that"
                                + " simple name",
                        name, "one module interface",
                        "qualify the supertype in the source, or index only the module it belongs to");
                return null;
            }
            divergences.report("module_interface_not_indexed", context.fqn() + " -> " + name,
                    "the context names " + name + " as its module, and the index has no such type, so its"
                            + " factory methods are unknown",
                    name, "the module interface in the same index",
                    "index the module's own source root as well, or fix the supertype");
            return null;
        }
        return null;
    }

    /** {@code default buildMapper(...)} on the module becomes the factory for the {@code mapper} bean. */
    private static Map<String, IocModel.Factory> factoriesOf(ClassRecord module, List<IocModel.Bean> beans) {
        Map<String, IocModel.Factory> factories = new LinkedHashMap<>();
        for (MemberRecord member : module.members()) {
            if (member.kind() != MemberRecord.Kind.METHOD || !member.modifiers().contains("default")) {
                continue;
            }
            String methodName = member.name();
            if (!methodName.startsWith(FACTORY_PREFIX) || methodName.length() == FACTORY_PREFIX.length()) {
                continue;
            }
            String suffix = methodName.substring(FACTORY_PREFIX.length());
            for (IocModel.Bean bean : beans) {
                // The method name decides which bean it builds, and the bean's own capitalisation decides the
                // spelling — `buildMapper` for `mapper`, and `buildURLSource` for `uRLSource` would be wrong, so a
                // bean whose capitalised name does not match is simply not this factory's.
                if (bean.capitalized().equals(suffix)) {
                    factories.put(bean.name(), new IocModel.Factory(bean.name(), methodName,
                            parametersOf(member)));
                }
            }
        }
        return factories;
    }

    /**
     * {@code default void initMapper(Mapper mapper)} on the module becomes the hook called right after the
     * {@code mapper} bean is created (DEC-036 § 3's amendment).
     *
     * <p>The recognition rules are deliberately narrow, because the alternative to a narrow rule is calling a
     * method the user did not intend as a hook: the method must be a {@code default} method (the generated class
     * inherits it, which is what makes the call compile), return {@code void}, take exactly one parameter, and
     * that parameter's type must be the type of a bean this context builds. Anything else named {@code init*} is
     * simply not a hook and is left alone — the generator only ever <em>adds</em> a call to code it can place.</p>
     *
     * <p>A bean may have at most one hook. Two methods claiming the same bean are reported rather than resolved
     * by order, because "which initialiser runs" is not a question to answer by declaration order.</p>
     */
    private static Map<String, String> initHooksOf(ClassRecord module, List<IocModel.Bean> beans,
                                                DivergenceReporter divergences) {
        Map<String, String> hooks = new LinkedHashMap<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (MemberRecord member : module.members()) {
            if (member.kind() != MemberRecord.Kind.METHOD || !member.modifiers().contains("default")) {
                continue;
            }
            String methodName = member.name();
            if (!methodName.startsWith(INIT_PREFIX) || methodName.length() == INIT_PREFIX.length()) {
                continue;
            }
            if (!"void".equals(member.type()) || member.parameters().size() != 1) {
                continue;
            }
            String parameterType = member.parameters().get(0).type().trim();
            for (IocModel.Bean bean : beans) {
                if (!bean.typeText().trim().equals(parameterType)) {
                    continue;
                }
                if (claimed.add(bean.name())) {
                    hooks.put(bean.name(), methodName);
                } else {
                    divergences.report("init_hook_ambiguous",
                            module.fqn() + "." + methodName,
                            "the bean '" + bean.name() + "' already has the initialisation hook '"
                                    + hooks.get(bean.name()) + "', and this context can only call one",
                            methodName + "(" + parameterType + ")",
                            "one hook per bean",
                            "rename or remove one of the two hooks");
                }
            }
        }
        return hooks;
    }

    /**
     * A factory method's parameters, each with the {@code @Circular} marker the generator needs.
     *
     * <p>A parameter's own annotations are in the model because step 3.0e part one put them there: the question
     * "which of these parameters is circular" is a question about a parameter, and answering it from source was
     * the second parse this rewrite removes.</p>
     */
    private static List<IocModel.Parameter> parametersOf(MemberRecord method) {
        List<IocModel.Parameter> parameters = new ArrayList<>(method.parameters().size());
        for (MemberParameter parameter : method.parameters()) {
            parameters.add(new IocModel.Parameter(parameter.type(), parameter.name(),
                    parameter.hasAnnotation("Circular")));
        }
        return parameters;
    }

    /**
     * The {@code P} of a {@code ChildContext<P>} supertype, or empty.
     *
     * <p>Recovered by <strong>slicing</strong> the declaring file at the relation's range, because the model keeps
     * the bare name for matching and the range for the written form (DEC-040 D2). When the slice is not usable —
     * the file moved on, or the caller built rows without ranges — the relation's own name is the fallback, which
     * is the honest degradation: a parent type without its argument rather than a guess at it.</p>
     */
    private static String parentTypeOf(ClassRecord context, ClassIndex index, DivergenceReporter divergences) {
        for (TypeRelation relation : context.relations()) {
            String written = relation.name();
            SourceSlice.Slice slice = SourceSlice.read(index, context, relation);
            if (slice.usable()) {
                written = slice.text();
            } else if ("ChildContext".equals(written)) {
                // The relation names the parent marker but its written form could not be sliced, so the `P` of
                // `ChildContext<P>` is unknown. Reported rather than guessed: the generated context then omits the
                // parent accessors, and a reader has to know that the reason is a missing range rather than a
                // context that has no parent (DEC-040 D2/D4).
                divergences.report("parent_type_not_readable", context.fqn(),
                        "the context names ChildContext as a supertype and its written form could not be read ("
                                + slice.problem() + "), so the parent type argument is unknown",
                        "ChildContext", "ChildContext<P> sliced from the declaring file",
                        "index this file with its content checksum (write the table after indexing) so the"
                                + " relation's range is usable");
            }
            String trimmed = written.trim();
            if (trimmed.startsWith("ChildContext<") && trimmed.endsWith(">")) {
                return trimmed.substring("ChildContext<".length(), trimmed.length() - 1).trim();
            }
        }
        return "";
    }

    /**
     * The class-valued entries of an annotation attribute, from the argument text the model records.
     *
     * <p>{@code dependencies = {A.class, B.class}} arrives as one string, because DEC-029 records an annotation's
     * arguments <em>as written</em>. The attribute is therefore taken apart here — the one place in this reader
     * that interprets text rather than reading a fact — and the trailing {@code .class} is trimmed because a class
     * literal is spelled that way and the generator needs the type's name.</p>
     */
    private static List<String> classValues(TypeAnnotation annotation, String attribute) {
        List<String> values = new ArrayList<>();
        for (String argument : annotation.arguments()) {
            String text = argument.trim();
            if (!text.startsWith(attribute)) {
                continue;
            }
            int equals = text.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String value = text.substring(equals + 1).trim();
            if (value.startsWith("{")) {
                value = value.substring(1, value.endsWith("}") ? value.length() - 1 : value.length());
            }
            for (String element : value.split(",")) {
                String trimmed = element.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed.endsWith(".class")
                            ? trimmed.substring(0, trimmed.length() - ".class".length()).trim()
                            : trimmed);
                }
            }
        }
        return values;
    }

    /** Whether {@code impl()} names a class, in which case an implementation already exists. */
    private static boolean hasImplementation(TypeAnnotation annotation) {
        for (String value : classValues(annotation, "impl")) {
            return !value.isEmpty() && !value.endsWith("Void");
        }
        return false;
    }

    /**
     * The import lines of the context's file <em>and</em> of its module's, deduplicated, in order.
     *
     * <p>Both files' imports, because the generated class names the types both files name: a bean's type comes
     * from the context, and a factory's parameter type comes from the module. They are read from the index's
     * {@code imports.json} sidecar (DEC-040 D1) — and when a file's imports were never recorded, that is reported
     * rather than silently producing a class whose types are unimported.</p>
     */
    private static List<String> importLines(ClassIndex index, ClassRecord context, ClassRecord module,
                                            DivergenceReporter divergences) {
        List<String> imports = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        paths.add(context.path());
        if (module != null && !paths.contains(module.path())) {
            paths.add(module.path());
        }
        for (String path : paths) {
            List<String> recorded = index.importsOf(path);
            if (recorded == null) {
                divergences.report("imports_not_recorded", path,
                        "the index carries no import lines for this file, so the generated class cannot name the"
                                + " types it writes",
                        "no imports recorded", "the file's imports in the index",
                        "index this file with a parse (addTypes with its compilation unit) rather than from facts");
                continue;
            }
            for (String line : recorded) {
                // The marker annotation's own import is dropped: the generated class implements the interface and
                // never names the annotation, and an unused import in generated source is noise a reviewer stops
                // reading.
                if (!imports.contains(line) && !line.endsWith("." + CONTEXT_ANNOTATION + ";")) {
                    imports.add(line);
                }
            }
        }
        return imports;
    }

    /** The package of a fully qualified name, empty for the default package. */
    static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /** The last segment of a fully qualified name — the simple name a row is addressed by. */
    static String simpleNameOfFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** The last segment of a written type name, with any type arguments removed. */
    private static String simpleNameOf(String written) {
        String withoutArguments = written.replaceAll("<.*", "").trim();
        int dot = withoutArguments.lastIndexOf('.');
        return dot < 0 ? withoutArguments : withoutArguments.substring(dot + 1);
    }
}
