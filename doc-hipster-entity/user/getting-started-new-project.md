# Getting started in a new project

This guide takes a new project from zero to a working, generated view:
add the dependency, write the view interface, run the generator, use the
generated builder and tracking builder, and serialize the result as JSON.

Every name and signature below is taken from code that exists in this
repository — the example module's `person` package. For the level
details, see the [Materialization Guide](materialization-guide.md); this
page is the *workflow*.

## 1. Add the dependency

The modules are published under the `hr.hrg.jcodebuddy` group id at
version `1.0-SNAPSHOT`.

**Runtime** (what your application and your generated code compile
against):

```xml
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-api</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-core</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-jackson</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

**Dev-time only** — the generator:

```xml
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-tooling</artifactId>
  <version>1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

Keep the tooling dependency out of the packaged artifact. The root
[`README.md`](../../README.md) states the rule in its Dev-Time Only
Guarantee: the dev-time orchestrator (`project-automation`) and its tools
do not participate when the project is packaged. Generated source is
plain Java and compiles against `hipster-entity-api` /
`hipster-entity-core` alone.

## 2. Write the view interface

Two pieces: a **marker** interface for the entity, and one or more
**view** interfaces that extend it.

```java
package com.example.person.entity;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;

/** The entity marker for the person package. */
public interface Person extends EntityBase<Long>, Identifiable<Long> {
    String firstName();
    String lastName();
}
```

```java
package com.example.person.entity;

import hr.hrg.hipster.entity.api.FieldKind;
import hr.hrg.hipster.entity.api.FieldSource;
import hr.hrg.hipster.entity.api.GenLevel;
import hr.hrg.hipster.entity.api.View;
import hr.hrg.hipster.entity.api.ViewWriter;

import java.util.List;
import java.util.Map;

@View(gen = GenLevel.BUILDER_ALL)
public interface PersonSummary extends Person {

    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
    Integer age();

    @FieldSource(kind = FieldKind.JOINED, relation = "department.name")
    String departmentName();

    Map<String, List<Long>> metadata();
}
```

What the pieces mean:

- **`extends EntityBase<Long>`** on the marker is what makes the
  hierarchy an entity. A view extends the marker (directly or
  transitively), which is how the generator finds it.
- **`@View(gen = …)`** selects the materialization. The attribute is
  optional: `@View` alone is enough to declare a view, and
  `GenLevel.DEFAULT` is resolved from the view's shape — a matching
  nested `record` resolves to `RECORD`, a nested `Write` interface to
  `BUILDER`, otherwise `META`. `BUILDER_ALL` (the level above) is the
  explicit "give me both builders" choice; `BUILDER_TRACKED` gives you
  the tracking builder and the plain builder.
- **Accessors are record-style**: `String firstName();` — the accessor
  name **is** the field name. There is no `getFirstName()`, no mapping
  layer.
- **`@FieldSource`** classifies a field's origin. An unannotated
  accessor is `COLUMN`, i.e. writable. `DERIVED` and `JOINED` fields are
  readable but not writable (rule S1), so they get no setter.
- **`Identifiable<Long>`** is opt-in: only views that expose explicit
  identity need it.
- You may also declare the generated members **by hand** on the view —
  the example does, for a better editor experience:

  ```java
  public record Record(
      Long id, String firstName, String lastName,
      Integer age, String departmentName, Map<String, List<Long>> metadata
  ) implements PersonSummary {}

  public interface Write extends PersonSummary, ViewWriter {
      Write id(Long value);
      Write firstName(String value);
      // ...
  }

  public default PersonSummaryBuilder toBuilder() { return new PersonSummaryBuilder(this); }
  public default PersonSummaryBuilderTracking toBuilderTracking() { return new PersonSummaryBuilderTracking(this); }
  ```

  Declaring them is not required, but a nested `record` whose component
  list matches the field order is also what makes `GenLevel.DEFAULT`
  resolve to `RECORD`.

**Naming convention (and what the validator does with it).** A view's name
should end in `Summary`, `Details`, `Update`, `Form` or `Dto` (a trailing
`Entity` is allowed, so `PersonSummaryEntity` counts). The name is how every
generated artifact is derived — `PersonSummary_`, `PersonSummaryBuilder`,
`PersonSummaryBuilderTracking` — so a name outside the set produces files nobody
can predict.

