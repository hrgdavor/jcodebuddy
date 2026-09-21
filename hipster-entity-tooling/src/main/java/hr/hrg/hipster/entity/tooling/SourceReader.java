package hr.hrg.hipster.entity.tooling;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.ParseExceptionResult;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The one place the generator reads an existing source file (plan.dsflash § 8.7/3.20 and DR-7).
 *
 * <h3>Why a shared read exists at all</h3>
 * <p>JavaParser was <strong>error tolerant</strong>: for genuinely broken source it still returned a
 * <em>partial</em> compilation unit. Treating "the parse returned a result" as "the file is readable"
 * is therefore wrong in a way that is invisible — notes F-34 records the worst instance, where a
 * syntax error inside an enum's constant list produced a unit with <strong>no constants</strong>, the
 * R1 ledger planner read that as "a fresh enum", and rebuilt the constant list from the resolved
 * fields: a silent renumbering of a persisted positional array, reached by the code written to
 * prevent it.</p>
 *
 * <p>The fail-safe direction is therefore <em>always</em> "the file could not be read": preserve what
 * is on disk, emit a divergence, and let a human look. This class makes that the only way the
 * generator opens an existing file, so a new call site cannot forget the check — the {@code
 * orElse(null)} pattern it replaces had been copied into five places, each with the same latent
 * defect and only one of them (the ledger) fixed.</p>
 *
 * <h3>The Phase 6 port, and what deliberately did not change</h3>
 * <p>This class now parses with OpenRewrite instead of JavaParser. The <strong>contract is unchanged
 * on purpose</strong>: {@link Read} still distinguishes "there is no file" from "there is a file and
 * it is broken" through {@link Read#readable()} and {@link Read#unparseable()}, because that
 * distinction — not the parser — is what the five call sites depend on. Only the tree type moved,
 * from {@code com.github.javaparser.ast.CompilationUnit} to
 * {@link org.openrewrite.java.tree.J.CompilationUnit}.</p>
 *
 * <p>Two things are genuinely different, and neither is optional:</p>
 * <ol>
 *   <li><strong>Two failure channels, not one.</strong> JavaParser reported failure through a single
 *       {@code isSuccessful()} flag. OpenRewrite reports it two ways and <em>both</em> must be
 *       checked: it <em>throws</em> where JavaParser was tolerant, and a tree that is not a
 *       {@link J.CompilationUnit} means the input did not become Java at all. Checking only one
 *       silently reintroduces F-34.</li>
 *   <li><strong>The language level is not configured here.</strong> There is no
 *       {@code ParserConfiguration} to set and none is needed: the level <em>is</em> the parser
 *       artifact on the classpath ({@code rewrite-java-25}, pinned in this module's POM). That is
 *       the same {@code maven.compiler.release=25} the root POM sets, and the regression it guards is
 *       why {@link #readText} insists on a real {@link J.CompilationUnit} rather than accepting any
 *       {@code SourceFile}: a parser below the project's level could not read the generator's own
 *       output (switch expressions, records, {@code sealed}) — exactly the F-23 failure, where a
 *       JavaParser too old for {@code sealed} made five example files generate nothing, with no error
 *       at all.</li>
 * </ol>
 *
 * <p>What has no equivalent is the old {@code parser()} accessor, which handed out a live
 * {@code com.github.javaparser.JavaParser} for callers to parse snippets with. Each such caller now
 * asks this class for the tree it actually wanted; there is deliberately no replacement accessor,
 * because handing out the parser is how the single-read guarantee leaks.</p>
 */
public final class SourceReader {

    /**
     * The shared OpenRewrite parser, <strong>told about the project's own compiled classes</strong>.
     *
     * <p>Built once: constructing a parser resolves and reads a classpath, which is too expensive to
     * repeat per file. Unlike JavaParser, OpenRewrite's parser carries no language-level setting — the
     * level comes from the {@code rewrite-java-25} artifact on the classpath, which is why that
     * dependency is pinned rather than left to the parent's managed version.</p>
     *
     * <p>{@code runtimeClasspath()} rather than the default empty classpath so JDK types attribute.
     * It is <em>not</em> a source-root classpath: a type that exists only as source in this tree stays
     * unattributed, so no caller may depend on {@code getType()} being non-null.</p>
     */
    private static final JavaParser PARSER = JavaParser.fromJavaVersion()
            .classpath(JavaParser.runtimeClasspath())
            .build();

    /**
     * The JavaParser the un-ported call sites borrow, configured for the project's language level.
     *
     * <p>Kept only for the lifetime of the Phase 6 port, and configured exactly as the old
     * {@code PARSER} field was: {@code LanguageLevel.JAVA_25}, matching the root POM's
     * {@code maven.compiler.release=25}. A parser left at {@code POPULAR} (Java 11) cannot read
     * records, switch expressions or {@code sealed}, which is most of what this module generates, and
     * the failure is silent — a case can parse to something and be reported as clean (notes F-23 and
     * F-34). The comment is kept because the constant is the kind of thing a tidy-up deletes.</p>
     */
    private static final com.github.javaparser.JavaParser JPARSER =
            new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                    .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_25));

    /**
     * Execution context for reading something that is <em>not required to print back to itself</em>.
     *
     * <p>{@code org.openrewrite.requirePrintEqualsInput} is disabled deliberately, for the reason
     * recorded in {@code merge-java/.../ResolvedTypeReader}: it normally guards code
     * <em>generation</em>, where a tree that does not round-trip would corrupt a refactoring pass.
     * Nothing here is written back — the tree is read for its shape. Disabling it does not weaken the
     * signal that matters: text that cannot be parsed yields no {@link J.CompilationUnit} and is
     * reported as unparseable rather than silently accepted.</p>
     *
     * <p>The one caller for which this is not merely convenient is the fragment path
     * ({@link #readFragmentUnit}), where the supplied text is an expression and cannot round-trip by
     * definition. That method narrows the relaxation itself rather than relying on this context: see
     * {@link #parseFragment}.</p>
     */
    static final ExecutionContext PARSE_CONTEXT = parseContext();

    private static ExecutionContext parseContext() {
        InMemoryExecutionContext context = new InMemoryExecutionContext();
        context.putMessage("org.openrewrite.requirePrintEqualsInput", false);
        return context;
    }

    private SourceReader() {
    }

    /**
     * The configured JavaParser, for the queue files that have <strong>not yet been ported</strong>.
     *
     * <p>The old {@code parser()} accessor was public and unmarked, so nothing distinguished "this
     * caller is mid-migration" from "this caller is the toolchain's parser owner" — and a public
     * parser accessor is how a second, differently-configured parser gets built behind
     * {@link SourceReader}'s back. That is the defect this class exists to stop: a bare
     * {@code new JavaParser()} reads at Java 11 and cannot see records, switch expressions or
     * {@code sealed}, which made part of this module's own output silently unreadable (notes F-23).</p>
     *
     * <p>This accessor exists so the remaining JavaParser call sites borrow <em>this</em> configured
     * parser instead of constructing their own, and it is named for its lifetime rather than its
     * function: <strong>every caller is a Phase 6 porting target</strong>, and the accessor is deleted
     * with the last of them. It carries no {@code @Deprecated} because that reads as "use the
     * replacement instead"; there is no replacement, only files that have not been ported yet — the
     * Phase 6 checklist tracks which.</p>
     */
    public static com.github.javaparser.JavaParser portingParser() {
        return JPARSER;
    }

    /**
     * A parse through the JavaParser path, for the queue files that are <strong>not yet
     * ported</strong>.
     *
     * <p>Exists so an un-ported consumer can share the single configured parser and the single
     * "could this be read at all" answer while keeping the tree type it still manipulates. The
     * result's tree is a JavaParser {@code CompilationUnit}; the OpenRewrite path is
     * {@link #readText(String)}. Two methods rather than one generic one, because the two tree
     * types are not interchangeable and a caller silently handed the wrong one would fail at a
     * distance that is hard to read.</p>
     *
     * <p>Same contract as the LST path: a missing file is not an error, and a partial parse is a
     * failure rather than an empty file. That is the whole reason {@code isSuccessful()} is checked
     * here instead of at each call site.</p>
     *
     * @return the read outcome; {@link ReadJp#unit()} is null unless the parse was clean
     */
    public static ReadJp readJp(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return new ReadJp(null, false);
        }
        return readJpText(Files.readString(file));
    }

    /** {@link #readJp(Path)} for a caller holding source text. */
    public static ReadJp readJpText(String source) {
        com.github.javaparser.ParseResult<com.github.javaparser.ast.CompilationUnit> parsed =
                JPARSER.parse(source == null ? "" : source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return new ReadJp(null, true);
        }
        return new ReadJp(parsed.getResult().get(), false);
    }

    /**
     * The JavaParser-tree read outcome, mirroring {@link Read}.
     *
     * <p>A record of its own rather than a generic {@code Read<T>}: the two units have nothing in
     * common above {@code Object}, and a generic would invite a caller to pass one where the other
     * is meant — which is the failure this class exists to make impossible.</p>
     */
    public record ReadJp(com.github.javaparser.ast.CompilationUnit unit, boolean unparseable) {

        public boolean readable() {
            return unit != null;
        }
    }

    /**
     * {@link #readJp(Path)} for a caller outside this package that needs the JavaParser tree.
     *
     * <p>One accessor per parser, deliberately: {@link #readUnit(Path)} returns the LST and this
     * returns the JavaParser unit, so a caller's tree type is visible in the method it called rather
     * than in a cast. Both are deleted together when the last JavaParser caller is ported; today this
     * one serves the class index, which still describes types by walking a JavaParser tree.</p>
     *
     * @return the parsed unit, or {@code null} when the file is absent or could not be read cleanly
     */
    public static com.github.javaparser.ast.CompilationUnit readUnitJp(Path file) throws IOException {
        ReadJp read = readJp(file);
        return read.readable() ? read.unit() : null;
    }

    /** {@link #readJpText(String)} for a caller that only needs the JavaParser tree. */
    public static com.github.javaparser.ast.CompilationUnit readUnitJpText(String source) {
        ReadJp read = readJpText(source);
        return read.readable() ? read.unit() : null;
    }

    /**
     * The outcome of reading a file: either a compilation unit that parsed cleanly, or the reason it
     * could not be read.
     *
     * <p>{@code unparseable} is a separate signal rather than a {@code null} unit because the callers
     * need to distinguish "there is no file" from "there is a file and it is broken": the first is
     * normal (a fresh generation), the second must be reported.</p>
     */
    public record Read(J.CompilationUnit unit, boolean unparseable) {

        public boolean readable() {
            return unit != null;
        }

        static Read of(J.CompilationUnit unit) {
            return new Read(unit, false);
        }

        /**
         * Not named {@code unparseable()}: a static method with a record component's name and a
         * different return type is rejected by javac ("invalid accessor method in record") — which is
         * the compiler usefully refusing to let a factory be mistaken for an accessor.
         */
        static Read ofUnparseable() {
            return new Read(null, true);
        }
    }

    /** Reads a path if it exists; a missing file is not an error and yields an empty read. */
    public static Read read(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return Read.of(null);
        }
        return readText(Files.readString(file));
    }

    /**
     * {@link #read(Path)} for a caller outside this package — the class index, which has to describe a
     * generated artifact's types by parsing the file the pass just wrote.
     *
     * <p>The record it returns is deliberately <strong>not</strong> public: a caller outside this
     * package only needs the {@link J.CompilationUnit}, and widening the record's visibility would
     * invite a second reader to depend on the {@code unparseable} flag's exact meaning.</p>
     *
     * @return the parsed unit, or {@code null} when the file is absent or could not be read cleanly
     */
    public static J.CompilationUnit readUnit(Path file) throws IOException {
        Read read = read(file);
        return read.readable() ? read.unit() : null;
    }

    /**
     * {@link #readText(String)} for a caller outside this package: the parsed unit, or {@code null}
     * when the text could not be read cleanly.
     */
    public static J.CompilationUnit readSourceText(String source) {
        Read read = readText(source);
        return read.readable() ? read.unit() : null;
    }

    /**
     * Parses source text, distinguishing a clean parse from a partial one.
     *
     * <p>The old implementation existed to use JavaParser's {@code isSuccessful()} rather than
     * {@code getResult().isPresent()}. OpenRewrite has no such flag, so the equivalent question is
     * asked in the two places it can be answered: did the parse throw, and is the result actually a
     * Java compilation unit.</p>
     */
    public static Read readText(String source) {
        if (source == null) {
            return Read.ofUnparseable();
        }
        ParseOutcome outcome = parse(source);
        // A parse failure is one of three things, and checking only the first two silently
        // reintroduces F-34 — that is a measured finding, not a precaution:
        //
        //   1. the parser threw (it does throw for some inputs);
        //   2. it attached a ParseExceptionResult marker;
        //   3. it *recovered*: it returned a well-formed J.CompilationUnit for source with a
        //      syntax error in it, with no throw and no marker.
        //
        // Case 3 is the one OpenRewrite adds over JavaParser, and it is invisible from the
        // tree: verified on F-34's own fixture (`id(java.lang.Long.class;` inside an enum),
        // which yields exactly one well-formed enum and an empty marker list. So the
        // question the old isSuccessful() call answered has to be asked of something that
        // still knows the difference — see JavaSyntaxCheck.
        if (!outcome.failures().isEmpty()) {
            return Read.ofUnparseable();
        }
        for (SourceFile sourceFile : outcome.files()) {
            if (sourceFile instanceof J.CompilationUnit unit) {
                return JavaSyntaxCheck.isSyntacticallyValid(source) ? Read.of(unit) : Read.ofUnparseable();
            }
        }
        // Nothing parsed, or nothing that is Java: "could not be read", never "empty file".
        return Read.ofUnparseable();
    }

    /**
     * The parse failures for {@code source}, or an empty list when it read cleanly.
     *
     * <p>Exists so a caller that wants the parser's own words can have them, without being handed the
     * parser itself. The messages are the {@link ParseExceptionResult} markers OpenRewrite attaches to
     * a failed tree, plus the exception message when the parse threw.</p>
     */
    static List<String> problemsIn(String source) {
        if (source == null) {
            return List.of("the source was null");
        }
        ParseOutcome outcome = parse(source);
        List<String> problems = new ArrayList<>(outcome.failures());
        if (problems.isEmpty()) {
            for (SourceFile sourceFile : outcome.files()) {
                if (!(sourceFile instanceof J.CompilationUnit)) {
                    problems.add("the source did not parse as a Java compilation unit");
                }
            }
        }
        if (problems.isEmpty() && outcome.files().isEmpty()) {
            problems.add("the source produced no parse result");
        }
        return problems;
    }

    /**
     * What one parse produced: the trees it yielded, and the failures it reported.
     *
     * <p>One parse, not two — {@link #readText} and {@link #problemsIn} both ask "did this read
     * cleanly", and asking it twice by parsing twice was a real defect in the first draft of this
     * port.</p>
     */
    private record ParseOutcome(List<SourceFile> files, List<String> failures) {
    }

    /**
     * Parse {@code source} into whatever OpenRewrite produced, capturing rather than propagating the
     * throw.
     *
     * <p>The throw is captured <em>here</em> rather than at each call site because OpenRewrite throws
     * where JavaParser was tolerant, and every caller needs the same translation: a throw is a read
     * failure, not a crash. The message is kept so {@link #problemsIn} can report the parser's own
     * words.</p>
     *
     * <p>A parser caches the sources it has parsed and refuses a second set declaring the same fully
     * qualified names, so this shared instance is safe only because each call is self-contained and no
     * caller relies on cross-call attribution. A caller that needs several revisions of the
     * <em>same</em> file to attribute must build its own parser, as
     * {@code merge-java/.../ResolvedTypeReader} does.</p>
     */
    private static ParseOutcome parse(String source) {
        return parse(source, PARSE_CONTEXT);
    }

    private static ParseOutcome parse(String source, ExecutionContext context) {
        String text = source == null ? "" : source;
        Path inputPath = PARSER.sourcePathFromSourceText(Path.of("SourceReader.java"), text);
        try {
            List<SourceFile> files = PARSER.parseInputs(
                    List.of(input(inputPath, text)), null, context).toList();
            List<String> failures = new ArrayList<>();
            for (SourceFile sourceFile : files) {
                sourceFile.getMarkers().findAll(ParseExceptionResult.class).forEach(marker ->
                        failures.add(marker.getExceptionType() + ": " + marker.getMessage()));
            }
            return new ParseOutcome(files, failures);
        } catch (RuntimeException e) {
            return new ParseOutcome(List.of(), List.of(describe(e)));
        } finally {
            // A parser caches the sources it has parsed and refuses a second set declaring
            // the same fully qualified names ("Call reset() on JavaParser before parsing
            // another set of source files that have some of the same fully qualified
            // names"). This reader is called once per file *and* once per re-read of the
            // same file, which is exactly that case — without the reset, the second read of
            // any type fails and every generator sees "unparseable" for its own previous
            // output. merge-java/.../ResolvedTypeReader hit the same wall and chose a fresh
            // parser per read; resetting is the cheaper equivalent for a shared instance.
            PARSER.reset();
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Parser.Input input(Path path, String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new Parser.Input(path, () -> new ByteArrayInputStream(bytes));
    }

    /**
     * Reads an expression or type fragment such as {@code new Class<?>[0]}.
     *
     * <p>Replaces the old {@code SourceReader.parser().parseExpression(source)} escape hatch, which is
     * the only reason the parser accessor existed. OpenRewrite has no standalone expression parser, so
     * the fragment is wrapped in the smallest compilation unit that can hold it and read back out;
     * {@link #readFragment} does the extraction and documents how.</p>
     *
     * <p>Kept package-private and deliberately narrow: it returns the whole wrapper unit rather than
     * guessing which node the caller wanted, so the extraction stays at the call site where the
     * expected shape is known.</p>
     *
     * @return the wrapper unit, never {@code null}
     * @throws IllegalArgumentException if the fragment does not parse
     */
    static J.CompilationUnit readFragmentUnit(String fragment) {
        // The wrapper must itself be print-idempotent (it is ordinary Java), so the
        // relaxed context is not needed for correctness here — only for the fact that
        // a *caller* may pass a fragment whose formatting the printer would normalise.
        ParseOutcome outcome = parse(fragmentWrapper(fragment));
        for (SourceFile sourceFile : outcome.files()) {
            if (sourceFile instanceof J.CompilationUnit unit) {
                return unit;
            }
        }
        throw new IllegalArgumentException(
                "unable to parse expression fragment: " + fragment
                        + (outcome.failures().isEmpty() ? "" : " (" + String.join("; ", outcome.failures()) + ")"));
    }

    /**
     * The compilation unit that holds one expression fragment.
     *
     * <p>A static field initialiser rather than a method body, because the generator parses
     * <em>expressions</em> and an initialiser is the shortest context that accepts any expression
     * without also requiring a return type or a statement terminator.</p>
     */
    private static String fragmentWrapper(String fragment) {
        return "class $Fragment { Object $value = " + fragment + "; }";
    }

    /**
     * Reports the fail-safe decision for a file that could not be read.
     *
     * <p>The message names what was <em>not</em> done, because that is the consequence a reader has to
     * act on: a pass over an unreadable file must not look like a pass over a fresh one.</p>
     */
    static void reportUnparseable(DivergenceReporter divergences, String kind, String location,
                                  String cause, String notDone) {
        if (divergences == null) {
            return;
        }
        divergences.report(kind, location, cause, "unparseable source",
                "the previous revision kept as it is on disk",
                "inspect the file by hand: this pass did NOT " + notDone);
    }
}
