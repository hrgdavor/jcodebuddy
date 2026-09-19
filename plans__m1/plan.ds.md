# hipster-entity Implementation Plan

## Overview

This plan outlines the path to a first usable implementation of `hipster-entity` in a real project. The concept supports interface-first entity definitions with generated field metadata, multiple materialization levels (from metadata-only to concrete builders with change tracking), and source-visible wiring per DEC-019.

## Core Capabilities

### 1. Interface-First Entity Model
- Define entities as Java interfaces with getter-style methods
- Support multiple view types (Summary, Details, UpdateForm)
- Generate companion metadata enums with `_` suffix (e.g., `PersonSummary_`)

### 2. Materialization Levels
- **Level 0 (META)**: Interface + field metadata enum + ViewMeta
- **Level 1 (RECORD)**: Add immutable record implementation
- **Level 2 (WRITABLE)**: Add write-capable interface for proxy-backed updates
- **Level 3 (BUILDER)**: Generate concrete builder class
- **Level 4 (BUILDER_TRACKED)**: Add change-tracking builder
- **Level 5 (BUILDER_ALL)**: Generate both regular and tracking builders

### 3. Tracking Functionality (Full Depth Tracing)
The tracking feature tracks which fields changed during updates. This is critical for:
- Partial update operations
- Optimistic locking
- Audit trails
- Change diff generation

Tracking implementation levels:
- **Metadata-only**: No tracking (Level 0-2)
- **Proxy tracking**: Array-backed proxy with change bitmaps (Level 3)
- **Concrete tracking**: Direct field access with change tracking (Level 4)

## Implementation Steps

### Phase 1: Core Infrastructure (Week 1)

#### 1.1 Set up Project Structure
- Create Maven multi-module project:
  - `hipster-entity-api`: Core interfaces (EntityBase, Identifiable, View, ViewReader, ViewWriter, ViewMeta)
  - `hipster-entity-core`: Implementation classes (EEnumSet*, EntityReadArray, EntityUpdateTrackingArray, ArrayBackedViewProxyFactory)
  - `hipster-entity-example`: Sample usage
  - `project-automation`: Code generation tools

#### 1.2 Implement Core API Interfaces
- `EntityBase<ID>`: Marker interface for entity types
- `Identifiable<ID>`: Opt-in identity mixin (DEC-017)
- `ViewReader<ID, T, F>`: Field accessor interface
- `ViewWriter<ID, T, F>`: Mutable view extending ViewReader
- `ViewMeta<V, F>`: Metadata contract (DEC-015)
- `FieldDef`, `FieldNameMapper`: Field definition utilities
- `View`: Annotation for read/write mode

#### 1.3 Implement Core Utilities
- `EEnumSet` family: Generic enum set implementations with size variants (32, 64, large)
- `EEnumSetBuilder`: Builder for tracking changes
- `EEnumSetTrackingJmhBenchmark`: Performance benchmark

#### 1.4 Implement Array-Backed Views
- `EntityReadArray`: Read-only array-backed view
- `EntityUpdateArray`: Basic update array
- `EntityUpdateTrackingArray`: Update with change tracking
- `EntityUpdateTrackingArray64`: Optimized for ≤64 fields
- `EntityUpdateTrackingArrayLarge`: Optimized for >64 fields
- `ArrayBackedViewProxyFactory`: Proxy factory using metadata

### Phase 2: Metadata Generation (Week 2)

#### 2.1 Generate Field Metadata Enum
For each view interface (e.g., `PersonSummary`), generate:
```java
enum PersonSummary_ implements FieldDef {
    firstName(String.class),
    lastName(String.class),
    email(String.class);

    private final Type propertyType;

    @Override
    public Type javaType() { return propertyType; }

    public static PersonSummary_ forName(String name) { ... }
}
```

#### 2.2 Generate ViewMeta Implementation
```java
class PersonSummaryMeta implements ViewMeta<PersonSummary, PersonSummary_> {
    @Override
    public Class<PersonSummary> viewType() { return PersonSummary.class; }
    @Override
    public Class<PersonSummary_> fieldType() { return PersonSummary_.class; }
    @Override
    public int fieldCount() { return PersonSummary_.values().length; }
    @Override
    public PersonSummary_[] fieldValues() { return PersonSummary_.values(); }
    @Override
    public String fieldNameAt(int ordinal) { return PersonSummary_.values()[ordinal].name(); }
    @Override
    public Type fieldTypeAt(int ordinal) { return PersonSummary_.values()[ordinal].getPropertyType(); }
    @Override
    public FieldNameMapper<PersonSummary_> forName() { return ...; }
    @Override
    public PersonSummary create(Object[] values) { ... }
}
```