The convention is documented and the generator relies on it, but **the validator
does not enforce it**. It was tried and withdrawn: run over this repository's own
example it reported six findings about six correct views — the four
`*PaymentMethod` subclasses of the polymorphic family, `PersonAuditable` (an
addon field-source that is a view in its own right), and the doc-sample
`person/iface/Person` that the generator deliberately excludes from its
`packages` filter. A suffix rule needs to know which interfaces the generator
actually treats as views, and a source-level rule cannot know that; a rule that
reports correct classes is a rule people stop reading.

What the validator *does* report:

- `view_does_not_derive_from_marker` — an interface named like a view that
  neither reaches `EntityBase` nor carries `@View`. The generator emits nothing
  for it, so it is either a missing marker or a name collision, and both are
  worth one line;
- `marker_declares_domain_method` — a marker that declares an accessor, which
  would then appear in every view's field list;
- the `@View` shape and addon diagnostics in `ViewAnnotationRule`, and the R1
  ledger diagnostics in `EntityFieldEnumOrderRule`.

A **marker** is the interface the package's views derive from: the one that
reaches `EntityBase` without passing through another marker. It is not required
to be named `PersonEntity` — the example's is `Person`.

**Validation is a step you can run.** The same rules are available as a gate:

```text
java -cp hipster-entity-tooling.jar hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     validate src/main/java --strict
```

It exits `0` on a clean tree, `1` when a rule reports, and `2` on a usage error.
A generation pass can run the same rules with `--validate` (report and continue)
or `--validate=STRICT` (refuse to write anything until the issues are fixed) —
see step 3.

**Addons.** A shared interface of extra accessors can be merged into a
view with `@View(addons = {SomeInterface.class})`. The addon's fields are
**appended after** the view's own and inherited accessors, in the
addon's declaration order; a colliding name is skipped with a diagnostic;
and the declaration applies to the one interface that carries it — it
does not propagate to subtypes. See the example's `PersonDetails`
(`@View(addons = {PersonAuditable.class})`).

## 3. Run the generator

`EntityMetadataGenerator` is the entry point. It takes a source root (or
a single `.java` file) and an output directory, plus the flags below:

```text
java -cp hipster-entity-tooling.jar hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     src/main/java .jcodebuddy/metadata/entity \
     --java-out src/main/java \
     --packages com.example.person.entity
```

| Argument | Meaning |
|---|---|
| positional 1 | the source root, or a single `.java` file — for a file, the tool searches upward for `src/main/java` or `src/test/java` to derive the root |
| positional 2 | the output directory for the metadata JSON. The convention is the module's own `.jcodebuddy/metadata/entity` — the `.jcodebuddy/` directory is the marker that says "this module uses JCodeBuddy", and only modules that apply `project-automation` have one |
| `--java-out <dir>` | **where the generated `.java` goes.** Without it, generated source is written into positional 2 — the *metadata* directory — which is almost never what you want. Passing your source root here is what makes the generated files land next to the view, and it is what the example's Maven binding does |
| `--packages a.b,c.d` | restrict **generation** to these packages. Indexing is *not* restricted: every source file under the root is still parsed, so cross-package supertypes and addons stay resolvable. Omit it to generate everything |
| `--validate[=OFF\|REPORT\|STRICT]` | run the entity rules before writing anything. Bare `--validate` = `REPORT` (print the issues, keep going); `STRICT` refuses to write until they are fixed; `OFF` is the default for a library caller |
| `--mapper <Src>:<Tgt>[:<ClassName>]` | also emit a statically-dispatched mapper between two views. Repeatable; defaults to class `<Src>To<Tgt>Mapper`, method `to<Tgt>` |
| `--adapters` | **[draft/exploration, opt-in]** also emit the JDBC `<View>RowAdapter` and `<View>Binder` classes. Off unless given; not a supported generator — see [the JDBC pattern](patterns/jdbc-row-adapter.md) |

Generated **Java** goes back into the source tree next to the view when
`--java-out` points there, using the underscore-suffix convention:
`PersonSummary` produces `PersonSummary_.java` in the same package. Commit it —
the committed source is the source of truth (DEC-019), and a developer with a
stock IDE must be able to follow the program without running the generator.

