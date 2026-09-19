# hipster-entity: Plan to First Usable Implementation

## 1. Executive Summary

`hipster-entity` is an **interface-first Java entity model** with cooperative code generation. The runtime (`hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson`) is functional. The code generator (`hipster-entity-tooling`) currently produces only the `_` property enum and `<Entity>.metadata.json`. All higher-level materialization — `record`, `Write` inner interface, `Builder`, `BuilderTracking`, `ViewMeta.META` constant — is **hand-written** in the example module.

**First usable implementation** means a developer can:
1. Declare a marker entity + view interfaces with `@View`.
2. Run a generator (Maven plugin or `project-automation` watcher).
3. Get all boilerplate committed into `src/main/java`, IDE-navigable, and regenerable without losing hand-edits.

This plan documents the current state, traces the tracking materialization level end-to-end, and lays out the implementation tasks in phased order.

---

## 2. Module Inventory and Status

| Module | Status | Key Artifacts | Gap |
|---|---|---|---|
| **`hipster-entity-api`** | Complete | `View`, `FieldSource`, `FieldDef`, `FieldKind`, `ViewReader`, `ViewWriter`, `ViewMeta`, `DefaultViewMeta`, `EntityBase`, `Identifiable`, `GenLevel`, `ForNameOrdinal`, `BooleanOption`, `LazyConstantWrapper`, `FieldNameMapper` | None |
| **`hipster-entity-core`** | Complete | `EntityReadArray`, `EntityUpdateArray`, `EntityUpdateTrackingArray` (factory → `EntityUpdateTrackingArray64` / `EntityUpdateTrackingArrayLarge`), `ArrayBackedViewProxyFactory`, `EEnumSet` family (`EEnumSet64`, `EEnumSetLarge`, `EEnumSetEmpty`, `EEnumSetBuilder64`, `EEnumSetBuilderLarge`, `EEnumSetBuilder`, `EEnumSetRead`), `ViewChangeTracking` | None |
| **`hipster-entity-jackson`** | Partial | `EntityJacksonViewDeserializer` (zero-alloc hot path, ~1 216 ops/ms benchmarked), `EntityJacksonViewSerializer` (stub), `EntityJacksonViewJsonSerializer` (stub), `EntityJacksonViewJsonDeserializer` (stub), `EntityJacksonViewModule`, `EntityJacksonMapper` | Serializer path incomplete; round-trip unverified |
| **`hipster-entity-tooling`** | Partial | `EntityMetadataGenerator` (CLI: scans source, emits `<Entity>.metadata.json` + per-view `_` enum via `FieldBoilerplateGenerator`), meta model (`EntityMeta`, `EntityFieldMeta`, `ViewMeta`, `ViewAttributes`, `InterfaceInfo`, `Property`, `SourceMetadata`), validation rules (`EntityRulesValidator`, `MarkerEntityRule`, `ViewInterfaceRule`, `ViewAnnotationRule`, `AuditableRule`) | No generation of `record`, `Write`, `Builder`, `BuilderTracking`, `ViewMeta.META`, class-file headers, cooperative preservation, or divergence reporting |
| **`hipster-entity-example`** | Demo only | `Person`, `PersonSummary` (+ `Record`, `Write`, `toBuilder`, `toBuilderTracking`), `PersonDto`, `PersonDetails`, `PersonCreateForm`, `PersonUpdateForm`, `PersonUpdatableView`, `Auditable`, `PaymentMethod` sealed hierarchy, all `_` enums, all builders | All non-`_` boilerplate is hand-written; generator does not yet produce it |
| **`hipster-entity-test`** | Minimal | `PersonEntity`, `PersonSummary` (interface), `PersonSummary_` (`META`) | No integration tests for proxy, Jackson, tracking, or builder patterns |

---

## 3. Materialization Levels Reference

From `GenLevel` and `doc-hipster-entity/architecture/materialization-levels.md`:

| Level | Symbol | What is generated / materialized |
|---|---|---|
| **META** | `META` | `<View>_` enum with `forName`, `META` (`ViewMeta`), field ordinals |
| **RECORD** | `RECORD` | `public record Record(...) implements <View>` inside the view interface |
| **WRITABLE** | `WRITABLE` | `public interface Write extends <View>, ViewWriter` with fluent setters |
| **BUILDER** | `BUILDER` | `<View>Builder` — mutable fields, getters, fluent setters, `build()` → `Record` |
| **BUILDER_TRACKED** | `BUILDER_TRACKED` | `<View>BuilderTracking` — like `Builder` plus `ViewChangeTracking`, field-bitmask via `EEnumSetBuilder64`/`Large` |
| **BUILDER_ALL** | `BUILDER_ALL` | Both `Builder` and `BuilderTracking` |
| **DEFAULT** | `DEFAULT` | Generator decides from other options |

Combo matrix from the doc:

