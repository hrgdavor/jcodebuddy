// {@link hr.hrg.rewrite.validation.ToolResult} Result of a tool operation.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

/**
 * Represents the result of a tool operation.
 *
 * <p>Compliance: DEC-021 (generator class-file header), DEC-022 (refactor-sensitive naming).</p>
 */
public interface ToolResult {

    boolean succeeded();

    String getMessage();

    default ToolResult ok(String message) {
        return new ToolResult() {
            @Override
            public boolean succeeded() {
                return true;
            }

            @Override
            public String getMessage() {
                return message;
            }
        };
    }

    default ToolResult failed(String message) {
        return new ToolResult() {
            @Override
            public boolean succeeded() {
                return false;
            }

            @Override
            public String getMessage() {
                return message;
            }
        };
    }
}
