package hr.hrg.hipster.entity.tooling;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What a position costs once a file is already read — the question the generator actually pays per
 * declaration.
 *
 * <h3>Why this is separate from the parse benchmark</h3>
 * <p>{@link ReadPathJmhBenchmark} measures one read of one file. A real metadata pass asks a
 * <em>per-declaration</em> question: the accessor's line, the annotation's line, the member's verbatim
 * text, for every field of every view in the module. Those queries all go through
 * {@link JavaSyntaxCheck#inspect}, which memoises one javac parse per source text — so the shape to
 * measure is "cost of a lookup against a cached parse", and the shape to catch is a lookup that
 * re-parses, which turns a linear pass quadratic. The 2 000-member fixture is here for exactly that
 * reason: at that size the difference between the two is three orders of magnitude, not a rounding
 * error.</p>
 *
 * <p>Absolute numbers only, for the reason stated on {@link ReadPathJmhBenchmark}: the parser this
 * migration replaced is no longer in the build, so there is nothing honest to compare against.</p>
 *
 * <p>Run with the {@code jmh} profile:</p>
 * <pre>
 * scripts\mvn-jdk25.cmd -o clean test-compile -Pjmh -pl hipster-entity-tooling -am
 * scripts\mvn-jdk25.cmd -o -Pjmh -pl hipster-entity-tooling exec:java ^
 *     -Dexec.mainClass=org.openjdk.jmh.Main -Dexec.classpathScope=test ^
 *     -Dexec.args="-e PositionQueryJmhBenchmark -rff doc/brainstorm/rewrite-migration/07-testing/benchmarks/position-query.json -rf json"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PositionQueryJmhBenchmark {

    /** Members per fixture: 2 000 accessors and 2 000 setters in one interface. */
    private static final int ACCESSOR_PAIRS = 2_000;

    private String wideSource;
    private List<J.MethodDeclaration> wideMethods;
    private J.ClassDeclaration wideType;

    private String generatorSource;
    private J.CompilationUnit generatorUnit;
    private List<J.MethodDeclaration> generatorMethods;
    private String generatorOwner;

    @Setup
    public void build() {
        StringBuilder source = new StringBuilder(1_000_000);
        source.append("package p;\n\nimport java.util.List;\n\npublic interface Wide {\n");
        for (int i = 0; i < ACCESSOR_PAIRS; i++) {
            source.append("    /**\n     * Property ").append(i).append(".\n     */\n");
            source.append("    @FieldSource(name = \"prop").append(i).append("\")\n");
            source.append("    Long prop").append(i).append("();\n");
            source.append("    void setProp").append(i).append("(Long value);\n");
        }
        source.append("}\n");
        wideSource = source.toString();

        J.CompilationUnit wide = SourceReader.readSourceText(wideSource);
        wideType = TreeQueries.interfaces(wide).get(0);
        wideMethods = TreeQueries.methodsOf(wideType);
        if (wideMethods.size() != ACCESSOR_PAIRS * 2) {
            throw new IllegalStateException("fixture lost members: " + wideMethods.size());
        }

        Path generator = CompileHarness.findRepoRoot().resolve(
                "hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java");
        generatorSource = read(generator);
        generatorUnit = SourceReader.readSourceText(generatorSource);
        if (generatorUnit == null) {
            throw new IllegalStateException("the benchmark's real file must read: " + generator);
        }
        List<J.ClassDeclaration> generatorTypes = TreeQueries.topLevelTypes(generatorUnit);
        if (generatorTypes.isEmpty()) {
            throw new IllegalStateException("no top-level type in " + generator);
        }
        J.ClassDeclaration generatorType = TreeQueries.topLevelTypes(generatorUnit).get(0);
        generatorOwner = generatorType.getSimpleName();
        generatorMethods = TreeQueries.methodsOf(generatorType);
        if (generatorMethods.size() < 50) {
            throw new IllegalStateException("the real file should carry real methods, found "
                    + generatorMethods.size());
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
     * Every accessor's line, against a parse that is already cached. Reported per operation, so the number
     * is what a location payload costs for one member — the cost DEC-028 pays once per field.
     */
    @Benchmark
    @OperationsPerInvocation(ACCESSOR_PAIRS)
    public long accessorLinesOnTheSyntheticTree() {
        long total = 0;
        for (int i = 0; i < wideMethods.size(); i += 2) {
            total += TreeQueries.methodLineOf(wideMethods.get(i), "Wide", wideSource);
        }
        return total;
    }

    /**
     * The same question on a real 3 391-line file, which is the case the previous sentence's synthetic
     * fixture cannot settle: real code has overloads, nested types and annotations with arguments.
     */
    @Benchmark
    public long accessorLinesOnARealLargeFile() {
        long total = 0;
        for (J.MethodDeclaration method : generatorMethods) {
            total += TreeQueries.methodLineOf(method, generatorOwner, generatorSource);
        }
        return total;
    }

    /**
     * The annotation line of every annotated accessor — a second query per member, and the one whose
     * answer differs from the accessor's own line only because the position layer keeps them apart.
     */
    @Benchmark
    @OperationsPerInvocation(ACCESSOR_PAIRS)
    public long annotationLinesOnTheSyntheticTree() {
        long total = 0;
        for (int i = 0; i < wideMethods.size(); i += 2) {
            J.MethodDeclaration accessor = wideMethods.get(i);
            total += TreeQueries.annotationLineOf("Wide", accessor.getSimpleName(), "FieldSource", wideSource);
        }
        return total;
    }

    /**
     * Cooperative codegen's verbatim carry-through (DEC-020): the member's original text, sliced out of
     * the source at the recorded offsets, comment included. Every regenerated member pays this once.
     */
    @Benchmark
    @OperationsPerInvocation(ACCESSOR_PAIRS)
    public long memberTextsOnTheSyntheticTree() {
        long total = 0;
        for (int i = 0; i < wideMethods.size(); i += 2) {
            String text = TreeQueries.memberText("Wide", "method",
                    wideMethods.get(i).getSimpleName(), 0, wideSource);
            total += text == null ? -1 : text.length();
        }
        return total;
    }

    /**
     * The class index's question: every type in the file with its enclosing chain, which walks the tree
     * once and asks {@link JavaSyntaxCheck} for lines. Cost per type, at 2 006 declarations.
     */
    @Benchmark
    public long typesWithEnclosingOnARealLargeFile() {
        return TreeQueries.typesWithEnclosing(generatorUnit).size();
    }

    /**
     * A tree walk with no parse in it, to separate the walk's cost from the read's: this is what the
     * class index pays per file once the file is already in memory.
     */
    @Benchmark
    public long treeWalkOnlyOnARealLargeFile() {
        return TreeQueries.findAll(generatorUnit, J.MethodDeclaration.class).size()
                + TreeQueries.typeDeclarations(generatorUnit).size();
    }
}
