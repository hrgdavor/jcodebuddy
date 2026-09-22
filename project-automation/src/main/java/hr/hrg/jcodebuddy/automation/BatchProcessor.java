package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs one transformation over many files, sequentially or in parallel, and reports the tally.
 *
 * <h3>Why the parallel path exists, and what it must not break</h3>
 * <p>Transforming a tree is dominated by reading and parsing each file, which is embarrassingly parallel
 * — but only because a {@link Transformation} is a function of one file's text. This class therefore
 * never lets two threads touch the same file, and it collects the futures <strong>in the order the files
 * were found</strong> rather than in completion order, so a parallel run's report is identical to a
 * sequential one's. The plan's sketch did that part right and it is the part worth keeping.</p>
 *
 * <h3>Default to not writing</h3>
 * <p>{@link #process} computes; {@link #processInPlace} writes changed files. The sketch had
 * {@code process} call its parallel helper and report totals, but nothing wrote anything and nothing
 * returned the results either — a caller could neither see what changed nor cause a change. Both are
 * available now, and the write has to be asked for.</p>
 *
 * <h3>The report is data, not stdout</h3>
 * <p>{@link #summarise} returns a {@link BatchReport} and {@code render()}s it; the sketch printed to
 * {@code System.out} from inside the processor, which makes the one thing a caller needs to assert on
 * unobservable. The migration tooling in this repository reports the same way — a value first, a
 * rendering second.</p>
 */
public class BatchProcessor {

    /** The tally and the per-file results of one batch run. */
    public record BatchReport(String transformationName, List<TransformationResult> results) {

        public BatchReport {
            results = results == null ? List.of() : List.copyOf(results);
        }

        public int total() {
            return results.size();
        }

        public int succeeded() {
            return (int) results.stream().filter(TransformationResult::succeeded).count();
        }

        public int failed() {
            return total() - succeeded();
        }

        public int changed() {
            return (int) results.stream().filter(TransformationResult::changed).count();
        }

        /** Whether every file that was read also ran to completion. */
        public boolean ok() {
            return failed() == 0;
        }

        /** One summary line, then one line per file that changed or failed. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(transformationName).append(": ").append(total()).append(" file(s), ")
                    .append(changed()).append(" changed, ").append(succeeded() - changed())
                    .append(" unchanged, ").append(failed()).append(" failed");
            for (TransformationResult result : results) {
                if (result.changed() || !result.succeeded()) {
                    sb.append(System.lineSeparator()).append("  ").append(result.render());
                }
            }
            return sb.toString();
        }
    }

    private final AutomationEngine engine;
    private final ExecutorService executor;
    private final int threads;

    /** A processor over {@code engine} with one worker per available processor. */
    public BatchProcessor(AutomationEngine engine) {
        this(engine, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * A processor over {@code engine} with a fixed number of workers.
     *
     * <p>The sketch hard-coded eight threads in a constructor that took no decision, which on a
     * single-core machine is eight threads competing for one core. The default here is the machine's own
     * count, and this constructor is how a caller chooses otherwise — or passes one thread to get the
     * sequential path's behaviour without changing code.
     */
    public BatchProcessor(AutomationEngine engine, int threads) {
        if (engine == null) {
            throw new IllegalArgumentException("engine cannot be null");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, was " + threads);
        }
        this.engine = engine;
        this.threads = threads;
        this.executor = Executors.newFixedThreadPool(threads);
    }

    /** The engine this processor runs through. */
    public AutomationEngine getEngine() {
        return engine;
    }

    /** How many workers a parallel run uses. */
    public int getThreads() {
        return threads;
    }

    /** Runs one transformation over every Java file under {@code sourceRoot}, without writing. */
    public BatchReport process(Path sourceRoot, String transformationName) {
        return new BatchReport(transformationName, processParallel(sourceRoot, transformationName, false));
    }

    /** Runs one transformation over every Java file under {@code sourceRoot}, writing changed files. */
    public BatchReport processInPlace(Path sourceRoot, String transformationName) {
        return new BatchReport(transformationName, processParallel(sourceRoot, transformationName, true));
    }

    /** Runs one transformation over every Java file under {@code sourceRoot}, one file at a time. */
    public BatchReport processSequential(Path sourceRoot, String transformationName) {
        return new BatchReport(transformationName,
                engine.applyAll(sourceRoot, transformationName, false));
    }

    /**
     * Runs one transformation over the files matching a glob, without writing.
     *
     * <p>Useful for the report-only passes this repository actually runs — every {@code *_.java} field
     * enum, every controller — and the reason {@link SourceFiles#findJavaFilesMatching} had to be a real
     * glob rather than a regular expression over an absolute path.</p>
     */
    public BatchReport processMatching(Path sourceRoot, String glob, String transformationName) {
        requireRegistered(transformationName);
        List<Path> files = SourceFiles.findJavaFilesMatching(sourceRoot, glob);
        List<TransformationResult> results = new ArrayList<>();
        for (Path file : files) {
            results.add(engine.apply(file, transformationName));
        }
        return new BatchReport(transformationName, results);
    }

    /**
     * Confirms the name is registered before any work starts.
     *
     * <p>An unregistered name is a caller error and belongs to the caller's stack trace. Discovering it
     * inside a worker would surface it as a {@link java.util.concurrent.CompletionException} from
     * {@code join()} with the registry's message buried inside — and discovering it inside the per-file
     * loop would report one identical failure per file.</p>
     */
    private void requireRegistered(String transformationName) {
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
        engine.getRegistry().get(transformationName);
    }

    /** Runs {@code transformationName} over each file in parallel, preserving the files' order. */
    private List<TransformationResult> processParallel(Path sourceRoot, String transformationName,
                                                      boolean writeInPlace) {
        requireRegistered(transformationName);
        List<Path> files = SourceFiles.findJavaFiles(sourceRoot);
        List<CompletableFuture<TransformationResult>> futures = new ArrayList<>(files.size());
        for (Path file : files) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> writeInPlace ? engine.applyInPlace(file, transformationName)
                            : engine.apply(file, transformationName),
                    executor));
        }
        List<TransformationResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<TransformationResult> future : futures) {
            results.add(future.join());
        }
        return results;
    }

    /**
     * Stops the worker pool, waiting briefly for in-flight files.
     *
     * <p>A processor that is never shut down keeps non-daemon threads alive and a command-line run never
     * exits; the sketch's version waited sixty seconds, which is a long time to hang for a batch that has
     * already finished its work. Ten seconds is longer than any local file read.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
