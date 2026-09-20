package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The one place the generator reads an existing source file (plan.dsflash § 8.7/3.20 and DR-7).
 *
 * <h3>Why a shared read exists at all</h3>
 * <p>JavaParser is <strong>error tolerant</strong>: for genuinely broken source it still returns a
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
 * <p>It also owns the <strong>configured parser</strong> ({@link #parser()}): a bare
 * {@code new JavaParser()} reads at Java 11 and therefore cannot read the generator's own output.
 * Every read in the tooling — generator and validator alike — goes through that one instance.</p>
 */
public final class SourceReader {

    /**
     * The pass's shared JavaParser, <strong>configured for the language level this project targets</strong>.
     *
     * <p>This is not a micro-optimisation; it is a correctness fix in the same family as the
     * {@code isSuccessful()} guard, and it was found by that guard. A bare {@code new JavaParser()}
     * uses {@link ParserConfiguration.LanguageLevel#POPULAR}, i.e. Java 11 — so the generator was
     * parsing its own output, the tree, and every developer file at a level that does not contain
     * switch expressions ({@code case 0 -> id;}, emitted by every builder's {@code get(int)}), records
     * (the whole {@code RECORD} level) or {@code sealed} (the example's payment-method hierarchy).
     * Every such file was a <em>parse problem</em>, and before this class existed the code path
     * silently swallowed it — notes F-23 records the extreme form of exactly this, where a JavaParser
     * too old to understand {@code sealed} caused five example files to generate nothing with no error
     * at all.</p>
     *
     * <p>The level is pinned to the project's own target (the root POM's
     * {@code maven.compiler.release=25}) rather than "the newest known constant", so the parser and
     * the compiler are always told the same thing.</p>
     */
    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));

    private SourceReader() {
    }

    /**
     * The shared, language-level-configured parser.
     *
     * <p>Every read of an existing source file must go through this instance rather than
     * {@code new JavaParser()}: an unconfigured parser cannot read the generator's own output, which
     * makes "unparseable" mean "uses a feature newer than Java 11" for a large part of the tree.</p>
     */
    public static JavaParser parser() {
        return PARSER;
    }

    /**
     * The outcome of reading a file: either a compilation unit that parsed cleanly, or the reason it
     * could not be read.
     *
     * <p>{@code unparseable} is a separate signal rather than a {@code null} unit because the two
     * callers need to distinguish "there is no file" from "there is a file and it is broken": the
     * first is normal (a fresh generation), the second must be reported.</p>
     */
    record Read(CompilationUnit unit, boolean unparseable) {

        boolean readable() {
            return unit != null;
        }

        static Read of(CompilationUnit unit) {
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
    static Read read(Path file) throws IOException {
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
     * package only needs the {@link CompilationUnit}, and widening the record's visibility would invite
     * a second reader to depend on the {@code unparseable} flag's exact meaning.</p>
     *
     * @return the parsed unit, or {@code null} when the file is absent or could not be read cleanly
     */
    public static CompilationUnit readUnit(Path file) throws IOException {
        Read read = read(file);
        return read.readable() ? read.unit() : null;
    }

    /**
     * {@link #readText(String)} for a caller outside this package: the parsed unit, or {@code null} when
     * the text could not be read cleanly.
     */
    public static CompilationUnit readSourceText(String source) {
        Read read = readText(source);
        return read.readable() ? read.unit() : null;
    }

    /**
     * Parses source text, distinguishing a clean parse from a partial one.
     *
     * <p>Deliberately uses {@link ParseResult#isSuccessful()} rather than
     * {@code getResult().isPresent()} — that substitution is the whole reason this class exists.</p>
     */
    static Read readText(String source) {
        ParseResult<CompilationUnit> parsed;
        try {
            parsed = SourceReader.parser().parse(source);
        } catch (ParseProblemException e) {
            return Read.ofUnparseable();
        }
        Optional<CompilationUnit> unit = parsed.getResult();
        if (!parsed.isSuccessful() || unit.isEmpty()) {
            // The parse problems are deliberately not printed here: every caller has a report line that
            // names the file and the consequence (`source_not_parsed` / `enum_not_parsed`), and a second
            // channel printing the raw JavaParser problem list is noise on top of it.
            return Read.ofUnparseable();
        }
        return Read.of(unit.get());
    }

    /**
     * Reports the fail-safe decision for a file that could not be read.
     *
     * <p>The message names what was <em>not</em> done, because that is the consequence a reader has
     * to act on: a pass over an unreadable file must not look like a pass over a fresh one.</p>
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
