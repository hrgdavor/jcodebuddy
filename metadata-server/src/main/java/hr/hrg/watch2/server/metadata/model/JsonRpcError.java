package hr.hrg.watch2.server.metadata.model;

public class JsonRpcError {
    public int code;
    public String message;

    public JsonRpcError() {}

    public JsonRpcError(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
