// {@link ProjectAutomation} Integration point for project-automation module.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Integration point with project-automation module.
 * 
 * Provides a high-level API for executing transformations.
 * 
 * @see AutomationEngine
 * @see TransformationRegistry
 */
public class ProjectAutomation {
    private final AutomationEngine engine;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final TransformationRegistry registry = new TransformationRegistry();
    
    /**
     * Creates a new ProjectAutomation with the given automation engine.
     * 
     * @param engine the automation engine to use
     */
    public ProjectAutomation(AutomationEngine engine) {
        this.engine = engine;
    }
    
    /**
     * Creates a new ProjectAutomation with default automation engine.
     */
    public ProjectAutomation() {
        this(new AutomationEngine());
    }
    
    /**
     * Registers a transformation by name.
     * 
     * @param name the transformation name
     * @param transformation the transformation to register
     */
    public void registerTransformation(String name, Transformation transformation) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Transformation name cannot be null or empty");
        }
        if (transformation == null) {
            throw new IllegalArgumentException("Transformation cannot be null");
        }
        registry.register(name, transformation);
    }
    
    /**
     * Registers a transformation from a class that implements Transformation.
     * 
     * @param name the transformation name
     * @param transformationClass the transformation class
     */
    public <T extends Transformation> void registerTransformation(
            String name, Class<T> transformationClass) {
        registerTransformation(name, transformationClass);
    }
    
    /**
     * Gets a transformation by name.
     * 
     * @param name the transformation name
     * @return the transformation
     * @throws IllegalArgumentException if name is null or empty
     * @throws NoSuchElementException if transformation not found
     */
    public <T extends Transformation> T getTransformation(String name) {
        return registry.get(name);
    }
    
    /**
     * Gets all registered transformation names.
     * 
     * @return list of transformation names
     */
    public List<String> getAllTransformations() {
        return registry.getAll();
    }
    
    /**
     * Executes a transformation by name.
     * 
     * @param transformationName the transformation name
     * @return the transformation result
     * @throws IllegalArgumentException if transformation not found
     */
    public TransformationResult execute(String transformationName) {
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("Transformation name cannot be null or empty");
        }
        
        Transformation transformation = registry.get(transformationName);
        return transformation.apply("");
    }
    
    /**
     * Executes a transformation on a file.
     * 
     * @param sourceFile the source file path
     * @param transformationName the transformation name
     * @return the transformation result
     */
    public TransformationResult executeOnFile(Path sourceFile, String transformationName) {
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile cannot be null");
        }
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
        
        return engine.apply(sourceFile, transformationName);
    }
    
    /**
     * Executes all registered transformations sequentially.
     * 
     * @param sourceFile the source file to transform
     * @return list of transformation results
     */
    public List<TransformationResult> executeAllSequential(Path sourceFile) {
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile cannot be null");
        }
        
        List<String> names = registry.getAll();
        return engine.applyAllSequential(sourceFile, names);
    }
    
    /**
     * Gets the transformation registry.
     * 
     * @return the registry
     */
    public TransformationRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Gets the automation engine.
     * 
     * @return the engine
     */
    public AutomationEngine getEngine() {
        return engine;
    }
    
    /**
     * Initializes default transformations.
     */
    public void initialize() {
        // Default initialization can be done here
        // Register any default transformations
        // This is called automatically when needed
    }
}
