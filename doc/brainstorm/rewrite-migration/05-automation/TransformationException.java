// {@link TransformationException} Exception thrown when a transformation fails.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

/**
 * Exception thrown when a transformation operation fails.
 * 
 * Used by {@link Transformation} implementations to signal failure.
 * {@link AutomationEngine} catches and reports these exceptions.
 */
public class TransformationException extends Exception {
    
    /**
     * Creates a new TransformationException with the specified detail message.
     * 
     * @param message the detail message
     */
    public TransformationException(String message) {
        super(message);
    }
    
    /**
     * Creates a new TransformationException with the specified detail message and cause.
     * 
     * @param message the detail message
     * @param cause the cause (nullable)
     */
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
}
