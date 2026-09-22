package hr.hrg.hipster.entity.tooling;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * What one read costs, in absolute numbers.
 *
 * <h3>Why these three inputs</h3>
 * <p>The migration's honest performance claim is about the read path, not about a parser in the abstract,
 * so the benchmark runs the two entry points a generator actually calls — {@link SourceReader#readText}
 * (OpenRewrite's tree plus javac's validity check) and {@link JavaSyntaxCheck#inspect} (javac alone) —
 * against a small fragment, a real committed view, and the largest source file in the repository. The
 * split between the two entry points is the number that decides whether keeping javac in the loop is
 * affordable: it is a second parse of every file, paid for with F-34's guarantee that a recovered parse is
 * never mistaken for an empty file.</p>
 *
 * <p>Absolute only. The JavaParser comparison the plan sketched is not measured here because JavaParser
 * left the build in Phase 6; a phase that has removed the alternative cannot report against it, and
 * inventing a baseline would be worse than not having one. The shape to read out of these numbers is
 * cost-per-KB and the guard's share of a read — both of which the next run of this benchmark can be
 * compared against.</p>
 *
 * <p>Run with the {@code jmh} profile, from the repository root:</p>
 * <pre>
 * scripts\mvn-jdk25.cmd -o clean test-compile -Pjmh -pl hipster-entity-tooling -am
 * scripts\mvn-jdk25.cmd -o -Pjmh -pl hipster-entity-tooling exec:java ^
 *     -Dexec.mainClass=org.openjdk.jmh.Main -Dexec.classpathScope=test ^
 *     -Dexec.args="-e ReadPathJmhBenchmark -rff doc/brainstorm/rewrite-migration/07-testing/benchmarks/read-path.json -rf json"
 * </pre>
 *
 * <p>One fork, three warmup iterations, five measured ones: enough for a stable order of magnitude on a
 * workload dominated by parsing, short enough that someone will actually re-run it.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ReadPathJmhBenchmark {

    /** A view accessor pair: the shape the generator reads most often, by file count. */
    private static final String FRAGMENT = """
            package p;
            public interface V {
                /** The identifier. */
                @FieldSource(kind = FieldKind.DERIVED, expression = "id + 1")
                Long id();
            }
            """;

    private String view;
    private String largest;
    /** Makes each cold call a different cache key; see {@link #coldSyntaxCheckLargestFileInRepository()}. */
    private int calls;

    @Setup
    public void load() {
        Path root = CompileHarness.findRepoRoot();
        view = read(root.resolve(
                "hipster-entity-example/src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonSummary.java"));
        largest = read(root.resolve(
                "hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java"));
        // A benchmark whose inputs are silently missing measures nothing, so the sizes are asserted here
        // rather than trusted: 206 KB and 3391 lines at the time of writing.
        if (view.length() < 1_000 || largest.length() < 100_000) {
            throw new IllegalStateException("benchmark inputs are not the files they claim to be: view="
                    + view.length() + " chars, largest=" + largest.length() + " chars");
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path.toString(), e);
        }
    }

    /**
     * Each method returns the number of types in the tree it read, so the result cannot be optimised away
     * and the query cost it adds is the cheap {@link TreeQueries#typeDeclarations} walk, not a parse.
     */
    @Benchmark
    public int readFragment() {
        return TreeQueries.typeDeclarations(SourceReader.readSourceText(FRAGMENT)).size();
    }

    @Benchmark
    public int readCommittedView() {
        return TreeQueries.typeDeclarations(SourceReader.readSourceText(view)).size();
    }

    @Benchmark
    public int readLargestFileInRepository() {
        return TreeQueries.typeDeclarations(SourceReader.readSourceText(largest)).size();
    }

    /**
     * javac alone, which is what {@link SourceReader#readText} spends on the F-34 guard - the difference
     * between this and the numbers above is the guard's share of a read.
     */
    @Benchmark
    public int syntaxCheckFragment() {
        return JavaSyntaxCheck.inspect(FRAGMENT).types().size();
    }

    @Benchmark
    public int syntaxCheckCommittedView() {
        return JavaSyntaxCheck.inspect(view).types().size();
    }

    @Benchmark
    public int syntaxCheckLargestFileInRepository() {
        return JavaSyntaxCheck.inspect(largest).types().size();
    }

    /**
     * The same text inspected again, which is the path every position query in
     * {@link TreeQueries} takes after the first one. This is what the memoisation buys: the generator
     * asks a per-declaration question once per declaration, and without the cache the cost below would be
     * the cost above multiplied by the number of declarations in the file.
     */
    @Benchmark
    public int syntaxCheckAlreadyInspected() {
        return JavaSyntaxCheck.inspect(largest).types().size()
                + JavaSyntaxCheck.inspect(largest).methods().size();
    }

    /**
     * javac on a text it has not seen, which is the honest cost of the F-34 guard on one file.
     *
     * <p>{@link JavaSyntaxCheck} memoises on the exact source text, so calling it twice on one string
     * measures a hash lookup. The variant comment below makes every call a different key — which is what
     * a real generator faces: each file it reads is new text. One line appended, at the end, where it
     * cannot shift any other line's number.</p>
     */
    @Benchmark
    public int coldSyntaxCheckFragment() {
        return JavaSyntaxCheck.inspect(FRAGMENT + "// " + (++calls) + "\n").types().size();
    }

    @Benchmark
    public int coldSyntaxCheckCommittedView() {
        return JavaSyntaxCheck.inspect(view + "// " + (++calls) + "\n").types().size();
    }

    @Benchmark
    public int coldSyntaxCheckLargestFileInRepository() {
        return JavaSyntaxCheck.inspect(largest + "// " + (++calls) + "\n").types().size();
    }

    /**
     * A whole read of a text javac has not seen, so the two halves of {@link SourceReader#readText} can
     * be subtracted: this minus the cold javac number is OpenRewrite's share, and the remainder is what
     * keeping a second parser in the loop costs per file.
     */
    @Benchmark
    public int coldReadLargestFileInRepository() {
        String text = largest + "// " + (++calls) + "\n";
        return TreeQueries.typeDeclarations(SourceReader.readSourceText(text)).size();
    }

    /**
     * The cost of building the variant string above, on its own. Subtracted where a number has to be
     * honest about the harness that produces it — for the 206 KB input this is a real copy of the text
     * per call, not a rounding error.
     */
    @Benchmark
    public int stringConcatenationAlone() {
        return (largest + "// " + (++calls) + "\n").length();
    }
}
