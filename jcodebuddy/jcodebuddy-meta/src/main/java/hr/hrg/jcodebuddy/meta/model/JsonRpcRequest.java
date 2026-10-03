package hr.hrg.jcodebuddy.meta.model;

import java.util.Map;

public class JsonRpcRequest {
    public String jsonrpc;
    public String id;
    public String method;
    public Map<String, Object> params;

    public JsonRpcRequest() {}

    public JsonRpcRequest(String jsonrpc, String id, String method, Map<String, Object> params) {
        this.jsonrpc = jsonrpc;
        this.id = id;
        this.method = method;
        this.params = params;
    }
}
