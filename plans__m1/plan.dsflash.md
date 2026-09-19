# hipster-entity — Plan to First Usable Implementation

**Status:** proposed
**Scope analysed:** every root folder whose name starts with `hipster-entity-`
(`hipster-entity-api`, `hipster-entity-core`, `hipster-entity-example`,
`hipster-entity-jackson`, `hipster-entity-test`, `hipster-entity-tooling`) plus the
`doc-hipster-entity/` design corpus and the `project-automation` module that consumes them.
**Deep-dive requirement:** § 7 gives a full-depth trace of the tracking functionality
(`BUILDER_TRACKED` / `BUILDER_ALL`), the materialization level with the largest gap between
documentation and working code.

---

## 1. Objective and definition of "first usable"

### 1.1 Objective

Make `hipster-entity` usable in a real project as a **vertical slice**, not as a pile of
half-wired modules: a developer declares entity view interfaces, runs the generator, and gets
committed Java that compiles, round-trips through JSON, and performs a tracked partial update —
and can prove it by running tests.

### 1.2 The first usable slice (definition of done)

One worked scenario, end to end, on the `Person` entity:

```java
// 1. SOURCE (hand-written, committed)
@View(gen = GenLevel.BUILDER_ALL)
public interface PersonSummary extends Person {
    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
    Integer age();
    @FieldSource(kind = FieldKind.JOINED, relation = "department.name")
    String departmentName();
    Map<String, List<Long>> metadata();
}
```

```java
// 2. GENERATED (committed, navigable with a stock IDE — DEC-019/020/021)
enum PersonSummary_ implements FieldDef { ...; public static final ViewMeta<PersonSummary, PersonSummary_> META = ...; }
record PersonSummaryRecord(...) implements PersonSummary {}
class PersonSummaryBuilder implements PersonSummary.Write { ... }
class PersonSummaryBuilderTracking implements PersonSummary.Write,
        ViewChangeTracking<PersonSummary_, EEnumSetBuilder64<PersonSummary_>> { ... }
```

```java
// 3. RUNTIME (the payoff)
Object[] row = jdbcRowToOrdinalArray(rs, PersonSummary_.values());   // § 5 contract
PersonSummary read   = PersonSummary_.META.create(row);              // proxy or record, per level
String json          = EntityJacksonMapper.toJson(PersonSummary_.META, (ViewReader) read, w);
PersonSummary back   = EntityJacksonMapper.fromJson(PersonSummary_.META, parser);

PersonSummaryBuilderTracking patch = back.toBuilderTracking();
patch.firstName("Bob").lastName("Smith");                 // lastName equal -> NOT tracked
patch.changes().toList();                                 // == [PersonSummary_.firstName]
patch.previousValue(PersonSummary_.firstName.ordinal());  // == "Alice"
patch.diff();                                             // == [(firstName, "Alice", "Bob")]
```

**DoD checklist**

1. `mvn -Phipster-entity -o test` is green on JDK 25 for
   `hipster-entity-api|core|jackson|test|tooling|example`.
2. `EntityMetadataGenerator` emits, for a `GenLevel.META` view, a metadata enum that
   **implements `FieldDef`** and carries `META`, `FieldNameMapper`, and `create(Object[])`.
3. Generator output for `GenLevel.RECORD` makes `META.create(values)` return the **record**, not
   a proxy; for `GenLevel.BUILDER_TRACKED` / `BUILDER_ALL` it also emits the builder classes.
4. The tracking path in § 7 works through **both** materializations (generated builder, and
   array-backed proxy) with identical `changes()` / `previousValue()` results.
5. `changes()` is empty when a re-assignment writes an equal value (DEC-012 no-op rule), and is
   empty after `clearChanges()`.
6. `hipster-entity-example` contains a runnable demo (`PersonDemo.main`) printing the diff.
7. Documentation states the *actual* state; no doc claims a level that does not exist.

### 1.3 Explicit non-goals for the first usable release

- SQL/JDBC or MongoDB adapters (only the **ordinal array contract**, § 5, is frozen).
- Bean Validation, type-divergence converter registry (DEC-006), implementation-selection
  factory (DEC-013), freeze markers (DEC-009/018).
- Runtime reflection-based discovery of any kind (forbidden by `AGENTS.md` § 1).

---

## 2. Current-state audit (what the folders actually contain)

### 2.1 Module inventory and build reality

| Module | Source files | Depends on | Builds | Tests | Notes |
|---|---|---|---|---|---|
| `hipster-entity-api` | 21 | — | yes | none | good contracts, small gaps (§ 4.1) |
| `hipster-entity-core` | 17 main / 9 test | api | yes | 42 pass | tracking bug (§ 4.2) |
| `hipster-entity-jackson` | 6 | api, core, jackson 3.2.1 | yes | none | no tests of its own |
| `hipster-entity-test` | 3 main / 8 test | api, core, jackson | yes | 4 pass | **the only module with a real `META`** |
| `hipster-entity-tooling` | 17 main / 7 test | api, core, javaparser, jackson | yes | 22 pass | generator + dead validator (§ 4.4) |
| `hipster-entity-example` | 40+ | core, api | yes | none | hand-written "generated" files, no demo |
| `project-automation` | 10 main | api, core, metadata-server | no | none | blocked by POM (§ 3) |

`doc-hipster-entity/` contains 22 ADRs (DEC-001…DEC-022), 3 architecture guides, 4 user guides,
4 pattern guides, a roadmap, and a brainstorm corpus. It is far ahead of the code.

### 2.2 Verified commands and observed results

```powershell
# JDK 25 is REQUIRED (root pom: maven.compiler.release = 25)
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'

# Per-module build works (install order matters)
cd hipster-entity-api     ; mvn -o -DskipTests install
cd hipster-entity-core    ; mvn -o -DskipTests install
cd hipster-entity-jackson ; mvn -o -DskipTests install
cd hipster-entity-test    ; mvn -o -DskipTests install
cd hipster-entity-tooling ; mvn -o -DskipTests install
cd hipster-entity-example ; mvn -o -DskipTests install
# -> BUILD SUCCESS for all six

# Per-module tests
cd hipster-entity-core    ; mvn -o test   # Tests run: 42, Failures: 0, Errors: 0
cd hipster-entity-tooling ; mvn -o test   # Tests run: 22, Failures: 0, Errors: 0
cd hipster-entity-test    ; mvn -o test   # Tests run:  4, Failures: 0, Errors: 0
cd hipster-entity-jackson ; mvn -o test   # BUILD SUCCESS (no tests)
cd hipster-entity-example ; mvn -o test   # BUILD SUCCESS (no tests)
```

Two environment facts must be fixed before any work is verifiable, and both are cheap:

1. **Reactor build is broken.** `mvn -pl … -am compile` from the repo root fails at POM
   validation:
   ```
   [ERROR] 'dependencies.dependency.version' for hr.hrg.jcodebuddy:metadata-server:jar is missing. @ project-automation/pom.xml line 45
   [ERROR] 'dependencies.dependency.version' for hr.hrg.jcodebuddy:metadata-mcp-server:jar is missing. @ project-automation/pom.xml line 49
   [ERROR] 'dependencies.dependency.version' for hr.hrg.jcodebuddy:metadata-server:jar is missing. @ metadata-mcp-server/pom.xml line 21
   ```