The metadata JSON is named after the entity's marker and written to positional 2
— `<Marker>.metadata.json` (so `Person.metadata.json`), not one file per view.

Flags may appear anywhere after the two positionals, and
`--packages=a.b` is accepted too.

For the Maven build, invoke the same entry point with
`exec-maven-plugin` bound to `generate-sources`, passing `sourceRoot`,
`outputDir` and the flags as `<arguments>` (not `-D` system properties),
so one flag surface serves both the build and a manual run.

## 4. What the generator produced

### The field enum — `PersonSummary_`

```java
// {@link com.example.person.entity.PersonSummary} Field metadata for the PersonSummary view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
public enum PersonSummary_ implements FieldDef {

    id(java.lang.Long.class) {
        @Override public String column() { return "id"; }
    },
    firstName(java.lang.String.class) {
        @Override public String column() { return "firstName"; }
    },
    lastName(java.lang.String.class) {
        @Override public String column() { return "lastName"; }
    },
    age(java.lang.Integer.class) {
        // DERIVED: no column() override — a non-COLUMN field keeps the null default,
        // because there is no column that could hold it.
        @Override public FieldKind fieldKind() { return FieldKind.DERIVED; }
        @Override public String expression() { return "YEAR(NOW()) - YEAR(birthDate)"; }
    },
    departmentName(java.lang.String.class) {
        // JOINED: likewise no column().
        @Override public FieldKind fieldKind() { return FieldKind.JOINED; }
        @Override public String relation() { return "department.name"; }
    },
    metadata(TypeUtils.parameterizedType(java.util.Map.class, java.lang.String.class,
             TypeUtils.parameterizedType(java.util.List.class, java.lang.Long.class))) {
        @Override public String column() { return "metadata"; }
    };

    // javaType(), forName(String), NAME_MAPPER, and:
    public static final ViewMeta<PersonSummary, PersonSummary_> META = ...;
}
```

Four things to notice:

- **The constant name is the field name.** `PersonSummary_.firstName` is
  the constant for `PersonSummary.firstName()`. This intentionally
  differs from `UPPER_SNAKE_CASE` enum style.
- **The header is load-bearing.** The `{@link …}` line makes the
  relationship between the view and the generated artifacts navigable to
  an IDE, and the JSON5 line carries the per-file knobs, including
  `entityFieldEnum:true` (see step 7).
- **`column()` is emitted for `COLUMN` fields only, and it is resolved, not
  copied.** The generator answers with `@FieldSource(column = …)` when the
  accessor carries one and the **accessor name** otherwise, so adapter code never
  has to re-implement the fallback. A `DERIVED` or `JOINED` field gets **no**
  override: it is not a column, so the interface default (`null`) is the honest
  answer — an adapter driven by `column()` therefore never tries to write it. An
  earlier revision of this page showed `column()` overrides on `age` and
  `departmentName`, which is the opposite of the rule and of the emitted
  example.
- **The constant order is a contract.** `values[field.ordinal()]` is
  that field, in every persisted array. Constants are append-only.

### The builders — `PersonSummaryBuilder` and `PersonSummaryBuilderTracking`

`BUILDER_ALL` emits both. The plain builder has typed fluent setters for
the writable fields only, `get(int)`, `set(int, Object)` (throwing for a
non-writable ordinal), `set(String, Object)` (returning the ordinal, or
`-1` for an unknown name), and `build()` returning the record.

The tracking builder implements
`ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>>` and keeps
one piece of tracking state:

```java
public class PersonSummaryBuilderTracking
        implements ViewChangeTracking<PersonSummary_, EEnumSet<PersonSummary_>> {

    /** The one piece of tracking state. Both accessors derive from it. */
    final EEnumSetBuilder64<PersonSummary_> mf = new EEnumSetBuilder64<>(PersonSummary_.values());

    /** Builds the tracking state from a baseline view. */
    public PersonSummaryBuilderTracking(PersonSummary source) { ... }

    public PersonSummaryBuilderTracking firstName(String value) {
        if (!java.util.Objects.equals(this.firstName, value)) {
            this.firstName = value;
            mf.addOrdinal(1);
        }
        return this;
    }

    @Override public EEnumSet<PersonSummary_> changes() { return mf.toImmutable(); }
    @Override public EEnumSetBuilder<PersonSummary_> changesBuilder() { return mf; }
    // isChanged(), clearChanges(), currentValue(...), changedValues()
}
```

