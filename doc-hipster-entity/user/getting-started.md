# Getting Started with hipster-entity

## 1. Add the dependency

Add the `hipster-entity-api` dependency to your project. For Maven, that looks like:

```xml
<dependency>
  <groupId>hr.hrg</groupId>
  <artifactId>hipster-entity-api</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 2. Define a simple entity interface

Create an interface for your entity shape:

```java
package example.person;

import hr.hrg.hipster.entity.api.EntityBase;

public interface PersonEntity extends EntityBase<String> {
    String id();
}

public interface PersonSummary extends PersonEntity {
    String firstName();
    String lastName();
    String email();
}
```

## 3. Generate the metadata

Run the tooling script to generate the companion metadata enum and view helpers.

```bash
bun ./scripts/run-tooling.js build --mvn "D:\\programs\\mvnd\\bin\\mvnd"
bun ./scripts/run-tooling.js run --mvn "D:\\programs\\mvnd\\bin\\mvnd" path/to/PersonSummary.java target/generated
```

After generation, you should see a metadata enum such as `PersonSummary_` that includes field definitions.

## 4. Inspect the generated metadata

The generated enum provides a `ViewMeta` instance and a `forName(String)` lookup:

```java
PersonSummary_.forName("firstName");
PersonSummary_.META.fieldCount();
```

## 5. Use the generated view metadata

The generated metadata makes it easy to build generic adapters and serializers without reflection.

```java
ViewMeta<PersonSummary, PersonSummary_> meta = PersonSummary_.META;
Object[] values = new Object[meta.fieldCount()];

values[PersonSummary_.firstName.ordinal()] = "Alice";
values[PersonSummary_.lastName.ordinal()] = "Smith";
values[PersonSummary_.email.ordinal()] = "alice@example.com";

PersonSummary summary = meta.create(values);
```

## 6. Next steps

- [Core Concepts](core-concepts.md)
- [Materialization Guide](materialization-guide.md)
- [JSON and Jackson setup](patterns/jackson-setup.md)
