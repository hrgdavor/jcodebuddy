// {@link Transformation} Interface for transformation operations.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

/**
 * Interface defining a transformation operation.
 * 
 * Implementations are registered with {@link TransformationRegistry}
 * and executed via {@link AutomationEngine}.
 */
public interface Transformation {
    /**
     * Gets the name of this transformation.
     * 
     * @return transformation name
     */
    String getName();
    
    /**
     * Applies the transformation to the given source.
     * 
     * @param source the source content to transform
     * @return the transformed content
     * @throws TransformationException if transformation fails
     */
    String apply(String source);
    
    /**
     * Returns a description of what this transformation does.
     * 
     * @return transformation description
     */
    String getDescription();
}