## 5. Use the generated builder and `META`

A row array becomes a view through `META`, and a view becomes a builder
through the generated constructor:

```java
import static com.example.person.entity.PersonSummary_.META;

Object[] row = { 1L, "Ada", "Lovelace", 36, "Engineering", Map.of() };

PersonSummary view = META.create(row);          // positional -> record
System.out.println(view.firstName());            // Ada
System.out.println(META.fieldCount() == row.length);   // true
```

```java
PersonSummaryBuilder plain = new PersonSummaryBuilder(view);
PersonSummary edited = plain.firstName("Grace").build();
```

The builder's name-based `set` is a probe — it returns the ordinal it
wrote, or `-1` for a name the view does not have:

```java
int ordinal = plain.set("firstName", "Grace");   // 1
int unknown = plain.set("nope", "x");            // -1
```

The array/builder returns `-1`; the user-facing proxy throws
`IllegalArgumentException` instead. Both are contract (D5).

## 6. Track changes, and compare against your baseline

```java
PersonSummaryBuilderTracking tracked = new PersonSummaryBuilderTracking(view);
System.out.println(tracked.isChanged());         // false

tracked.firstName("Grace");
System.out.println(tracked.isChanged());         // true

// changedValues(): one FieldChange per changed field, in ascending ordinal
// order, each carrying the field and the value it holds NOW. There is no
// "previous" half — the old value is not the tracker's to keep.
tracked.changedValues().forEach(change ->
        System.out.println(change.fieldName() + " is now " + change.current()));
// firstName is now Grace

// The old half comes from the baseline you still hold: the mutable above was
// built from `view`, and `view` is your object, not the tracker's state.
if (!java.util.Objects.equals(view.firstName(), tracked.firstName())) {
    System.out.println("firstName " + view.firstName() + " -> " + tracked.firstName());
}
// firstName Ada -> Grace
```

The two accessors are views over **one** piece of state, never two
copies:

```java
EEnumSet<PersonSummary_> snapshot = tracked.changes();  // immutable, safe to retain
EEnumSetBuilder<PersonSummary_> live = tracked.changesBuilder();  // live, mutable
live.add(PersonSummary_.lastName);
System.out.println(snapshot.size());   // unchanged — it is a snapshot
System.out.println(tracked.changes().size());  // grew
```

Two semantics to rely on:

- **The comparison happens at the write site.** The generated setter is
  `if (!java.util.Objects.equals(this.firstName, value)) { this.firstName = value; mf.addOrdinal(1); }`,
  so a write equal to the value the field currently holds marks nothing.
  The change set keeps no values at all — it is a plain ordinal set.
- **A no-op write changes nothing.** Writing a value `equals` to the one
  already there neither marks the field nor records anything (DEC-012).

And the one consequence to know before you rely on a mark: **a write back
to the original value afterwards leaves the field marked.** No previous
value is kept, so no stored baseline could unmark it. Ask "does this
differ from my baseline?" by comparing the current instance with the
baseline instance you hold — as above.

`clearChanges()` resets the change set (no value baseline is involved,
because none is kept).
`changesDeep()`, `shallowPaths()` and `nestedTrackers()` are the deep
variants; for a view that nests nothing they return the shallow result.

## 7. Serialize with `EntityJacksonMapper`

A `RECORD`-level view is a plain record and is **not** a `ViewReader`, so
the view-based overload takes a function that maps the view to its
positional array — the same order `META.create()` consumes. In the
example that function is a method reference, `PersonDemo::positional`:

```java
import hr.hrg.hipster.entity.jackson.EntityJacksonMapper;
import java.io.StringWriter;

// static Object[] positional(PersonSummary view) — field-enum ordinal order
StringWriter full = new StringWriter();
EntityJacksonMapper.toJson(PersonSummary_.META, view, PersonDemo::positional, full);
System.out.println(full);
```

A `META`-level view (no record) is an array-backed proxy and **is** a
`ViewReader`, so it uses the two-argument overload:
`EntityJacksonMapper.toJson(meta, viewReader, writer)`.

