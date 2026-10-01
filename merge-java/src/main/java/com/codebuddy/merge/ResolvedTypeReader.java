// {@link com.codebuddy.merge.ResolvedTypeReader} Reads declared methods with their resolved parameter types.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.ParseExceptionResult;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses a version of a file and reports its declared methods with fully resolved
 * parameter types.
 *
 * <p>This is what makes two spellings of the same signature comparable. Compiling
 * the same method as {@code List<String>} on one branch and
 * {@code java.util.List<java.lang.String>} on the other produces text that looks
 * different but resolves identically, so a text comparison reports a collision that
 * does not exist. Resolved types make the comparison a question about the language
 * rather than about spelling.
 *
 * <h2>Parsing is pooled</h2>
 *
 * <p>Building a {@link JavaParser} is expensive, so one is created per classpath
 * and reused. A batch over many files pays for it once.
 *
 * <h2>Failure is reported, never swallowed</h2>
 *
 * <p>When the source cannot be parsed the result carries the parser's own message
 * instead of an empty method list. An empty list and a failure are different
 * answers - one means "no methods here", the other means "I could not tell" - and
 * conflating them would silently turn a conflict into a non-conflict.
 */
final class ResolvedTypeReader {

    /**
     * The outcome of reading one version of a file.
     *
     * @param methodsBySignature declared methods keyed by {@code name(json-params)}
     * @param failure            the parser's message when parsing failed, otherwise
     *                           {@code null}
     */
    record Reading(Map<String, Set<String>> methodsBySignature, String failure) {

        boolean succeeded() {
            return failure == null;
        }

        /**
         * The parameter signatures declared for a method name, as canonical
         * resolved-type lists.
         */
        Set<String> parametersOf(String methodName) {
            return methodsBySignature.getOrDefault(methodName, Set.of());
        }

        /**
         * The method names declared in this version.
         */
        Set<String> methodNames() {
            return methodsBySignature.keySet();
        }

        static Reading failed(String message) {
            return new Reading(Map.of(), message);
        }
    }

    private ResolvedTypeReader() {
    }

    /**
     * Execution context for parsing an <em>analysis</em> fragment.
     *
     * <p>{@code org.openrewrite.requirePrintEqualsInput} is disabled deliberately.
     * It normally guards code generation: a tree that does not print back to
     * exactly its input would corrupt a refactoring pass. Nothing here is written
     * back - the tree is only asked for its resolved types - and the property would
     * reject the very inputs this module handles, because a conflicting hunk is a
     * <em>fragment</em>. A bare {@code void process(String id) { }} parses to a
     * compilation unit containing a class, which by definition does not print back
     * to the fragment it came from.
     *
     * <p>Disabling it does not weaken the failure signal that matters: a version
     * that cannot be parsed produces no compilation unit and is reported as a
     * failure.
     */
    private static ExecutionContext analysisContext() {
        InMemoryExecutionContext context = new InMemoryExecutionContext();
        context.putMessage("org.openrewrite.requirePrintEqualsInput", false);
        return context;
    }

    /**
     * Read the declared methods of one version of a file.
     */
    static Reading read(String code, String filePath, TypeContext context) {
        if (code == null || code.isBlank()) {
            return new Reading(Map.of(), null);
        }
        if (context == null) {
            return Reading.failed("no type context was supplied");
        }

        List<J.MethodDeclaration> declarations = new ArrayList<>();
        String failure = parseInto(code, filePath, context, sourceFile -> {
            if (sourceFile instanceof J.CompilationUnit) {
                collectMethods(sourceFile, declarations);
            }
        });
        if (failure != null) {
            return Reading.failed(failure);
        }
        return new Reading(indexByName(declarations), null);
    }