| Usage | Interface | Record | Builder | Builder-Tracking | Meta |
|---|---|---|---|---|---|
| RPC param | | record | | | metadata |
| Complex method param | | | builder | | |
| Entity | interface | record | | builder-tracking | metadata |
| POJO inside entity (doc part) | interface | record | | builder-tracking | metadata |
| EntityDTO | interface | | | | metadata |
| EntityForm | interface | | | | metadata |

---

## 4. Full-Depth Trace: Tracking Materialization Level

This section traces every code path touched when a view is annotated `@View(gen = GenLevel.BUILDER_TRACKED)` or `BUILDER_ALL`, from the developer's `@View` declaration through runtime mutation and change detection.

### 4.1 Developer Declaration

```java
@View(gen = GenLevel.BUILDER_ALL)
public interface PersonSummary extends Person {
    Integer age();
    String departmentName();
    Map<String, List<Long>> metadata();
    // hand-written or generated Record / Write / toBuilder / toBuilderTracking
}
```

### 4.2 Compile-Time: Generator Input

`EntityMetadataGenerator.generate(Path sourceRoot, Path outputDir)`:
1. Walks `sourceRoot` for `.java` files.
2. Parses each with `JavaParser` → `CompilationUnit`.
3. For every `ClassOrInterfaceDeclaration` that `isInterface()`:
   - Builds an `InterfaceInfo` containing package, name, extended types, getter-only methods (zero-arg, non-void), `@View` annotation attributes (`read`, `write`), and `@EntityBase<ID>` id type.
4. Collects **marker entities** (interfaces extending `EntityBase<ID>`) and groups them by package.
5. For each marker:
   - Discovers all derived views via `isDerivedFromRecursive`.
   - Builds `EntityMeta` (entity name, package, marker interface, id type, views, merged `allFields`).
   - Serializes `EntityMeta` → `<EntityName>.metadata.json`.
   - For each view, calls `generateViewPropertyEnum(...)` → `FieldBoilerplateGenerator.builder(...).withPropertyEnumMode().withEnumTypeName(viewName + "_").generate(outputDir)`.

**Current gap**: `EntityMetadataGenerator` only emits the `_` enum. It does **not** generate:
- `Record` inner class inside the view interface.
- `Write` inner interface.
- `Builder` / `BuilderTracking` classes.
- `ViewMeta.META` static field with `DefaultViewMeta` factory body.
- Class-file `// {@link}` + JSON5 header.

### 4.3 Generated Artifact: `<View>_` Enum

`FieldBoilerplateGenerator` produces (example: `PersonSummary_.java`):

```java
public enum PersonSummary_ implements FieldDef {
    id(Long.class),
    firstName(String.class),
    // ...
    toBuilder(PersonSummaryBuilder.class),
    toBuilderTracking(PersonSummaryBuilderTracking.class);

    private final Type propertyType;
    private PersonSummary_(Type propertyType) { this.propertyType = propertyType; }
    public Type getPropertyType() { return propertyType; }
    public static PersonSummary_ forName(String name) { /* switch */ }
    // FieldNameMapper.NAME_MAPPER static field (field-enum mode)
}
```

This enum is the **type-level key** for the tracking bitmask. Its ordinals (`id`=0, `firstName`=1, ...) become bit positions in the `long` mask.

### 4.4 Runtime: Creating a Tracked Builder

Typical call site:

```java
PersonSummary source = ...;
PersonSummaryBuilderTracking builder = source.toBuilderTracking();
// mutate
builder.firstName("New Name");
// inspect changes
if (builder.isChanged()) {
    EEnumSetBuilder64<PersonSummary_> changes = builder.changes();
    // ...
}
PersonSummary updated = builder.build();
```

**Step-by-step execution:**

1. `source.toBuilderTracking()` (default method on `PersonSummary`):
   - `new PersonSummaryBuilderTracking(source)`
   - Copies all field values from `source` into the builder's fields.
   - Constructs `new EEnumSetBuilder64<>(PersonSummary_.class)` → `mf`.
   - `mf` starts empty (`bits0 = 0L`, `size = 0`).

2. `builder.firstName("New Name")`:
   - Executes `mf.addOrdinalChange(1, firstName, "New Name")`.
   - `EEnumSetBuilder64.addOrdinalChange(ordinal, oldValue, newValue)`:
     - `Objects.equals(oldValue, newValue)` → if equal, **no-op**, returns `false`.
     - Otherwise calls `addOrdinal(1)`:
       - Bounds check: `ordinal >= 64 || ordinal >= universe.length` → `universe = PersonSummary_.class.getEnumConstants()`.
       - `mask = 1L << 1` (`0b10`).
       - `bits0 |= mask` → bit 1 is set.
       - `size++` → `size = 1`.
       - Returns `true`.
   - Builder field `firstName` is updated.
   - Setter returns `this` for fluent chaining.

3. `builder.isChanged()`:
   - Returns `mf.size() > 0` → `true`.

4. `builder.changes()`:
   - Returns the mutable `EEnumSetBuilder64<PersonSummary_>` directly.
   - Caller can:
     - `mf.has(PersonSummary_.firstName)` → `true`.
     - `mf.forEach((field, idx) -> ...)` → iterates only set bits via `Long.numberOfTrailingZeros`.
     - `mf.toImmutable()` → `EEnumSet64<PersonSummary_>` (snapshot, `bits0` copied, `size` copied).