For a **tracking** source, the change-set serializer writes only the
changed fields — the JSON shape of a partial update:

```java
StringWriter patch = new StringWriter();
EntityJacksonMapper.toJsonChanges(PersonSummary_.META, tracked, patch);
// {"firstName":"Grace"}
```

The document is a **JSON Merge Patch of current values only** — the
three-argument form is the whole API. The old
`toJsonChanges(meta, tracking, writer, true)` overload and the
`{"firstName":{"previous":"Ada","current":"Grace"}}` shape no longer
exist, because the tracker keeps no previous value: an audit-style
old&nbsp;→&nbsp;new document is a comparison of two instances, so the
caller builds it from the baseline view it holds (section 6).

Per S4, an unchanged field is **absent** (not `null`), while a changed
field whose value is `null` is written as an explicit `null`.

To use a stock `ObjectMapper` instead, register the view's module
(`hipster-entity-jackson` targets Jackson 3, so the type is
`tools.jackson.databind.ObjectMapper`):

```java
ObjectMapper mapper = EntityJacksonMapper.registerModule(new ObjectMapper(), PersonSummary_.META);
```

You can also pre-build the serializer for a hot path:
`EntityJacksonMapper.changeSerializer(meta)`, and
`EntityJacksonMapper.fromJson(meta, parser)` for one-off reads.

## 8. From a change set to a partial `UPDATE`

The change set is the answer to "which columns would a partial write touch?" — one marked ordinal per
changed column, and nothing else:

```java
boolean[] changed = new boolean[PersonSummary_.META.fieldCount()];
tracked.changesBuilder().forEach((field, index) -> changed[field.ordinal()] = true);

// The column names are the field enum constants; a DERIVED/JOINED field can never be marked,
// because it is not writable (S1).
StringBuilder columns = new StringBuilder();
tracked.changesBuilder().forEach((field, index) -> {
    if (columns.length() > 0) columns.append(", ");
    columns.append(field.name());
});
// -> "firstName"
```

Turning that into `UPDATE person SET firstName = ?` is your own code today. The tooling contains a
**draft** SQL generator (the `--adapters` flag, which emits a `<View>RowAdapter` / `<View>Binder`
pair) but it is an **exploration, not a supported generator, and it is strictly opt-in**: it runs
only when a project asks for it, the default pass emits none of it, and the example project does not
enable it. See
[the JDBC row adapter pattern](patterns/jdbc-row-adapter.md) for the hand-written adapter shape,
which needs no generator at all.

## 9. Adding a field later (the R1 rule)

Once a field enum has been generated, its **constant order is a
persisted ordinal layout**:

1. add the accessor to the view interface, wherever it reads best;
2. regenerate — the new constant is appended **at the end** of the
   constant list, never inserted next to its accessor;
3. never hand-edit the constant list into a "nicer" order;
4. gate the change in CI:

```text
java -cp hipster-entity-tooling.jar hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     enum-order --repo . --baseline origin/main --strict
```

That exits `0` when the ledger is append-only, `1` on a violation, and
`2` on a usage error. Removing a field does **not** delete its constant:
it is tombstoned in place (`@Deprecated`, `FieldDef.retired()` →
`true`), and writers skip it. The full rule, the marker, the
`allowReorder` escape hatch and the checker CLI are documented in
[DEC-023](../architecture/decisions/DEC-023.md) and in
[`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md).

## 10. Next steps

- [Materialization Guide](materialization-guide.md) — what each
  `GenLevel` gives you and how to choose one.
- [The Ordinal Array Contract](patterns/ordinal-array-contract.md) — the
  contract the generated code is built on.
- [JDBC Row Adapter](patterns/jdbc-row-adapter.md) — reading a
  `ResultSet` into a view.
- [Jackson Setup](patterns/jackson-setup.md) — the JSON configuration and
  the change-set shape.
- [`hipster-entity-core/README.md`](../../hipster-entity-core/README.md) —
  the runtime classes the generated code uses.
- [`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md) —
  the generator, its CLI, the naming contract, and the R1 order contract.
- [DEC-023 — R1](../architecture/decisions/DEC-023.md) — the append-only
  field-enum rule.
