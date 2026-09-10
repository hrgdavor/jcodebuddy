# Jackson Setup

## Goal

Use `hipster-entity` views with Jackson for JSON serialization and deserialization.

## When to use

Use this guide when you want to read or write JSON using generated view metadata.

## Setup

Add the Jackson integration module to your project when you need JSON support.

```xml
<dependency>
  <groupId>hr.hrg</groupId>
  <artifactId>hipster-entity-jackson</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Basic flow

1. Define your view interfaces.
2. Generate metadata for the views.
3. Use Jackson with the generated helpers to parse or write JSON.

## Example

```java
String json = "{\"id\":\"1\",\"firstName\":\"Alice\",\"lastName\":\"Smith\"}";
PersonSummary summary = objectMapper.readValue(json, PersonSummary.class);
```

## What to expect

The generated metadata makes the view schema available to Jackson-based adapters without requiring reflection or hand-written field mapping.

## Performance note

Jackson integration is designed to be efficient, and the library includes benchmark data showing competitive throughput.

## See also

- [Getting Started](../getting-started.md)
- [Core Concepts](../core-concepts.md)
