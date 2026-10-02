package hr.hrg.watch2.server.metadata.rpc;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import hr.hrg.watch2.server.metadata.model.JsonRpcRequest;
import hr.hrg.watch2.server.metadata.model.JsonRpcResponse;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.*;

public class RpcDispatcher {
    private final MetadataProvider provider;
    private final ObjectMapper mapper;
    private final Map<String, Entry> methods = new LinkedHashMap<>();

    private record Entry(Object target, Method method) {}

    public RpcDispatcher(MetadataProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    public void register(Object service) {
        for (Method method : service.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(RpcMethod.class)) {
                method.setAccessible(true);
                methods.put(method.getAnnotation(RpcMethod.class).value(), new Entry(service, method));
            }
        }
    }

    public JsonRpcResponse dispatch(JsonRpcRequest req) {
        Entry entry = methods.get(req.method);
        if (entry == null) {
            return JsonRpcResponse.err(req.id, -32601, "Method not found: " + req.method);
        }
        try {
            Object result;
            if (entry.method().getParameterCount() == 0) {
                result = entry.method().invoke(entry.target());
            } else {
                result = entry.method().invoke(entry.target(), req.params);
            }
            return JsonRpcResponse.ok(req.id, result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return JsonRpcResponse.err(req.id, -32603, cause.getMessage());
        }
    }
}
