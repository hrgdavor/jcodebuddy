# Apache Fory Serialization Issues

## Context

While building and testing `metadata-server`, we integrated **Apache Fory** (v1.3.0) as a binary serialization layer for JSON-RPC messages over HTTP and Unix domain sockets. The goal was to support both JSON and Fory transports side-by-side.

## Issues Encountered

### 1. `DeferedLazySerializer.write()` NullPointerException

**Symptom:** Server returned HTTP 500 on `/api/fory` endpoint. Stack trace:
```
org.apache.fory.exception.SerializationException: java.lang.NullPointerException
    at org.apache.fory.Fory.processSerializationError(Fory.java:391)
    at org.apache.fory.Fory.serialize(Fory.java:358)
    at org.apache.fory.Fory.serialize(Fory.java:319)
    at hr.hrg.watch2.server.metadata.transport.HttpTransport$ForyHandler.handle(HttpTransport.java:91)
Caused by: java.lang.NullPointerException
    at org.apache.fory.serializer.DeferedLazySerializer.write(DeferedLazySerializer.java:46)
    at org.apache.fory.context.WriteContext.writeData(WriteContext.java:635)
    at org.apache.fory.context.WriteContext.writeRef(WriteContext.java:459)
    at org.apache.fory.Fory.serialize(Fory.java:355)
```

**Root Cause (suspected):** Fory's lazy serializer encountered a `null` field during serialization of our custom types (`JsonRpcRequest`, `JsonRpcResponse`, `JsonRpcError`). This appears to be a known limitation/bug when serializing records or classes with nullable fields without explicit schema registration.

### 2. Record vs Class Serialization

Initially we converted the RPC models from **records** to **plain classes** to work around Fory issues, but this did not resolve the NPE. The serialization failure persisted regardless of whether we used records or mutable classes.

**Files affected:**
- `metadata-server/src/main/java/hr/hrg/watch2/server/metadata/model/JsonRpcRequest.java`
- `metadata-server/src/main/java/hr/hrg/watch2/server/metadata/model/JsonRpcResponse.java`
- `metadata-server/src/main/java/hr/hrg/watch2/server/metadata/model/JsonRpcError.java`

### 3. `ForyBuilder.withNumberCompressed()` Interaction

Enabling `withNumberCompressed(true)` did not fix the issue. We also tried `withRefTracking(false)` to avoid reference-tracking-related lazy serialization, but the NPE remained when serializing `JsonRpcResponse` directly.

## Workarounds Applied

### Serialize via `LinkedHashMap`

Instead of serializing the `JsonRpcResponse` record directly, we now build a `LinkedHashMap<String, Object>` representation and serialize that:

```java
// HttpTransport.java ForyHandler
Map<String, Object> resMap = new LinkedHashMap<>();
resMap.put("jsonrpc", res.jsonrpc);
resMap.put("id", res.id);
if (res.error != null) {
    Map<String, Object> errMap = new LinkedHashMap<>();
    errMap.put("code", res.error.code);
    errMap.put("message", res.error.message);
    resMap.put("error", errMap);
} else {
    resMap.put("result", res.result);
}
byte[] out = fory.serialize(resMap);
```

The same pattern was applied in `UnixSocketTransport.java`.

**Rationale:** Fory has robust built-in serializers for `Map`/`List` types. By routing through generics, we avoid the custom class serializer path that triggers `DeferedLazySerializer`.

### Preserve Records for API Compatibility

We reverted the models back to **records** because:
1. They provide value-based equality and concise syntax
2. The `null` field issue is not resolved by switching to classes
3. Jackson 3 handles records natively for the JSON transport path

### Fory Builder Configuration

```java
Fory.builder()
    .withNumberCompressed(true)
    .withRefTracking(false)
    .build();
```

- `withNumberCompressed(true)` — reduces payload size for numeric IDs
- `withRefTracking(false)` — disables object reference tracking which can introduce lazy serializers

## Open Questions / Possible Long-Term Solutions

| Option                                             | Effort     | Trade-off                                                                                |
| -------------------------------------------------- | ---------- | ---------------------------------------------------------------------------------------- |
| **Keep Map-based serialization**                   | Low        | Works but loses type safety; response shape is not encoded in the type system            |
| **Implement custom `Serializer<JsonRpcResponse>`** | Medium     | Restores type safety but adds boilerplate; need to maintain parity across client/server  |
| **Use Fory with explicit schema/metadata sharing** | Medium     | May resolve null-handling; requires `metaShareEnabled` and consistent class registration |
| **Switch to protobuf/FlatBuffers**                 | High       | More robust but breaks JSON compatibility and adds build-time schema steps               |
| **Upgrade Fory version**                           | Low-Medium | 1.3.0 is recent; a newer patch may fix the `DeferedLazySerializer` NPE                   |

## Impact

- **Functionality:** All transports are operational (JSON + Fory over HTTP, JSON over Unix sockets).
- **Performance:** Fory serialization via `LinkedHashMap` is still faster than Jackson JSON for binary transport, but we lose some of the zero-copy benefits.
- **Maintenance:** The `LinkedHashMap` workaround adds ~10 lines of boilerplate per response. Must be kept in sync if new RPC error fields are added.

## Recommendation

If Fory serialization of custom types is a hard requirement, the next concrete step should be to:

1. Try enabling `withMetaShare(true)` + `withScopedMetaShare(true)` on both client and server Fory instances.
2. If that fails, implement a minimal `Serializer<JsonRpcResponse>` and register it explicitly via `registerSerializerAndType(JsonRpcResponse.class, Serializer.class)`.

Otherwise, if the Map-based workaround is acceptable, document it in the architecture decisions and move on.
