package hr.hrg.jcodebuddy.ipc;

/**
 * One refused call, carrying the JSON-RPC error code a consumer branches on.
 *
 * <p>The code is a number rather than a parsed message, for the same reason the fs-meta client branches on
 * {@code ERROR_BATCH_TOO_OLD} and never on the message text: a message is for a person, a code is for a
 * program, and a consumer that matches words breaks the day the words improve.</p>
 */
public final class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    /** JSON-RPC {@code parse error}. */
    public static final int PARSE_ERROR = -32700;
    /** JSON-RPC {@code invalid request}. */
    public static final int INVALID_REQUEST = -32600;
    /** JSON-RPC {@code method not found}: the requested method is not in the tool's capabilities. */
    public static final int METHOD_NOT_FOUND = -32601;
    /** JSON-RPC {@code invalid params}. */
    public static final int INVALID_PARAMS = -32602;
    /** JSON-RPC {@code internal error}: the tool failed while answering. */
    public static final int INTERNAL_ERROR = -32603;

    private final int code;

    public ToolException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** The numeric code the caller branches on. */
    public int code() {
        return code;
    }

    /** The refusal a tool throws for a method it does not implement. */
    public static ToolException methodNotFound(String method) {
        return new ToolException(METHOD_NOT_FOUND, "no such method: " + method);
    }

    /** The refusal for a params object the method cannot use. */
    public static ToolException invalidParams(String message) {
        return new ToolException(INVALID_PARAMS, message);
    }
}
