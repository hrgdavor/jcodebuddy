package hr.hrg.watch2.server.metadata.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class JsonRpcError {
    public int code;
    public String message;

    public JsonRpcError() {}

    public JsonRpcError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * This error as the plain map the binary transport puts on the wire.
     *
     * <p>See {@link JsonRpcResponse#toWireMap()} for why the transport carries maps rather than these
     * model objects.</p>
     */
    public Map<String, Object> toWireMap() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("code", code);
        wire.put("message", message);
        return wire;
    }
}
