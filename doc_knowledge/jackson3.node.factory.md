# Creating static utils for jacson

**Jackson does not provide any static helper methods** to create an `ObjectNode` from an array of key-value pairs (or from a `Map`). You will need to create your own utility methods.

While `ObjectNode` has instance methods like `put()` and `set()`, there is no built-in factory method that accepts a batch of key-value pairs. `JsonNodeFactory` only gives you an empty `ObjectNode` to start with.

### What you should do

Since you already have a static `JsonNodeFactory`, the best approach is to write your own lightweight utility method. Here's a clean example that returns an `ObjectNode` from a varargs array:

```java
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public class JsonUtils {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    public static ObjectNode createObjectNode(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Key-values must be in pairs");
        }

        ObjectNode node = FACTORY.objectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = keyValues[i].toString();
            Object value = keyValues[i + 1];
            
            // Basic type handling
            if (value == null) {
                node.putNull(key);
            } else if (value instanceof String) {
                node.put(key, (String) value);
            } else if (value instanceof Number) {
                node.put(key, ((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                node.put(key, (Boolean) value);
            } else if (value instanceof JsonNode) {
                node.set(key, (JsonNode) value);
            } else {
                // For complex objects, you'd need an ObjectMapper
                // or simply throw an unsupported exception
                throw new IllegalArgumentException("Unsupported type: " + value.getClass());
            }
        }
        return node;
    }
}
```

**Usage**:

```java
ObjectNode node = JsonUtils.createObjectNode(
    "name", "Test",
    "count", 42,
    "enabled", true
);
```

### Why this is your responsibility

Jackson intentionally keeps `JsonNodeFactory` minimal and focuses on low-level node construction. Batch creation from key-value pairs is application-specific—you might have custom logic for handling dates, enums, or other types. This is why Jackson doesn't provide a one-size-fits-all static helper.

### Important note

If your utility previously "bound" to an `ObjectMapper` (e.g., using `mapper.valueToTree()` for complex values), you have two options:

1. **Keep your utility stateless**: Use the static `JsonNodeFactory` for simple types, and require an `ObjectMapper` parameter only when you need to convert complex objects to `JsonNode`.
2. **Inject the mapper**: Pass the `ObjectMapper` to your utility when needed.

Either way, the static factory approach still works seamlessly with different `ObjectMapper` instances for serialization, as you already know.