#### 2.3 Implement ViewMeta Factory
Create factory method to generate ViewMeta instances for each view type.

### Phase 3: Materialization Levels (Week 3)

#### 3.1 Level 0: Meta Generation
Generate only field metadata and ViewMeta. No concrete implementation.

```java
interface PersonSummary {
    String firstName();
    String lastName();
    String email();
}
```

#### 3.2 Level 1: Record Materialization
Generate record implementation:

```java
record PersonSummaryRecord(
    String firstName,
    String lastName,
    String email
) implements PersonSummary {}
```

#### 3.3 Level 2: Writable Interface
Add write interface for proxy-backed updates:

```java
interface PersonSummaryWriter extends PersonSummary, ViewWriter<String, PersonSummary, PersonSummary_> {
    int set(String fieldName, Object value);
    void set(int fieldOrdinal, Object value);
}
```

#### 3.4 Level 3: Builder Materialization
Generate concrete builder class:

```java
public class PersonSummaryBuilder implements PersonSummaryWriter {
    String firstName;
    String lastName;
    String email;

    @Override
    public String firstName() { return firstName; }
    @Override
    public int set(String fieldName, Object value) { ... }
    @Override
    public PersonSummary build() { return new PersonSummaryRecord(firstName, lastName, email); }
}
```

#### 3.5 Level 4: Builder with Tracking
Generate tracking builder:

```java
public class PersonSummaryBuilderTracking implements PersonSummaryWriter, ViewChangeTracking<..., ...> {
    final EEnumSetBuilder64<PersonSummary_> mf;
    String firstName;
    String lastName;
    String email;

    @Override
    public boolean isChanged() { return mf.size() > 0; }
    @Override
    public EEnumSetBuilder64<PersonSummary_> changes() { return mf; }

    @Override
    public PersonSummaryBuilderTracking firstName(String value) {
        mf.addOrdinalChange(0, firstName, value);
        firstName = value;
        return this;
    }
}
```

### Phase 4: Tracking Implementation (Week 4)

#### 4.1 Change Tracking Data Structure
```java
public interface ViewChangeTracking<E extends Enum<E>, S extends EEnumSetRead<E>> {
    boolean isChanged();
    S changes();
}
```

#### 4.2 Change Bit Implementation
```java
public class EEnumSetBuilder64<E extends Enum<E>> implements EEnumSetRead<E> {
    private final BitSet changedBits = new BitSet(64);
    private final E[] fieldValues;
    private final Object[] previousValues;

    public void addOrdinalChange(int ordinal, Object previous, Object current) {
        if (Objects.equals(previous, current)) return;
        changedBits.set(ordinal);
        previousValues[ordinal] = previous;
    }

    public boolean size() { return changedBits.cardinality(); }
}
```

#### 4.3 Tracking Array Implementation
In `EntityUpdateTrackingArray`:

```java
@Override
public boolean mark(int ordinal) {
    return changedBits.set(ordinal);
}

@Override
public void clear() {
    changedBits.clear();
}

@Override
public EEnumSet<F> changesSnapshot() {
    return new EEnumSet<>(changedBits.get(0, fieldCount), fieldValues);
}
```

#### 4.4 Full Depth Tracing
The tracking functionality must trace:
1. **Field value changes**: Previous value → new value
2. **Change bitmap**: Which fields changed
3. **Change snapshot**: Immutable snapshot of changed fields
4. **Reset semantics**: Clear all changes after flush

### Phase 5: Example Usage (Week 5)

#### 5.1 Create Example Domain
- Create `Person` entity with fields: `id`, `firstName`, `lastName`, `age`, `departmentName`, `metadata`
- Create view types: `PersonSummary`, `PersonDetails`, `PersonUpdateForm`
- Create payment method example (polymorphic views)