2. **Default JDK is wrong.** `JAVA_HOME` is `C:\Program Files\Java\jdk-21`; the project targets
   25. On JDK 21 the tests fail with
   `class file version 69.0, this version … only recognizes class file versions up to 65.0`
   and `hipster-entity-test` fails to compile with `release version 25 not supported`.

### 2.3 What is genuinely implemented

- **Metadata contract:** `FieldDef` (name == accessor name, ordinal == array index),
  `ViewMeta<V,F>` + `DefaultViewMeta`, `FieldNameMapper` (generated `switch` reverse lookup,
  DEC-015/016), `TypeUtils`, `ForNameOrdinal`.
- **Array primitives:** `EntityReadArray`, `EntityUpdateArray` (overwrite, `id` immutable),
  `EntityUpdateTrackingArray` + `64` / `Large` variants, `ArrayBackedViewProxyFactory`
  (`ReadHandler`, `UpdatableHandler`).
- **Enum bitsets:** `EEnumSet*`, `EEnumSetBuilder64` / `EEnumSetBuilderLarge` with `Strict`
  subclasses, immutable snapshots (`EEnumSetEmpty`, `EEnumSet64`, `EEnumSetLarge`), JMH
  benchmarks.
- **Jackson 3.x integration:** `EntityJacksonViewSerializer/Deserializer`,
  `EntityJacksonViewModule`, `EntityJacksonMapper` — metadata-driven, no reflection,
  zero-allocation parse loop.
- **Generator (partial):** `FieldBoilerplateGenerator` (metadata enum with `forName`,
  `NAME_MAPPER`, `META`, discriminator metadata) and `EntityMetadataGenerator` (JavaParser scan
  → `*.metadata.json` + one property enum per view).
- **A real consumer:** `hipster-entity-test/src/main/java/.../person/PersonSummary_.java` is a
  correct, hand-written `FieldDef` enum with `META` + `create()` — the reference shape the
  generator must reproduce.

### 2.4 The artifact that proves the rest is not wired

`hipster-entity-example/.../PersonSummary_.java` (hand-written, 46 lines):

```java
public enum PersonSummary_ {                       // NOT `implements FieldDef`
    id(java.lang.Long.class), …,
    toBuilder(PersonSummaryBuilder.class),          // a DEFAULT METHOD leaked in as a field
    toBuilderTracking(PersonSummaryBuilderTracking.class);
    public Type getPropertyType() { … }             // FieldDef calls this javaType()
    public static PersonSummary_ forName(String name) { … }   // no NAME_MAPPER, no META
}
```

Consequences: it cannot be passed to `DefaultViewMeta`, `EntityReadArray` or
`EntityJacksonViewDeserializer`; `toBuilder`/`toBuilderTracking` are not fields; and
`getPropertyType()` is not the `FieldDef.javaType()` contract method. The example is a sketch
that compiled once and was never exercised.

---

## 3. Phase 0 — unblock the build (½ day, do first)

| # | Change | File | Detail |
|---|---|---|---|
| 0.1 | Add JDK 25 to the build contract | `.mvn/jvm.config` (new), `scripts/run-tooling.js`, `README.md` | Pin `JAVA_HOME` in run scripts; document "JDK 25 required". |
| 0.2 | Fix reactor POM validation | `pom.xml` `<dependencyManagement>` | Add `hr.hrg.jcodebuddy:metadata-server:${project.version}` and `:metadata-mcp-server:${project.version}`, **or** move both out of `<modules>` until they compile. |
| 0.3 | Make the six-module chain one command | root `pom.xml` (profiles) | Profile `hipster-entity` listing only the six modules so `mvn -Phipster-entity test` works without parsing `project-automation`. |
| 0.4 | Record the baseline | `plans_dsflash/plan.md` § 2.2 (this file) | Keep the test-count table as the regression baseline. |

**Exit gate:** `mvn -Phipster-entity -o test` from the repo root is green on JDK 25.

---

## 4. Phase 1 — make the runtime true (3–4 days)

Everything here is required by the tracking deep-dive in § 7 and by the DoD in § 1.2.

### 4.1 `hipster-entity-api` — three small holes

**4.1.1 `ViewWriter` is untyped and cannot express "read one field"**

`hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/ViewWriter.java` is
`interface ViewWriter extends ViewReader { int set(String,Object); void set(int,Object); }`.
`ViewReader.get(int)` exists but there is no `get(String)`, and no way to ask "is this field
writable?"; the generator needs the latter to avoid emitting setters for `DERIVED`/`JOINED`
fields (`@FieldSource`).

```java
public interface ViewWriter extends ViewReader {
    int set(String field, Object value);   // returns ordinal, or -1 when unknown
    void set(int fieldOrdinal, Object value);
    default Object get(String field) { throw new UnsupportedOperationException(); } // optional
    default boolean supports(String field) { return true; }                          // optional
}
```

Keep both `default` methods non-abstract so existing generated classes keep compiling.

**4.1.2 `@View` has no way to say "tracked"** — `GenLevel.BUILDER_TRACKED` already exists; nothing
else is needed. What *is* missing is the **write-mode contract** (DEC-005) the generator needs to
exclude non-writable fields: resolve it from `@FieldSource` alone (preferred — no new annotation
attribute).

**4.1.3 Freeze/header contract per DEC-021** — `FieldBoilerplateGenerator` emits no header. Add
the two-line `//` header to generated files:
`// {@link <viewFqn>} Field metadata for the <View> view.` followed by
`// {enabled:true, blockMarker: "implicit"}`. This is a **generator** change (§ 6.8) but the
JSON5 subset must be documented in `hipster-entity-tooling/README.md`.

### 4.2 `hipster-entity-core` — the tracking runtime is broken

**4.2.1 Blocking bug: the tracking array cannot be constructed** — a `NullPointerException` at
construction time, not a subtle mismatch:

`hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/EntityUpdateTrackingArray64.java:18`

```java
this.changes = new EEnumSetBuilder64<>(null); // <-- NPE inside EEnumSetBuilder64 ctor
```

`EEnumSetBuilder64(Class<E> enumClass)` immediately calls `enumClass.getEnumConstants()`
(line 15). Same defect in `EntityUpdateTrackingArrayLarge.java:18`.
`EntityUpdateTrackingArray.create(...)` (`EntityUpdateTrackingArray.java:32-36`) takes
`(ForNameOrdinal, int fieldCount, Object... values)` and has no access to the field enum class at
all — hence the `null`.