5. `builder.build()`:
   - `new PersonSummary.Record(id, firstName, lastName, age, departmentName, metadata)`.
   - Returns immutable record.

6. `builder.clearChanges()` (if implemented, or `mf.clear()`):
   - `EEnumSetBuilder64.clear()` → `bits0 = 0L`, `size = 0`.
   - Bitmask is reset; subsequent `isChanged()` → `false`.

### 4.5 Runtime: Proxy-Based Tracking Path (Alternative)

Instead of a generated builder, the proxy path uses `EntityUpdateTrackingArray`:

```java
ViewMeta<PersonSummary, PersonSummary_> meta = PersonSummary_.META;
EntityUpdateTrackingArray<PersonSummary, PersonSummary_> array =
    EntityUpdateTrackingArray.create(meta.forName(), fieldCount, values);
PersonSummary proxy = ArrayBackedViewProxyFactory.createUpdatable(
    PersonSummary.class, array, meta.forName());

proxy.firstName("New Name"); // via UpdatableHandler
```

**Execution trace:**

1. `ArrayBackedViewProxyFactory.createUpdatable(...)`:
   - Creates `UpdatableHandler` wrapping `updateArray` and `fieldByMethodName::forName`.
   - `Proxy.newProxyInstance` with `PersonSummary.class` only (not `ViewReader`; updatable proxy is single-interface).

2. `proxy.firstName("New Name")` (one-arg mutator):
   - `UpdatableHandler.invoke`:
     - `parameterCount == 1`, name = `"firstName"`.
     - `int ordinal = updateArray.set("firstName", "New Name")`.
       - `EntityUpdateTrackingArray.set(String, Object)`:
         - `forNameOrdinal.forNameOrdinal("firstName")` → `1`.
         - Calls `set(1, "New Name")`.
           - `Objects.equals(previous, "New Name")` → if equal, no-op.
           - `values[1] = "New Name"`.
           - `mark(1)`.
             - `EntityUpdateTrackingArray64.mark(1)` → `changes.addOrdinal(1)` → bit 1 set.
     - Returns `proxy` (for fluent API).

3. `proxy.changes()` (zero-arg, `ViewChangeTracking` contract):
   - `updateArray.changesSnapshot()`:
     - `EntityUpdateTrackingArray64.changesSnapshot()` → `changes.toImmutable()`.
     - `EEnumSetBuilder64.toImmutable()`:
       - If `size == 0` → `EEnumSetEmpty.of(enumClass)`.
       - Else → `new EEnumSet64<>(enumClass, bits0, size)`.
     - Returns immutable `EEnumSet64<PersonSummary_>`.

4. `proxy.clearChanges()`:
   - `updateArray.clear()` → `changes.clear()` → `bits0 = 0L`, `size = 0`.

### 4.6 Tracking Class Hierarchy Summary

```
ViewChangeTracking<E, S>                          (interface: isChanged / changes)
  ↑ implemented by
PersonSummaryBuilderTracking                      (concrete class: hand-written today, generated tomorrow)
  ↑ uses
EEnumSetBuilder64<PersonSummary_>                 (mutable bitmask, 1 long, ≤64 fields)
  ↑ extends
EEnumSetBuilder<PersonSummary_>                   (interface: addOrdinal, removeOrdinal, clear, toImmutable, ...)
  ↑ extends
EEnumSetRead<PersonSummary_>                      (read interface: getBits0, has, forEach, ...)

Immutable snapshots:
EEnumSet64<PersonSummary_>                        (wraps long bits0 + int size)
EEnumSetEmpty<PersonSummary_>                     (singleton, bits0=0)
EEnumSet<PersonSummary_>                          (mutable parent: union, intersect, difference, toBuilder)

Proxy-backed tracking:
EntityUpdateTrackingArray<T, F>                   (abstract base: values[], set() with equality check + mark)
  ↑ concreted by
EntityUpdateTrackingArray64<T, F>                 (≤64 fields: EEnumSetBuilder64)
EntityUpdateTrackingArrayLarge<T, F>              (>64 fields: EEnumSetBuilderLarge)
  ↑ created by
EntityUpdateTrackingArray.create(ForNameOrdinal, int fieldCount, Object... values)
```

### 4.7 Bitmask Mechanics (≤64 Fields)

For a view with `N ≤ 64` fields:

- **Storage**: single `long bits0` (64 bits).
- **Field ordinal `i`** → bit position `i` (0-indexed).
- **Mark**: `bits0 |= (1L << i)`.
- **Unmark**: `bits0 &= ~(1L << i)`.
- **Check**: `(bits0 & (1L << i)) != 0`.
- **Iteration**: `while (remaining != 0) { int ordinal = Long.numberOfTrailingZeros(remaining); ... remaining &= (remaining - 1); }` — this is the standard "iterating set bits" idiom, O(number of changed fields).
- **Snapshot**: `new EEnumSet64<>(enumClass, bits0, size)` — copies the long and size, no heap allocation for mask array.

