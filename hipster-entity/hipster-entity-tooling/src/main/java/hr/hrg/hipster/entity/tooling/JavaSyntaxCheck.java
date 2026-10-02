package hr.hrg.hipster.entity.tooling;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two things this migration needs from javac, because the LST cannot answer them: whether source
 * is syntactically valid, and where its declarations sit.
 *
 * <h3>1. Is this text syntactically well formed?</h3>
 * <p>{@link SourceReader}'s whole reason for existing is the fail-safe recorded as F-34: JavaParser is
 * <em>error tolerant</em>, so "a parse produced a result" is not "the file is readable", and treating
 * the two as the same once produced a compilation unit with <em>no enum constants</em> for a file whose
 * constant list had a syntax error. The ledger planner read that as "a fresh enum" and rebuilt the list
 * — a silent renumbering of a persisted positional array, reached by the code written to prevent it.</p>
 *
 * <p>OpenRewrite does not restore that signal. It <em>also</em> recovers, and more quietly: measured on
 * F-34's own fixture ({@code id(java.lang.Long.class;} inside an enum), {@code parseInputs} neither
 * throws, nor attaches a {@link org.openrewrite.ParseExceptionResult} marker, nor returns anything other
 * than a well-formed {@code J.CompilationUnit} with one enum in it. So the marker check the migration
 * guide prescribes — and that {@code merge-java} uses — cannot detect this class of breakage.</p>
 *
 * <h3>2. Where is this declaration?</h3>
 * <p>DEC-029 keys the class index by FQN and records the declaration's line so a report can link to it,
 * and DEC-028 verifies every link against the line a member is declared on. JavaParser answered with
 * {@code getName().getBegin().line} and {@code getBegin().line}; the LST exposes <strong>no positions at
 * all</strong>. So the lines come from javac's {@link LineMap} over the same text.</p>
 *
 * <h3>One parse answers both, and the answer is cached</h3>
 * <p>Both questions need the same javac parse, and the tooling asks them repeatedly about the same file
 * (every declaration of a file, then every member). {@link #inspect} therefore does one parse and
 * returns a {@link FileCheck} carrying the validity verdict <em>and</em> every position, and the result
 * is memoised per source text. Before this, the validity check and the line lookup each built their own
 * {@link StandardJavaFileManager} — on a full pass that is two handles per file — and the
 * {@link JavacTask} was never closed at all.</p>
 *
 * <h3>Why javac rather than something cheaper</h3>
 * <p>Cheaper checks were tried and fail. A structural sanity check ("does the tree look plausible")
 * passes on the broken fixture, because the tree <em>is</em> plausible — that is what "recovered" means.
 * Re-parsing with JavaParser would work but re-introduces the dependency this migration exists to
 * remove, and would take the guard away again the moment the dependency goes.</p>
 *
 * <p>javac is already on the classpath in the strongest possible sense: OpenRewrite's Java parser
 * <em>is</em> a javac front end, which is why this module compiles against {@code jdk.compiler}. So
 * asking javac costs no new dependency and cannot drift from the parser's own notion of the language.
 * </p>
 *
 * <h3>Scope: syntax only</h3>
 * <p>Type errors are ignored, because the reader is handed fragments and single files whose dependencies
 * are not on any classpath — the generator parses the file it is about to write, and
 * {@code addons = {SomeClass.class}} names types that exist only in the consuming project. Reporting
 * "cannot find symbol" as "the file is unreadable" would reject most of the tree. What F-34 needs is
 * narrower and exact: <em>is this text parseable Java?</em></p>
 */
final class JavaSyntaxCheck {

    /**
     * Error codes that are about types and resolution rather than syntax.
     *
     * <p>Listed explicitly so that an unrecognised code is treated as a syntax error, which is the
     * fail-safe direction: wrongly calling a file unreadable preserves it on disk and emits a divergence
     * a human reads, while wrongly calling it readable can silently rewrite it.</p>
     */
    private static final List<String> TYPE_ERROR_PREFIXES = List.of(
            "compiler.err.cant.resolve",
            "compiler.err.doesnt.exist",
            "compiler.err.not.def.access",
            "compiler.err.cant.apply.symbol",
            "compiler.err.cant.apply.symbols",
            "compiler.err.no.suitable.method",
            "compiler.err.incompatible.types",
            "compiler.err.prob.found.req",
            "compiler.err.no.match.entry",
            "compiler.err.abstract",
            "compiler.err.override",
            "compiler.err.non-static",
            "compiler.err.non-static.cant.be.ref",
            "compiler.err.already.defined",
            "compiler.err.cyclic.inheritance",
            "compiler.err.does.not.override.abstract");

    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    /**
     * The last results, keyed by the exact source text.
     *
     * <p>Bounded rather than unbounded: the access pattern is "many questions about one file, then move
     * on", so a small cache serves nearly every call, and clearing is preferable to holding a whole
     * tree's parsed sources alive.</p>
     */
    private static final int MAX_CACHED = 128;
    private static final ConcurrentHashMap<String, FileCheck> CACHE = new ConcurrentHashMap<>();

    private JavaSyntaxCheck() {
    }

    /**
     * One type declaration's position, keyed the way an FQN is composed.
     *
     * <p><strong>Two lines, because JavaParser exposed two.</strong> {@code getBegin().line} is the
     * declaration's start <em>including</em> its annotations (javac's own
     * {@code TreeInfo.getStartPos} returns the first annotation's position for a class), and
     * {@code getName().getBegin().line} is the line the name itself sits on. The two differ for any
     * annotated declaration, which in this module is most views — a metadata view line of 8 for
     * {@code @View(...)} on line 8 and {@code public interface PersonSummary} on line 9 was the
     * recorded behaviour of the ported pass. Keeping both on the one record means each call site can
     * ask for the line it actually meant instead of one of them silently winning.</p>
     *
     * @param declarationLine the declaration's start line, annotations included
     * @param nameLine        the line the declared name sits on
     */
    record TypePosition(String simpleName, List<String> enclosingNames, int declarationLine, int nameLine) {

        TypePosition {
            enclosingNames = List.copyOf(enclosingNames);
        }
    }

    /** One method's position: which type declares it, and the line its name sits on. */
    record MethodPosition(String declaringType, String simpleName, int parameterCount, int nameLine) {
    }

    /**
     * One field-like member's position: an enum constant, a record component or a plain field.
     *
     * <p>Three roles share one record because they share one javac shape — a {@code VariableTree} that
     * is a direct member of a type — and separating them is a question about the <em>enclosing type's
     * kind</em>, which the traversal knows and the node does not. They are recorded rather than derived
     * from a line scan because DEC-028's location payload is verified against the line it points at:
     * a regex that guessed the member would produce a link that opens the wrong line and looks like it
     * worked.</p>
     *
     * @param owner    the dotted display name of the declaring type, outermost first
     * @param role     {@code enum-constant}, {@code record-component} or {@code field}
     * @param nameLine the line the declared name sits on
     */
    record MemberPosition(String owner, String simpleName, String role, int nameLine) {
    }

    /**
     * One switch arm's character span, in source order.
     *
     * <p>Collected for the same reason as {@link MemberSpan}: field-enum compaction has to
     * <strong>delete</strong> the {@code forName} arm of a retired constant, and an LST node cannot be
     * removed from its parent. Slicing the arm's text out of the file is what replaces
     * {@code entry.remove()}.</p>
     *
     * @param startOffset the arm's first character (the {@code case} or {@code default} keyword)
     * @param endOffset   one past its last character
     */
    record CaseSpan(int startOffset, int endOffset) {
    }

    /** One annotation's position: the type or member it is written on, and its own line.
     *
     * @param declaringType the <strong>innermost</strong> enclosing type's simple name — the type
     *                      itself for an annotation on a type, its declaring type for one on a member.
     *                      Carried because a member name alone is not unique within a file: two nested
     *                      interfaces in one view file may each declare an accessor of the same name,
     *                      and their annotations sit on different lines.
     * @param owner         the member the annotation is written on, or the type's own name for a
     *                      type-level annotation
     */
    record AnnotationPosition(String declaringType, String owner, String simpleName, int line) {
    }

    /**
     * One member declaration's <strong>character span</strong>, for callers that must reproduce the
     * member's own text rather than re-print it.
     *
     * <p>Cooperative codegen has to carry a developer's member through a regeneration
     * <em>verbatim</em> — comments, formatting and all — which no AST round-trip preserves: a printer
     * normalises whitespace (and, on this migration, even the generic comma) and a re-printed member
     * would differ from the one the developer wrote. JavaParser answered this with
     * {@code node.getRange()}; the LST has no positions, so javac's own offsets are the substitute, and
     * they are exact rather than reconstructed.</p>
     *
     * @param owner          the dotted display name of the declaring type, outermost first
     * @param kind           {@code type}, {@code method}, {@code constructor}, {@code field},
     *                       {@code enum-constant} or {@code record-component}
     * @param parameterCount the declared arity for a method or constructor, {@code 0} otherwise — it
     *                       is what tells two overloads of one name apart
     * @param startOffset    the declaration's first character
     * @param endOffset      one past its last character
     */
    record MemberSpan(String owner, String kind, String name, int parameterCount,
                      int startOffset, int endOffset) {
    }

    /**
     * Everything one javac parse of a source text can answer.
     *
     * @param syntacticallyValid whether the text is well-formed Java (syntax errors only; type errors
     *                           are ignored, see the class comment)
     * @param types              every type declaration, with the line its name sits on
     * @param methods            every method declaration, with the line its name sits on
     * @param members            every enum constant, record component and field
     * @param spans              every member declaration's character span
     * @param annotations        every annotation written on a type or a method
     * @param caseLines          the start line of every switch arm in the file, in source order
     * @param caseSpans          the character span of every switch arm, in source order
     */
    record FileCheck(boolean syntacticallyValid, List<TypePosition> types,
                     List<MethodPosition> methods, List<MemberPosition> members,
                     List<MemberSpan> spans, List<AnnotationPosition> annotations,
                     List<Integer> caseLines, List<CaseSpan> caseSpans) {

        FileCheck {
            types = List.copyOf(types);
            methods = List.copyOf(methods);
            members = List.copyOf(members);
            spans = List.copyOf(spans);
            annotations = List.copyOf(annotations);
            caseLines = List.copyOf(caseLines);
            caseSpans = List.copyOf(caseSpans);
        }

        static FileCheck invalid() {
            return new FileCheck(false, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of());
        }
    }

    /**
     * One parse answering both questions, memoised per source text.
     *
     * <p>Fails safe toward "not valid, no positions": an unusable compiler, an IO failure or an
     * unrecognised error all report invalid, so a caller preserves the file rather than regenerating
     * over it.</p>
     */
    static FileCheck inspect(String source) {
        if (source == null || source.isBlank() || COMPILER == null) {
            return FileCheck.invalid();
        }
        FileCheck cached = CACHE.get(source);
        if (cached != null) {
            return cached;
        }
        FileCheck computed = parse(source);
        if (CACHE.size() > MAX_CACHED) {
            CACHE.clear();
        }
        CACHE.put(source, computed);
        return computed;
    }

    /** Whether {@code source} is syntactically well-formed Java. */
    static boolean isSyntacticallyValid(String source) {
        return inspect(source).syntacticallyValid();
    }

    /** Every type declaration in {@code source} with the line its name sits on, in source order. */
    static List<TypePosition> typeNameLines(String source) {
        return inspect(source).types();
    }

    private static FileCheck parse(String source) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<TypePosition> types = new ArrayList<>();
        List<MethodPosition> methods = new ArrayList<>();
        List<MemberPosition> members = new ArrayList<>();
        List<MemberSpan> spans = new ArrayList<>();
        List<AnnotationPosition> annotations = new ArrayList<>();
        List<Integer> caseLines = new ArrayList<>();
        List<CaseSpan> caseSpans = new ArrayList<>();
        try (StandardJavaFileManager manager =
                     COMPILER.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            // The manager is the resource to close: it holds the open file handles for the in-memory
            // source. `JavacTask` is deliberately *not* in the resource list — it is not
            // `AutoCloseable`, and its concrete implementation's `cleanup()` is package-private, so
            // there is no supported way to release it explicitly. Before this class was consolidated it
            // built a second manager per line lookup; that was the real cost, and one parse per text
            // answers both questions now.
            JavacTask task = (JavacTask) COMPILER.getTask(
                    null, manager, diagnostics, List.of("-proc:none", "-nowarn"),
                    null, List.of(new SourceFileObject(source)));
            // Parse only: no classpath, no analysis, no class output. `parse()` runs the grammar and
            // stops, which is precisely the question being asked.
            var units = task.parse();
            var unit = units.iterator().hasNext() ? units.iterator().next() : null;
            if (unit == null) {
                return FileCheck.invalid();
            }
            LineMap lineMap = unit.getLineMap();
            var positions = Trees.instance(task).getSourcePositions();

            new TreePathScanner<Void, List<String>>() {

                @Override
                public Void visitClass(ClassTree tree, List<String> enclosing) {
                    String simpleName = tree.getSimpleName().toString();
                    List<String> nested = new ArrayList<>(enclosing);
                    if (!simpleName.isEmpty()) {
                        types.add(new TypePosition(simpleName, List.copyOf(enclosing),
                                line(positions.getStartPosition(unit, tree), lineMap),
                                lineOfName(tree, simpleName, source, unit, positions, lineMap)));
                        collectAnnotations(tree.getModifiers().getAnnotations(), simpleName, simpleName,
                                source, unit, positions, lineMap, annotations);
                        // Anonymous classes contribute no name to the chain, and must not: the LST has
                        // no `J.ClassDeclaration` for one — its body hangs off `J.NewClass` — so a named
                        // type nested inside an anonymous class would otherwise carry a chain the LST
                        // side could never reproduce, and every lookup for it would miss.
                        nested.add(simpleName);
                        collectMembers(tree, String.join(".", nested), source, unit, positions, lineMap,
                                members, spans);
                    }
                    return super.visitClass(tree, nested);
                }

                @Override
                public Void visitMethod(MethodTree tree, List<String> enclosing) {
                    String simpleName = tree.getName().toString();
                    String owner = enclosing.isEmpty() ? "" : enclosing.get(enclosing.size() - 1);
                    methods.add(new MethodPosition(owner, simpleName,
                            tree.getParameters().size(),
                            lineOfName(tree, simpleName, source, unit, positions, lineMap)));
                    collectAnnotations(tree.getModifiers().getAnnotations(), owner, simpleName,
                            source, unit, positions, lineMap, annotations);
                    return super.visitMethod(tree, enclosing);
                }

                @Override
                public Void visitCase(com.sun.source.tree.CaseTree tree, List<String> enclosing) {
                    // Source order, file-wide: the caller zips this list against the LST's own
                    // source-ordered case list, because neither API can identify an arm to the other.
                    long start = positions.getStartPosition(unit, tree);
                    long end = positions.getEndPosition(unit, tree);
                    caseLines.add(line(start, lineMap));
                    caseSpans.add(new CaseSpan(start < 0 ? -1 : (int) start,
                            end < 0 ? -1 : (int) end));
                    return super.visitCase(tree, enclosing);
                }
            }.scan(units, new ArrayList<>());
        } catch (IOException | RuntimeException e) {
            return FileCheck.invalid();
        }

        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            if (!isTypeError(diagnostic.getCode())) {
                return new FileCheck(false, types, methods, members, spans, annotations, caseLines, caseSpans);
            }
        }
        return new FileCheck(true, types, methods, members, spans, annotations, caseLines, caseSpans);
    }

    /**
     * Records the field-like members of one type: its enum constants, its record components and its
     * fields.
     *
     * <p>Two rules separate the three roles, and both come from the <em>enclosing type</em> rather than
     * from the node, because javac gives all three the same shape (measured on JDK 25, see
     * {@code MIGRATION-CAVEATS.md}):</p>
     * <ul>
     *   <li><strong>enum constants come first.</strong> The language requires every constant to precede
     *       every other member, so within an enum a {@code VariableTree} is a constant until the first
     *       member that is not one. The type-name test is a second witness, not the rule — an enum
     *       constant's declared type is the enum itself.</li>
     *   <li><strong>a record's non-static {@code VariableTree} members are its components.</strong> The
     *       parser materialises each header component as a private final field positioned at the
     *       component in the header, so the component's own line is available; an explicitly declared
     *       field in a record must be static.</li>
     * </ul>
     *
     * <p>Only direct members are visited, so a local variable inside a method body can never be
     * mistaken for a field — the {@code VariableTree}s that appear in a body are not in
     * {@code getMembers()}.</p>
     */
    private static void collectMembers(ClassTree tree, String owner, String source,
                                       com.sun.source.tree.CompilationUnitTree unit,
                                       com.sun.source.util.SourcePositions positions, LineMap lineMap,
                                       List<MemberPosition> into, List<MemberSpan> spans) {
        boolean isEnum = tree.getKind() == Tree.Kind.ENUM;
        boolean isRecord = tree.getKind() == Tree.Kind.RECORD;
        boolean constantsEnded = false;
        List<? extends Tree> declared = tree.getMembers();
        for (int index = 0; index < declared.size(); index++) {
            Tree member = declared.get(index);
            if (member instanceof ClassTree nested) {
                constantsEnded = true;
                addSpan(spans, owner, "type", nested.getSimpleName().toString(), 0, member, unit, positions);
                continue;
            }
            if (member instanceof MethodTree method) {
                constantsEnded = true;
                boolean constructor = method.getName().contentEquals("<init>");
                addSpan(spans, owner, constructor ? "constructor" : "method",
                        constructor ? ownerSimpleName(owner) : method.getName().toString(),
                        method.getParameters().size(), method, unit, positions);
                continue;
            }
            if (!(member instanceof VariableTree variable)) {
                constantsEnded = true;
                continue;
            }
            String role;
            if (isEnum && !constantsEnded && isEnumConstant(variable, tree)) {
                role = "enum-constant";
            } else if (isRecord
                    && !variable.getModifiers().getFlags().contains(Modifier.STATIC)) {
                role = "record-component";
            } else {
                role = "field";
            }
            String name = variable.getName().toString();
            if (name.isEmpty()) {
                continue;
            }
            into.add(new MemberPosition(owner, name, role,
                    lineOfName(variable, name, source, unit, positions, lineMap)));

            if (!"field".equals(role)) {
                addSpan(spans, owner, role, name, 0, variable, unit, positions);
                continue;
            }
            // A field's span is the whole <em>declaration statement</em>, not the single declarator
            // javac reports. JavaParser modelled `private int a, b;` as one `FieldDeclaration` with two
            // variables, and both the identity and the preserved text of that member are the statement's
            // — so the span runs to the declaration's terminating semicolon. javac's own end position
            // cannot be used for this: measured, it already includes the semicolon of a single
            // declarator, which makes `int a; int b;` and `int a, b;` indistinguishable by offset alone.
            // A second, later declarator keeps its own line entry (which is what a location lookup asks
            // for) but is never looked up as a member identity — the first name is that identity, exactly
            // as `getVariables().get(0)` was.
            int start = startOffset(unit, positions, variable);
            spans.add(new MemberSpan(owner, "field", name, 0, start,
                    fieldDeclarationEnd(source, start)));
        }
    }

    /**
     * The offset just past the semicolon that ends a field declaration, or the declaration start.
     *
     * <p>A brace, bracket or parenthesis of the initialiser opens a nesting level, so a `;` inside an
     * anonymous class body or a lambda block is not the declaration's terminator; a string, character
     * or comment is skipped whole for the same reason. The scan stops at the class body's closing brace
     * ({@code depth < 0}) rather than running to the end of the file, so a malformed declaration
     * produces a span that is merely short instead of one that swallows the rest of the type.</p>
     */
    private static int fieldDeclarationEnd(String source, int start) {
        if (start < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = start; index < source.length(); index++) {
            char c = source.charAt(index);
            if (c == '"' || c == '\'') {
                index = skipQuoted(source, index);
            } else if (c == '/' && index + 1 < source.length()
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*')) {
                index = skipComment(source, index);
            } else if (c == '{' || c == '(' || c == '[') {
                depth++;
            } else if (c == '}' || c == ')' || c == ']') {
                depth--;
                if (depth < 0) {
                    return start;
                }
            } else if (c == ';' && depth == 0) {
                return index + 1;
            }
        }
        return start;
    }

    /**
     * The offset of the last character of the string, character or text block starting at {@code start}.
     */
    private static int skipQuoted(String source, int start) {
        char quote = source.charAt(start);
        if (quote == '"' && source.startsWith("\"\"\"", start)) {
            int close = source.indexOf("\"\"\"", start + 3);
            return close < 0 ? source.length() - 1 : close + 2;
        }
        int index = start + 1;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == quote || c == '\n') {
                return index;
            }
            index++;
        }
        return source.length() - 1;
    }

    /** The offset of the last character of the comment starting at {@code start}. */
    private static int skipComment(String source, int start) {
        if (source.startsWith("//", start)) {
            int newline = source.indexOf('\n', start);
            return newline < 0 ? source.length() - 1 : newline - 1;
        }
        int close = source.indexOf("*/", start + 2);
        return close < 0 ? source.length() - 1 : close + 1;
    }

    /** The innermost name of a dotted owner chain. */
    private static String ownerSimpleName(String owner) {
        int dot = owner.lastIndexOf('.');
        return dot < 0 ? owner : owner.substring(dot + 1);
    }

    private static void addSpan(List<MemberSpan> spans, String owner, String kind, String name,
                                int parameterCount, Tree member,
                                com.sun.source.tree.CompilationUnitTree unit,
                                com.sun.source.util.SourcePositions positions) {
        if (name.isEmpty()) {
            return;
        }
        spans.add(new MemberSpan(owner, kind, name, parameterCount,
                startOffset(unit, positions, member), endOffset(unit, positions, member)));
    }

    private static int startOffset(com.sun.source.tree.CompilationUnitTree unit,
                                   com.sun.source.util.SourcePositions positions, Tree tree) {
        long start = positions.getStartPosition(unit, tree);
        return start < 0 ? -1 : (int) start;
    }

    private static int endOffset(com.sun.source.tree.CompilationUnitTree unit,
                                 com.sun.source.util.SourcePositions positions, Tree tree) {
        long end = positions.getEndPosition(unit, tree);
        return end < 0 ? -1 : (int) end;
    }

    /** Whether an enum member is a constant: its declared type is the enum and it is constructed. */
    private static boolean isEnumConstant(VariableTree variable, ClassTree enumTree) {
        return variable.getType() instanceof IdentifierTree identifier
                && identifier.getName().contentEquals(enumTree.getSimpleName())
                && variable.getInitializer() instanceof com.sun.source.tree.NewClassTree;
    }

    private static void collectAnnotations(List<? extends AnnotationTree> written, String declaringType,
                                           String owner, String source,
                                           com.sun.source.tree.CompilationUnitTree unit,
                                           com.sun.source.util.SourcePositions positions, LineMap lineMap,
                                           List<AnnotationPosition> into) {
        for (AnnotationTree annotation : written) {
            String name = annotation.getAnnotationType().toString();
            int dot = name.lastIndexOf('.');
            into.add(new AnnotationPosition(declaringType, owner,
                    dot >= 0 ? name.substring(dot + 1) : name,
                    line(positions.getStartPosition(unit, annotation), lineMap)));
        }
    }

    /**
     * The line a declaration's <strong>name</strong> sits on.
     *
     * <p>Not the declaration's own start: a declaration carrying an annotation begins on the annotation's
     * line, and linking a reader there is wrong while looking authoritative. The name tree is not
     * addressable through {@code getSimpleName()} (it returns a {@code Name}, not a {@code Tree}), so the
     * name is located by a token-boundary scan inside the declaration's own span — which also stops a
     * declaration like {@code @Foo record Foo() {}} from resolving to the annotation's occurrence.</p>
     */
    private static int lineOfName(Tree tree, String simpleName, String source,
                                  com.sun.source.tree.CompilationUnitTree unit,
                                  com.sun.source.util.SourcePositions positions, LineMap lineMap) {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        return line(namePositionIn(source, simpleName, start, end), lineMap);
    }

    private static int line(long position, LineMap lineMap) {
        return position < 0 ? -1 : (int) lineMap.getLineNumber(position);
    }

    /**
     * The offset of a declaration's name within its own declaration text.
     *
     * <p>A token-boundary match inside {@code [start, end)}, not {@code indexOf} on the whole file: a
     * declaration like {@code @GenerateBuilder record GenerateBuilder {}} contains its own name inside
     * the annotation, and an unbounded search would return the annotation's occurrence — reporting the
     * annotation's line while looking authoritative. Staying inside the declaration's span also means a
     * following declaration can never supply the match.</p>
     *
     * <p>Staying inside the span is <em>not</em> enough on its own, and that was a defect until Phase 7
     * tested this class directly: javac's start position for a declaration <em>includes its
     * annotations</em> — which is exactly why {@code declarationLine} and {@code nameLine} differ at all —
     * so for {@code @GenerateBuilder record GenerateBuilder() {}} the first whole-token match inside the
     * record's own span is the annotation above it, and the record's line came out one to three lines
     * early. Two shapes are therefore rejected: an occurrence introduced by {@code @} or {@code .}, and an
     * occurrence inside a literal or comment — see {@link #insideLiteralOrComment}, which is the case an
     * annotation argument that repeats the member's name. The annotation collector on the path above
     * deliberately keeps the {@code @}: it is reporting the annotation, not a declaration.</p>
     */
    private static long namePositionIn(String source, String simpleName, long start, long end) {
        if (start < 0 || end > source.length()) {
            return Math.max(start, 0);
        }
        String region = source.substring((int) start, (int) end);
        for (int index = region.indexOf(simpleName); index >= 0; index = region.indexOf(simpleName, index + 1)) {
            boolean leftFree = index == 0 || !Character.isJavaIdentifierPart(region.charAt(index - 1));
            // `@Name` is an annotation reference and `Outer.Name` is a use of a nested type; neither is
            // ever how a declaration spells its own name.
            boolean belongsToSomethingElse = index > 0
                    && (region.charAt(index - 1) == '@' || region.charAt(index - 1) == '.');
            int after = index + simpleName.length();
            boolean rightFree = after >= region.length()
                    || !Character.isJavaIdentifierPart(region.charAt(after));
            if (leftFree && rightFree && !belongsToSomethingElse
                    && !insideLiteralOrComment(region, index)) {
                return start + index;
            }
        }
        return start;
    }

    /**
     * Whether an offset inside a declaration's own span falls in a string, character literal or comment.
     *
     * <p>The declaration's span includes its <em>annotations and their arguments</em>, because that is
     * what javac's start position means — and an argument is free to repeat the name it annotates.
     * {@code @FieldSource(name = "birthDate") LocalDate birthDate();} is the ordinary shape in this
     * project, not a corner case, and the naive scan returned the literal inside the annotation: an
     * accessor one to three lines early, at a line whose text mentions the field so it still reads as
     * plausible. Phase 7's large-fixture test found it. The whole span is walked with the same two
     * skip helpers {@link #fieldDeclarationEnd} uses, so the answer is consistent with the offsets the
     * spans themselves are built from.</p>
     */
    private static boolean insideLiteralOrComment(String region, int index) {
        int cursor = 0;
        while (cursor < region.length() && cursor <= index) {
            char c = region.charAt(cursor);
            if (c == '"' || c == '\'') {
                int literalEnd = skipQuoted(region, cursor);
                if (index <= literalEnd) {
                    return true;
                }
                cursor = literalEnd + 1;
            } else if (c == '/' && cursor + 1 < region.length()
                    && (region.charAt(cursor + 1) == '/' || region.charAt(cursor + 1) == '*')) {
                int commentEnd = skipComment(region, cursor);
                if (index <= commentEnd) {
                    return true;
                }
                cursor = commentEnd + 1;
            } else {
                cursor++;
            }
        }
        return false;
    }

    /** Whether a javac diagnostic code is about resolution/typing rather than syntax. */
    private static boolean isTypeError(String code) {
        if (code == null) {
            return false;
        }
        for (String prefix : TYPE_ERROR_PREFIXES) {
            if (code.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** An in-memory source file, so nothing touches the disk. */
    private static final class SourceFileObject extends SimpleJavaFileObject {

        private final String content;

        SourceFileObject(String content) {
            // The name is a fixed placeholder: `parse()` does not resolve it against anything, and
            // using the real path would make the caller's file appear in javac's errors.
            super(URI.create("string:///SourceReaderSyntaxCheck.java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
