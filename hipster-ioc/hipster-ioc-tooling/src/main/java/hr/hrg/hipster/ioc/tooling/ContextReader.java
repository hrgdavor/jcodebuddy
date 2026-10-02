package hr.hrg.hipster.ioc.tooling;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads one {@code @HipsterContext} interface into an {@link IocModel.Context}.
 *
 * <p>The reading goes through the repository's one source representation — OpenRewrite's LST, via
 * {@code hipster-entity-tooling}'s {@link TreeQueries} (DEC-030). That is not a stylistic preference: a
 * second, hand-rolled scan for annotations and method shapes is the defect DEC-030 exists to prevent, and
 * the queries this class needs (an interface's {@code extends} clause held in {@code getImplements()}, an
 * empty parameter list held as one {@code J.Empty}, a {@code default} modifier that is a keyword rather
 * than an accessor) are exactly the ones a second parser gets subtly wrong.</p>
 *
 * <p>A file that cannot be read cleanly is reported and yields nothing (F-34: the verdict is
 * {@code readable()}, never "a parse produced a result"), because a context half-read would generate a
 * context half-wired.</p>
 */
public final class ContextReader {

    /** The marker annotation DEC-036 generates for. */
    public static final String CONTEXT_ANNOTATION = "HipsterContext";

    /** The prefix of a module-interface factory method: {@code buildMapper} builds the {@code mapper} bean. */
    private static final String FACTORY_PREFIX = "build";

    private ContextReader() {
    }

    /**
     * Reads {@code file}, or returns empty when it declares no context (or cannot be read).
     *
     * @param file        the interface file
     * @param divergences where a problem is reported, in DEC-022's format
     */
    public static Optional<IocModel.Context> read(Path file, DivergenceReporter divergences) throws IOException {
        String text = Files.readString(file);
        SourceReader.Read read = SourceReader.readText(text);
        if (!read.readable()) {
            divergences.report("source_not_parsed", file.getFileName().toString(),
                    "the file could not be parsed, so its context is unknown",
                    "the file as it is on disk", "a readable Java interface",
                    "fix the syntax error; nothing was generated for this context");
            return Optional.empty();
        }

        J.CompilationUnit cu = read.unit();
        J.ClassDeclaration context = null;
        for (J.ClassDeclaration declaration : TreeQueries.interfaces(cu)) {
            if (TreeQueries.annotationNamed(declaration, CONTEXT_ANNOTATION) != null) {
                context = declaration;
                break;
            }
        }
        if (context == null) {
            return Optional.empty();
        }

        J.Annotation annotation = TreeQueries.annotationNamed(context, CONTEXT_ANNOTATION);
        String packageName = TreeQueries.packageName(cu);
        Map<String, J.ClassDeclaration> declaredTypes = new LinkedHashMap<>();
        for (J.ClassDeclaration declaration : TreeQueries.interfaces(cu)) {
            declaredTypes.put(declaration.getSimpleName(), declaration);
        }

        List<IocModel.Bean> beans = beansOf(context);
        // The module interface is the context's own supertype: the API documents it as a package-private
        // interface holding the `default buildXxx` methods, and in practice it is a *sibling file*
        // (`CtxMain extends CtxMainModule`, one file each). So the lookup is the same file first, then the
        // file named after the supertype beside it — the two shapes the API's own example uses.
        List<String> supertypes = TreeQueries.supertypeTexts(context);
        J.ClassDeclaration module = moduleOf(context, declaredTypes);
        String moduleText = null;
        if (module == null) {
            moduleText = siblingModuleText(file, supertypes);
            module = moduleIn(moduleText, supertypes);
        }
        Map<String, IocModel.Factory> factories = module == null
                ? Map.of()
                : factoriesOf(module, beans);

        return Optional.of(new IocModel.Context(packageName, context.getSimpleName(), beans, factories,
                classValues(annotation, "dependencies"), parentTypeOf(context), hasImplementation(annotation),
                importLines(text, moduleText)));
    }

    /** The text of the sibling file named after one of the supertypes, or {@code null} when there is none. */
    private static String siblingModuleText(Path file, List<String> supertypes) throws IOException {
        Path directory = file.getParent();
        if (directory == null) {
            return null;
        }
        for (String supertype : supertypes) {
            String simpleName = simpleNameOf(supertype);
            Path sibling = directory.resolve(simpleName + ".java");
            if (Files.isRegularFile(sibling)) {
                return Files.readString(sibling);
            }
        }
        return null;
    }

    /** The interface named by one of {@code supertypes} inside {@code text}, or {@code null}. */
    private static J.ClassDeclaration moduleIn(String text, List<String> supertypes) {
        if (text == null) {
            return null;
        }
        SourceReader.Read read = SourceReader.readText(text);
        if (!read.readable()) {
            return null;
        }
        for (String supertype : supertypes) {
            String simpleName = simpleNameOf(supertype);
            for (J.ClassDeclaration declaration : TreeQueries.interfaces(read.unit())) {
                if (declaration.getSimpleName().equals(simpleName)) {
                    return declaration;
                }
            }
        }
        return null;
    }

    /** {@code ChildContext<AppContext>} becomes {@code ChildContext}. */
    private static String simpleNameOf(String supertype) {
        return supertype.replaceAll("<.*", "").trim();
    }

    /** Every abstract no-arg, non-void accessor: DEC-036 § 2's definition of a bean. */
    private static List<IocModel.Bean> beansOf(J.ClassDeclaration context) {
        List<IocModel.Bean> beans = new ArrayList<>();
        for (J.MethodDeclaration method : TreeQueries.methodsOf(context)) {
            if (TreeQueries.isDefaultMethod(method) || TreeQueries.isStaticMethod(method)
                    || TreeQueries.isVoidReturn(method) || !TreeQueries.hasNoParameters(method)) {
                continue;
            }
            beans.add(new IocModel.Bean(method.getSimpleName(),
                    TreeQueries.typeText(method.getReturnTypeExpression())));
        }
        return beans;
    }

