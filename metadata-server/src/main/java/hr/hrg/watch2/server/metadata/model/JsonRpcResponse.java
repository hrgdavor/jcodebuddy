package hr.hrg.watch2.server.metadata.model;

public class JsonRpcResponse {
    public String jsonrpc;
    public String id;
    public Object result;
    public JsonRpcError error;

    public JsonRpcResponse() {}

    public JsonRpcResponse(String jsonrpc, String id, Object result, JsonRpcError error) {
        this.jsonrpc = jsonrpc;
        this.id = id;
        this.result = result;
        this.error = error;
    }

    public static JsonRpcResponse ok(String id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    public static JsonRpcResponse err(String id, int code, String message) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message));
    }
}