For `N > 64`:
- **Storage**: `long[] bits` of length `ceil(N / 64)`.
- **Ordinal `i`**: `segment = i / 64`, `offset = i & 63`, `mask = 1L << offset`.
- Same semantics, slightly more indexing arithmetic.

### 4.8 Equality Short-Circuit

Both the builder path (`PersonSummaryBuilderTracking`) and the proxy path (`EntityUpdateTrackingArray.set`) perform:

```java
if (Objects.equals(previousValue, newValue)) {
    return; // or return false from addOrdinalChange
}
```

This means:
- Setting a field to its current value is a **no-op** — the bit is not set.
- `EEnumSetBuilder64` `size` accurately reflects the number of **actually changed** fields.
- `isChanged()` is equivalent to `size > 0`.

---

## 5. Gap Analysis

### 5.1 Critical Gaps (block real project use)

| # | Gap | Impact |
|---|---|---|
| G1 | **Generator does not produce `record`, `Write`, `Builder`, `BuilderTracking`** | Every consumer must hand-write or copy-paste from example. No regeneration possible. |
| G2 | **No cooperative preservation (DEC-020)** | If generator overwrites a file, all hand-edits are lost. Unsafe for real projects where developers tweak generated code. |
| G3 | **No build hook / Maven plugin / `project-automation` integration** | Generator is a standalone CLI. Developers must manually invoke it or wire it themselves. |
| G4 | **Jackson serializer incomplete** | Round-trip JSON (deserialize → mutate → serialize) is unverified. |
| G5 | **No persistence adapters (JDBC, MQ, KV)** | Entities exist only in memory. No DB read/write path. |

### 5.2 High-Priority Gaps

| # | Gap | Impact |
|---|---|---|
| G6 | **No class-file headers (DEC-021)** | Generated files lack `// {@link}` + JSON5 provenance. IDE rename-refactor cannot reach generated names. |
| G7 | **No divergence reporting (DEC-022)** | When a developer renames a view method, the generator's `forName` switch and builder setters become stale; no diagnostic is emitted. |
| G8 | **No mapper generation (P-1)** | Converting `PersonSummary` → `PersonDto` requires hand-written mapping code. |
| G9 | **No validation boilerplate (P-6)** | Jakarta Validation / standalone validators not generated. |
| G10 | **No annotation runtime exposure** | `@FieldSource(kind=DERIVED, expression=...)` is parsed at tooling time but not exposed at runtime for dynamic SQL builders or derived-field evaluators. |

### 5.3 Medium / Low Gaps

| # | Gap | Impact |
|---|---|---|
| G11 | **`hipster-entity-test` minimal** | No integration tests for proxy, Jackson round-trip, tracking arrays, builder patterns. |
| G12 | **Auditing is structural only** | `Auditable<ID>` interface exists but no audit-trail recording or diff journal. |
| G13 | **JMH benchmarks limited** | Only Jackson deserializer benchmarked; proxy vs generated, tracking overhead not measured. |
| G14 | **User documentation empty** | `doc-hipster-entity/user/` placeholders not populated. |

---

## 6. Phased Implementation Plan

### Phase 0: Foundation (P0) — Generator Completes the Loop

**Goal**: A developer writes view interfaces; running the generator produces all boilerplate (`_` enum, `record`, `Write`, `Builder`, `BuilderTracking`, `ViewMeta.META`, class-file headers) with cooperative preservation.

**Tasks**:

**T0.1 — Extend `FieldBoilerplateGenerator` with new generation modes**
- File: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java`
- Add builder methods:
  - `.withRecordMode()` — emits `public record Record(...) implements <View>`.
  - `.withWriteInterfaceMode()` — emits `public interface Write extends <View>, ViewWriter` with fluent setters returning `Write`.
  - `.withBuilderMode()` — emits `<View>Builder` with fields, getters, fluent setters, `build()`, `get(int)`, `set(int, Object)`, `set(String, Object)`.
  - `.withBuilderTrackingMode()` — emits `<View>BuilderTracking` with `EEnumSetBuilder64<F>` / `EEnumSetBuilderLarge<F>` field, `isChanged()`, `changes()`, `clearChanges()`, setters calling `mf.addOrdinalChange(...)`.
  - `.withViewMetaMode(metaCreatorBody)` — emits `public static final ViewMeta<<View>, <View>_> META = new DefaultViewMeta<>(...)`.
- All modes use JavaParser AST (not string concatenation) per DEC-019.

**T0.2 — Wire new modes into `EntityMetadataGenerator`**
- File: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java`
- In `generate(...)` loop over views:
  - Determine target file paths for each generated artifact (same package, underscore-suffix or inner-class location).
  - For each view, invoke the appropriate `FieldBoilerplateGenerator` mode.
  - For `ViewMeta.META`, construct `DefaultViewMeta` factory body:
    ```java
    new DefaultViewMeta<>(<View>.class, <View>_.class, <View>_.NAME_MAPPER,
        meta -> { /* view-specific field initialization if needed */ },
        "<discriminatorField>", "<discriminatorValue>",
        new Class<?>[] { <subtype1>.class, ... })
    ```

