package hr.hrg.jcodebuddy.tool;

/** A tool declined the request without touching the filesystem. */
public final class ToolRefusal extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolRefusal(String message) {
        super(message);
    }

    public ToolRefusal(String message, Throwable cause) {
        super(message, cause);
    }
}
