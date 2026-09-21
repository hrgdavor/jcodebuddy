// {@link ValidationException} Exception thrown when validation fails.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

/**
 * Exception thrown when validation of a source file fails.
 * 
 * Used by {@link AutomationEngine} to signal validation failures.
 */
public class ValidationException extends Exception {
    
    /**
     * Creates a new ValidationException with the specified detail message.
     * 
     * @param message the detail message
     */
    public ValidationException(String message) {
        super(message);
    }
}
