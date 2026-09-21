package hr.hrg.hipster.entity.tooling;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Whether a piece of Java source is syntactically well formed, asked of javac.
 *
 * <h3>Why this exists at all</h3>
 * <p>{@link SourceReader}'s whole reason for existing is the fail-safe recorded as F-34: JavaParser
 * is <em>error tolerant</em>, so "a parse produced a result" is not "the file is readable", and
 * treating the two as the same once produced a compilation unit with <em>no enum constants</em> for a
 * file whose constant list had a syntax error. The ledger planner read that as "a fresh enum" and
 * rebuilt the list — a silent renumbering of a persisted positional array, reached by the code
 * written to prevent it.</p>
 *
 * <p>OpenRewrite does not restore that signal. It <em>also</em> recovers from syntax errors, and it
 * does so more quietly than JavaParser did: measured on F-34's own fixture
 * ({@code id(java.lang.Long.class;} inside an enum), {@code parseInputs} neither throws, nor attaches
 * a {@link org.openrewrite.ParseExceptionResult} marker, nor returns anything other than a
 * well-formed {@code J.CompilationUnit} with one enum in it. So the marker check the migration guide
 * prescribes (and that {@code merge-java} uses) cannot detect this class of breakage, and the port
 * would silently lose the guard.</p>
 *
 * <h3>Why javac rather than something cheaper</h3>
 * <p>Cheaper checks were tried and do not work. A structural sanity check ("does the tree look
 * plausible") passes on the broken fixture, because the tree <em>is</em> plausible — that is exactly
 * what "recovered" means. Re-parsing with JavaParser would work but re-introduces the dependency this
 * migration exists to remove, and would make the guard disappear again the moment the dependency
 * does.</p>
 *
 * <p>javac is already on the classpath in the strongest possible sense: OpenRewrite's own Java parser
 * <em>is</em> a javac front end, which is why this module compiles against {@code jdk.compiler}. So
 * asking javac directly costs no new dependency and cannot drift from the parser's own notion of the
 * language.</p>
 *
 * <h3>Scope: syntax only</h3>
 * <p>Only <strong>syntax</strong> errors are reported. Type errors are deliberately ignored, because
 * this reader is handed fragments and single files whose dependencies are not on any classpath — the
 * generator parses the file it is about to write, a validator parses one source root, and
 * {@code addons = {SomeClass.class}} references types that exist only in the consuming project.
 * Reporting "cannot find symbol" as "the file is unreadable" would reject most of the tree. What F-34
 * needs is narrower and exact: <em>is this text parseable Java?</em></p>
 *
 * <p>The distinction is drawn on the diagnostic code, not the message: javac reports syntax problems
 * under {@code compiler.err.premature.eof}, {@code compiler.err.illegal.start.of.expr},
 * {@code compiler.err.expected} and their siblings, while types arrive as
 * {@code compiler.err.cant.resolve.location} and friends. A code that is neither is treated as a
 * syntax problem only when javac itself classifies it at
 * {@link Diagnostic.Kind#ERROR} during the <em>parse</em> phase, which is what
 * {@link com.sun.source.util.JavacTask#parse} is limited to below.</p>
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
     * <p>Fails safe toward "not well-formed" — an unusable compiler, an IO failure, or an
     * unrecognised error all report {@code false}, so the caller preserves the file rather than
     * regenerating over it.</p>
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
            // Parse only: no classpath, no analysis, no class output. `parse()` runs the
            // grammar and stops, which is precisely the question being asked.
            var task = (com.sun.source.util.JavacTask) COMPILER.getTask(
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
            // The name is a fixed placeholder: `parse()` does not resolve it against anything,
            // and using the real path would make the caller's file appear in javac's errors.
            super(URI.create("string:///SourceReaderSyntaxCheck.java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
