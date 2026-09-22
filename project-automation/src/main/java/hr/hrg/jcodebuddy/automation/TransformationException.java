package hr.hrg.jcodebuddy.automation;

/**
 * Thrown when a {@link Transformation} cannot be applied.
 *
 * <p>A checked exception on purpose: the engine's job is to run many transformations over many files and
 * report what happened, so "this one could not run" is an expected outcome the caller has to handle, not
 * an error state it may forget. It carries the transformation's name where that is known, because a
 * batch report with one failed file is only useful if it says which step failed.</p>
 */
public class TransformationException extends Exception {

    private final String transformationName;

    public TransformationException(String message) {
        this(null, message, null);
    }

    public TransformationException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public TransformationException(String transformationName, String message) {
        this(transformationName, message, null);
    }

    public TransformationException(String transformationName, String message, Throwable cause) {
        super(transformationName == null ? message : transformationName + ": " + message, cause);
        this.transformationName = transformationName;
    }

    /** The transformation that failed, or {@code null} when it failed before one was reached. */
    public String transformationName() {
        return transformationName;
    }
}