#### 5.2 Demonstrate Tracking
```java
PersonSummaryBuilderTracking builder = new PersonSummaryBuilderTracking();
builder.firstName("Alice").lastName("Smith");  // Tracked

PersonSummaryRecord record = builder.build();  // Create read view

// Later, track changes
PersonSummaryBuilderTracking update = new PersonSummaryBuilderTracking();
update.firstName("Alice Updated");  // Only firstName tracked as changed
```

### Phase 6: Jackson Integration (Week 6)

#### 6.1 Configure Jackson Mixins
Generate Jackson mixins from field metadata:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PersonSummaryRecord.class, name = "summary"),
    @JsonSubTypes.Type(value = PersonSummaryWriter.class, name = "writer")
})
```

#### 6.2 Implement Deserialization
Use `ViewMeta.forName()` to map JSON field names to ordinals:

```java
public class PersonSummaryDeserializer extends JsonDeserializer<PersonSummary> {
    private final ViewMeta meta;

    @Override
    public PersonSummary deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectNode node = p.getCodec().readTree(p);
        String type = node.get("@type").asText();
        
        PersonSummaryMeta meta = getMeta(type);
        Object[] values = new Object[meta.fieldCount()];
        
        for (FieldDef field : meta.fieldValues()) {
            String name = field.name();
            if (node.has(name)) {
                values[field.ordinal()] = node.get(name).get();
            }
        }
        
        return meta.create(values);
    }
}
```

### Phase 7: Testing and Validation (Week 7)

#### 7.1 Unit Tests
- Test all materialization levels
- Test change tracking accuracy
- Test polymorphic deserialization
- Test Jackson serialization/deserialization

#### 7.2 Performance Benchmarks
- Benchmark array-backed vs proxy vs concrete implementations
- Benchmark change tracking overhead
- Benchmark polymorphic dispatch

#### 7.3 Integration Tests
- Test end-to-end workflows
- Test with real database scenarios
- Test with REST APIs

### Phase 8: Documentation (Week 8)

#### 8.1 User Guides
- Getting Started guide
- Core Concepts documentation
- Materialization Guide
- Patterns (Builder Usage, CRUD Views, Jackson Setup, Polymorphic Views)

#### 8.2 Developer Guides
- Architecture decisions (DEC-001 through DEC-022)
- Code generation strategy
- Naming conventions
- Decision records

#### 8.3 FAQ and Troubleshooting
- Common issues
- Migration guides
- Best practices

## Acceptance Criteria

### First Usable Implementation
The implementation is usable when:

1. **Core API works**: Entity interfaces can be defined and metadata generated
2. **Materialization works**: Views can be read, written, and serialized
3. **Tracking works**: Change tracking accurately reports modified fields
4. **Polymorphism works**: Polymorphic views deserialize correctly
5. **Jackson integration works**: Serialization/deserialization with Jackson
6. **Documentation exists**: User guides explain how to use the library

### Tracking Functionality Acceptance
For tracking specifically:

1. **isChanged() returns true** when fields have been modified since last clear
2. **changes() returns accurate set** of modified fields
3. **Change snapshot is immutable** and doesn't affect subsequent changes
4. **Reset semantics work**: After flush, all changes are cleared
5. **Partial updates work**: Only specified fields are tracked
6. **Full depth tracing**: Can trace from field accessor → change bit → snapshot

## Next Steps

1. **Create project structure**: Set up Maven multi-module project
2. **Implement core API**: Create interfaces in hipster-entity-api
3. **Implement core utilities**: Create EEnumSet* and array-backed views
4. **Build generation tools**: Create project-automation module
5. **Create examples**: Build hipster-entity-example with Person and payment methods
6. **Write documentation**: Create user and developer guides
7. **Test thoroughly**: Run unit and integration tests
8. **Benchmark**: Measure performance of different materialization levels

## References

- [DEC-019](doc-hipster-entity/architecture/decisions/DEC-019.md): Source-visible wiring
- [DEC-017](doc-hipster-entity/architecture/decisions/DEC-017.md): Identifiable mixin
- [Materialization Guide](doc-hipster-entity/user/materialization-guide.md): Levels explanation
- [Core Concepts](doc-hipster-entity/user/core-concepts.md): Basic concepts
- [Getting Started](doc-hipster-entity/user/getting-started.md): Quick start
