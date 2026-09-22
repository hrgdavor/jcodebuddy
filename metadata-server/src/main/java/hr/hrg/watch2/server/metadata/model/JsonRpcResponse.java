package hr.hrg.watch2.server.metadata.model;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * This response as the plain map the binary transport puts on the wire.
     *
     * <h3>Why the wire carries a map, not this object</h3>
     * <p>Measured against Apache Fory 1.3.0 (the version this reactor pins, and the only one in the
     * offline repository): <strong>it cannot write a {@code null} into an object field.</strong>
     * {@code fory.serialize(new JsonRpcResponse())} — or any registered POJO with one null field — fails
     * with {@code SerializationException: java.lang.NullPointerException} from the field writer, under
     * every configuration tried ({@code withCompatible}, {@code withCodegen(false)},
     * {@code withAsyncCompilation(false)}, {@code withRefTracking(true)}, {@code withMetaShare}) and with
     * every combination of the transport's own flags. A {@code Map} <em>value</em> of {@code null} and a
     * top-level {@code null} both serialise correctly, and a POJO with all fields set does too — so the
     * defect is exactly "null in a field", and JSON-RPC cannot avoid nulls: a successful call has no
     * {@code error}, a failed one has no {@code result}, and {@code getEntry} legitimately returns a null
     * result.</p>
     *
     * <p>So the envelope crosses as a map, which is also the shape the JSON transport already speaks
     * through Jackson and the shape {@code MetadataServerTest} asserts on the client side
     * ({@code assertTrue(res instanceof Map)}). The model classes remain what the dispatcher and Jackson
     * use; they are simply not the wire format. {@code ForyCodec} documents the contract, and the
     * transport's flags are still part of it.</p>
     *
     * <p>One consequence worth knowing: a <em>value</em> carried inside {@code result} is still an object,
     * so it may not have null fields either. {@code MetadataProvider.CacheEntry} is the one that crosses
     * this wire — its {@code metadata} map must be empty rather than null.</p>
     */
    public Map<String, Object> toWireMap() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("jsonrpc", jsonrpc);
        wire.put("id", id);
        wire.put("result", result);
        wire.put("error", error == null ? null : error.toWireMap());
        return wire;
    }
}