**Fix (reflection-free, per DEC-014's "concrete dispatch" intent):** pass the enum `universe`
that `ViewMeta` already exposes, and validate arity instead of reflecting:

```java
// EntityUpdateTrackingArray.java
protected EntityUpdateTrackingArray(F[] universe, int fieldCount, Object[] values)

public static <T, F extends Enum<F> & FieldDef>
EntityUpdateTrackingArray<T, F> create(F[] universe, Object... values) {
    int fieldCount = universe.length;
    return fieldCount <= 64
        ? new EntityUpdateTrackingArray64<>(universe, fieldCount, values)
        : new EntityUpdateTrackingArrayLarge<>(universe, fieldCount, values);
}
```

```java
// EntityUpdateTrackingArray64.java
EntityUpdateTrackingArray64(F[] universe, int fieldCount, Object[] values) {
    super(universe, fieldCount, values);
    this.changes = new EEnumSetBuilder64<>(universe);   // new ctor, see 4.2.2
}
```

Keep a deprecated overload `create(Class<F> enumClass, int, Object...)` delegating via
`enumClass.getEnumConstants()` for the JMH harness
(`hipster-entity-core/src/test/.../EEnumSetTrackingJmhBenchmark.java:98,115,167,188` call the
current signature).

**4.2.2 `EEnumSetBuilder64` / `EEnumSetBuilderLarge` need an array-based constructor.**
Both currently take `Class<E>` and call `getEnumConstants()` (reflection per builder). Add the
mirror-image constructors and keep the old ones delegating:

```java
public EEnumSetBuilder64(E[] universe) {          // new
    this.universe = universe;
    if (universe.length > 64) throw new IllegalArgumentException(...);
}
public EEnumSetBuilder64(Class<E> enumClass) { this((E[]) enumClass.getEnumConstants()); } // delegate
```

**4.2.3 `ViewChangeTracking` is a two-method stub.** Current file (6 lines):
`boolean isChanged(); S changes();`. It cannot express the accepted DEC-012 semantics (no-op on
equal write, explicit-null is a marked field, merge modes) beyond the single bitmask, and exposes
no previous value — the difference between "a patch" and "an audit trail".

```java
package hr.hrg.hipster.entity.core;

public interface ViewChangeTracking<F extends Enum<F> & FieldDef, S extends EEnumSetRead<F>> {
    boolean isChanged();
    S changes();                                         // immutable snapshot
    default void clearChanges() { throw new UnsupportedOperationException(); }
    default Object previousValue(int ordinal) { return null; }
    default boolean hasPreviousValue(int ordinal) { return false; }
    record FieldChange<F extends Enum<F>>(F field, Object previous, Object current) {}
    default List<FieldChange<F>> diff() { /* iterate changes() + previousValue() */ }
}
```

New imports: `hr.hrg.hipster.entity.api.FieldDef`, `java.util.List`. Note that `EEnumSet64` is
`final` and `EEnumSetRead` is not parameterised by a `FieldDef`-bound `F`, so
`ViewChangeTracking<PersonSummary_, EEnumSetBuilder64<PersonSummary_>>` (today's example
signature) compiles only because the bound is looser; tightening it as above is the intended
contract, and § 6.7 regenerates the one hand-written implementor.

**4.2.4 Store previous values (required for § 7.7 diff and audit).** Add a tiny holder; do not
touch `EEnumSet*` for this.

```java
// hipster-entity-core/src/main/java/hr/hrg/hipster/entity/core/ChangeRecorder.java (new)
final class ChangeRecorder {
    private long[] trackedBits;      // ordinals that have a captured previous value
    private Object[] previousValues; // by ordinal
    boolean record(int ordinal, Object previous, Object next); // false when Objects.equals == true
    Object previous(int ordinal);
    boolean hasPrevious(int ordinal);
    void clear();
}
```

`EEnumSetBuilder` declares `addOrdinalChange` as a `default` interface method, so the recorder
cannot be an interface field. Move the body into both concrete builders and keep the interface
method abstract (or use a Java 9+ `private` helper):

```java
// EEnumSetBuilder64 / EEnumSetBuilderLarge — concrete state
private final ChangeRecorder recorder = new ChangeRecorder();

@Override
public boolean addOrdinalChange(int ordinal, Object OldValue, Object NewValue) {
    if (Objects.equals(OldValue, NewValue)) return false;   // DEC-012 no-op
    recorder.record(ordinal, OldValue, NewValue);
    addOrdinal(ordinal);
    return true;
}
```

**Aliasing hazard, must be documented and tested:** storing a reference captures the *object*, not
its value. If a caller mutates a `Map`/`List` field in place instead of replacing it,
`previousValue()` will compare equal to `current` and the diff will be empty even though
`changes()` reports the field. Pin this with a test and note it in
`doc-hipster-entity/architecture/decisions/DEC-012.md` ("previous values are shallow references").

**4.2.5 `EntityUpdateTrackingArray` semantics gaps** (`EntityUpdateTrackingArray.java:53-84`):

- `set(int,Object)` throws `UnsupportedOperationException` for ordinal 0 (immutable `id`). Keep.
- The equality check (line 68) precedes `mark` (line 73) — correct today; do not regress it.
- `set(String,Object)` returns `-1` for unknown fields (line 79) while `UpdatableHandler` turns
  that into `IllegalArgumentException` (`ArrayBackedViewProxyFactory.java:150-152`). Pick one
  policy and document it: **return -1 from the array, throw from the proxy** is defensible (the
  array is a low-level primitive), but it must reach the user docs.
- Add `previousSnapshot()` / `previousValue(int)` delegating to `ChangeRecorder`, and extend
  `clear()` to clear the recorder. `changesSnapshot()` stays allocation-free for the empty case
  (`EEnumSetEmpty.of(enumClass)`).

### 4.3 `hipster-entity-jackson` — expose the change set

Nothing in the module can currently serialize a *patch*. Add to
`hipster-entity-jackson/src/main/java/hr/hrg/hipster/entity/jackson/`:

- `EntityJacksonChangeSerializer` — takes `ViewChangeTracking<F,?>` + `ViewMeta`, emits only
  marked fields, and (when `includePrevious` is enabled) wraps each as
  `{"previous": …, "current": …}`. Field names come from `meta.fieldNameAt(ordinal)`; **no
  `HashMap` name→index lookup** (DEC-016).
- `EntityJacksonMapper.toJsonChanges(ViewMeta, ViewChangeTracking, Writer)` and the
  `includePrevious` overload.
- Register the same shapes in `EntityJacksonViewModule` so `ObjectMapper` users get them free.

### 4.4 `hipster-entity-tooling` — fix the validator contract

`ViewAnnotationRule` still enforces the **obsolete** `@View(read=…, write=…)` contract:

```java
String text = viewAnn.toString();
if (!text.contains("read") && !text.contains("write")) {
    issues.add(new ValidationIssue(file, "@View must declare read and/or write modes: " + …));
}
```

The real annotation (`hipster-entity-api/.../View.java`) is
`{ GenLevel gen; String discriminatorField; Class<?>[] addons; }` — so `@View(gen = GenLevel.META)`
is rejected today, and any `@View(...)` passes only if its source text happens to contain the
substring `"write"` or `"read"`. The rule is dead code (referenced only from tests), which is why
nobody noticed.

Rewrite `ViewAnnotationRule` to parse `gen` (default `DEFAULT` → treat as `META`), reject a
`GenLevel` incompatible with the declared shape (e.g. `BUILDER_TRACKED` on a view with no
writable fields), and drop the string-`contains` check. Update `ViewAnnotationRuleTest`,
`EntityRulesValidatorTest`, `EntityMetadataGeneratorTest` fixtures, which all still use
`@View(read = BooleanOption.TRUE, write = BooleanOption.FALSE)`.

Then **wire the validator into the generator**: `EntityMetadataGenerator.generate(...)` should
collect `ValidationIssue`s and fail (or warn, via a `strict` flag) before writing files.

---

## 5. Phase 2 — the ordinal array contract (1 day, freezes the integration boundary)

This is the only interface a real project's persistence layer must implement, and it must be
written down before adapters proliferate.

```
values[f.ordinal()] == the value of field f, for every f in the companion FieldDef enum
values.length == FieldDef.values().length
ordinal 0 is the identity field for entity roots (immutable in tracking arrays)
a JOINED/DERIVED field occupies its ordinal; the adapter may fill it with null
  and let a projector or the view's own Record/Builder supply the value
```

Deliverables:

- `doc-hipster-entity/user/patterns/jdbc-row-adapter.md` (new) — a ~30-line reference adapter
  `Object[] fromResultSet(ResultSet, FieldDef[])` driven by `meta.fieldTypeAt(i)` (no reflection),
  warning that column order must come from `meta`, never from `SELECT *`.
- `doc-hipster-entity/user/patterns/ordinal-array-contract.md` (new) — the contract above plus the
  `QueryProjection → Object[] → META.create()` flow that `roadmap/README.md` § 4.1 Phase 5 calls
  "missing".
- A test in `hipster-entity-test`: build a `PersonSummary` from an `Object[]` produced by a fake
  `ResultSet`, assert every accessor, and assert
  `values.length == PersonSummary_.values().length`.
- Guard test: `EntityReadArray` / `EntityUpdateTrackingArray.create` throw
  `IllegalArgumentException` on arity mismatch (already implemented in `EntityReadArray.java:11-13`;
  missing in the tracking array — add it).

---

## 6. Phase 3 — the generator, level by level (5–8 days)

`EntityMetadataGenerator` currently emits **only** the metadata enum + JSON and never interprets
`GenLevel`. Work through the levels in order; each is independently shippable.

### 6.1 `GenLevel` is parsed nowhere

`EntityMetadataGenerator.parseViewAnnotation` (lines 438-473) reads `read`/`write` from the source
text — attributes that no longer exist — and stores them in `ViewAttributes(read, write)`.
`GenLevel` is never read. Consequence: `@View(gen = GenLevel.BUILDER_ALL)` on `PersonSummary`
produces exactly the same output as `@View`.

Changes:

- `meta/ViewAttributes.java` → `record ViewAttributes(GenLevel gen, String discriminatorField, List<String> addons)`.
- `parseViewAnnotation` → read `gen`, `discriminatorField`, `addons` via
  `NormalAnnotationExpr.getPairs()`; map `GenLevel.X` by simple-name comparison (no class
  loading), default `GenLevel.DEFAULT`.
- Resolution rule for `DEFAULT` (make it explicit, per `GenLevel.java`'s own javadoc):
  `META` when the view has no `Write` interface and no sibling record; `RECORD` when a
  nested/companion record exists; otherwise `META`.
- `meta/ViewMeta.java` (tooling) → carry `gen` through to the JSON: add `"gen": "META"` to each
  view object in `EntityMetadataGenerator.toJson` (lines 531-572) and parse it back in `fromJson`
  (lines 52-74). Round-tripping must stay lossless — there is a test for `fromJson`.

### 6.2 Two concrete generator bugs to fix while in there

1. **`default` methods leak into the field enum.** `generate()` filters methods with
   `method.getParameters().isEmpty() && !getType().isVoidType()` (lines 293-296) but does not
   exclude `default` methods. The hand-written `PersonSummary_` in the example proves the effect:
   `toBuilder` and `toBuilderTracking` became enum constants. Add `.filter(m -> !m.isDefault())`
   and a regression test.
2. **Raw source types break `TypeUtils` expressions.** `parseProperty` stores
   `method.getType().asString()` verbatim (line 354) → `"Map<String, List<Long>>"`, but
   `FieldBoilerplateGenerator.parseTypeExpression` emits `TypeUtils.parameterizedType(Map.class, …)`
   only for types it recognises; bare `Person`, `PersonDetails`, `LocalDate` fall through to an
   unqualified class literal that may not resolve. Resolve the string through JavaParser's symbol
   solver (or an import table built from the `CompilationUnit`), then emit fully-qualified names,
   matching what `EntityMetadataGenerator.classLiteral` already attempts (lines 803-834).

### 6.3 Level META — the metadata enum (regenerate `PersonSummary_` correctly)

Emit, per view, in the view's own package:

```java
// {@link hr.hrg…PersonSummary} Field metadata for the PersonSummary view.
// {enabled:true, blockMarker: "implicit"}
public enum PersonSummary_ implements FieldDef {
    id(Long.class),
    firstName(String.class),
    lastName(String.class),
    age(Integer.class),
    departmentName(String.class),
    metadata(TypeUtils.parameterizedType(Map.class, String.class,
                     TypeUtils.parameterizedType(List.class, Long.class))),
    ;

    private final Type javaType;
    PersonSummary_(Type javaType) { this.javaType = javaType; }
    @Override public Type javaType() { return javaType; }

    public static PersonSummary_ forName(String name) { switch (name) { case "firstName" -> … } }
    private static final FieldNameMapper<PersonSummary_> NAME_MAPPER = PersonSummary_::forName;

    public static final ViewMeta<PersonSummary, PersonSummary_> META = new DefaultViewMeta<>(
        PersonSummary.class, PersonSummary_.class, NAME_MAPPER, PersonSummary_::create);

    static PersonSummary create(Object[] values) { /* level-dependent, see 6.4-6.7 */ }
}
```

`FieldBoilerplateGenerator` already has every piece (`fieldEnumMode`, `withMetaCreatorBody`,
`withDiscriminatorField`, `withPermittedSubtypeClassNames`) but emits
`getPropertyType()`/`getPropertyName()` in `propertyEnumMode` — which is what produced the
non-`FieldDef` example enum. **Deprecate `propertyEnumMode` for view enums** and route view
metadata through the `fieldEnumMode` path; keep `propertyEnumMode` only for the tooling's own
`Property`/`ViewMeta` model. Reference shape to match:
`hipster-entity-test/src/main/java/hr/hrg/hipster/entity/person/PersonSummary_.java`.

Also add per-field annotation exposure (`FieldSource` kind/column/relation/expression) to the
enum — `roadmap/README.md` lists "Annotation metadata exposure in generated view enums
(FieldAnnotation)" as an open item; do it now because the write path needs the writability
classification to know what to skip.

### 6.4 Level RECORD — `create()` returns the record

For `GenLevel.RECORD` (and higher), `create(Object[] values)` becomes
`return new PersonSummaryRecord(values[0], values[1], …)`, i.e. Jackson deserialization yields an
immutable record instead of a proxy. If the record is the nested `PersonSummary.Record` that
already exists in hand-written code, do **not** emit a second one: detect it by shape (DEC-020
implicit block detection) and generate the `create()` call against it. Otherwise generate a
top-level `PersonSummaryRecord.java` matching component order to enum ordinal order (the
"field enum order vs record component alignment" invariant in `roadmap/README.md` § 4.1 Phase 5).

### 6.5 Level WRITABLE — the `Write` interface

Emit as a **nested** interface when possible:

```java
public interface Write extends PersonSummary, ViewWriter {
    Write id(Long value);
    Write firstName(String value);
    // ... one per writable field only
}
```

The hand-written example already has this exact shape
(`hipster-entity-example/.../PersonSummary.java:35-42`), so use it as the golden output. Skip
`@FieldSource(kind = DERIVED|JOINED)` fields; if the view has none, omit the `Write` interface and
log a diagnostic.

### 6.6 Level BUILDER — `PersonSummaryBuilder`

Golden output is `hipster-entity-example/.../PersonSummaryBuilder.java` (hand-written, 79 lines):
mutable fields, copy-constructor from the view, getters, fluent setters returning `this`,
`Object get(int)`, `int set(String,Object)` with the `-1` unknown-field contract, and
`void set(int,Object)`. Regenerate this file with the generator and delete the hand-written one
only after the generated file passes the same tests.

### 6.7 Levels BUILDER_TRACKED / BUILDER_ALL — see § 7

`BUILDER_TRACKED` → `PersonSummaryBuilderTracking` only. `BUILDER_ALL` → both, and the view gets
`default` helpers `toBuilder()` / `toBuilderTracking()` (already present by hand at
`PersonSummary.java:44-45`) — those are hand-written or shape-recognised generated blocks, never
strict-marker blocks (DEC-020).

### 6.8 Cooperative codegen and refactor-sensitivity (DEC-020/021/022)

- Whole-file header: the two-line `//` header from § 4.1.3. `enabled:false` freezes a file.
- Block recognition by shape: existing `case "…" ->` arms in `forName`, existing `withXxx(...)`
  builder methods, existing `public static final ViewMeta<…> META` constant. On regen keep the body
  verbatim, including user edits; append only genuinely new fields. Deleting a block opts back into
  regeneration.
- After each regen pass, print the DEC-022 divergence report (`kind, location, cause, current,
  canonical, action`) — at minimum for "field in the enum but not in the interface" and "field in
  the interface but not in the enum"; those are the two that bit the example.
- Publish the naming-contract table in `hipster-entity-tooling/README.md`: derived names
  (`PersonSummary_`, `PersonSummaryBuilder`, `PersonSummaryBuilderTracking`, `PersonSummaryRecord`,
  `Write`, `toBuilder`, `toBuilderTracking`, `META`, `forName`, `NAME_MAPPER`) vs labels that must
  **not** follow an IDE rename (`case "firstName"` JSON/JDBC names, discriminator values). Add
  `{@link}` back to the view interface from every generated file (DEC-019 navigability).

### 6.9 Generator test harness that actually compiles output

`EntityMetadataGeneratorTest` (5 tests) only asserts strings exist. Add
`GeneratedSourceCompilesTest`:

1. write a fixture source root (`Person` marker + `PersonSummary` with `@View(gen = …)`) into JUnit
   `@TempDir`;
2. run `EntityMetadataGenerator.generate(sourceRoot, outDir)`;
3. assert the emitted file set per `GenLevel`;
4. compile the emitted sources with `javax.tools.JavaCompiler` against the module's real classpath
   (api + core), asserting **zero diagnostics**;
5. load the `META` field reflectively *only in the test* and assert `fieldCount()`, `forName()`
   round-trip, and `create()` concrete type.

Step 4 is the acceptance test that closes the doc↔code gap permanently.

---

## 7. Full-depth trace: tracking as a materialization level

This section traces a single tracked write from the declared annotation to the SQL/JSON that leaves
the process, listing **every file that must change**, the exact call chain with current line
numbers, and the failure mode at each hop.

### 7.1 Layer map

```
 L1 DECLARATION      @View(gen=…) on the hand-written view interface
 L2 METADATA         generated FieldDef enum + ViewMeta (+ META.create)
 L3 MUTATION         generated builder tracking  |  array-backed updatable proxy
 L4 RECORDING        EEnumSetBuilder64 / .Strict / Large + ChangeRecorder
 L5 SNAPSHOT         changes() / previousValue() / diff()
 L6 CONSUMPTION      patch serializer (JSON), SQL UPDATE builder, audit log
```

Two writes exist and both must agree bit-for-bit:
**(A) materialized builder** (`PersonSummaryBuilderTracking`, generated, concrete dispatch) and
**(B) proxy** (`ArrayBackedViewProxyFactory.createUpdatable` over `EntityUpdateTrackingArray`,
metadata dispatch). The roadmap invariant is "MUST track changes only on value change (no-op on
equal assignment)" for both.

### 7.2 The generator's problem, precisely

| Gap | Location | Effect |
|---|---|---|
| `gen` never parsed | `EntityMetadataGenerator.parseViewAnnotation` (438-473) | `BUILDER_TRACKED` invisible; no builder emitted |
| `read`/`write` still parsed | same | dead fields in `ViewAttributes`, JSON emits `"read": null` |
| no builder emitter | generator has only `FieldBoilerplateGenerator` (enum only) | levels 3–5 have no code path |
| `default` methods leak | property filter (293-296) | `toBuilder`/`toBuilderTracking` become fields |
| enum not `FieldDef` | `propertyEnumMode` path in `FieldBoilerplateGenerator` | output unusable by `ViewMeta`/Jackson |

### 7.3 L1 → L2: declaration becomes metadata

```
@View(gen = BUILDER_TRACKED)                  [hand-written PersonSummary.java:13]
  └─ EntityMetadataGenerator.generate()       [tooling:267]
       ├─ JavaParser scan → InterfaceInfo     [tooling:270-308]
       ├─ parseViewAnnotation → gen           [4.4 / 6.1]  <- NEW
       ├─ collectEntityFields → allFields     [tooling:395-436]
       ├─ write Person.metadata.json          [tooling:341]  (+ "gen":"BUILDER_TRACKED")
       └─ per view: generateViewPropertyEnum  [tooling:343-347, 662-667]
            └─ FieldBoilerplateGenerator      [FieldBoilerplateGenerator.java:80-89]
                 emits PersonSummary_ implements FieldDef
                       + forName switch (DEC-015)
                       + NAME_MAPPER
                       + META = new DefaultViewMeta<>(view, enum, NAME_MAPPER, create)
```

Ordinal source of truth: **declaration order** of accessors, after merging inherited interfaces in
`collectInterfaceProperties` (tooling:688-705), with `id` forced to ordinal 0
(`collectViewProperties`, tooling:676-686). This order is the array layout contract (§ 5) and must
never be reordered without a migration — it is also what `EEnumSetBuilder64` bit positions mean.

### 7.4 L2 → L3(A): the generated tracking builder

Target output (the hand-written file to be replaced):
`hipster-entity-example/.../person/entity/PersonSummaryBuilderTracking.java`

```
class PersonSummaryBuilderTracking
        implements PersonSummary.Write,
                   ViewChangeTracking<PersonSummary_, EEnumSetBuilder64<PersonSummary_>> {   // line 10

  final EEnumSetBuilder64<PersonSummary_> mf;                     // line 12  <- L4 state
  Long id; String firstName; …; Map<String,List<Long>> metadata;  // lines 14-19

  PersonSummaryBuilderTracking(PersonSummary source)              // line 21
      -> copy-construct every field from the source (baseline for the diff)
  PersonSummaryBuilderTracking()                                  // line 45
  boolean isChanged()      -> mf.size() > 0                       // line 74
  EEnumSetBuilder64<…> changes() -> mf                            // line 77

  PersonSummaryBuilderTracking firstName(String value) {           // line 80  <- the hot path
      mf.addOrdinalChange(1, firstName, value);   // record previous + set bit
      firstName = value;                          // last write wins
      return this;
  }
  void set(int field, Object value)  -> switch with the same two steps  // lines 88-97
  int  set(String field, Object value) -> switch, returns ordinal or -1 // lines 100-111
}
```

Generator changes (a new `BuilderBoilerplateGenerator` beside `FieldBoilerplateGenerator`, or a
new `materialization` package under
`hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/`):

1. one field per enum constant, type taken from `meta.fieldTypeAt(ordinal)`;
2. one fluent setter per **writable** field (`@FieldSource.kind` == COLUMN) performing
   `record -> assign -> return this` in that order;
3. `addOrdinalChange(<ordinal literal>, <field>, value)` — the ordinal must be a literal so the JIT
   can constant-fold the bit mask (`1L << ordinal` in `EEnumSetBuilder64.addOrdinal` line 225);
4. `set(int,Object)` / `set(String,Object)` arms with an explicit cast per field and the `-1`
   unknown-field return;
5. `build()` -> `new PersonSummaryRecord(...)` (level RECORD) or `META.create(values)` when no
   record exists;
6. the copy-constructor from the view is mandatory: without a baseline, "changed" is meaningless;
7. `TrackingStrict extends PersonSummaryBuilderTracking` (line 55) — `EEnumSetBuilder64.Strict`
   re-checks equality; `PersonSummaryBuilderTracking` deliberately does not, because
   `addOrdinalChange` already checks. Keep both, document why, and do not let the generator emit a
   strictness flag that nothing reads.

### 7.5 L2 → L3(B): the proxy path

```
ArrayBackedViewProxyFactory.createUpdatable(viewType, updateArray, meta.forName())
    [core/ArrayBackedViewProxyFactory.java:40-52]
  -> InvocationHandler = UpdatableHandler                                      [line 96]
  -> Proxy.newProxyInstance(viewType, {viewType}, handler)   // interface only, no ViewReader
      |
      |- age()            -> 0-arg, name "age" -> fieldByMethodName("age") -> readArray.get(ord)  [116-129]
      |- firstName("Bob") -> 1-arg             -> updateArray.set("firstName", v)                [148-153]
      |- set(PersonSummary_.age, 42) -> 2-arg "set" -> updateArray.set(field.ordinal(), v)        [138-146]
      |- changes()        -> updateArray.changesSnapshot()                                       [117-119]
      \- clearChanges()   -> updateArray.clear()                                                 [120-123]
```

`updateArray.set(int,Object)` (`EntityUpdateTrackingArray.java:59-74`) is the whole of the tracking
semantics on this path:

```
set(ordinal, value):
  bounds check                        lines 60-62  -> IndexOutOfBoundsException
  ordinal == 0                        lines 63-65  -> UnsupportedOperationException("ID field is immutable")
  Objects.equals(previous, value)     lines 67-70  -> return, NO bit set   (DEC-012 no-op rule)
  values[ordinal] = value             line  72
  mark(ordinal)                       line  73  -> EEnumSetBuilder64.addOrdinal -> bits0 |= (1L<<ordinal)
```

`set(String,Object)` (lines 76-84) maps through `ForNameOrdinal` (`ViewMeta.forNameOrdinal`,
`DefaultViewMeta.java:91-97`) and returns `-1` for unknown names.

**Changes needed on this path:** the constructor fix of § 4.2.1 (today the array cannot even be
built), `previousValue` forwarding (§ 4.2.4/4.2.5), `clear()` also clearing the recorder, and a
`changesSnapshot()` that stays allocation-free when empty.

### 7.6 L4: the recording data structure, field by field

`EEnumSetBuilder64<E>` (`core/EEnumSetBuilder64.java`) — <= 64 fields:

```
fields:  Class<E> enumClass;  E[] universe;  long bits0;  int size;
ctor:    EEnumSetBuilder64(E[] universe)              <- NEW (4.2.2)
         EEnumSetBuilder64(Class<E>)                  delegate, keeps reflection for callers
         EEnumSetBuilder64(Class<E>, long bits0, int)  fast path used by snapshots
addOrdinal(ordinal):  guard 0..63 and < universe.length; mask = 1L << ordinal;
                      if already set return false; bits0 |= mask; size++; return true     [223-230]
removeOrdinal:        symmetric                                                        [238-245]
has(ordinal):         (bits0 & (1L<<ordinal)) != 0                                     [52-55]
toImmutable():        size==0 -> EEnumSetEmpty.of(enumClass) else new EEnumSet64<>(…)  [302-305]
equals/hashCode:      EEnumSetUtils.hashCode(this); equals compares bits0 only when
                      getSegmentCount()==1                                            [264-282]
addOrdinalChange:     Objects.equals short-circuit -> false; else addOrdinal + record    [34-40]
```

`EEnumSetBuilderLarge<E>` — > 64 fields: `long[] bits` sized `ceil(n/64)`
(`EEnumSetBuilderLarge.java:16`), `has(ordinal)` at `ordinal/64`, `1L << (ordinal & 63)`
(lines 49-53). `EntityUpdateTrackingArrayLarge` (line 12) is selected when `fieldCount > 64`
(`EntityUpdateTrackingArray.java:33-35`).

**Two hazards to fix/pin with tests:**

- `EEnumSetBuilder64.addOrdinal` silently rejects ordinals >= `universe.length` (line 224) — a
  mis-ordered enum makes marks vanish with no error. Add a strict mode or a construction-time
  diagnostic in `ViewMeta`.
- `EntityUpdateTrackingArray.create` chooses 64 vs Large on `fieldCount`, so a view with exactly
  64 fields uses the single-`long` path (ordinals 0–63) — correct, but only because `EEnumSet64`
  and `EEnumSetBuilder64` both cap at 64. Pin it with a boundary test at 64 and 65 fields.

### 7.7 L5: snapshot, reset, and the diff

```
changes()                -> EEnumSetBuilder64<F> (mutable, live view)      [example line 77]
                            proxy path returns the IMMUTABLE snapshot      [handler line 118]
changesSnapshot()        -> EEnumSet<F>   (EEnumSetEmpty | EEnumSet64)      [array lines 37-39]
clear() / clearChanges() -> bits0 = 0; size = 0  (+ recorder.clear() — NEW)
previousValue(ordinal)   -> ChangeRecorder.previous(ordinal)                (NEW)
diff()                   -> List<FieldChange<F>> { field, previous, current } (NEW)
```

**Inconsistency to resolve:** `changes()` returns a mutable builder on the builder path but an
immutable snapshot on the proxy path (`ArrayBackedViewProxyFactory.java:118` ->
`EntityUpdateTrackingArray.changesSnapshot()`). A caller cannot tell whether it may hold the
result. Decide: **`changes()` returns the immutable snapshot on both paths**, and expose
`changesBuilder()` (or `getChanges()`, as in `EntityUpdateTrackingArray.java:51`) for the mutable
one. This is an API break in name only — the example's `changes()` returns `mf`, and § 6.7
regenerates that file.

**`clear()` does not reset the baseline.** After `clear()`, re-assigning the *original* value is
still seen as a change relative to the (now stale) field value. Semantics to document: `clear()`
resets the *change set*, not the *comparison baseline*; to re-baseline, rebuild the tracking
builder from the persisted row (`toBuilderTracking(rowView)`), or add `refreshBaseline()` if
§ 4.2.4's recorder is extended to snapshot all values.

### 7.8 L6: consumption — diff to JSON, diff to SQL

```
diff() / changes()
  |- JSON patch   EntityJacksonChangeSerializer (NEW, § 4.3)
  |                 iterate changes().forEach(field -> meta.fieldNameAt(field.ordinal()))
  |                 emit {"firstName":"Bob"} or {"firstName":{"previous":"Alice","current":"Bob"}}
  |                 unknown field handling mirrors the parse loop: skip, never build a Map (DEC-016)
  |- SQL UPDATE   per changed ordinal -> the @FieldSource(kind=COLUMN, column="first_name") label
  |                 parameterised, columns in ordinal order (stable plans, cacheable PreparedStatements)
  \- audit trail  FieldChange(field=PersonSummary_.firstName, previous="Alice", current="Bob")
                  + actor/timestamp supplied by the caller, not by the core
```

### 7.9 Change inventory for the tracking feature (the "full depth" list)

| # | File | Change | Phase |
|---|---|---|---|
| 1 | `hipster-entity-core/.../EntityUpdateTrackingArray64.java:18` | `new EEnumSetBuilder64<>(null)` -> `new EEnumSetBuilder64<>(universe)` | 1 |
| 2 | `hipster-entity-core/.../EntityUpdateTrackingArrayLarge.java:18` | same fix | 1 |
| 3 | `hipster-entity-core/.../EntityUpdateTrackingArray.java:22-36` | ctor + `create(F[] universe, Object...)`; arity validation; keep deprecated `Class` overload | 1 |
| 4 | `hipster-entity-core/.../EntityUpdateTrackingArray.java:44-51` | `clear()` clears recorder; add `previousValue`/`previousSnapshot`; `changes()` -> immutable | 1 |
| 5 | `hipster-entity-core/.../EEnumSetBuilder64.java:13-29` | array ctor; `Class` ctor delegates | 1 |
| 6 | `hipster-entity-core/.../EEnumSetBuilderLarge.java:13-26` | same | 1 |
| 7 | `hipster-entity-core/.../EEnumSetBuilder.java:34-40` | move `addOrdinalChange` into builders; record previous value | 1 |
| 8 | `hipster-entity-core/.../ChangeRecorder.java` | **new** previous-value store | 1 |
| 9 | `hipster-entity-core/.../ViewChangeTracking.java` | real contract: `changes`/`clearChanges`/`previousValue`/`diff`/`FieldChange` | 1 |
| 10 | `hipster-entity-core/.../ArrayBackedViewProxyFactory.java:96-158` | forward `previousValue`, `diff`, `clearChanges`; strict diagnostics | 1 |
| 11 | `hipster-entity-api/.../ViewWriter.java` | optional `get(String)`, `supports(String)` | 1 |
| 12 | `hipster-entity-jackson/.../EntityJacksonChangeSerializer.java` | **new** patch serializer | 1 |
| 13 | `hipster-entity-jackson/.../EntityJacksonMapper.java:19-58` | `toJsonChanges(...)` overloads | 1 |
| 14 | `hipster-entity-tooling/.../meta/ViewAttributes.java` | `record ViewAttributes(GenLevel, String, List<String>)` | 3 |
| 15 | `hipster-entity-tooling/.../meta/ViewMeta.java` | carry `gen` | 3 |
| 16 | `hipster-entity-tooling/.../EntityMetadataGenerator.java:293-296` | exclude `default` methods | 3 |
| 17 | `hipster-entity-tooling/.../EntityMetadataGenerator.java:438-473` | parse `gen`/`discriminatorField`/`addons` | 3 |
| 18 | `hipster-entity-tooling/.../EntityMetadataGenerator.java:43-101, 531-572` | `"gen"` in `toJson`/`fromJson` | 3 |
| 19 | `hipster-entity-tooling/.../validation/ViewAnnotationRule.java` | drop obsolete `read`/`write` rule; validate `gen` vs shape | 1 |
| 20 | `hipster-entity-tooling/.../EntityMetadataGenerator.java:263-350` | run the validator before writing | 1 |
| 21 | `hipster-entity-tooling/.../materialization/BuilderBoilerplateGenerator.java` | **new** builder + tracking-builder emitter | 3 |
| 22 | `hipster-entity-tooling/.../materialization/RecordBoilerplateGenerator.java` | **new** record / `create()` emitter | 3 |
| 23 | `hipster-entity-tooling/.../FieldBoilerplateGenerator.java:50-140` | header (DEC-021), `FieldDef` mode for views, `create()` body, `{@link}` | 3 |
| 24 | `hipster-entity-example/.../person/entity/PersonSummaryBuilderTracking.java` | replace hand-written with generated | 3 |
| 25 | `hipster-entity-example/.../person/entity/PersonSummaryBuilder.java` | same | 3 |
| 26 | `hipster-entity-example/.../person/entity/{PersonSummary_,Write_,PersonDto_}.java` | delete/regenerate as `FieldDef` + `META` | 3 |
| 27 | `hipster-entity-example/.../person/entity/PersonSummary.java:13,35-45` | `Write` + `default toBuilder*()` become recognised generated blocks | 3 |
| 28 | `hipster-entity-test/src/main/java/.../person/*` | add a tracking-enabled view fixture (nothing in `-test` uses tracking today) | 2 |

### 7.10 Tests that pin the tracking behaviour (write these first)

`hipster-entity-core/src/test/java/hr/hrg/hipster/entity/core/EntityUpdateTrackingArrayTest.java` (**new**)

1. `create(universe, values)` builds both variants (<= 64 and 65 fields) — regression for the
   `null` ctor bug;
2. arity mismatch -> `IllegalArgumentException`;
3. equal-value `set` -> `mark` returns false, `changesSnapshot().isEmpty()` is true;
4. `set(0, x)` -> `UnsupportedOperationException`;
5. `clear()` empties the snapshot; `previousValue` after `clear()` behaves as documented;
6. `previousValue(ord)` returns the pre-write value for a changed field, `null` for an unchanged one;
7. boundary: 64-field and 65-field views produce equivalent `changes()` for the same mutation.

`hipster-entity-test/src/test/java/hr/hrg/hipster/entity/tracking/PersonSummaryTrackingTest.java` (**new**)

8. `row -> read view -> toBuilderTracking -> same values -> changes() empty` (baseline integrity);
9. `firstName("Bob")` -> `changes().toList() == [PersonSummary_.firstName]`, `isChanged()`;
10. `lastName("Smith")` where it is already `"Smith"` -> still empty (DEC-012 no-op);
11. set, set back to original -> field still marked (touched semantics, pinned by DEC-012);
12. `diff()` yields `(firstName, "Alice", "Bob")`;
13. JSON patch contains exactly one field, and `includePrevious` emits previous/current;
14. **proxy parity:** the same assertions against
    `ArrayBackedViewProxyFactory.createUpdatable(...)` over `EntityUpdateTrackingArray`.

`hipster-entity-core` JMH: extend `EEnumSetTrackingJmhBenchmark` to the new
`create(F[] universe, …)` signature and add a `previousValue`-on/off axis, so the cost of § 4.2.4
is measured rather than assumed.

---

## 8. Phase 4 — example as the acceptance surface (2 days)

1. `hipster-entity-example/.../person/PersonController.java` is an **empty class** (5 lines).
   Replace it with a real `PersonDemo` (`main`, no framework): row array -> read proxy -> JSON ->
   tracking patch -> diff printed -> the parameterised UPDATE it would send.
2. Same for `paymentMethod/PaymentMethodController.java` using the polymorphic `PaymentMethod_` /
   `CreditCardPaymentMethod_` enums (`discriminatorField` support already exists in
   `FieldBoilerplateGenerator` but is exercised nowhere).
3. Add the missing READMEs: `hipster-entity-core/README.md`, `hipster-entity-tooling/README.md`
   (incl. the § 6.8 naming-contract table), `hipster-entity-test/README.md`.
4. Correct docs that are currently wrong or aspirational:
   - `user/getting-started.md:10` says groupId `hr.hrg`, version `0.1.0` — real coordinates are
     `hr.hrg.jcodebuddy:*:1.0-SNAPSHOT`;
   - `user/getting-started.md:39-42` documents `bun ./scripts/run-tooling.js` — verify it works on
     JDK 25 or replace with the plain `java -jar hipster-entity-tooling.jar` form;
   - `user/faq.md:28-31` describes tracking as "generated helpers and builder patterns" — link the
     real classes after § 4.2 lands;
   - `architecture/materialization-levels.md` is internally contradictory: it defines two
     different Level 0–4 ladders (lines 182-270 and 286-353) and refers to `PersonSummaryField`
     while the code generates `PersonSummary_` (`naming-conventions.md:42` agrees with the code).
     Rewrite it to the six `GenLevel` values and strike the duplicated section;
   - `roadmap/plan-for-continuation.md` is a stale generated document describing a `jcodebuddy`
     module that does not exist under that name (`project-automation` does) and contains a literal
     `</new_content>}` artefact at line 333. Mark it superseded by this plan;
   - `architecture/decisions/README.md:119` contains the text `(line is not valid UTF-8)` — repair.

---

## 9. Phase 5 — harden the boundary a real project touches first: JSON

1. `hipster-entity-jackson` has **no tests**; the only Jackson tests live in `hipster-entity-test`.
   Add coverage next to the code: round-trip for a RECORD level (`create()` returns the record),
   for a META level (proxy), and for a polymorphic `PaymentMethod` hierarchy using
   `discriminatorField`/`permittedSubtypes`.
2. Ensure `EntityJacksonViewDeserializer` handles `@FieldSource(DERIVED/JOINED)` fields that are
   absent from the payload: `values[ord]` stays `null`, and `RECORD`-level `create()` must accept
   `null` for them (or the record must be constructed only from `COLUMN` fields — decide and test).
3. Document the ObjectMapper registration path (`EntityJacksonMapper.registerModule`) in
   `user/patterns/jackson-setup.md` with the real Jackson 3.x (`tools.jackson.*`) coordinate the
   POMs already use.

---

## 10. Sequencing, effort, and exit gates

| Phase | Content | Effort | Exit gate |
|---|---|---|---|
| 0 | build unblock, JDK 25, baseline | ½ d | `mvn -Phipster-entity -o test` green from root |
| 1 | runtime truth: tracking ctor, `ViewChangeTracking`, recorder, validator, patch serializer | 3–4 d | § 7.10 tests 1–14 green, both paths |
| 2 | ordinal array contract + JDBC adapter doc + arity tests | 1 d | adapter doc + test |
| 3 | generator levels META->BUILDER_ALL, headers, cooperative blocks, compiling-output test | 5–8 d | `GeneratedSourceCompilesTest` green for all six levels |
| 4 | example demo + doc corrections | 2 d | `PersonDemo` prints a 1-field diff |
| 5 | Jackson coverage + polymorphism | 2 d | round-trip tests for RECORD/META/polymorphic |
| — | **total** | **~14–18 d** | DoD § 1.2 fully checked |

Ordering constraints:

- Phase 0 before anything (no verification otherwise).
- § 4.2.1 (`null` ctor) before any tracking test can even run.
- § 4.2.3/4.2.4 (`ViewChangeTracking` + recorder) before § 7.7 (snapshot/diff) and § 6.7 (builder
  emitter), because the generated builder must implement the final interface.
- § 6.1 (`GenLevel` parsing) before § 6.3–6.7; the levels are a ladder.
- § 6.9 (compiling-output test) lands with § 6.3 and grows with each level — never regenerate a
  level without a compiling assertion.

---

## 11. Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `EEnumSet*` API changes ripple into JMH harness + example | High | Medium | Keep `Class`-based ctors as delegating overloads (§ 4.2.2); update JMH in the same commit |
| `changes()` return-type change breaks the hand-written example | Certain | Low | Regenerate the example in the same commit (§ 6.7); it is generated code by definition |
| Ordinal reordering silently corrupts persisted patches | Medium | High | § 5 contract doc + `ViewMeta` construction-time arity/order check + `allFields` JSON as the migration record |
| `ChangeRecorder` retains large object graphs | Medium | Medium | Shallow-reference semantics documented; `clear()` releases; JMH benchmark; per-view opt-out |
| Generator scope creep (cooperative blocks, divergence reporting) delays the slice | High | Medium | Phases 2–3 are ordered so META->BUILDER_TRACKED is usable *before* DEC-020/021 sophistication |
| Docs keep drifting ahead of code | High | Medium | § 6.9 compile test + Phase 4 doc corrections are part of the DoD, not optional |

---

## 12. Immediate next actions (this week)

1. Phase 0.1–0.3: pin JDK 25, add the missing `dependencyManagement` versions, add the
   `hipster-entity` profile. Confirm `mvn -Phipster-entity -o test` green.
2. § 4.2.1: fix the `null` enum-class bug in both tracking-array variants and add § 7.10 tests 1–3.
   This is the single highest-value commit in the plan.
3. § 4.2.3/4.2.4: land the final `ViewChangeTracking` + `ChangeRecorder` API, then bring
   `PersonSummaryBuilderTracking` to that signature by hand and get tests 8–12 green.
4. § 4.4: repair `ViewAnnotationRule` and wire the validator into the generator.
5. § 6.1/6.3/6.9: parse `GenLevel`, emit a correct `FieldDef` + `META` enum, and prove the emitted
   source compiles.
6. File the two doc defects that mislead newcomers first: the duplicated ladder in
   `materialization-levels.md` and the stale validator contract message.