**T0.3 — Implement cooperative preservation (DEC-020)**
- File: `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java` (or new `CooperativeCodegen.java`)
- Before overwriting a file, parse existing file with JavaParser.
- Detect preserved regions by **shape**, not strict markers:
  - For `Builder`: find a class named `<View>Builder` whose body contains field declarations matching view properties + a `build()` method.
  - For `BuilderTracking`: additionally look for `mf` / `modifiedFields` field and `isChanged()` / `changes()` methods.
  - For `Write` inner interface: look for `interface Write` inside the view interface.
  - For `Record`: look for `record Record(...) implements <View>`.
- If a preserved region is found, copy its body verbatim into the new AST before printing.
- If not found, emit the canonical block.
- Emit optional UX hint comment: `// generated by FieldBoilerplateGenerator — edit freely, delete to regen`.

**T0.4 — Emit class-file headers (DEC-021)**
- Above `package` declaration, emit:
  ```java
  // {@link <fqn>} <one-line description>.
  // {enabled:true, blockMarker: "implicit"}
  ```
- The `enabled: false` knob takes a file fully under manual control (generator skips it).
- JSON5 subset: single-quoted strings, unquoted keys, trailing commas, leading decimal point, leading plus sign, non-numeric numbers, backslash escaping of any character. Reject anything else with a diagnostic.

**T0.5 — Divergence reporting (DEC-022)**
- After generating each artifact, compare generated AST with committed file AST.
- Report any divergence:
  - **kind**: `stale_switch`, `missing_setter`, `type_mismatch`, `ordinal_drift`.
  - **location**: file path, line number.
  - **cause**: developer renamed a method, added a field, changed a type.
  - **current**: what is in the file.
  - **canonical**: what the generator would emit.
  - **action**: "regenerate" or "add `enabled:false` to header".
- Output format: structured diagnostics (not just log lines).

**T0.6 — Maven plugin / `project-automation` integration**
- Create `hipster-entity-maven-plugin` (or integrate into `project-automation`):
  - Mojo bound to `generate-sources` phase.
  - Configuration: `sourceRoot`, `outputDir`, `genLevels` (per-view or global default), `cooperative` (default `true`), `strict` (fail on divergence).
  - Adds generated sources to the project compile source roots.
- Wire `project-automation` file watcher to trigger regeneration on `*.java` change under entity packages (per README.md).

### Phase 1: Usability (P1) — Jackson Serialization + Runtime Metadata Exposure

**Goal**: Entities can round-trip through JSON; `@FieldSource` annotations are usable at runtime.

**Tasks**:

**T1.1 — Complete `EntityJacksonViewSerializer`**
- File: `hipster-entity-jackson/.../EntityJacksonViewSerializer.java`
- Implement serialization using `ViewMeta.META` to iterate fields.
- Use `EEnumSetRead.forEach` to write only changed fields when `ViewChangeTracking` is present (patch semantics).
- Register `EntityJacksonViewModule` automatically.

**T1.2 — Expose `@FieldSource` at runtime**
- File: `hipster-entity-api/.../FieldSource.java` (add `@Retention(RUNTIME)`).
- Add `FieldDef.fieldKind()`, `column()`, `relation()`, `expression()` accessors if not already present.
- Populate these from the generated `_` enum or `ViewMeta` so runtime code can build SQL fragments or derived-field expressions without reflection.

**T1.3 — Type divergence validation**
- Add a generator pass that walks `EntityFieldMeta.typeByView`.
- If the same field name has different types across views, emit a diagnostic.
- Require explicit converter registration or fail the build.

### Phase 2: Integration (P2) — Persistence and Mapping

**Goal**: Entities can be read from / written to a database and mapped between views.

**Tasks**:

**T2.1 — JDBC adapter stub (P-3)**
- Generate `ResultSet` reader: for each view, emit a `RowMapper<View>` that reads `ResultSet` by column name / ordinal and populates a `BuilderTracking` or proxy array.
- Generate `PreparedStatement` binder: `set(int index, View value)` using field ordinals; emit `INSERT` / `UPDATE` SQL fragments using `@FieldSource(column=...)`.
- This does not require a full ORM; it is raw JDBC scaffolding aligned with DEC-019 (source-visible, navigable).

**T2.2 — Mapper generation (P-1)**
- For each pair of views with compatible fields, generate a statically-dispatched mapper method:
  ```java
  public static PersonDto toDto(PersonSummary source) {
      return new PersonDto.Record(source.id(), source.firstName(), ...);
  }
  ```
- Use JavaParser to emit direct field-access code (not reflection).
- Divergent types require explicit conversion or are left unmapped with a diagnostic.

**T2.3 — Validation boilerplate (P-6)**
- Read `@FieldSource` / `@View` constraints.
- Generate Jakarta Validation annotations (`@NotNull`, `@Size`, `@Min`, `@Max`, `@Pattern`) on `Record` / `Builder` fields.
- Or generate standalone `Validator<View>` implementations.