    /** The supertype declared in this same file, which is where {@code default buildXxx} methods live. */
    private static J.ClassDeclaration moduleOf(J.ClassDeclaration context,
                                               Map<String, J.ClassDeclaration> declaredTypes) {
        for (String supertype : TreeQueries.supertypeTexts(context)) {
            String simpleName = supertype.replaceAll("<.*", "").trim();
            J.ClassDeclaration declaration = declaredTypes.get(simpleName);
            if (declaration != null && !simpleName.equals(context.getSimpleName())) {
                return declaration;
            }
        }
        return null;
    }

    /** {@code default buildMapper(...)} on the module interface becomes the factory for the {@code mapper} bean. */
    private static Map<String, IocModel.Factory> factoriesOf(J.ClassDeclaration module,
                                                             List<IocModel.Bean> beans) {
        Map<String, IocModel.Factory> factories = new LinkedHashMap<>();
        for (J.MethodDeclaration method : TreeQueries.methodsOf(module)) {
            if (!TreeQueries.isDefaultMethod(method)) {
                continue;
            }
            String methodName = method.getSimpleName();
            if (!methodName.startsWith(FACTORY_PREFIX) || methodName.length() == FACTORY_PREFIX.length()) {
                continue;
            }
            String suffix = methodName.substring(FACTORY_PREFIX.length());
            for (IocModel.Bean bean : beans) {
                // The method name decides which bean it builds, and the bean's own capitalisation decides
                // the spelling — `buildMapper` for `mapper`, `buildURLSource` for `uRLSource` would be
                // wrong, so a bean whose capitalised name does not match is simply not this factory's.
                if (bean.capitalized().equals(suffix)) {
                    factories.put(bean.name(),
                            new IocModel.Factory(bean.name(), methodName, parametersOf(method)));
                }
            }
        }
        return factories;
    }

    private static List<IocModel.Parameter> parametersOf(J.MethodDeclaration method) {
        List<IocModel.Parameter> parameters = new ArrayList<>();
        if (method.getParameters() == null) {
            return parameters;
        }
        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
            if (!(parameter instanceof J.VariableDeclarations declarations) || declarations.getVariables() == null) {
                continue;
            }
            for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                boolean circular = declarations.getLeadingAnnotations() != null
                        && declarations.getLeadingAnnotations().stream()
                                .anyMatch(a -> "Circular".equals(TreeQueries.annotationName(a)));
                parameters.add(new IocModel.Parameter(TreeQueries.typeText(declarations.getTypeExpression()),
                        variable.getSimpleName(), circular));
            }
        }
        return parameters;
    }

    /**
     * The class-valued entries of an annotation attribute, as written.
     *
     * <p>{@code Text} rather than a resolved type, and that is the honest level of this reader: the
     * generator matches parameter types to a bean's <em>declared</em> text, exactly as a reader of the
     * source would, and a name that resolves to nothing is reported instead of being guessed at. The
     * trailing {@code .class} is trimmed because the LST spells a class literal as an expression whose text
     * ends that way, and the generator only needs the type's name.</p>
     */
    private static List<String> classValues(J.Annotation annotation, String attribute) {
        Expression value = TreeQueries.annotationArg(annotation, attribute);
        List<String> values = new ArrayList<>();
        if (value instanceof J.NewArray array && array.getInitializer() != null) {
            for (Expression element : array.getInitializer()) {
                values.add(classText(element));
            }
        } else if (value != null) {
            values.add(classText(value));
        }
        return values;
    }

    private static String classText(Expression expression) {
        String text = expression.toString().trim();
        return text.endsWith(".class") ? text.substring(0, text.length() - ".class".length()) : text;
    }

    /** The {@code P} of a {@code ChildContext<P>} supertype, or empty. */
    private static String parentTypeOf(J.ClassDeclaration context) {
        for (String supertype : TreeQueries.supertypeTexts(context)) {
            String trimmed = supertype.trim();
            if (trimmed.startsWith("ChildContext<") && trimmed.endsWith(">")) {
                return trimmed.substring("ChildContext<".length(), trimmed.length() - 1).trim();
            }
        }
        return "";
    }

    /** Whether {@code impl()} names a class, in which case an implementation already exists. */
    private static boolean hasImplementation(J.Annotation annotation) {
        Expression impl = TreeQueries.annotationArg(annotation, "impl");
        return impl != null && !impl.toString().contains("Void");
    }

    /**
     * The import lines of the interface <em>and</em> of its module interface, deduplicated, in order.
     *
     * <p>Both files' imports, because the generated class names the types both files name: a bean's type
     * comes from the context, and a factory's parameter type comes from the module. Copying only the
     * context's imports produces a file that does not compile the moment a factory mentions a type the
     * context did not — which is the common case, since the factory exists to build that type.</p>
     */
    private static List<String> importLines(String text, String moduleText) {
        List<String> imports = new ArrayList<>();
        for (String source : new String[]{text, moduleText}) {
            if (source == null) {
                continue;
            }
            for (String line : source.split("\n", -1)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ") || imports.contains(trimmed)) {
                    continue;
                }
                // The marker annotation's own import is dropped: the generated class implements the
                // interface and never names the annotation, and an unused import in generated source is
                // exactly the noise a reviewer stops reading.
                if (trimmed.endsWith("." + CONTEXT_ANNOTATION + ";")) {
                    continue;
                }
                imports.add(trimmed);
            }
        }
        return imports;
    }
}