    /**
     * The resolved type of a named declaration — a field, a local variable or a
     * parameter — in a fragment.
     *
     * <p>What a resolver needs when the question is about a <em>declaration's</em>
     * type rather than a method's parameters: the type that a branch wrote on the
     * left-hand side of {@code long count = 0}. Resolution is what makes the answer
     * a fact about the language instead of a lookup in a hand-written list —
     * {@code TreeSet} is a {@code NavigableSet} because javac says so, not because
     * somebody added it to a table.
     *
     * <p>Empty means "no type information at all" — a parse failure, a missing
     * context, or a declaration the fragment does not contain. It is never an empty
     * answer standing in for a negative one: the caller must treat it as "no answer"
     * and escalate, which is the opposite of treating it as "not assignable".
     *
     * <p><strong>A type that is absent from the classpath is a fourth case, and it is
     * not empty.</strong> Measured: {@code com.example.Missing value = null} parsed
     * against a JDK-only classpath resolves to {@link JavaType.Unknown} — present, and
     * assignable to nothing. So a caller that needs to tell "not a widening
     * relationship" apart from "could not resolve" cannot use an emptiness check; it
     * has to look at the type ({@code instanceof JavaType.Unknown}, or an unrenderable
     * type) as well. Both outcomes escalate, so the difference is in what the reviewer
     * is told, not in whether the answer is safe.
     */
    static Optional<JavaType> declaredType(String code, String name, String filePath,
        TypeContext context) {
        if (code == null || code.isBlank() || context == null || name == null) {
            return Optional.empty();
        }
        Map<String, JavaType> byName = new LinkedHashMap<>();
        String failure = parseInto(code, filePath, context, sourceFile -> {
            if (sourceFile instanceof J.CompilationUnit) {
                collectVariableTypes(sourceFile, byName);
            }
        });
        if (failure != null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * Parses one fragment and hands each compilation unit to {@code onUnit}.
     *
     * <p>One parse path for every question asked of a fragment. Two readers with
     * their own {@link JavaParser} setup would drift — the analysis context, the
     * source-path derivation and the failure handling below are each subtle enough
     * that a second copy would eventually disagree with this one about what "could
     * not be parsed" means.
     *
     * @return the parser's message when parsing failed, otherwise {@code null}
     */
    private static String parseInto(String code, String filePath, TypeContext context,
        Consumer<SourceFile> onUnit) {
        Path sourcePath = context.sourcePathFor(filePath);
        Path sourceRoot = context.sourceRoot();

        List<String> failures = new ArrayList<>();

        JavaParser parser = parserFor(context);
        // Let the parser derive the source path from the text rather than
        // fabricating one. OpenRewrite verifies that a parsed tree prints back to
        // exactly its input, and a path that does not match the content makes that
        // verification fail with "is not print idempotent".
        Path inputPath = parser.sourcePathFromSourceText(sourcePath, code);

        try (Stream<SourceFile> parsed = parser.parseInputs(
                List.of(input(code, inputPath)), sourceRoot, analysisContext())) {

            parsed.forEach(sourceFile -> {
                sourceFile.getMarkers().findAll(ParseExceptionResult.class).forEach(error ->
                    failures.add(error.getExceptionType() + ": " + error.getMessage()));

                if (sourceFile instanceof J.CompilationUnit) {
                    onUnit.accept(sourceFile);
                } else if (failures.isEmpty()) {
                    // A ParseError tree: not a compilation unit and no marker text.
                    failures.add("the source could not be parsed");
                }
            });
        } catch (RuntimeException e) {
            failures.add(e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        return failures.isEmpty() ? null : String.join("; ", failures);
    }

    /**
     * Index every named variable's resolved type.
     *
     * <p>Fields, locals and parameters all arrive here as
     * {@link J.VariableDeclarations}; a declaration naming several variables
     * ({@code int a, b;}) contributes each name with the same type, which is what
     * the source means. The first binding for a name wins, so an inner redeclaration
     * does not displace the outer one — the fragments this reads declare each name
     * once, and a stable answer matters more than the rare shadowing case.
     */
    private static void collectVariableTypes(SourceFile sourceFile, Map<String, JavaType> into) {
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(
                J.VariableDeclarations declarations, ExecutionContext context) {
                if (declarations.getVariables() != null) {
                    for (J.VariableDeclarations.NamedVariable variable : declarations.getVariables()) {
                        into.putIfAbsent(variable.getSimpleName(), declarations.getType());
                    }
                }
                return super.visitVariableDeclarations(declarations, context);
            }
        }.visit(sourceFile, new InMemoryExecutionContext());
    }

    private static void collectMethods(SourceFile sourceFile, List<J.MethodDeclaration> into) {
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(
                J.MethodDeclaration method, ExecutionContext context) {
                into.add(method);
                return super.visitMethodDeclaration(method, context);
            }
        }.visit(sourceFile, new InMemoryExecutionContext());
    }

    /**
     * Index declarations by name, collecting the parameter signature of each.
     */
    private static Map<String, Set<String>> indexByName(List<J.MethodDeclaration> declarations) {
        Map<String, Set<String>> byName = new LinkedHashMap<>();
        for (J.MethodDeclaration declaration : declarations) {
            String name = declaration.getSimpleName();
            byName.computeIfAbsent(name, key -> new LinkedHashSet<>())
                .add(parameterSignature(declaration));
        }
        return byName;
    }

    /**
     * The canonical parameter signature of a declaration, built from resolved
     * types.
     *
     * <p>Fall is back to the parameter's own source text when a type could not be
     * attributed, so a partially resolvable method still compares consistently with
     * itself rather than becoming an empty signature.
     */
    static String parameterSignature(J.MethodDeclaration declaration) {
        return declaration.getParameters().stream()
            .map(ResolvedTypeReader::renderParameter)
            .collect(Collectors.joining(","));
    }

    private static String renderParameter(J parameter) {
        if (parameter instanceof J.Empty) {
            // OpenRewrite models an empty parameter list as a single J.Empty
            // placeholder rather than as no parameters, so a no-arg method must be
            // rendered as an empty signature - not as the placeholder's tree text,
            // which would make it look like a parameter named "J.Empty".
            return "";
        }
        if (parameter instanceof J.VariableDeclarations declarations) {
            JavaType type = declarations.getType();
            String rendered = renderType(type);
            if (rendered != null) {
                return isVarargs(declarations) ? rendered + "..." : rendered;
            }
            // Could not attribute: fall back to the declared type's own text so the
            // parameter still contributes something stable to the signature.
            return declarations.getTypeExpression() == null
                ? "?"
                : declarations.getTypeExpression().toString().replaceAll("\\s+", "");
        }
        return parameter.toString().replaceAll("\\s+", "");
    }

    /**
     * Whether a parameter is declared varargs.
     *
     * <p>Detected from the declaration's own text rather than an accessor, because
     * a varargs parameter is the same resolved type as its array form and only the
     * declaration distinguishes them.
     */
    private static boolean isVarargs(J.VariableDeclarations declarations) {
        return declarations.getTypeExpression() != null
            && declarations.getTypeExpression().toString().contains("...");
    }

    /**
     * A canonical rendering of a resolved type, or {@code null} when the parser
     * could not attribute one.
     *
     * <p>Generic arguments are included, because {@code List<String>} and
     * {@code List<Integer>} are different parameter types. The rendering is
     * canonical rather than pretty: it is only ever compared with another
     * rendering, never shown to a user.
     */
    static String renderType(JavaType type) {
        if (type == null) {
            return null;
        }
        if (type instanceof JavaType.Parameterized parameterized) {
            String base = parameterized.getFullyQualifiedName();
            StringBuilder rendered = new StringBuilder(base).append('<');
            List<JavaType> typeParameters = parameterized.getTypeParameters();
            for (int index = 0; index < typeParameters.size(); index++) {
                if (index > 0) {
                    rendered.append(',');
                }
                rendered.append(renderType(typeParameters.get(index)));
            }
            return rendered.append('>').toString();
        }
        if (type instanceof JavaType.Array array) {
            String element = renderType(array.getElemType());
            return element == null ? null : element + "[]";
        }
        JavaType.FullyQualified fullyQualified = TypeUtils.asFullyQualified(type);
        if (fullyQualified != null) {
            return fullyQualified.getFullyQualifiedName();
        }
        if (type instanceof JavaType.GenericTypeVariable variable) {
            return variable.getName();
        }
        if (type instanceof JavaType.Primitive primitive) {
            return primitive.getKeyword();
        }
        // Last resort: the type's own string form, which is stable for comparison.
        String text = type.toString();
        return text.isBlank() || "null".equals(text) ? null : text;
    }

    private static Parser.Input input(String code, Path sourcePath) {
        return new Parser.Input(sourcePath,
            () -> new java.io.ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * One parser per classpath, reused across reads.
     */
    /**
     * A parser for one read.
     *
     * <p>Deliberately <b>not</b> pooled. A {@link JavaParser} caches the sources it
     * has parsed and refuses to parse another set declaring the same fully
     * qualified names - "Call reset() on JavaParser before parsing another set of
     * source files that have some of the same fully qualified names". Comparing
     * three versions of <em>the same file</em> is exactly that case, so a shared
     * parser fails on the second version every time.
     *
     * <p>Constructing a parser per read costs something, but the alternative is a
     * cache whose invalidation rules are not exposed, and a wrong answer here is
     * worse than a slower one.
     */
    private static JavaParser parserFor(TypeContext context) {
        return JavaParser.fromJavaVersion()
            .classpath(context.classpath())
            .build();
    }

    /**
     * The method names declared by a version, without needing type attribution.
     *
     * <p>Used to decide whether a conflict is about overloads at all before paying
     * for a comparison of resolved types.
     */
    static Set<String> methodNamesOnly(String code) {
        Set<String> names = new LinkedHashSet<>();
        for (DeclarationScanner.MethodDeclaration declaration
            : DeclarationScanner.declarationsIn(code)) {
            names.add(declaration.name());
        }
        return names;
    }

    /**
     * The first parse failure for a version, if any, for diagnostics.
     */
    static Optional<String> failureFor(String code, String filePath, TypeContext context) {
        Reading reading = read(code, filePath, context);
        return Optional.ofNullable(reading.failure());
    }
}