---

## 7. Detailed Change Trace: Tracking End-to-End

This section documents every class, method, and field that participates when tracking is enabled, so a developer modifying any part of the system can see the full blast radius.

### 7.1 Compile-Time Chain (Generator → Generated Source)

```
EntityMetadataGenerator.generate(sourceRoot, outputDir)
  │
  ├─ InterfaceInfoCollector
  │   └─ parseProperty(method) → Property(name, type, fieldKind, column, relation, expression)
  │       └─ reads @FieldSource annotation via JavaParser AST
  │
  ├─ Marker detection: InterfaceInfo.isMarkerEntity() (extends EntityBase<ID>)
  │
  ├─ View discovery: isDerivedFromRecursive(candidate, marker, interfaceMap)
  │   └─ follows extends chain to marker
  │
  ├─ EntityMeta construction
  │   ├─ entityName, packageName, markerInterface, idType
  │   ├─ views: List<ViewMeta> (name, extends, read, write, properties, lineNumber)
  │   └─ allFields: List<EntityFieldMeta> (merged across views, typeByView)
  │
  ├─ JSON serialization → <Entity>.metadata.json
  │   └─ toJson(entityMeta) using appendJsonType, parseTypeDescriptor
  │
  └─ Per-view generation via FieldBoilerplateGenerator:
      ├─ withPropertyEnumMode() → <View>_.java
      │   ├─ enum constants: id, firstName, ... + toBuilder, toBuilderTracking
      │   ├─ private final Type propertyType
      │   ├─ getPropertyName(), getPropertyType()
      │   ├─ forName(String) switch
      │   ├─ FieldNameMapper<...> NAME_MAPPER
      │   └─ (if meta mode) ViewMeta<<View>, <View>_> META = new DefaultViewMeta<>(...)
      │
      ├─ (T0.1) withRecordMode() → inner `record Record(...) implements <View>`
      │   └─ written into the view interface source file
      │
      ├─ (T0.1) withWriteInterfaceMode() → inner `interface Write extends <View>, ViewWriter`
      │   └─ fluent setters returning Write
      │
      ├─ (T0.1) withBuilderMode() → <View>Builder.java
      │   ├─ fields matching view properties + id
      │   ├─ getters
      │   ├─ fluent setters returning <View>Builder
      │   ├─ build() → new <View>.Record(...)
      │   ├─ get(int field) switch
      │   ├─ set(int field, Object value) switch
      │   └─ set(String field, Object value) switch
      │
      └─ (T0.1) withBuilderTrackingMode() → <View>BuilderTracking.java
          ├─ fields matching view properties + id
          ├─ final EEnumSetBuilder64/<View>_ mf (or EEnumSetBuilderLarge for >64)
          ├─ getters
          ├─ fluent setters returning <View>BuilderTracking
          │   └─ each calls mf.addOrdinalChange(ordinal, old, new)
          ├─ isChanged() → mf.size() > 0
          ├─ changes() → mf
          ├─ clearChanges() → mf.clear()
          ├─ build() → new <View>.Record(...)
          ├─ get(int field) switch
          ├─ set(int field, Object value) switch
          │   └─ each calls mf.addOrdinalChange(ordinal, old, new)
          └─ set(String field, Object value) switch
              └─ each calls mf.addOrdinalChange(ordinal, old, new)
```

### 7.2 Runtime Chain (Builder Tracking Path)

```
source.toBuilderTracking()
  │
  └─ new PersonSummaryBuilderTracking(source)
      ├─ mf = new EEnumSetBuilder64<>(PersonSummary_.class)
      │   ├─ enumClass = PersonSummary_.class
      │   ├─ universe = PersonSummary_.class.getEnumConstants()  // cached by JVM
      │   ├─ bits0 = 0L
      │   └─ size = 0
      └─ copies all fields from source into builder fields

builder.firstName("Alice")
  │
  └─ mf.addOrdinalChange(1, oldFirstName, "Alice")
      ├─ Objects.equals(oldFirstName, "Alice") → false (value changed)
      └─ addOrdinal(1)
          ├─ bounds: 1 < 64 && 1 < universe.length → ok
          ├─ mask = 1L << 1 = 0b10
          ├─ (bits0 & mask) == 0 → not already set
          ├─ bits0 |= mask → bits0 = 0b10
          └─ size++ → size = 1
  └─ builder.firstName = "Alice"
  └─ returns this

builder.isChanged()
  └─ mf.size() > 0 → true

builder.changes()
  └─ returns mf (EEnumSetBuilder64<PersonSummary_>)
      caller can:
        mf.has(PersonSummary_.firstName)  → (bits0 & 0b10) != 0 → true
        mf.forEach((field, idx) -> ...)   → iterates set bits
        mf.toImmutable()                   → new EEnumSet64<>(enumClass, bits0=0b10, size=1)

builder.clearChanges()
  └─ mf.clear() → bits0 = 0L, size = 0

builder.build()
  └─ new PersonSummary.Record(id, firstName, lastName, age, departmentName, metadata)
```

