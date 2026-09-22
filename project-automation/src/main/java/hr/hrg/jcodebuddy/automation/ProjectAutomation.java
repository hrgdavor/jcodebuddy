package hr.hrg.jcodebuddy.automation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The integration point: one place a caller reaches the engine, the registry and the batch processor.
 *
 * <h3>What the plan asked for, and the three things it could not have meant</h3>
 * <p>The sketch's facade had four defects, each of which would have shipped as a bug rather than as a
 * rough edge, so they are fixed here and named here — a reader comparing this class with
 * {@code 05-automation/ProjectAutomation.java} should find the differences deliberate:</p>
 * <ol>
 *   <li><strong>It had its own registry.</strong> {@code registerTransformation} wrote to a registry on
 *       the facade while the engine read its own, so a transformation registered through the only public
 *       entry point could never run. There is one registry — the engine's — and
 *       {@link #registerTransformation(Transformation)} delegates to it.</li>
 *   <li><strong>{@code registerTransformation(String, Class<T>)} called itself.</strong> It was infinite
 *       recursion, and its intent (instantiate the class reflectively) is the invisible wiring DEC-019
 *       rejects. There is no such overload: a transformation is registered as an instance, at a site a
 *       reader can navigate to.</li>
 *   <li><strong>{@code execute(String)} returned {@code transformation.apply("")} as a
 *       {@code TransformationResult}</strong>, which is a type error <em>and</em> a meaningless operation:
 *       a transformation applied to an empty string has nothing to transform. Running a transformation
 *       needs input, so the methods here take a file or take text.</li>
 *   <li><strong>{@code initialize()} was empty</strong> and documented as "called automatically when
 *       needed", which nothing did. There is no such method: a registry that silently decided its own
 *       contents is exactly the invisible-registration problem, and the transformations this project will
 *       register (the entity emitters) belong to their own modules' wiring.</li>
 * </ol>
 *
 * <p>What is kept is the shape the plan wanted from a facade: register, list, run on a file, run on a
 * tree, validate, analyse, and reach the parts for anything else.</p>
 */
public class ProjectAutomation {

    private final AutomationEngine engine;
    private final BatchProcessor batchProcessor;

    /** A facade over a fresh engine. */
    public ProjectAutomation() {
        this(new AutomationEngine());
    }

    /** A facade over {@code engine}, sharing its registry. */
    public ProjectAutomation(AutomationEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine cannot be null");
        }
        this.engine = engine;
        this.batchProcessor = new BatchProcessor(engine);
    }

    /** Registers a transformation with the engine, so every entry point can see it. */
    public void registerTransformation(Transformation transformation) {
        engine.register(transformation);
    }

    /** Every registered transformation name, in registration order. */
    public List<String> getAllTransformations() {
        return engine.registeredTransformations();
    }

    /** The registered transformation under {@code name}. */
    public Transformation getTransformation(String name) {
        return engine.getRegistry().get(name);
    }

    /** Runs one transformation over one file, without writing. */
    public TransformationResult executeOnFile(Path sourceFile, String transformationName) {
        return engine.apply(sourceFile, transformationName);
    }

    /** Runs one transformation over one file, writing it when the text changed. */
    public TransformationResult executeOnFileInPlace(Path sourceFile, String transformationName) {
        return engine.applyInPlace(sourceFile, transformationName);
    }

    /**
     * Runs several transformations over one file in order, each seeing the previous one's output.
     *
     * <p>Nothing is written; the text to write is the last result's
     * {@link TransformationResult#output()}. This delegates to the engine's one-file chain rather than to
     * its tree form: a facade method named "on a file" that quietly ran over the file's whole directory
     * would be the kind of surprise this class exists to remove.</p>
     */
    public Map<String, TransformationResult> executeOnFile(Path sourceFile,
                                                           List<String> transformationNames) {
        return engine.applySequential(sourceFile, transformationNames);
    }

    /** Runs one transformation over every Java file under {@code sourceRoot}, without writing. */
    public BatchProcessor.BatchReport executeOnTree(Path sourceRoot, String transformationName) {
        return batchProcessor.process(sourceRoot, transformationName);
    }

    /** Runs one transformation over every Java file under {@code sourceRoot}, writing changed files. */
    public BatchProcessor.BatchReport executeOnTreeInPlace(Path sourceRoot, String transformationName) {
        return batchProcessor.processInPlace(sourceRoot, transformationName);
    }

    /** Validates one file. */
    public ValidationResult validate(Path sourceFile) {
        return engine.validate(sourceFile);
    }

    /** Analyses one file. */
    public AnalysisResult analyze(Path sourceFile) {
        return engine.analyze(sourceFile);
    }

    /** The engine this facade drives. */
    public AutomationEngine getEngine() {
        return engine;
    }

    /** The registry the engine runs from. */
    public TransformationRegistry getRegistry() {
        return engine.getRegistry();
    }

    /** The batch processor this facade uses. */
    public BatchProcessor getBatchProcessor() {
        return batchProcessor;
    }

    /** Stops the batch processor's worker pool, so a command-line run exits. */
    public void shutdown() {
        batchProcessor.shutdown();
    }
}
