package hr.hrg.jcodebuddy.automation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs registered {@link Transformation}s over files, and reports the facts a batch run needs.
 *
 * <h3>What it is, and what it deliberately is not</h3>
 * <p>An engine over a registry: given a file and a transformation name it reads the file, runs the
 * transformation, and returns what happened; given a source root it does that for every Java file in a
 * deterministic order. Validation and analysis read the same files and answer questions about them.</p>
 *
 * <p><strong>Reading and writing are separate operations.</strong> {@link #apply} never touches the file
 * it read; {@link #applyInPlace} writes only when the transformation changed the text. The plan's sketch
 * conflated them — it produced output text and left the caller to guess whether anything was written,
 * which for a batch tool over a source tree is the difference between a dry run and a rewrite. The same
 * distinction the migration tooling draws ({@code verify-migration.js} reports; a caller applies), and the
 * reason {@code EntityMetadataGenerator} has an explicit {@code javaOutputRoot}.</p>
 *
 * <p><strong>The engine has no thread pool.</strong> The sketch created an {@code ExecutorService} in the
 * engine and never used it, while {@link BatchProcessor} created a second one. Concurrency lives in the
 * batch processor, which is the only thing that needs it, and the engine stays deterministic — which is
 * what makes {@link #applyAllSequential} mean anything.</p>
 *
 * @see TransformationRegistry
 * @see BatchProcessor
 */
public class AutomationEngine {

    private final TransformationRegistry registry = new TransformationRegistry();

    /**
     * The registry this engine runs from.
     *
     * <p>Returned rather than copied, because {@link ProjectAutomation} must register into <em>this</em>
     * registry: the sketch gave the facade a registry of its own, and a transformation registered there
     * could never run (see {@link TransformationRegistry}).</p>
     */
    public TransformationRegistry getRegistry() {
        return registry;
    }

    /** Registers a transformation; see {@link TransformationRegistry#register(Transformation)}. */
    public void register(Transformation transformation) {
        registry.register(transformation);
    }

    /**
     * Runs one transformation over one file <strong>without writing anything</strong>.
     *
     * @return what happened; {@link TransformationResult#output()} always holds the text that
     *         <em>would</em> replace the file, and {@code succeeded} says whether the transformation ran
     * @throws IllegalArgumentException when an argument is null or empty
     */
    public TransformationResult apply(Path sourceFile, String transformationName) {
        requireFile(sourceFile);
        // Resolved before the file is read, and outside run(...)'s catch: an unregistered name is a caller
        // error — a typo fails the whole run with the registry's list of real names — while a
        // transformation that fails on one file is a per-file result the batch reports and continues past.
        Transformation transformation = registry.get(transformationName);
        String source;
        try {
            source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // An unreadable file is a per-file failure, not an engine failure: a batch over a tree must
            // report it and continue, which is the whole point of a result object.
            return TransformationResult.failure(sourceFile, transformationName, "", "cannot read the file: "
                    + e.getMessage(), 0L);
        }
        return run(sourceFile, transformation, source);
    }

    /**
     * Runs one transformation over one file, writing the result when it differs from the input.
     *
     * @return what happened, with {@code changed} saying whether the file on disk was replaced
     */
    public TransformationResult applyInPlace(Path sourceFile, String transformationName) {
        TransformationResult result = apply(sourceFile, transformationName);
        if (result.succeeded() && result.changed()) {
            write(sourceFile, result.output());
        }
        return result;
    }

    /**
     * Runs one transformation over every Java file under {@code sourceRoot}, without writing.
     *
     * @see SourceFiles#findJavaFiles(Path) for the order and the exclusions
     */
    public List<TransformationResult> applyAll(Path sourceRoot, String transformationName) {
        return applyAll(sourceRoot, transformationName, false);
    }

    /**
     * Runs one transformation over every Java file under {@code sourceRoot}.
     *
     * @param writeInPlace whether a changed file is written back; a dry run is the default elsewhere in
     *                     this class for a reason — a batch rewrite of a source tree should be asked for
     */
    public List<TransformationResult> applyAll(Path sourceRoot, String transformationName,
                                               boolean writeInPlace) {
        requireRegistered(transformationName);
        List<TransformationResult> results = new ArrayList<>();
        for (Path file : SourceFiles.findJavaFiles(sourceRoot)) {
            results.add(writeInPlace ? applyInPlace(file, transformationName) : apply(file, transformationName));
        }
        return List.copyOf(results);
    }

    /**
     * Runs several transformations over every Java file under {@code sourceRoot}, in the order given.
     *
     * <p>"Sequential" is a promise about ordering, not a claim about threads: the transformations are
     * applied one after another to the same file, and each one sees what the previous one produced. That
     * matters for the emitters this repository has — a field enum must be reconciled after the interface
     * it describes has been read, not before — so the result is keyed by transformation name and each
     * list is in file order.</p>
     *
     * <p>The file is read once and the transformations are chained <em>in memory</em>; only the last
     * output is a candidate for writing. Writing after every step would put a half-transformed tree on
     * disk if a later step failed.</p>
     */
    public Map<String, List<TransformationResult>> applyAllSequential(Path sourceRoot,
                                                                     List<String> transformationNames) {
        return applyAllSequential(sourceRoot, transformationNames, false);
    }

    /** As {@link #applyAllSequential(Path, List)}, with optional write-back of the final text. */
    public Map<String, List<TransformationResult>> applyAllSequential(Path sourceRoot,
                                                                     List<String> transformationNames,
                                                                     boolean writeInPlace) {
        List<Transformation> transformations = resolveAll(transformationNames);
        Map<String, List<TransformationResult>> results = new LinkedHashMap<>();
        for (String name : transformationNames) {
            results.put(name, new ArrayList<>());
        }
        for (Path file : SourceFiles.findJavaFiles(sourceRoot)) {
            Read read = Read.of(file);
            if (read.text() == null) {
                for (String name : transformationNames) {
                    results.get(name).add(TransformationResult.failure(file, name, "",
                            "cannot read the file: " + read.error(), 0L));
                }
                continue;
            }
            String current = read.text();
            Map<String, TransformationResult> steps = runChain(file, transformationNames, transformations,
                    current);
            String text = current;
            for (Map.Entry<String, TransformationResult> step : steps.entrySet()) {
                results.get(step.getKey()).add(step.getValue());
                text = step.getValue().output();
            }
            if (writeInPlace && !text.equals(current)) {
                write(file, text);
            }
        }
        return results;
    }

    /**
     * Runs several transformations over <strong>one</strong> file, in order, chaining in memory.
     *
     * <p>The one-file form of {@link #applyAllSequential(Path, List)}: same ordering guarantee, same
     * "each step sees the previous step's output", and no file is written — a caller holding a single file
     * wants the final text back, and gets it from the last entry's {@code output}.</p>
     *
     * @return one result per name, in the order given
     */
    public Map<String, TransformationResult> applySequential(Path sourceFile,
                                                             List<String> transformationNames) {
        requireFile(sourceFile);
        List<Transformation> transformations = resolveAll(transformationNames);
        if (transformations.isEmpty()) {
            return Map.of();
        }
        Read read = Read.of(sourceFile);
        if (read.text() == null) {
            Map<String, TransformationResult> failures = new LinkedHashMap<>();
            for (String name : transformationNames) {
                failures.put(name, TransformationResult.failure(sourceFile, name, "",
                        "cannot read the file: " + read.error(), 0L));
            }
            return Collections.unmodifiableMap(failures);
        }
        return Collections.unmodifiableMap(runChain(sourceFile, transformationNames, transformations,
                read.text()));
    }

    /**
     * Resolves every name to a registered transformation, refusing a list it cannot honour.
     *
     * <p>All of it happens before any file is read: an unknown or repeated name is a caller error, and
     * letting either surface from inside the per-file loop would report it once per file.</p>
     */
    private List<Transformation> resolveAll(List<String> transformationNames) {
        if (transformationNames == null) {
            throw new IllegalArgumentException("transformationNames cannot be null");
        }
        List<Transformation> transformations = new ArrayList<>(transformationNames.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String name : transformationNames) {
            requireName(name);
            if (!seen.add(name)) {
                // Two entries with one name would key one list twice and silently drop the first one's
                // results, which is a report that disagrees with what ran.
                throw new IllegalArgumentException("transformation name appears twice in the list: '" + name + "'");
            }
            transformations.add(registry.get(name));
        }
        return transformations;
    }

    /** Chains the transformations over one text, returning an ordered map of name to result. */
    private Map<String, TransformationResult> runChain(Path sourceFile, List<String> names,
                                                       List<Transformation> transformations, String source) {
        Map<String, TransformationResult> steps = new LinkedHashMap<>();
        String text = source;
        for (int i = 0; i < names.size(); i++) {
            TransformationResult step = run(sourceFile, transformations.get(i), text);
            steps.put(names.get(i), step);
            text = step.output();
        }
        return steps;
    }

    /** The file's text, or the reason it could not be read. */
    private record Read(String text, String error) {

        static Read of(Path file) {
            try {
                return new Read(Files.readString(file, StandardCharsets.UTF_8), null);
            } catch (IOException e) {
                return new Read(null, e.getMessage());
            }
        }
    }

    /**
     * Validates one file: is it readable Java, is its public type named after it, does it declare a type.
     *
     * <p>See {@link SourceFacts} for what is checked and, more importantly, for what is not: a validator
     * that invented rules would be a second, disagreeing compiler.</p>
     */
    public ValidationResult validate(Path sourceFile) {
        requireFile(sourceFile);
        long started = System.nanoTime();
        String source;
        try {
            source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new ValidationResult(sourceFile, List.of("cannot read the file: " + e.getMessage()),
                    List.of(), elapsedMs(started));
        }
        SourceFacts.Facts facts = SourceFacts.of(fileName(sourceFile), source);
        return new ValidationResult(sourceFile, facts.errors(), facts.warnings(), elapsedMs(started));
    }

    /**
     * Analyses one file: its structural facts, and anything worth a reader's attention.
     *
     * <p>A file that cannot be read still produces a result — with an {@code issue} saying so and no
     * structural facts — because analysis is advisory and a batch over a tree should not stop at the
     * first file it cannot parse.</p>
     */
    public AnalysisResult analyze(Path sourceFile) {
        requireFile(sourceFile);
        long started = System.nanoTime();
        String source;
        try {
            source = Files.readString(sourceFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new AnalysisResult(sourceFile, Map.of(), List.of("cannot read the file: "
                    + e.getMessage()), elapsedMs(started));
        }
        SourceFacts.Facts facts = SourceFacts.of(fileName(sourceFile), source);
        List<String> issues = new ArrayList<>(facts.errors());
        issues.addAll(facts.warnings());
        // Analysis is not validation: the errors above are facts about the file, reported as issues so a
        // caller reading one result object sees everything this engine knows about the file.
        Map<String, Object> analysis = facts.analysis();
        if (countOf(analysis, AnalysisResult.CODE_LINES) >= 0
                && countOf(analysis, AnalysisResult.CODE_LINES) < 10
                && countOf(analysis, AnalysisResult.TOTAL_LINES) > 0) {
            issues.add("the file has fewer than 10 lines of code");
        }
        return new AnalysisResult(sourceFile, analysis, issues, elapsedMs(started));
    }

    /** One numeric fact out of an analysis map, or {@code -1} when the fact is not a number. */
    private static int countOf(Map<String, Object> analysis, String key) {
        Object value = analysis.get(key);
        return value instanceof Number number ? number.intValue() : -1;
    }

    /** The engine's registry, for a caller that wants to unregister or enumerate. */
    public List<String> registeredTransformations() {
        return registry.getAll();
    }

    /** Runs a transformation over text already in hand, measuring it. */
    private TransformationResult run(Path sourceFile, Transformation transformation, String source) {
        long started = System.nanoTime();
        String name = transformation.getName();
        try {
            String output = transformation.apply(source);
            if (output == null) {
                // A null return would otherwise travel into a result and then into a writer. Naming the
                // transformation is what makes it fixable.
                return TransformationResult.failure(sourceFile, name, source,
                        "the transformation returned null instead of the transformed text", elapsedMs(started));
            }
            return TransformationResult.success(sourceFile, name, source, output, elapsedMs(started));
        } catch (TransformationException e) {
            return TransformationResult.failure(sourceFile, name, source, e.getMessage(),
                    elapsedMs(started));
        } catch (RuntimeException e) {
            // A transformation that throws unchecked has a bug; the batch reports it as this file's
            // failure rather than losing the files after it.
            return TransformationResult.failure(sourceFile, name, source,
                    "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    elapsedMs(started));
        }
    }

    /** Writes the transformed text, turning an IO failure into an unchecked one the caller must see. */
    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String fileName(Path file) {
        return file.getFileName() == null ? null : file.getFileName().toString();
    }

    private static void requireFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("sourceFile cannot be null");
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
    }

    /**
     * Confirms a name is registered, before any file is read.
     *
     * <p>An unregistered name is a caller error, and the registry's {@link java.util.NoSuchElementException}
     * names every transformation that does exist — which is what actually fixes a typo. Letting it surface
     * from inside the per-file path would turn one mistake into one identical failure per file.</p>
     */
    private void requireRegistered(String name) {
        requireName(name);
        registry.get(name);
    }
}