### 7.3 Runtime Chain (Proxy Tracking Path)

```
EntityUpdateTrackingArray.create(meta.forName(), fieldCount, values...)
  │
  └─ fieldCount <= 64
      ? new EntityUpdateTrackingArray64<>(forNameOrdinal, fieldCount, values)
      : new EntityUpdateTrackingArrayLarge<>(forNameOrdinal, fieldCount, values)

  EntityUpdateTrackingArray64:
    ├─ values = Object[] (backing array, indexed by field ordinal)
    ├─ changes = new EEnumSetBuilder64<>(null)  // null enumClass; no universe needed for pure bitmask
    └─ factory ensures fieldCount == values.length

ArrayBackedViewProxyFactory.createUpdatable(viewType, updateArray, meta.forName())
  │
  └─ Proxy.newProxyInstance(viewType.getClassLoader(),
                             new Class[]{viewType},
                             new UpdatableHandler(updateArray, fieldByMethodName::forName, label))

proxy.firstName("Alice")
  │
  └─ UpdatableHandler.invoke(proxy, method, args)
      ├─ parameterCount == 1, name = "firstName"
      ├─ int ordinal = updateArray.set("firstName", "Alice")
      │   ├─ forNameOrdinal.forNameOrdinal("firstName") → 1
      │   └─ set(1, "Alice")
      │       ├─ Objects.equals(values[1], "Alice") → false
      │       ├─ values[1] = "Alice"
      │       └─ mark(1)
      │           └─ changes.addOrdinal(1) → bits0 |= 0b10, size = 1
      └─ returns proxy (for fluent chaining)

proxy.changes()
  │
  └─ updateArray.changesSnapshot()
      └─ changes.toImmutable()
          └─ new EEnumSet64<>(enumClass, bits0=0b10, size=1)
              // immutable snapshot; caller cannot mutate

proxy.clearChanges()
  │
  └─ updateArray.clear()
      └─ changes.clear() → bits0 = 0L, size = 0
```

### 7.4 Key Invariants and Edge Cases

| Invariant | Enforcement Point |
|---|---|
| ID field (ordinal 0) is immutable | `EntityUpdateTrackingArray.set(int, Object)` throws `UnsupportedOperationException` if `fieldOrdinal == 0`. Builder tracking classes do not expose an `id` setter that calls `addOrdinalChange(0, ...)`. |
| No-op on equal values | `Objects.equals(previous, value)` guard in `EntityUpdateTrackingArray.set` and `EEnumSetBuilder.addOrdinalChange`. Bit is not set; `size` does not increment. |
| `size` matches popcount(`bits0`) | All mutators update `size` atomically with `bits0`. `toImmutable()` trusts the passed `size`. |
| `EEnumSetBuilder64` only for ≤64 enums | Constructor validates `universe.length <= 64`. Factory `EEnumSetBuilder.create(enumClass)` picks variant by `enumClass.getEnumConstants().length`. |
| Snapshot is immutable | `changesSnapshot()` returns `EEnumSet64` / `EEnumSetLarge` / `EEnumSetEmpty`. Caller cannot mutate bits. |
| Builder `changes()` returns mutable | `PersonSummaryBuilderTracking.changes()` returns the internal `EEnumSetBuilder64` directly. Caller can iterate and even call `clear()`. This is intentional for performance (no copy on read). |

---

## 8. Task Dependency Graph

```
T0.1 (extend FieldBoilerplateGenerator)
  └─ T0.2 (wire into EntityMetadataGenerator)
       └─ T0.3 (cooperative preservation)
            └─ T0.4 (class-file headers)
                 └─ T0.5 (divergence reporting)
                      └─ T0.6 (Maven plugin / project-automation integration)

Phase 1 tasks can run in parallel with T0.3–T0.5:
  T1.1 (Jackson serializer)     [independent]
  T1.2 (FieldSource runtime)    [independent]
  T1.3 (type divergence check)  [depends on T0.2]

Phase 2 tasks depend on Phase 0 + Phase 1:
  T2.1 (JDBC adapter)           [depends on T1.2, T0.1]
  T2.2 (mapper generation)      [depends on T0.1]
  T2.3 (validation boilerplate) [depends on T0.1, T1.2]
```

---

## 9. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Generator output breaks existing hand-written examples | High | Medium | Cooperative preservation (T0.3) + `enabled:false` header (T0.4) lets users opt out per file. |
| `EEnumSetBuilder64` `null` enumClass in `EntityUpdateTrackingArray64` | Medium | Low | Fix constructor to require non-null `enumClass` or pass the enum `Class` from `ViewMeta`. |
| JavaParser AST churn across versions | Medium | High | Pin JavaParser version in `hipster-entity-tooling/pom.xml`; add AST snapshot tests. |
| Jackson deserializer zero-alloc path breaks on new Java versions | Low | Medium | JMH benchmarks gate changes; add CI benchmark profile. |
| Cooperative block detection false positives | Medium | High | Start with shape-based detection only; fall back to full overwrite + diagnostic if ambiguous. |
| Performance regression from `EEnumSetBuilder64` boxing | Low | Medium | `size` is `int`; `bits0` is `long`. No boxing in hot path. Benchmark T0.1 builders against proxy. |

