package hr.hrg.hipster.entity.tooling;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

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

/**
 * The two things this migration needs from javac, because the LST cannot answer them.
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
 * guide prescribes — and that {@code merge-java} uses — cannot detect this class of breakage, and a
 * straight port would silently lose the guard.</p>
 *
 * <h3>2. What line is this declaration on?</h3>
 * <p>DEC-029 keys the class index by FQN and records the declaration's line so a report can link to it,
 * and DEC-028 verifies every link against the line a member is declared on. JavaParser answered with
 * {@code getName().getBegin().line}. The LST has no line numbers at all: a node does not expose its
 * offset on the public API. So the line has to come from something that still models positions, which
 * is javac's {@link LineMap} over the same text.</p>
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
 * <h3>Scope</h3>
 * <p>Syntax only, for the validity question: type errors are ignored, because the reader is handed
 * fragments and single files whose dependencies are not on any classpath — the generator parses the
 * file it is about to write, and {@code addons = {SomeClass.class}} names types that exist only in the
 * consuming project. Reporting "cannot find symbol" as "the file is unreadable" would reject most of
 * the tree. What F-34 needs is narrower and exact: <em>is this text parseable Java?</em></p>
 */
final class JavaSyntaxCheck {

    /**
     * Error codes that are about types and resolution rather than syntax.
     *
     * <p>Listed explicitly so that an unrecognised code is treated as a syntax error, which is the
     * fail-safe direction: wrongly calling a file unreadable preserves it on disk and emits a
     * divergence a human reads, while wrongly calling it readable can silently rewrite it.</p>
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

    private JavaSyntaxCheck() {
    }

    /**
     * Whether {@code source} is syntactically well-formed Java.
     *
     * <p>Fails safe toward "not well-formed" — an unusable compiler, an IO failure, or an unrecognised
     * error all report {@code false}, so the caller preserves the file rather than regenerating over
     * it.</p>
     */
    static boolean isSyntacticallyValid(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        if (COMPILER == null) {
            // No system compiler: cannot answer the question, so do not claim the file is fine.
            return false;
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager =
                     COMPILER.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            // Parse only: no classpath, no analysis, no class output. `parse()` runs the grammar and
            // stops, which is precisely the question being asked.
            var task = (JavacTask) COMPILER.getTask(
                    null, manager, diagnostics, List.of("-proc:none", "-nowarn"),
                    null, List.of(new SourceFileObject(source)));
            task.parse();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            if (!isTypeError(diagnostic.getCode())) {
                return false;
            }
        }
        return true;
    }

    /**
     * One type declaration's name and the line that name sits on.
     *
     * @param simpleName     the type's own name
     * @param enclosingNames the enclosing types' simple names, outermost first, so a nested type whose
     *                       name collides with a top-level one is still distinguishable
     * @param line           1-based line of the <strong>name</strong>
     */
    record NamePosition(String simpleName, List<String> enclosingNames, int line) {

        NamePosition {
            enclosingNames = List.copyOf(enclosingNames);
        }
    }

    /**
     * Every type declaration in {@code source} with the line its name sits on, in source order.
     *
     * <p>Replaces JavaParser's {@code getName().getBegin().line}, which is why this class exists at all
     * — the LST does not expose positions. Returns an empty list when the source cannot be parsed, which
     * the caller reads as "line unknown" rather than as "line 1".</p>
     */
    static List<NamePosition> typeNameLines(String source) {
        List<NamePosition> found = new ArrayList<>();
        if (source == null || source.isBlank() || COMPILER == null) {
            return found;
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager =
                     COMPILER.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavacTask task = (JavacTask) COMPILER.getTask(
                    null, manager, diagnostics, List.of("-proc:none", "-nowarn"),
                    null, List.of(new SourceFileObject(source)));
            var units = task.parse();
            var unit = units.iterator().hasNext() ? units.iterator().next() : null;
            if (unit == null) {
                return found;
            }
            LineMap lineMap = unit.getLineMap();
            // The name tree is not addressable through `getSimpleName()` (it returns a `Name`, not a
            // `Tree`), so the name's position is found by scanning forward from the declaration's start
            // for the identifier. The scan is bounded by the declaration's own end, so it can never
            // walk into a following declaration.
            var positions = Trees.instance(task).getSourcePositions();
            new TreePathScanner<Void, List<String>>() {

                @Override
                public Void visitClass(ClassTree tree, List<String> enclosing) {
                    String simpleName = tree.getSimpleName().toString();
                    if (!simpleName.isEmpty()) {
                        long start = positions.getStartPosition(unit, tree);
                        long end = positions.getEndPosition(unit, tree);
                        long namePosition = namePositionIn(source, simpleName, start, end);
                        found.add(new NamePosition(simpleName, List.copyOf(enclosing),
                                (int) lineMap.getLineNumber(namePosition)));
                    }
                    List<String> nested = new ArrayList<>(enclosing);
                    nested.add(simpleName);
                    return super.visitClass(tree, nested);
                }
            }.scan(units, new ArrayList<>());
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return found;
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

    /**
     * The offset of a declaration's name within its own declaration text.
     *
     * <p>Found by a <strong>token-boundary</strong> match, not {@code indexOf}: a declaration like
     * {@code @Marker interface Marker {}} contains its own name inside the annotation, so a plain search
     * would return the annotation's occurrence and report the annotation's line — sending a reader to the
     * line above the declaration while looking authoritative. Staying inside
     * {@code [start, end)} means a following declaration can never supply the match.</p>
     *
     * @return the name's offset, or {@code start} when the name cannot be located (the declaration's
     *         own line, which is the best available answer rather than a fabricated one)
     */
    private static long namePositionIn(String source, String simpleName, long start, long end) {
        if (start < 0 || end > source.length()) {
            return Math.max(start, 0);
        }
        String region = source.substring((int) start, (int) end);
        for (int index = region.indexOf(simpleName); index >= 0; index = region.indexOf(simpleName, index + 1)) {
            boolean leftFree = index == 0 || !isIdentifierPart(region.charAt(index - 1));
            int after = index + simpleName.length();
            boolean rightFree = after >= region.length() || !isIdentifierPart(region.charAt(after));
            if (leftFree && rightFree) {
                return start + index;
            }
        }
        return start;
    }

    private static boolean isIdentifierPart(char character) {
        return Character.isJavaIdentifierPart(character);
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
