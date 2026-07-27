# JSON serialization/deserialization using annotation processing

To set up **Micronaut Serialization** for compile-time Jackson-compatible serialization/deserialization—without using the full Micronaut framework—you can use the `micronaut-serde-jackson` library. This library processes your classes at build time, uses Jackson-style annotations, and provides fast, reflection-free (native-image-friendly) JSON serialization.

## Steps to set up Micronaut Serialization for Compile-Time Jackson

## 1. **Add the Dependency**

Add the following to your Maven or Gradle build:

**Maven:**

```xml
<dependency>
  <groupId>io.micronaut.serde</groupId>
  <artifactId>micronaut-serde-jackson</artifactId>
</dependency>
```

**Gradle:**

```groovy
implementation("io.micronaut.serde:micronaut-serde-jackson")
```

## 2. **Annotate Your Classes**

Mark each class you want to serialize/deserialize with the `@Serdeable` annotation (from Micronaut), and add Jackson annotations like `@JsonProperty` or `@JsonCreator` as usual:

```java
import io.micronaut.serde.annotation.Serdeable;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;

@Serdeable
public class Book {
    private final String title;

    @JsonProperty("qty")
    private final int quantity;

    @JsonCreator
    public Book(String title, int quantity) {
        this.title = title;
        this.quantity = quantity;
    }
    // getters...
}
```

## 3. **Use the Micronaut ObjectMapper**

Micronaut Serialization provides its own `ObjectMapper` interface for (de)serializing:

```java
import io.micronaut.serde.ObjectMapper;

// Typically acquired via dependency injection, or instantiate as needed
byte[] json = objectMapper.writeValueAsBytes(book);
Book restored = objectMapper.readValue(json, Book.class);
```

## 4. **No Need for Full Micronaut Application**

You do **not** need the entire Micronaut framework—just the serialization module and its processor. It works in plain Java projects, including JVM and GraalVM native images. Your build system must have annotation processing enabled.

## 5. **Key Points**

- Explicitly annotate each serializable/deserializable class with `@Serdeable`.
- Use familiar Jackson annotations—they are supported by Micronaut Serialization.
- There is no reflection at runtime: everything is generated or wired by annotation processing during your build.
- Great for microservices, CLI tools, or any Java app needing fast, native-friendly serialization.

------

**References:**

- Micronaut Serialization [Guide](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)
- Micronaut Framework 4.0 and Micronaut Jackson [post](https://micronaut.io/2023/02/27/micronaut-framework-4-0-and-micronaut-jackson-databind-transitive-dependency/)
- Micronaut Serialization intro blog [post](https://micronaut.io/2022/01/31/micronaut-serialization/)