---

## 10. Definition of Done for "First Usable Implementation"

The following criteria must be met before `hipster-entity` is considered usable in a real project:

1. **A developer can declare a marker entity + 2–3 views and run the generator** to produce all boilerplate (`_` enum, `record`, `Write`, `Builder`, `BuilderTracking`, `ViewMeta.META`, class-file headers) without hand-writing any of it.
2. **Regeneration preserves hand-edits** to recognized blocks (cooperative preservation).
3. **Generated files carry provenance headers** (`// {@link}` + JSON5) and divergence is reported when IDE refactors break them.
4. **Jackson round-trip works**: deserialize JSON → `BuilderTracking` → mutate → serialize JSON with only changed fields (patch semantics).
5. **Tracking works end-to-end**: `isChanged()`, `changes()`, `clearChanges()` behave correctly for ≤64 and >64 field views, through both builder and proxy paths.
6. **Build integration exists**: Maven plugin or `project-automation` watcher triggers generation automatically.
7. **At least one integration test** covers the full lifecycle: parse → generate → compile → deserialize → mutate → serialize → assert changes.

---

## 11. File Inventory for Implementation

| File to Create / Modify | Purpose |
|---|---|
| `hipster-entity-tooling/.../FieldBoilerplateGenerator.java` | Add `withRecordMode`, `withWriteInterfaceMode`, `withBuilderMode`, `withBuilderTrackingMode`, `withViewMetaMode`. Cooperative preservation logic. Class-file header emission. |
| `hipster-entity-tooling/.../EntityMetadataGenerator.java` | Wire new modes; construct `DefaultViewMeta` factory body; call cooperative preservation before overwrite. |
| `hipster-entity-tooling/.../CooperativeCodegen.java` | (new) Shape-based block detection and body preservation across regeneration. |
| `hipster-entity-tooling/.../DivergenceReporter.java` | (new) Diff generated AST vs committed file; emit structured diagnostics. |
| `hipster-entity-tooling/.../ClassFileHeader.java` | (new) Emit `// {@link}` + JSON5 header. |
| `hipster-entity-maven-plugin/.../EntityGenerateMojo.java` | (new) Maven plugin for `generate-sources` phase. |
| `project-automation/.../EntityWatcher.java` | (new) File watcher that triggers regeneration on entity view changes. |
| `hipster-entity-jackson/.../EntityJacksonViewSerializer.java` | Complete serialization implementation. |
| `hipster-entity-api/.../FieldSource.java` | Add `@Retention(RUNTIME)` and runtime accessor methods. |
| `hipster-entity-core/.../EntityUpdateTrackingArray64.java` | Fix `null` `enumClass` in constructor (pass from `ViewMeta`). |
| `hipster-entity-test/.../PersonSummaryBuilderTrackingTest.java` | (new) Integration test for builder tracking path. |
| `hipster-entity-test/.../ProxyTrackingTest.java` | (new) Integration test for proxy tracking path. |
| `hipster-entity-test/.../JacksonRoundTripTest.java` | (new) Integration test for deserialize → mutate → serialize. |

---

## 12. Non-Negotiable Constraints (from AGENTS.md / DEC-019 / DEC-020 / DEC-021 / DEC-022)

1. **Source-visible wiring**: Every declared connection must be a concrete method body in committed Java source. No `Map<String, Method>`, no `META-INF/services`, no runtime proxy-based dispatch that the IDE cannot navigate to.
2. **Cooperative codegen**: Generators recognize their previous output by shape and preserve user edits verbatim. User opts into regeneration by **deleting** the block. Optional hint comment allowed; strict `// generator:begin/end` discouraged as default.
3. **Class-file header**: Two-line `//` comment pair above `package`: `// {@link <fqn>} <desc>.` and `// {enabled:true, blockMarker: "implicit"}`. JSON5 subset pinned to Jackson `JsonReadFeature`s listed in DEC-021 §4.
4. **Refactor-sensitivity**: Names derived from Java identifiers are refactor-sensitive (wired via `{@link}`, `@see`, `@GeneratedFrom`). Explicit API labels are refactor-insensitive. Divergence reported in uniform format (kind, location, cause, current, canonical, action).
5. **No DI magic**: Explicit constructor / parameter injection only. Annotations are markers; they do not replace wiring.

---

*Generated by Kilo on 2026-09-19. Based on source-visible analysis of `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson`, `hipster-entity-tooling`, `hipster-entity-example`, `hipster-entity-test`, `doc-hipster-entity/architecture/materialization-levels.md`, `doc-hipster-entity/architecture/decisions/DEC-019.md`, `DEC-020.md`, `DEC-021.md`, `DEC-022.md`, and `README.md`.*
