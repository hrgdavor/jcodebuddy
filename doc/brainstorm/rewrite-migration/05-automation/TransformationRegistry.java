// {@link TransformationRegistry} Registry for transformation operations.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for transformation operations.
 * 
 * Manages registration and retrieval of {@link Transformation} implementations.
 * Used by {@link ProjectAutomation} to execute transformations.
 */
public class TransformationRegistry {
    private final Map<String, Transformation> transformations = new HashMap<>();
    
    /**
     * Registers a transformation by name.
     * 
     * @param name the transformation name
     * @param transformation the transformation to register
     * @throws IllegalArgumentException if name is null or empty
     */
    public void register(String name, Transformation transformation) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Transformation name cannot be null or empty");
        }
        transformations.put(name, transformation);
    }
    
    /**
     * Gets a transformation by name.
     * 
     * @param name the transformation name
     * @param <T> the transformation type
     * @return the transformation
     * @throws IllegalArgumentException if name is null or empty
     * @throws NoSuchElementException if transformation not found
     */
    public <T extends Transformation> T get(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Transformation name cannot be null or empty");
        }
        Transformation transformation = transformations.get(name);
        if (transformation == null) {
            throw new NoSuchElementException("No transformation registered with name: " + name);
        }
        return (T) transformation;
    }
    
    /**
     * Gets all registered transformation names.
     * 
     * @return list of transformation names
     */
    public List<String> getAll() {
        return List.copyOf(transformations.keySet());
    }
    
    /**
     * Checks if a transformation is registered with the given name.
     * 
     * @param name the transformation name to check
     * @return true if registered, false otherwise
     */
    public boolean has(String name) {
        return transformations.containsKey(name);
    }
    
    /**
     * Gets the number of registered transformations.
     * 
     * @return count of registered transformations
     */
    public int size() {
        return transformations.size();
    }
    
    /**
     * Clears all registered transformations.
     */
    public void clear() {
        transformations.clear();
    }
}
