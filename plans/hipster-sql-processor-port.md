# Porting `processor` to JCodeBuddy generation

> **Status: draft for review â€” 2026-11-17. Nothing has been changed yet.**
>
> **Scope.** The **new port lives in this JCodeBuddy checkout** as
> `hipster-sql/hipster-sql-tooling`. The external `D:\wrk\java\java-hipster-sql-1` is a reference:
> its `processor` is the legacy implementation/oracle and is **not modified**, and its `sql` runtime
> stays **untouched except for one approved fix** â€” the one-line
> `HipsterSqlUtil.annotation(Class, Object...)` value bug (Â§ 2.6 item 10), needed to make
> `genAnnotations` work. Every other subproject (`dao-test`, `mongo`, `test-nodep`, `ioc`) is out of
> scope.
>
> **Implementation started 2026-11-17.** The first vertical slice is in the checkout:
> `hipster-sql/hipster-sql-tooling` is wired into the reactor; it discovers `@HipsterEntity`
> interfaces through `SourceReader`/`TreeQueries`, builds the first `EntityModel`/`PropertyModel`,
> has the LF text writer, emits `<Entity>Visitor`, writes per-entity metadata + `manifest.json`, and
> writes/reads an append-only ordinal ledger. It supports `--source-root`, `--java-out`,
> `--metadata-out`, `--ledger-out`, `--check`, `--allow-reorder`, `--gen-update`, `--gen-builder`,
> `--gen-meta`, `--gen-json`, `--gen-mongo`, `--gen-annotations`, `--classpath`, `--packages` and
> `--version`, and has **29 passing tests**. Remaining: annotated-inheritance/package options,
> metadata/UI consumer coverage, and the watch phase.
>
> **D3 and D6 are confirmed.** The new generator must match the legacy processor's generated behaviour
> semantically, not byte-for-byte. The generated files are a **new starting point and the new baseline**; their
> formatting, line endings and blank lines are canonical, and no existing generated file has to be
> preserved or migrated. LF is the default and canonical EOL. The baseline must be genuinely good to
> read, not merely different: Â§ 8.4 defines it and the golden tests pin it.
>
> **D5 is confirmed:** all JCodeBuddy tooling moves to **Java 25 as the lowest supported version**.
> The new `hipster-sql-tooling` module is therefore a JDK 25 tool. Generated code still targets Java 8
> because `sql` is a Java 8 runtime; the Java 25 minimum applies to the generator, not to the code it
> emits for this runtime.
>
> **The first delivery includes both metadata and the ordinal ledger.** Entity metadata is not only an
> audit trail: it is the **model for tooling that builds UI/project-understanding views** (entity
> browser, reference pages, dependency drill-down). Â§ 10.2 defines that model, its stable keys and
> the rules that make it safe to consume; Â§ 9.3 defines the ledger split and tracking.
>
> The sources reviewed are the uncommitted state of `D:\wrk\java\java-hipster-sql-1` on 2026-11-17
> (`HipsterDaoProcessor`, `GenImmutable`, `GenMeta`, `GenUpdate`, the two POMs). The uncommitted fixes
> must be carried into the new emitters, not discarded (Â§ 2.6).

## 1. Summary

The external `processor` is a classic `AbstractProcessor`: javac hands it the round's annotated
interfaces and it writes the generated class set per entity into `target/generated-sources` through
`Filer`. `sql` is the runtime the generated code compiles against. Both are reference inputs; the new
generator lives in this checkout.

The port adds a **new module**, `hipster-sql/hipster-sql-tooling`, and leaves the legacy processor as
the reference/oracle:

| Today (annotation processing in the legacy `processor`)            | After (JCodeBuddy generation in `hipster-sql-tooling`)                                             |
| ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| `@HipsterEntity` triggers `HipsterDaoProcessor` at compile time    | `@HipsterEntity` is a **marker** read by the new module's source pass; a normal program writes the code |
| Output in `target/generated-sources` via `Filer`                   | Output to a `--java-out` directory as ordinary `.java`, ready to be committed by the consuming project |
| JavaPoet builds trees and reprints them                            | text emitters with an explicit `JavaSourceWriter`; annotations copied verbatim                     |
| javac's `TypeMirror`/`AnnotationMirror` model                      | OpenRewrite LST + textual type resolution (DEC-030), with unresolved names reported, never guessed |
| No metadata or ordinal identity                                    | first-class entity metadata (the UI/project-understanding model) + a tracked ordinal ledger        |
| JavaPoet fixes member spacing, reflows annotations, writes LF only | the writer owns LF by default, indentation, blank lines and annotation layout                      |
| Processor on the consumer's compile path, `-A...` options          | a dev-time CLI (`java -cp` or `mvn exec`), no `Filer`, no `-A`, no `META-INF/services`             |
| Static maps leak between rounds/compilations                       | one pass, one immutable model                                                                      |

`sql` is not edited except for the approved one-line `HipsterSqlUtil.annotation(Class, Object...)`
fix: its `ColumnMeta` constructor, `EntityMeta` shape and `IEnumGetter`/`IUpdatable` contracts stay
exactly as they are. The generated code is written to work with that runtime, not to improve it.

**Toolchain consequence (accepted).** The new `hipster-sql-tooling` module is **Java 25**, because the JCodeBuddy engine (`jcodebuddy-core`) is compiled at release 25 and all JCodeBuddy tooling
now has Java 25 as its lowest supported version. The tooling jar therefore does not run inside a
JDK 8/11/17 `javac`; it is a standalone JDK 25 tool. Generated code still targets Java 8, because
`sql` is a Java 8 runtime (with only the approved annotation fix) and compiles at 1.8. This is D5 and
is confirmed; the remaining risk is only that
a developer running the generator needs JDK 25, not that the generated code changes level.

The generated API keeps the existing names: `<Entity>Immutable`, `<Entity>Builder`, `<Entity>Update`,
`<Entity>Meta`, `<Entity>Visitor` and `AllEntitiesInPackage`, with the same members and supertypes.
The output format is new; the contract is not.

### Implementation progress (2026-11-17)

| Slice                                                                                              | State                                      |
| -------------------------------------------------------------------------------------------------- | ------------------------------------------ |
| `hipster-sql/hipster-sql-tooling` module, parent POM, reactor entry, gate entry                    | **done**                                   |
| `EntityDiscovery` + `EntityModel`/`PropertyModel` (top-level entities, getter rules, skip/keepRest, imports, supertypes, annotations, FQN sort) | **done for the basic fixture** |
| `JavaSourceWriter` (LF, exact `blank(n)`, indent, final newline) + `ImportCollector`               | **done**                                   |
| `<Entity>Visitor` emitter                                                                          | **done**                                   |
| `<Entity>Immutable` emitter (fields, 3 constructors, getters, `getValue`, skipped pass-through, optional Jackson serializer, `@Id` equality) | **done for the basic fixture** |
| `<Entity>Update` + `<Entity>Builder` emitters (change-set bits, setters, fluent build)             | **done for the basic fixture**             |
| `<Entity>Meta` + `AllEntitiesInPackage` (columns, ordinals, fromResultSet/visitResult, Jackson mapper, Mongo encode/decode, impl classes, registry) | **done for the basic fixture** |
| CLI `--source-root`, `--java-out`, `--metadata-out`, `--ledger-out`, `--check`, `--allow-reorder`, `--gen-update`, `--gen-builder`, `--gen-meta`, `--gen-json`, `--gen-mongo`, `--gen-annotations`, `--classpath`, `--packages`, `--version` | **done for the slice** |
| Per-entity metadata + `manifest.json` (identity, source hash, table, primary, options, locations, supertypes/inputs, fields/ordinals/annotations, artifacts; no source text) | **done for the slice** |
| Append-only ordinal ledger (bootstrap, append, refused removal, `--allow-reorder` renumber, atomic write, `--check`) | **done for the slice**   |
| Javac compile of generated Visitors/Immutables/Updates/Builders/Meta/registry at `--release 8` against `sql` | **done**                         |
| Remaining: annotated-inheritance/package options, metadata/UI consumer coverage, watch phase       | **next**                                   |


### Core metadata extended (2026-11-17)

`jcodebuddy-core` now has the extended layer beside `classes.json`:

- `type-refs.json` — `TypeReference`/`TypeReferenceSite` resolved FQNs, status, `via`, candidates.
- `type-uses.json` — `TypeUseRecord` body/member dependency edges.
- `artifacts.json` — `ArtifactRecord`, generator/version, inputs, options hash.
- `packages.json` / `modules.json` — `PackageRecord`/`ModuleRecord` (`package-info`, directive lines).
- `AnnotationFacts`, `AnnotationTypeFacts`, `EffectiveAnnotation` — written values and declared defaults.
- `TypeDefinition` now carries members, annotations, path, line and span, not only fields/relations.
- `ClassIndex.writeExtended(...)` / `readExtended(...)` write and read the whole layer.

The hipster extractor derives table, primary, transient, skip, keepRest and initial values from the
generic `AnnotationModel` rows. Core still stores no table, ordinal or column semantics; those stay in the
entity documents.

Verification run: `JAVA_HOME=jdk-25 mvn -o -f hipster-sql/hipster-sql-tooling/pom.xml test` ->
Core: **90 tests, 0 failures**; port: **29 tests, 0 failures**. The full repository gate was not run.

## 2. What `processor` does today

### 2.1 Inventory

All paths are under `D:\wrk\java\java-hipster-sql-1\processor`.

| File                                                                         | Lines | Role                                                                            | Fate                        |
| ---------------------------------------------------------------------------- | ----: | ------------------------------------------------------------------------------- | --------------------------- |
| `src/main/java/.../HipsterDaoProcessor.java`                                 | 368   | `AbstractProcessor`; discovery, options, `Filer` output, `AllEntitiesInPackage` | replaced by the pass        |
| `src/main/java/.../EntityDef.java`                                           | 107   | per-entity model: names, table, prefixes, primary property, `GenOptions`        | replaced by `EntityModel`   |
| `src/main/java/.../Property.java`                                            | 265   | per-accessor model: names, column, type, annotations, flags, custom type        | replaced by `PropertyModel` |
| `src/main/java/.../GenOptions.java`                                          | 93    | per-type/package options with parent fallback                                   | replaced by `GenConfig`     |
| `src/main/java/.../GenImmutable.java`                                        | 248   | `<Entity>Immutable` (+ Jackson serializer, equals/hash, enum getter)            | port                        |
| `src/main/java/.../GenBuilder.java`                                          | 73    | `<Entity>Builder`                                                               | port                        |
| `src/main/java/.../GenUpdate.java`                                           | 206   | `<Entity>Update` (+ change bits, `IUpdatable`)                                  | port                        |
| `src/main/java/.../GenMeta.java`                                             | 1097  | `<Entity>Meta` (SQL, Jackson, Mongo, visitor) â€” the bulk                      | port, split by concern      |
| `src/main/java/.../GenVisitor.java`                                          | 24    | `<Entity>Visitor`                                                               | port                        |
| `src/main/java/.../GenEnum.java`                                             | 187   | dead: call is commented out in `generateClass`                                  | do not port                 |
| `src/main/java/.../GenDelta.java`                                            | 26    | dead: call is commented out; references a type absent from `sql`                | do not port                 |
| `src/main/java/.../AnnotationDef.java`                                       | 17    | unused                                                                          | delete                      |
| `src/main/java/.../HipsterProcessorUtil.java`                                | 48    | `ClassName` constants, type-text helpers                                        | replaced by `TypeRef`       |
| `src/main/resources/META-INF/services/javax.annotation.processing.Processor` | â€”   | registry                                                                        | delete                      |

The legacy module has **no tests of its own** today. The new tests live in
`hipster-sql/hipster-sql-tooling/src/test` and use fixtures there; no other subproject is a test
dependency.

### 2.2 The artifact set

For each `@HipsterEntity` interface (or annotated super-interface), the processor emits:

| Artifact               | Generator             | Shape (abridged)                                                                       | Enabled by                                               |
| ---------------------- | --------------------- | -------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| `<E>Immutable`         | `GenImmutable`        | `public final class`, implements/extends `<E>`, `IEnumGetter`; private fields; `()` / all-args / copy constructors; getters with copied annotations; `getValue(Key)`/`getValue(int)`; `equals`/`hashCode` when `@Id`; optional `serialize` | always |
| `<E>Builder`           | `GenBuilder`          | `public class extends <E>Update`, implements `<E>`; `build()`; fluent `field(value)` methods; constructors | `genBuilder`                         |
| `<E>Update`            | `GenUpdate`           | `public class implements <E>, IUpdatable`; `protected long _changeSetN`; protected fields; getters/setters; `setValue`; `isEmpty`/`isChanged`/`setChanged`; constructors; `equals`/`hashCode`; optional `serialize` | `genUpdate` **or** `genBuilder` |
| `<E>Meta`              | `GenMeta`             | `public class extends EntityMeta` or `MongoEntityMeta`; static `ENTITY_CLASS`, `ENTITY_NAME`, `COLUMN_COUNT`; `ColumnMeta` fields; `(HipsterSql, int)` constructor; SQL `fromResultSet`/`visitResult`; Jackson mapper; Mongo `decode`/`encode`; copy methods | `genMeta` (defaults on; a type/package can set it false) |
| `<E>Visitor`           | `GenVisitor`          | `public interface`; one `visit(...)` with every non-transient, non-`keepRest` property | `genVisitor`                                             |
| `AllEntitiesInPackage` | `HipsterDaoProcessor` | `public class`; `ALL_META` and `ALL_ENTITIES` arrays via `HipsterSqlUtil.toArray(...)` | once per package with a `genMeta` entity                 |

`GenEnum` and `GenDelta` are not emitted.

### 2.3 Options and precedence

Options are read from `-A` compiler arguments in `HipsterDaoProcessor.init`:

| Processor option                    | Meaning                                                     | Default in the current build       |
| ----------------------------------- | ----------------------------------------------------------- | ---------------------------------- |
| `hipster_proc_jackson`              | `genJson`                                                   | off by default                     |
| `hipster_proc_builder`              | `genBuilder`                                                | off                                |
| `hipster_proc_annotations`          | `genAnnotations` (emit `.withAnnotations(annotation(...))`) | off                                |
| `hipster_proc_visitor`              | `genVisitor`                                                | off                                |
| `hipster_proc_update`               | `genUpdate`                                                 | off                                |
| `hipster_proc_mongo`                | `genMongo`                                                  | off                                |
| `hipster_proc_mongo_skip_null`      | `mongoSkipNull`                                             | off                                |
| `hipster_proc_mongo_use_field_name` | `mongoUseFieldName`                                         | off                                |
| `hipster_proc_sql`                  | `genSql`                                                    | off                                |
| `hipster_proc_column_meta_class`    | base class for generated column metas                       | `hr.hrg.hipster.entity.ColumnMeta` |

`@HipsterEntity` on the type can override each `BooleanEnum`; an `@HipsterEntity` on
`package-info.java` provides package defaults. Precedence is type annotation â†’ package annotation â†’
processor options â†’ built-in. **`genMeta` defaults to `true` through the `GenOptions` parent**, and a
type/package annotation can still set it false; there is no separate `-Ahipster_proc_meta` option.

`@HipsterColumn` overrides per accessor: `name`, `initial` (first element), `sql`, `table`,
`customType`, `customTypeKey`, `required`, `skip`, `keepRest`. `javax.persistence` supplies `@Id`,
`@Column(name/table)`, `@Transient`; `java.beans.Transient` is also honoured; `@JsonIgnore` is
recorded. Getter names must start with `get`, or `is`/`has` with a boolean/`Boolean` return type;
methods with parameters and `default` methods are skipped.

### 2.4 Processor flow (and why it is fragile)

1. `init` reads options into a **static** `genOptions` field.
2. `process` gets `roundEnv.getElementsAnnotatedWith(HipsterEntity.class)`, and for each interface
   builds an `EntityDef`, then calls `generateClass`.
3. `makeDef` reads the package-level annotation, builds `EntityDef`, and `makeProps` walks the
   interface's super-interfaces (recursively, only those annotated `@HipsterEntity`) and its methods.
4. `generateClass` builds JavaPoet `TypeSpec`s and writes them with `Filer.createSourceFile`.
5. `generateAllEntitiesInPackage` scans the package element for annotated types and emits the
   package registry.

Fragility the port removes or fixes:

- **Static mutable state** (`packageMetas`, `defMap`, `counter`, `genOptions`) is never cleared. In a
  long-lived compiler (IDE, incremental build) state from a previous round/compilation leaks.
- **`Filer` refuses to recreate a file.** A stale `target/generated-sources` entry or a second round
  can throw `FilerException: Attempt to recreate a file for type ...`; that is why consumer POMs set
  `<useIncrementalCompilation>false</useIncrementalCompilation>`. The pass writes ordinary files.
- **`findPackage` does not advance**: line 182 assigns
  `parentElement = typeElement.getEnclosingElement()` instead of
  `parentElement.getEnclosingElement()`, so a nested entity never sees `package-info` options.
- **`makeProps` has no cycle guard**: two annotated interfaces extending each other recurse forever.
- **Second `@Id`** is reported as an error and generation continues, which can produce a broken
  `Meta`.
- **`AllEntitiesInPackage` order** follows package scan/`HashMap` order, so the same source can
  produce different bytes on two runs.
- The processor's `@SupportedOptions` omits several options it reads (`annotations`, `visitor`,
  `update`, `mongo`, `mongo_skip_null`, `mongo_use_field_name`, `sql`), so javac warns about them.

### 2.5 `sql` contracts the generated code must keep

`sql` is not edited. These are the contracts the generator must reproduce exactly.

| Contract                                               | Where                                                                         | Consequence for the generator                                               |
| ------------------------------------------------------ | ----------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `Meta(HipsterSql hipster, int _ordinal)` constructor   | `EntitySource.newInstance` uses `getConstructor(HipsterSql.class, int.class)` | keep the constructor signature                                              |
| `EntityMeta` / `MongoEntityMeta` superclass            | `GenMeta`                                                                     | keep `EntityMeta<E, ID, C, V>` / `MongoEntityMeta<...>`                     |
| `ColumnMeta` constructor argument order                | `ColumnMeta` line 28                                                          | keep `(ordinal, name, columnName, getterName, required, meta, boxedType, primitiveTypeOrNull, sql, typeHandler, typeParams...)` |
| `getValue(int)` / `setValue(int, Object)` switch cases | `IEnumGetter`, `IUpdatable`, array-backed code                                | ordinal must be stable (see Â§ 9)                                           |
| `_changeSetN` bit per property                         | `GenUpdate`, `IUpdatable`                                                     | bit index must be stable                                                    |
| `AllEntitiesInPackage.ALL_META`                        | hand-written `register(...)` in a consumer                                    | keep the generated arrays; sorting is allowed                               |
| `@JsonProperty` / `@JsonCreator`                       | Jackson path                                                                  | keep when `genJson`                                                         |
| `@JsonSerialize(using = DirectSerializer.class)`       | `GenImmutable`/`GenUpdate`/`GenBuilder`                                       | keep when `genJson`                                                         |
| `org.bson` types                                       | Mongo path                                                                    | only emit when `genMongo`; the consumer supplies the driver                 |
| Java 8 language level                                  | `sql` compiles at 1.8                                                         | generated code must not use records, `var`, switch expressions, text blocks |

### 2.6 Defects to fix in the new emitters, and runtime defects to avoid

These are visible in the current `processor` sources/output; the new emitters must not reproduce
them. Each is worth a regression test.

1. **`customType` default is mis-compared.** `Property` (lines 104â€“112) compares the annotation's
   type mirror with `"hr.hrg.hipster.sql.ICustomType"`, but `HipsterColumn.customType()` is
   `hr.hrg.hipster.type.ICustomType`. The default is therefore treated as a *custom* type. The new
   model must treat the annotation's default as "no custom type".
2. **`@HipsterEntity(genAnnotations = TRUE)` is ignored.** `GenOptions(GenOptions, HipsterEntity)`
   never copies `genAnnotations`, and `GenMeta` compares the field
   (`genOptions.genAnnotations == BooleanEnum.TRUE`) instead of the resolved `isGenAnnotations()`.
   The port must read the annotation option through the normal precedence and use the resolved value.
3. **`GenMeta.getEncoderClass` uses the simple name as raw text** (`GenMeta` line 318:
   `return $L.class`, `def.simpleName`). For a nested entity the generated `Meta` lives in `pkg_` and
   the simple name may not resolve; use `ENTITY_CLASS` (or `$T.class` with the entity type).
4. **`GenUpdate`'s `inspectChange` test is inverted in HEAD**; the uncommitted change fixes it to
   `if (this.x != null && this.x.equals(x)) return this;`. Carry the fixed form over.
5. **`addHash` is uncommitted** (`GenImmutable`/`GenUpdate`). Carry it over; it pairs with `equals`
   on `@Id`.
6. **`GenMeta`'s stray `def.simpleName` argument** in `setCodecRegistry` was removed in the
   uncommitted change. Keep the removed form.
7. **`UserBuilder(User v)` renders as `super(v);}`** because `GenBuilder.genConstructors` calls
   `constr.addCode("super(v);")` with no newline. Correct in the new writer.
8. **`@Column(name="user_id")` is reflowed** to three lines by JavaPoet's `AnnotationSpec`. The new
   writer copies the accessor's annotation text verbatim (see Â§ 8).
9. **`AllEntitiesInPackage` order is not stable**; sort by entity FQN in the new emitter.

One runtime defect is **approved for a one-line fix** in `sql`; one stays untouched:

10. **`HipsterSqlUtil.annotation(Class, Object...)` puts keys as values** (`value[i - 1]` instead of
    `value[i]`, line 196). The fix is the approved `sql` exception:
    `map.put((String) values[i - 1], values[i]);` plus a regression test that builds an annotation
    proxy and reads a member value. With that fix the generated `.withAnnotations(annotation(...))`
    path becomes correct; `genAnnotations` stays off by default for semantic parity, but when enabled
    it emits the proxies rather than silently broken values. This is the only runtime change in this
    plan (D7), and it is a runtime patch: record it for the next `sql` version/release note.
11. **`HipsterSqlUtil` imports `javax.lang.model.element.*`** and exposes a `TypeElement` overload.
    That is processor-only code in the runtime, but it stays. The new tooling computes the same name
    prefixes from FQNs itself and never calls that method.

### 2.7 Why the current write path cannot give you the newlines you want

In short: JavaPoet always emits exactly one blank line between members, reflows annotations, and
writes `\n` only. The uncommitted `write()` change (`HipsterDaoProcessor` lines 330â€“356) splits the
rendered text on `"\n"` and re-prints each line with `PrintWriter.println`, which converts to the
platform line separator, drops trailing empty lines and cannot change member/annotation layout. Â§ 8
has the full diagnosis, the immediate patch and the text-writer design.

## 3. The JCodeBuddy model in one page

```
input source root (the consumer's .java)
        â”‚
        â–¼
HipsterSqlPass (inside processor)
  â”œâ”€ config/CLI options
  â”œâ”€ SourceReader / TreeQueries  (OpenRewrite LST)
  â”œâ”€ in-memory ClassIndex + name oracle (project types + classpath packages)
  â”œâ”€ EntityModel + PropertyModel
  â”œâ”€ ordinal ledger (from the previous pass, or a fresh baseline)
  â””â”€ emitters â”€â”€â–º JavaSourceWriter â”€â”€â–º --java-out/*.java
                â””â”€ entity metadata (UI model) + ledger
                â””â”€ divergence report
```

Rules that come from the repository, not from taste:

- **Annotations are markers, not wiring** (DEC-019). `@HipsterEntity` says "this is an entity"; the
  generated class is the wiring.
- **Generated code is real source** (DEC-009/DEC-019), written to a directory the consumer commits.
- **Whole generated files carry the two-line header** (DEC-021/DEC-035), with `enabled`.
- **User edits are preserved** (DEC-020) â€” relevant for future edits to the new output, not for any
  file generated by the old processor (those are not migrated).
- **Reading is the LST; positions are javac's; writing is text** (DEC-030).
- **Derived names are refactor-sensitive and navigable** (DEC-022); wire/database labels are not.
- **Entity metadata is the UI/project-understanding model** (DEC-027): the Java generator writes JSON
  with FQNs, fields, artifacts, relations and line anchors; a Bun/`jsx6` renderer owns presentation,
  and the generator never emits HTML. A field or artifact a UI cannot link is a metadata bug, not a
  renderer workaround.

## 4. Decisions

| Id     | Decision                           | Recommendation | Consequence                                             |
| ------ | ---------------------------------- | -------------- | ------------------------------------------------------- |
| **D1** | Where the new tool lives           | **A new module in this repository: `hipster-sql/hipster-sql-tooling`.** The external `processor` is the legacy implementation/oracle and is not modified; the external `sql` runtime is a provided dependency. | The generator is a JCodeBuddy consumer like `hipster-entity-tooling`: it depends on `jcodebuddy-core` and never on another project's automation. `sql` stays untouched except D7. |
| **D2** | How types are resolved             | **Text first, per DEC-030**: explicit imports â†’ same package â†’ on-demand packages (wildcards, `java.lang`, project index) â†’ unique simple name â†’ alias map â†’ report. No symbol solver. | Wildcard imports work through a name oracle over the input source root and an optional `--classpath`; unresolved or ambiguous names are reported, never guessed. |
| **D3** | Output parity                      | **Confirmed: semantic parity with a new, good baseline.** Same generated names, members, supertypes, annotations and behavior; the new bytes are canonical and follow Â§ 8.4. | No byte-for-byte comparison and no migration of existing generated files. The gate is structural + behavioral equivalence on fixtures, plus golden tests for the new baseline. |
| **D4** | Field ordinals                     | **Append-only ledger, in the first delivery**, starting from the first pass's declaration order; new fields get the next ordinal; reorder/removal is a hard divergence unless `--allow-reorder`. | Ordinals drive `ColumnMeta.ordinal`, `getValue`/`setValue`, change bits and array slots. The first pass writes the baseline ledger; the project tracks it. |
| **D5** | Tool and generated language levels | **Confirmed:** all JCodeBuddy tooling has Java 25 as its lowest supported version, so **the new `hipster-sql-tooling` module compiles at Java 25**; **generated code stays Java 8**, as `sql` compiles. | The tooling jar is a standalone JDK 25 tool; the external legacy processor is unchanged. Generated code is unchanged in level because `sql` is a Java 8 runtime. |
| **D6** | Newline/style policy               | **Confirmed: LF is the default and canonical EOL**, and the writer owns it along with indent, blank lines between fields/methods/sections, and annotation layout (default `preserve`). CRLF is opt-in per project, never the default. | Direct answer to Â§ 8; no JavaPoet in the emission path. A generated file is LF on every OS, so `git diff`/`--check` are stable. |
| **D7** | The `sql` boundary                 | **Do not modify `sql` except the approved one-line `HipsterSqlUtil.annotation(Class, Object...)` fix and its regression test.** If another generated-code need seems to require a runtime change, stop and report it instead of changing the runtime. | The `sql` diff contains exactly that one production line and its test; `HipsterSqlUtil`'s `javax.lang.model` overload stays. Any other `sql` change is out of scope. |

## 5. Architecture

### 5.1 Modules

```
this checkout (the new home)
  hipster-sql/hipster-sql-tooling/       dev-time generator, Java 25
    â”œâ”€ src/main/java/hr/hrg/hipster/sql/tooling/...   pass, model, emitters, writer, CLI
    â”œâ”€ src/test/java/...                              unit, compile and behavior tests
    â””â”€ src/test/resources/fixtures/...                input interfaces + expected output

external D:\wrk\java\java-hipster-sql-1 (reference, not modified)
  sql/                                  Java 8 runtime â€” only the approved annotation fix
  processor/                            legacy annotation processor and oracle
```

`hipster-sql-tooling` is a JCodeBuddy consumer: it depends on `jcodebuddy-core`/`jcodebuddy-generated`
and on the external `hr.hrg:hipster-sql` runtime as `provided`. It has no dependency on the external
`processor`; that module is read/frozen as the oracle. The only runtime edit is the approved one-line
`HipsterSqlUtil.annotation` fix (D7).

### 5.2 The pass pipeline

1. **Load options** from the CLI and an optional JSON5 config (`--config`).
2. **Inventory** the input source root(s) (`--source-root`, repeatable).
3. **Read `package-info.java`** per package and decode `@HipsterEntity` defaults.
4. **Parse every `.java`** with `SourceReader`; an unparseable file is a divergence, skipped
   (DEC-030's fail-safe).
5. **Build an in-memory class index** of the input types; build the name oracle from the index plus
   `--classpath` for wildcard/JDK/dependency packages.
6. **Discover entities**: interfaces annotated `@HipsterEntity` (directly or inherited), with cycle
   detection.
7. **Build the model**: accessors, flags, annotations, types, names, ordinals.
8. **Read the previous ledger** (from `--ledger-out`, or `<metadata-out>/ledger` by default) and
   assign ordinals append-only.
9. **Emit** each enabled artifact into `--java-out` (or a temp tree under `--check`).
10. **Write metadata/ledger when requested** and report writes/skips/divergences.

### 5.3 File layout inside `hipster-sql-tooling`

```
hipster-sql/hipster-sql-tooling/
  pom.xml
  src/main/java/hr/hrg/hipster/sql/tooling/
    HipsterSqlGenerator.java        CLI entry point (main) â€” implemented
    model/EntityModel.java          implemented (first slice)
    model/PropertyModel.java        implemented (first slice)
    model/TypeRef.java              planned
    model/AnnotationModel.java      planned
    read/EntityDiscovery.java       implemented (top-level entities)
    read/TypeResolver.java          planned (wildcard/name oracle)
    read/AnnotationReader.java      planned (full annotation model)
    emit/JavaSourceWriter.java      implemented (LF, exact blank lines)
    emit/ImportCollector.java       implemented
    emit/VisitorEmitter.java        implemented
    emit/ImmutableEmitter.java      planned
    emit/BuilderEmitter.java        planned
    emit/UpdateEmitter.java         planned
    emit/MetaEmitter.java           planned
    emit/AllEntitiesEmitter.java    planned
    ledger/OrdinalLedger.java       planned
    metadata/EntityMetadata.java    planned (UI model)
    report/Divergences.java         planned
  src/test/java/hr/hrg/hipster/sql/tooling/...
  src/test/resources/fixtures/<case>/src/...       input interfaces
  src/test/resources/fixtures/<case>/expected/...  expected generated output
```

The external `processor` classes (`EntityDef`, `Property`, `GenImmutable`, ...) are **not** moved or
deleted; they remain the legacy oracle. The new module reuses the JCodeBuddy engine, not those
classes.

### 5.4 CLI

```
java -cp hipster-sql/hipster-sql-tooling/target/classes:<deps> \
    hr.hrg.hipster.sql.tooling.HipsterSqlGenerator \
    [--source-root <dir>] [--java-out <dir>] [--metadata-out <dir>] [--ledger-out <dir>] \
    [--packages a.b,c.d] [--classpath <path>] [--config <json5>] \
    [--check] [--allow-reorder] [--version]
```

| Flag              | Meaning                                                                                |
| ----------------- | -------------------------------------------------------------------------------------- |
| `--source-root`   | the source tree to read (repeatable)                                                   |
| `--java-out`      | where generated `.java` is written; required unless `--check`                          |
| `--metadata-out`  | where the per-entity metadata + manifest is written; optional (Â§ 9.3)                 |
| `--ledger-out`    | where the ordinal ledger is written; defaults to `<metadata-out>/ledger`; track this directory (Â§ 9.3) |
| `--packages`      | restrict **emission** to these packages; indexing still covers the whole root          |
| `--classpath`     | the consumer's compile classpath, used only as the wildcard/type name oracle           |
| `--config`        | optional JSON5 options file; CLI wins                                                  |
| `--check`         | render into a temp tree, compare against `--java-out` and any requested ledger/metadata, write nothing; exit 1 on a diff |
| `--allow-reorder` | permit a field reorder/removal and report the renumbering; never a default             |
| `--version`       | generator identity and flag surface                                                    |

Boolean options (`--gen-json`, `--gen-builder`, `--gen-update`, `--gen-visitor`, `--gen-sql`,
`--gen-mongo`, `--gen-annotations`, `--mongo-skip-null`, `--mongo-use-field-name`,
`--inspect-change`) are accepted as flags too; `--version` prints the full list. A per-type
`@HipsterEntity` still wins over a CLI default.

There is no `--watch` in the first delivery; the optional Phase 7 in Â§ 12 may add it if the fs-meta
client is available.

### 5.5 Build and test

- `hipster-sql/hipster-sql-tooling/pom.xml`: parent `jcodebuddy-parent` (Java 25 inherited),
  `jcodebuddy-core`, `jcodebuddy-generated`, OpenRewrite, `hr.hrg:hipster-sql:1.3.4` as `provided`,
  and JUnit 5. No JavaPoet, no annotation-processing API.
- `sql/pom.xml` is **not touched**; the external runtime is consumed as an artifact.
- Tests run with `mvn -o -f hipster-sql/hipster-sql-tooling/pom.xml test` on JDK 25. A test compiles
  generated sources with `javax.tools.JavaCompiler` at the project's Java level against `sql`.
- There is no Bun script; the CLI is `java -cp` (a Maven exec goal can be added later, unbound).

**Why the tooling is Java 25 (confirmed).** All JCodeBuddy tooling now has Java 25 as its lowest
supported version, and `jcodebuddy-core` is compiled at release 25; a Java 8 class cannot link
against it. The generated code remains Java 8; only the tool moves. This is D5.

## 6. The model

### 6.1 Records

All tooling-private; none become runtime API.

```java
record EntityModel(
    String fqn, String packageName, String simpleName, String tableName,
    String sourcePath, int markerLine, String packageInfoPath,
    GenConfig options, List<PropertyModel> properties, PropertyModel primary,
    List<String> supertypes, ArtifactSet artifacts) {}

record PropertyModel(
    String name, String fieldName, String getterName, String setterName,
    TypeRef type, String columnName, String columnSql, String tableName,
    boolean required, boolean transient_, boolean keepRest, boolean readOnly,
    String initial, AnnotationSet annotations, TypeRef customType, String customTypeKey,
    int ordinal) {}

record TypeRef(String text, String rawFqn, List<TypeRef> typeArguments,
               int arrayDepth, boolean primitive) {}

record AnnotationModel(String fqn, String simpleName, String sourceText,
                       List<AnnotationArgument> arguments) {}
```

`TypeRef.text` is the source spelling for re-emission; `rawFqn` is the resolved name used for
imports/class literals. A `TypeRef` whose `rawFqn` cannot be resolved is **unresolved**, not `Object`.

### 6.2 Discovery and inheritance

- An entity is an **interface** annotated `@HipsterEntity`, directly or on a super-interface that is
  also annotated (the current `makeProps` rule). A non-interface annotated type is skipped with a
  warning.
- Walk `extends` recursively with a visited set; a cycle is `inheritance_cycle`, not a stack overflow.
- Accessors: no parameters; not `default`; not `static` (a deliberate fix); name starts with `get`, or
  `is`/`has` with a boolean/`Boolean` return type; `@HipsterColumn(skip = true)` skips.
- Property name: strip `get`/`has` (3 chars) or `is` (2), lowercase the first char; a Java keyword
  gets a `_` prefix; setter is `set` + name.
- `@Id` marks the primary property; a second `@Id` is a hard divergence (`second_id`) and the entity's
  artifacts are not written until it is fixed.
- `@Column(name/table)`, `@HipsterColumn(name/table/sql/initial/required/customType/customTypeKey/
  keepRest)`, `@Transient` (both forms), `@JsonIgnore` are decoded.
- Package-level `@HipsterEntity` is read from `package-info.java`; a nested entity's package is the
  package with a `_` suffix, matching `HipsterSqlUtil.entityNamesPrefixArray` (the new tool computes
  it itself; no runtime change needed).

### 6.3 Type resolution (DEC-030)

A simple name is resolved in Java's order:

1. an explicit single-type import (`import a.b.C;`);
2. a type declared in the same package;
3. the on-demand packages: every wildcard import, `java.lang`, and the input class index â€” exactly
   one hit resolves; two is `ambiguous_type`;
4. a type the class index knows by that simple name, if exactly one;
5. a `typeAliases` entry in config;
6. otherwise `unresolved_type`.

Wildcard imports are normal in these entities (`java.util.*`, `javax.persistence.*`,
`com.fasterxml.jackson.annotation.*`, `hr.hrg.hipster.entity.*`), so they are resolved through a
**name oracle**, not attribution:

- for each wildcard-imported package, try `pkg + "." + simpleName` on `--classpath`
  (`Class.forName(name, false, loader)`) and in the input class index's package map;
- exactly one hit resolves; two is `ambiguous_type`; none is `unresolved_type`;
- no class is initialized, no member is reflected on, no type is attributed.

Static imports contribute no type-name entry; annotation enum values (e.g. `BooleanEnum.TRUE`) are
handled as annotation argument text.

`TypeRef` parsing covers primitives, arrays, parameterized types, wildcards and type variables.
Boxing/erasure is explicit:

| Source         | Boxed class literal                        | Erasure   |
| -------------- | ------------------------------------------ | --------- |
| `int`          | `Integer.class`                            | `int`     |
| `boolean`      | `Boolean.class`                            | `boolean` |
| `Long`         | `Long.class`                               | `Long`    |
| `List<String>` | `List.class` (+ `String.class` type param) | `List`    |
| `String`       | `String.class`                             | `String`  |

A type variable is emitted as its written name if in scope; otherwise it is reported. No current
in-scope fixture needs one.

### 6.4 Annotations

- **Copying to generated getters:** copy the accessor's annotations **verbatim** (source text, one
  annotation per line), excluding the `hr.hrg.hipster.sql` package (the old `Property` rule). Collect
  imports for every simple name in the copied text.
- **Metadata:** parse `J.Annotation` arguments into name/value pairs. Do not read defaults at
  generation time; `AnnotationInvocationHandler.normalize` fills them from the annotation class at
  runtime.
- **`.withAnnotations(...)`:** the approved one-line `sql` fix (Â§ 2.6 item 10) makes the varargs
  `annotation(Class, Object...)` overload correct. `genAnnotations` stays **off by default** for
  semantic parity; when a project enables it, the emitter writes `.withAnnotations(annotation(...))`
  with the written values, and `AnnotationInvocationHandler.normalize` fills the defaults at runtime.
  When it is off, the emitter writes `.withAnnotations()` (empty) and `ColumnMeta.getAnnotation`
  reflects the copied getter annotations. Either way the values are correct; the option only chooses
  proxy-at-construction vs reflection-at-read.
- **Namespaces:** `@Column`/`@Id`/`@Transient` count only when resolved to `javax.persistence.*` (or
  `java.beans.Transient`). An unqualified name with no import is `unresolved_annotation`, never a
  guess.

### 6.5 Options and config

Options can be passed as CLI flags or an optional JSON5 file (`--config`), in the pinned DEC-021
subset:

```json5
{
  format: 1,
  columnMetaClass: "hr.hrg.hipster.entity.ColumnMeta",
  defaults: {
    genJson: false, genMeta: true, genBuilder: false, genUpdate: false,
    genVisitor: false, genSql: false, genMongo: false,
    genAnnotations: false, inspectChange: false,
    mongoSkipNull: false, mongoUseFieldName: false
  },
  packages: ["com.example.entities"],
  typeAliases: {},
  annotationAliases: {},
  style: {
    eol: "lf", indent: "    ",
    blankLinesBetweenFields: 0, blankLinesBetweenMethods: 1,
    blankLinesBetweenSections: 1, annotationLayout: "preserve"
  }
}
```

Precedence: CLI â†’ `@HipsterEntity` on the type â†’ `@HipsterEntity` on `package-info` â†’ config
`defaults` â†’ built-in. `genMeta` defaults on via the parent, exactly as before.

## 7. Emitters

### 7.1 Common contract

Every generated file gets:

1. the DEC-021 header above `package`:
   ```java
   // @generated file hr.hrg.hipster.processor.HipsterSqlGenerator â€” Immutable implementation for the User entity.
   // {enabled:true, blockMarker: "implicit"}
   ```
2. a class Javadoc with `{@link <Entity>}` (the refactor-sensitive link DEC-022 requires);
3. imports collected by FQN, sorted lexicographically, one blank line after;
4. the body emitted by `JavaSourceWriter` with the configured style;
5. exactly one final newline, no trailing spaces.

A file with `enabled:false` in its header is not regenerated; the pass reports `disabled_by_header`.
There is no old generated file to migrate, so cooperative preservation is for **future** edits
(Â§ 9.3), not a migration step.

### 7.2 `<E>Immutable`

| Section           | Content                                                                                       | Invariants                                                          |
| ----------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| declaration       | `public final class <E>Immutable implements <E>, IEnumGetter[, IDirectSerializerReady]` (or `extends <E>` for a class entity) | the entity type is the direct supertype; `final` |
| fields            | `private <type> <field>;` in ordinal order; initial values from `@HipsterColumn(initial=...)` | field names are the property names, `_`-prefixed if a keyword       |
| constructors      | `()`, all-args (`@JsonCreator`/`@JsonProperty` when `genJson`), copy `(<E> v)`                | all-args order is ordinal order; copy checks `v == null`            |
| accessors         | `@Override` + copied annotations + `return field;`                                            | one per property                                                    |
| transient setters | `public void set<X>(<type> v)`                                                                | only for `@Transient`                                               |
| enum getter       | `getValue(E)` + `getValue(int)` switch                                                        | every non-transient property has one case, numbered by ordinal      |
| equality          | `equals`/`hashCode` when a primary property exists                                            | `equals` checks the entity interface, then the primary              |
| serializer        | `serialize(...)` when `genJson`                                                               | field order = ordinal order; `keepRest` last; `@JsonIgnore` skipped |

### 7.3 `<E>Builder`

`public class <E>Builder extends <E>Update implements <E>[, IDirectSerializerReady]` (or extends the
entity for a class entity); constructors `()`, `(<E> v)`, all-args; `build()` returns
`new <E>Immutable(this)`; one fluent `<property>(<type> v)` per property calling the setter and
returning `this`; optional `serialize`.

### 7.4 `<E>Update`

`public class <E>Update implements <E>, IUpdatable[, IDirectSerializerReady]`; `protected long
_changeSetN` for every 64 properties (ordinal/64); protected fields in ordinal order; getters with
copied annotations; setters that set the bit for the property's ordinal and return `this` (or `void`
for transient); `setValue(E, T)` / `setValue(int, Object)`; `isEmpty`, `isChanged` (both overloads),
`setChanged` (both overloads); constructors; equality.

The uncommitted `inspectChange` fix (`if (this.x != null && this.x.equals(x)) return this;`) is part of
the port's contract, with a test.

### 7.5 `<E>Meta`

Split the 1097-line generator into five sections selected by options:

| Section      | Emits                                                                     | Option       |
| ------------ | ------------------------------------------------------------------------- | ------------ |
| common       | superclass (`EntityMeta`/`MongoEntityMeta`), `ENTITY_CLASS`, `ENTITY_NAME`, `COLUMN_COUNT`, `ColumnMeta` fields, `_ordinal`, `(HipsterSql,int)` constructor, column arrays, copy methods, `getImplClasses`, `getImmutableClass`, `getUpdateClass`, `getEntityName`, `getPrimaryColumn`, `entityGetPrimary`, `containsColumn`/`containsField` | always |
| SQL          | `_typeHandler` initialisation, `fromResultSet`, `visitResult`             | `genSql`     |
| Jackson      | `mapper` field + `WithMapper`, `getMapper`/`setMapper`, direct serializer | `genJson`    |
| Mongo        | `_codecs`, `setCodecRegistry`, `decode`, `encode`, `getEncoderClass`      | `genMongo`   |
| visitor hook | `visitResult` on the Meta when `genVisitor`                               | `genVisitor` |

`ColumnMeta` field declarations are in **ordinal order**, and the arrays are built from that order.
The constructor body preserves the existing argument order, including the trailing type-parameter
class literals.

### 7.6 `<E>Visitor`

`public interface <E>Visitor { void visit(<type> <name>, ...); }` for every non-transient,
non-`keepRest` property, in ordinal order. One method; no default methods.

### 7.7 `AllEntitiesInPackage`

`public class AllEntitiesInPackage` with `ALL_META` and `ALL_ENTITIES` arrays, sorted by entity FQN
for deterministic output. Included only when at least one entity in the package has `genMeta`. The
registration remains an explicit hand-written `register(...)` call in the consumer, so DEC-019 is
satisfied by the caller, not by a scan.

### 7.8 Deliberately not emitted

`GenEnum`, `GenDelta` and `AnnotationDef` are dead or unused; do not port them. `GenDelta` references
a runtime type that is not in `sql` (`EnumGetterUpdateDelta`), so it could not compile.

## 8. Writing Java sources: why newlines do not obey today, and the writer that fixes it

### 8.1 Diagnosis

Three causes are JavaPoet's, one is the uncommitted `write()` change.

**(a) JavaPoet fixes member spacing.** `TypeSpec.emit` calls `codeWriter.emit("\n")` before every
member after the first (fields, methods, nested types, enum constants). There is no API to make that
zero or two. Every generated file has exactly one blank line between fields and between methods.

**(b) JavaPoet reflows annotations.** `AnnotationSpec` formats `@Column(name = "user_id")` as:

```java
@Column(
    name = "user_id"
)
```

even though the source wrote it on one line. `@JsonSerialize(using = DirectSerializer.class)` becomes
three lines. There is no "preserve the source spelling" mode.

**(c) JavaPoet writes `\n` only.** `JavaFile.writeTo` has no line-ending setting. The uncommitted
`write()` post-processes with:

```java
String code = new String(out.toByteArray());
String[] lines = code.split("\n");
for (String line : lines) {
    pw2.println(line);
}
```

That has five defects: `println` uses the platform separator (CRLF on Windows, LF elsewhere);
`split("\n")` removes trailing empty strings (a final blank line is lost); a `\r` already in the text
survives as `\r\r\n`; `new String(bytes)` uses the platform default charset rather than UTF-8; and it
still cannot change member/annotation layout. It is a line-ending converter, not formatting control.

**(d) `GenBuilder`'s missing newline** is the visible symptom: `constr.addCode("super(v);")` followed
by JavaPoet's closing brace renders `super(v);}`. Adding `"\n"` fixes that one place, not the general
problem.

If the pass ever uses `SourceSplicer.withMembers`, note that it emits one member per line with no
blank-line parameter; add an overload if splicing is used.

### 8.2 Optional fix for the legacy processor (not part of this port)

The external legacy processor is **not edited** by this port. If you still run it locally and need
deterministic line endings before the new tool is adopted, replace the split/`println` body in
`HipsterDaoProcessor.write` with a direct write:

```java
try (OutputStream out = jfo.openOutputStream();
     Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
    writer.write(javaFile.toString());          // LF exactly as JavaPoet rendered it
}
```

This is the LF default: `javaFile.toString()` ends its lines with `\n` on every platform, and writing
it directly keeps that. If a project explicitly opts into CRLF, make the conversion explicit; never
let `println` choose:

```java
String text = javaFile.toString();
String eol = System.getProperty("hipster.proc.eol", "\n"); // default LF; set "\r\n" only on request
writer.write(text.replace("\n", eol));
```

This preserves every byte JavaPoet produced (including trailing blank lines) and makes the ending
deterministic. It does not give blank-line or annotation control; Â§ 8.3 does.

### 8.3 The writer API

A small `JavaSourceWriter` in the new module (package `hr.hrg.hipster.sql.tooling.emit`):

```java
public final class JavaSourceWriter {
    public enum Eol { LF, CRLF }
    public JavaSourceWriter(String indentUnit, Eol eol) { ... }

    public JavaSourceWriter line(String format, Object... args) { ... } // indent + text + EOL
    public JavaSourceWriter blank() { return blank(1); }
    public JavaSourceWriter blank(int count) { ... }                    // exactly `count` empty lines
    public JavaSourceWriter open(String header) { ... }                 // header + " {" ; indent++
    public JavaSourceWriter close() { ... }                             // indent-- ; "}"
    public JavaSourceWriter raw(String text) { ... }                    // verbatim, no re-indent
    public String finish() { ... }                                      // exactly one final EOL
}
```

The contract: **`blank(n)` writes exactly `n` empty lines.** No collapsing, no "at most one", no
reflow. `raw` is for copied annotation text and expressions.

An import collector sits beside it: `use("java.util.List")` returns `List` and records the import;
`writeImports()` sorts and emits. A simple-name collision gets the FQN instead of an import.

### 8.4 Style policy

| Knob                        | Values                          | Default     | Effect                                                   |
| --------------------------- | ------------------------------- | ----------- | -------------------------------------------------------- |
| `eol`                       | `lf`, `crlf`                    | `lf`        | exactly that terminator everywhere                       |
| `indent`                    | string                          | four spaces | one level; nested types add one                          |
| `blankLinesBetweenFields`   | 0..n                            | 0           | contiguous fields, or airy fields                        |
| `blankLinesBetweenMethods`  | 0..n                            | 1           | method separation                                        |
| `blankLinesBetweenSections` | 0..n                            | 1           | fields / constructors / methods / nested types           |
| `annotationLayout`          | `preserve`, `compact`, `expand` | `preserve`  | copied source text, one line, or JavaPoet-like expansion |
| `wrapLongLines`             | boolean                         | false       | no printer reflow by default                             |

`preserve` means the annotation you wrote is the annotation the file contains. **LF is the default
for every generated file**, on every operating system; CRLF is an explicit opt-in style, so a project
that wants it asks for it and `--check` follows the same setting. Generated files are never written
with the platform separator.

#### The default baseline, concretely

"I want a good new baseline" is a requirement, so the default style is specified rather than left to
the writer's mood:

- **Encoding and endings:** UTF-8, LF, exactly one final newline, no trailing whitespace, no tabs.
- **Indentation and braces:** four spaces per level; opening brace on the same line; `} else {` on one
  line; no aligned-column padding.
- **Imports:** one sorted block, no wildcard imports, no unused imports, no `java.lang.*` imports, one
  blank line after the block.
- **Type layout:** static constants, instance fields, constructors, accessors, remaining methods, then
  nested types. One blank line between sections and between methods; no blank line between fields.
- **Member order:** generated property members follow the **ordinal order**, not the interface order;
  fixed members (`ENTITY_CLASS`, `COLUMN_COUNT`, `_changeSetN`, ...) keep a fixed position.
- **Annotations:** copied accessor annotations keep the author's source text (each annotation on its
  own line, arguments exactly as written); generated annotations with a short single value are one
  line (`@JsonSerialize(using = X.class)`); an annotation that would exceed 120 columns breaks one
  member per line, deterministically.
- **`@Override`:** always the first annotation on an overriding member.
- **Javadoc:** the generated type has one short Javadoc with `{@link <Entity>}`; no generated
  placeholder comments, no "TODO", no per-line generation markers beyond the two-line header.
- **Line width:** soft 120 columns; no automatic reflow; an over-long parameter list breaks one
  parameter per line.
- **No cosmetic churn:** a second pass with the same inputs and style writes byte-identical files.

The knobs above can change spacing for a project that wants a different look, but the default is the
baseline the golden tests pin, and `--check` compares against it.

A fragment of the default baseline:

```java
private Long id;
private List<String> name;
private int age;

@Override
@Column(name = "user_id")
public Long getId() {
    return id;
}

@Override
public List<String> getName() {
    return name;
}
```

Contrast with the current JavaPoet output, where the same annotation is three lines and every field is
separated by a blank line. The new baseline is the readable one; the golden files pin it.

### 8.5 Tests

- `JavaSourceWriterTest`: exact bytes for `blank(0/1/2)`, `lf`/`crlf`, one final newline, no trailing
  whitespace.
- `FormattingGoldenTest`: one small entity's complete output, byte-for-byte with the chosen style.
- `AnnotationPreservationTest`: `@Column(name = "x")` and `@JsonSerialize(using = X.class)` stay on
  one line; a deliberately multi-line annotation stays multi-line.
- `IdempotenceTest`: a second pass changes no bytes.
- `write()` fix test: a known `JavaFile` renders with LF/CRLF and the expected final newline.

## 9. Ordinal ledger and cooperative codegen

### 9.1 Why ordinals are a persisted layout

A property's ordinal is written into generated code and survives there:

- `ColumnMeta.ordinal()` and the `_columnArray` index;
- `getValue(int)` and `setValue(int, Object)` switch cases;
- `_changeSetN` bit `1L << ordinal`;
- the Mongo `decode` switch;
- the row-reader position `ordinal + 1` in `fromResultSet`.

Inserting an accessor in the middle of an interface and regenerating today renumbers every later
property, silently changing every one of those. The new output is a fresh baseline, but it must not
repeat that on the next generation.

### 9.2 The ledger

When a ledger directory is supplied (`--ledger-out`, or `<metadata-out>/ledger` when only
`--metadata-out` is given), `<Entity>.ledger.json` carries:

```json5
{
  format: 1,
  entity: "com.example.User",
  ledger: {
    version: 1,
    fields: [
      { name: "id", ordinal: 0 },
      { name: "name", ordinal: 1 },
      { name: "age", ordinal: 2, retired: false }
    ]
  }
}
```

Rules:

1. **Bootstrap:** no ledger â†’ assign declaration order once, report `ledger_bootstrapped`.
2. **Existing field:** keep its ordinal, whatever the interface order says.
3. **New field:** append after `max(ordinal)`, in declaration order, report `field_appended`.
4. **Reordered field:** the ordinal does not move; declaration order is not the ledger order.
5. **Removed field:** the ledger keeps the slot as `retired: true`; the pass **fails** with
   `field_removed` unless `--allow-reorder`, because the generated arrays and switch cases need a
   slot for the old ordinal. `--allow-reorder` regenerates densely and reports `ordinal_renumbered`
   plus the migration list.
6. **Rename:** a new field (append) plus a retired old one; report both.

`Meta`, `Immutable`, `Update`, `getValue`, `setValue`, change bits and the Mongo switch iterate
**ordinal order**, not interface order. That is the post-port invariant.

The ledger is optional at the CLI level but is the **only** way to keep ordinals stable. Without
`--metadata-out` a pass starts from declaration order every time; with it, the previous ordinals are
the baseline. Â§ 9.3 details what the option writes, the failure modes, and why the directory should be
tracked.

### 9.3 `--metadata-out` and `--ledger-out` in detail

Both options are optional and neither changes the generated `.java`. They answer two different
questions:

- **`--metadata-out <dir>`** writes the pass's **model and audit trail**: what was seen, where it came
  from, which options produced which artifacts. It is derived output and can be ignored by git.
- **`--ledger-out <dir>`** writes the **ordinal contract**: the only place a field's ordinal survives
  from one pass to the next. It is small and stable, and it should be tracked. If it is omitted, the
  ledger is written under `<metadata-out>/ledger`; if both are omitted, there is no previous ordinal
  state and each pass falls back to declaration order.

Splitting the two is what lets a project ignore the churny metadata (line numbers, paths) while
tracking the tiny, semantically important ledger.

#### What they write

`--metadata-out`:

- **One file per entity**, under a path derived from the entity's fully qualified name:
  `com/example/User.metadata.json`. The FQN path keeps two `User` types in different packages from
  colliding; a flat `<SimpleName>.metadata.json` is not enough.
- **A `manifest.json`** listing the entity FQNs, their metadata files, source paths and artifact
  names. It answers "which entities did the last pass see?" and lets the next pass report orphans.
  It carries the generator version and the entity list, **no timestamp**, so a pass over unchanged
  input is byte-identical. Volatile run data (time, counts) is not written here; a separate
  `--run-record` can carry it.
- **No source text, no method bodies, no bytecode.** Names, paths, type spellings and options only.

`--ledger-out`:

- **One small file per entity**, `<fqn-path>/<SimpleName>.ledger.json`, containing the entity FQN and
  the ledger fields. No paths, no line numbers, no options, no source text â€” so a source edit does
  not churn it, and a clone that tracks it keeps the ordinals.

If `--metadata-out` is omitted but `--ledger-out` is given, the pass still reads and writes ledgers by
FQN; it simply has no manifest, so it cannot report an orphaned ledger. Use metadata when a full audit
is wanted, and the ledger when only the contract matters.

The per-entity metadata file is the model (abridged here; the full schema, with relations and
locations, is Â§ 10.2):

```json5
{
  format: 1,
  entity: "com.example.User",
  kind: "interface",
  source: "src/main/java/com/example/User.java",
  markerLine: 12,
  table: "user_table",
  options: { genJson: false, genMeta: true, genBuilder: false, genUpdate: false,
             genVisitor: false, genSql: false, genMongo: false },
  properties: [
    { name: "id", ordinal: 0, type: "java.lang.Long", getter: "getId",
      column: "user_id", transient: false, keepRest: false, required: false },
    { name: "name", ordinal: 1, type: "java.util.List<String>", getter: "getName",
      column: "name", transient: false, keepRest: false, required: false }
  ],
  artifacts: [
    { name: "UserImmutable", kind: "class", file: "src/main/java/com/example/UserImmutable.java", generated: true },
    { name: "UserMeta", kind: "class", file: "src/main/java/com/example/UserMeta.java", generated: true }
  ]
}
```

The ledger file is the contract:

```json5
{
  format: 1,
  entity: "com.example.User",
  ledger: {
    version: 1,
    fields: [
      { name: "id", ordinal: 0 },
      { name: "name", ordinal: 1 }
    ]
  }
}
```

`properties` is the model **this** pass read. `ledger.fields` may contain a `retired: true` entry for
a field no longer in `properties`, and it is the list the next pass reads first.

#### The two-pass example

1. **First pass:** fields `id, name, age`; no previous metadata. The pass writes the ledger
   `idâ†’0, nameâ†’1, ageâ†’2` and reports `ledger_bootstrapped`.
2. **Add `email` between `name` and `age`:** the next pass reads the ledger, keeps `id=0`,
   `name=1`, `age=2`, and gives `email` the next ordinal **3** (not 2). It reports `field_appended`.
   The generated `getValue(int)` cases are 0, 1, 2, 3; `age` stays at 2.
3. **Remove `name`:** the pass fails with `field_removed`; the ledger keeps `name` as a retired slot.
   With `--allow-reorder` it renumbers densely in declaration order (`id=0, age=1, email=2`) and reports
   `ordinal_renumbered` plus the migration list. That is an explicit, reviewable operation, not a
   silent one.

#### Failure modes, and why they fail safe

- **No metadata directory:** `ledger_absent`; ordinals come from declaration order. Fine for a
  one-shot or a deliberate rebaseline, not for steady state.
- **File unreadable/corrupt:** `ledger_unreadable`; the pass writes nothing for that entity and does
  not bootstrap over the file. A corrupt ledger is never "treated as fresh", because that is exactly
  how ordinals silently move.
- **FQN listed in the manifest but its file is missing:** `ledger_missing`; refuse to guess. A
  genuinely new entity is absent from the manifest, so it bootstraps normally.
- **Entity removed from the source:** its metadata/ledger file stays; the manifest no longer lists
  it. The pass reports `orphan_metadata` and does **not** delete a tracked ledger automatically. A
  future `--prune-metadata` can be an explicit operation.
- **Entity renamed:** the new FQN gets a new file and a fresh ledger; the old one is an orphan. The
  pass reports both; move the ledger by hand if the ordinal history should follow the rename.
- **Two source roots declare the same FQN:** a hard error; one pass must not pick a winner.

Writes are atomic (temporary file, then move), so a failed pass leaves the previous metadata intact.
Keys are sorted and the ledger carries no timestamps, so two passes over unchanged input produce
identical ledger bytes.

#### Tracking: metadata is derived, the ledger is a contract

This is the decision that makes or breaks D4. Under DEC-026, `.jcodebuddy/metadata/` is derived and
ignored, and a ledger that lives only there **does not survive a fresh clone** â€” a new checkout would
bootstrap again and renumber.

- **Recommended split:**
  - `--metadata-out .jcodebuddy/metadata/hipster-sql` â€” derived, ignored, churns with source lines
    and paths;
  - `--ledger-out .jcodebuddy/conf/hipster-sql` â€” tracked, small, stable, and the only record of
    ordinals.
- **Single-directory alternative:** point `--metadata-out` at a tracked directory such as
  `.jcodebuddy/conf/hipster-sql/` and accept that the full metadata (line numbers, paths) is also
  committed and churns.
- **No tracked ledger:** accept that a fresh checkout rebaselines; use metadata only for the current
  checkout's audit. Valid for a one-shot generation, but then D4's stability is a per-checkout
  property, not a project contract.

The plan recommends the split and includes the ledger in the first delivery: the first pass writes
the baseline ledger, and the project commits `.jcodebuddy/conf/hipster-sql/`. If the ledger is not
committed, `--check` can still verify the generated `.java`, but it cannot protect an ordinal from
moving.

#### Interaction with the other flags

| Flag              | Effect                                                                                             |
| ----------------- | -------------------------------------------------------------------------------------------------- |
| `--metadata-out`  | writes the derived model + manifest; independent of the ledger.                                    |
| `--ledger-out`    | writes the tracked ordinal contract; defaults to `<metadata-out>/ledger` when omitted.             |
| `--check`         | renders into a temp tree and compares `.java` plus whichever of metadata/ledger was requested; a ledger difference is a failure. If `--run-record` is used, that file is excluded. |
| `--allow-reorder` | permits the dense renumbering and writes the new ledger; without it, a removal/reorder is refused. |
| `--source-root`   | scopes what is read; ledger/metadata are written per FQN, so several roots share one directory safely. |
| `--java-out`      | independent; metadata describes the model, not the output path, so it can live outside the source tree. |
| `--packages`      | restricts emission; metadata/ledger for skipped entities is not written, and the manifest reflects the pass. |

Without either metadata or ledger output, `--check` compares only the generated source. That is a
weaker gate, and the plan treats it as an explicit choice rather than a default.

### 9.4 Cooperative codegen

The generated files are new, so there is no old file to preserve. For future edits:

- `enabled:false` in the two-line header: the pass leaves the file untouched and reports it.
- A user-added member in an otherwise generated class is preserved by shape (DEC-020); a change to a
  generated member is reported.
- `CooperativeCodegen.reconcileMembers` from `hipster-entity-tooling` is the reference implementation;
  reusing it means promoting it to JCodeBuddy (Â§ 15) or, if the owner prefers no extra module in this
  step, implementing the same shape check inside `hipster-sql-tooling` and reporting the duplication. The plan
  recommends promotion only if a second project needs it; for this step, a small in-module member
  check is acceptable because `processor` is explicitly scoped to this one project.

Because the output is a new baseline, this phase can land **after** the semantic parity phase; it is
not a migration blocker.

## 10. Headers, metadata and naming

### 10.1 DEC-021 header

Every generated `.java` starts with the two-line header above `package`:

```java
// @generated file hr.hrg.hipster.processor.HipsterSqlGenerator â€” Immutable implementation for the User entity.
// {enabled:true, blockMarker: "implicit"}
package com.example.entities;
```

The first line is the DEC-035 file marker; the second is the pinned JSON5 config. `enabled:false` is
the per-file opt-out. A malformed header is a diagnostic and fails safe toward "generated".

### 10.2 Entity metadata: the UI/project-understanding model

`--metadata-out` is the pass's **entity model**, not only a log. It is the input a renderer â€” Bun,
`jsx6`, or a future entity browser â€” reads to build project-understanding UI: an entity reference
page, a field/ordinal browser, an artifact/dependency drill-down, a link into the source. The Java
generator never emits HTML; it emits this JSON and a JavaScript renderer owns presentation (DEC-027).

The document is stable, versioned and contains **no source text**:

```json5
{
  format: 1,
  entity: "com.example.entities.User",
  package: "com.example.entities",
  kind: "interface",
  source: "src/main/java/com/example/entities/User.java",
  markerLine: 15,
  sourceHash: { algo: "wyhash64", normalize: "lf", value: "3f2c8d91a4b7e601" },
  table: "user_table",
  options: { genJson: false, genMeta: true, genBuilder: false, genUpdate: false,
             genVisitor: false, genSql: false, genMongo: false },
  supertypes: ["com.example.entities.Auditable"],
  inputs: ["com.example.entities.User", "com.example.entities.Auditable"],
  properties: [
    { name: "id", ordinal: 0, type: "java.lang.Long", typeFqn: "java.lang.Long",
      getter: "getId", setter: "setId", column: "user_id", table: "user_table",
      required: false, transient: false, keepRest: false, readOnly: true,
      annotations: [
        { name: "javax.persistence.Id", source: "@Id" },
        { name: "javax.persistence.Column", source: "@Column(name = \"user_id\")" }
      ],
      locations: { accessor: 18, annotation: 16, field: 4, setter: 24, ordinal: 41 } }
  ],
  artifacts: [
    { name: "UserImmutable", kind: "class", file: "src/main/java/com/example/entities/UserImmutable.java",
      line: 1, generated: true, inputs: ["com.example.entities.User"] },
    { name: "UserMeta", kind: "class", file: "src/main/java/com/example/entities/UserMeta.java",
      line: 1, generated: true, inputs: ["com.example.entities.User"] }
  ]
}
```

What each part is for:

| Key                                                               | UI/tooling use                                                                              |
| ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `format`                                                          | schema version; a consumer refuses an unknown version rather than half-reading it           |
| `entity`, `package`, `kind`, `source`, `markerLine`               | identity and a navigable source link                                                        |
| `sourceHash`                                                      | cache/staleness check; WyHash64 over LF-normalized content (the class-index convention, reusing `jcodebuddy-core`'s `ContentHash`/`Wyhash64`) |
| `options`, `table`                                                | why these artifacts exist, and which database table they bind                               |
| `supertypes`, `inputs`                                            | relation/dependency views; every entry is an FQN                                            |
| `properties[].ordinal`, `type`, `column`, flags, `annotations`    | field table/detail; annotations are FQN + source text, so a renderer need not re-parse Java |
| `locations`                                                       | line anchors for the accessor, its annotation, the generated field, setter and ordinal arm; absent roles are omitted |
| `artifacts[].name`, `kind`, `file`, `line`, `generated`, `inputs` | artifact inventory and â€œwhich generated type belongs to this entityâ€, with links        |

Rules that make it safe to consume:

- **FQNs for type references, module-relative paths for files.** No absolute paths, no `..`, no
  surrogate ids. An IDE rename updates an FQN; the next pass rewrites the document (DEC-029).
- **No file text.** Names, spellings, flags, ordinals and locations only. A metadata file never
  leaks a source body.
- **Deterministic.** Keys are sorted; `properties` are in ordinal order and `artifacts` by name; no
  timestamp in the per-entity file. Two passes over unchanged input are byte-identical.
- **Versioned.** A breaking schema change bumps `format`; consumers ignore unknown keys within a
  known format, but refuse an unknown format.
- **Derived, not a second source of truth.** The committed `.java` is the source; metadata is
  regenerable. A UI reads it after a pass, or a project commits it if it wants an offline view.
- **Links are verified by the renderer.** The metadata records the candidate line; the renderer
  checks that the file/line exists before rendering a link and drops an unverifiable one (DEC-027/028).

The generated `.java` is unaffected by whether metadata is written: metadata is for the tools around
it. A future entity browser/report is out of scope here, but this schema is the model it would read.

The ordinal ledger is a separate file written by `--ledger-out` (Â§ 9.2/Â§ 9.3), so a project can ignore
the churny metadata while tracking the contract.

### 10.3 Naming contract (DEC-022)

| Emitted name                    | Derivation                         | Kind                 | IDE contract                                                             |
| ------------------------------- | ---------------------------------- | -------------------- | ------------------------------------------------------------------------ |
| `<E>Immutable`                  | `<E>` + suffix                     | refactor-sensitive   | class Javadoc `{@link <E>}`; header names the generator                  |
| `<E>Builder`                    | `<E>` + suffix                     | refactor-sensitive   | same                                                                     |
| `<E>Update`                     | `<E>` + suffix                     | refactor-sensitive   | same                                                                     |
| `<E>Meta`                       | `<E>` + suffix                     | refactor-sensitive   | same                                                                     |
| `<E>Visitor`                    | `<E>` + suffix                     | refactor-sensitive   | same                                                                     |
| `AllEntitiesInPackage`          | fixed, per package                 | refactor-insensitive | explicit `register(...)` call                                            |
| `get<X>`/`set<X>`/`has<X>`      | accessor name                      | refactor-sensitive   | the entity's interface methods; implementations make the IDE follow them |
| `_changeSetN` bit               | ordinal                            | refactor-insensitive | ledger; not a Java name a user renames                                   |
| `ENTITY_NAME`, `COLUMN_ARRAY_*` | explicit strings                   | refactor-insensitive | wire/database labels                                                     |
| `@JsonProperty("x")`            | explicit wire name                 | refactor-insensitive | must survive a Java rename                                               |
| column/table names              | `@Column`/`@HipsterColumn`/default | refactor-insensitive | database contract                                                        |

The `processor` README publishes this table; a change to a derived suffix is a DEC-022 change.

## 11. Build, CLI and tests

### 11.1 POM

`hipster-sql/hipster-sql-tooling/pom.xml`:

- parent `hr.hrg.jcodebuddy:jcodebuddy-parent` (so `maven.compiler.release=25` is inherited);
- dependencies: `jcodebuddy-core`, `jcodebuddy-generated`, `rewrite-java`, `rewrite-java-25`,
  and the external `hr.hrg:hipster-sql:1.3.4` as `provided` (annotation types for fixtures and,
  later, `ColumnMeta`/`EntityMeta` names);
- test dependencies: JUnit 5 (`junit-jupiter-api`/`engine`); H2/Jackson/Mongo arrive only with the
  phases that exercise those generated paths;
- no JavaPoet, no annotation-processing API.

`sql/pom.xml` is untouched. The external `processor` is not a dependency and is not built by this
module.

### 11.2 Test layout

Implemented in this slice:

```
hipster-sql/hipster-sql-tooling/src/test/
  java/hr/hrg/hipster/sql/tooling/
    GeneratorSmokeTest.java       discovery/model, Visitor, Immutable/Update/Builder shape + behavior,
                                  CLI write+check and optional artifacts, javac compile,
                                  metadata/ledger write+check, append-only insertion, skipped accessor,
                                  supertype metadata, refused removal + renumber
  resources/fixtures/basic/src/demo/
    User.java                    getters, boolean forms, skip, keepRest
    Account.java                 a second entity
```

Planned, as the corresponding emitters/features land:

```
    ModelParityTest.java          new model vs the frozen oracle model
    ImmutableBehaviorTest.java    getters, getValue, equals/hash, initial values
    UpdateBehaviorTest.java       setters, change sets, isEmpty/isChanged/setChanged
    BuilderTest.java              fluent methods, build, copy constructor
    MetaSqlTest.java              columns, ordinals, fromResultSet (H2)
    MetaJsonTest.java             serializer output
    VisitorTest.java              already covered by GeneratorSmokeTest
    JavaSourceWriterTest.java     exact EOL/blank-line/annotation bytes
    LedgerTest.java               bootstrap, append, reorder, remove, rename
    MetadataSchemaTest.java       format/keys/FQN/no-source-text/determinism
    MetadataLocationsTest.java    accessor/annotation/field/setter/ordinal line anchors
    MetadataConsumerTest.java     read as a UI would: entity -> fields -> artifacts -> links
  resources/fixtures/
    annotated/ ...                @Id/@Column/@Transient/@JsonIgnore/@HipsterColumn
    generics/ ...                 List<T>, arrays, nested types
    inheritance/ ...              annotated super-interface, cycle fixture
    package-options/ ...          package-info defaults
    nested/ ...                   nested entity package suffix
    all-entities/ ...             two entities in one package
    options/ ...                  builder/update/visitor/json/sql/mongo on and off
    expected/ ...                 oracle output/model, frozen from the legacy processor
```

Fixtures are self-contained inputs under the module; no other subproject is a test dependency. Where a
shape comes from the old project's entities, it is re-expressed as a small fixture with the same
structural feature.

The approved `sql` fix carries its own regression test in the external `sql` module
(`sql/src/test/java/hr/hrg/hipster/sql/HipsterSqlUtilAnnotationTest.java`) when that fix lands; it is
the only test file added outside this checkout.

### 11.3 The oracle

The legacy `processor` is not deleted, so its output can be frozen at any point:

1. run `javax.tools.JavaCompiler` with the external legacy processor over a fixture source root,
   `-proc:only` or a normal compile into a temp `-s` directory;
2. store the generated `.java` and a derived **model JSON** (entity name, table, options, properties,
   ordinals, types, annotations, artifacts) under `fixtures/<case>/expected/`;
3. the new pass must match the oracle **structurally** (members, signatures, supertypes, annotations,
   option flags) and behaviorally (the generated code compiles and the behavior tests pass), not
   byte-for-byte;
4. keep the oracle small enough to review; it is a test resource, not a second implementation.

The legacy processor remains available as a reference; it is not on the new module's classpath and is
not part of the new build.

### 11.4 Compile harness

`CompileGeneratedTest` uses `ToolProvider.getSystemJavaCompiler()` with:

- `-proc:none` (no processor can run);
- `-source 8 -target 8` (or the project's `source/target 8`; verify at phase 0 that the JDK running
  the tests still supports that level â€” if it does not, run the compile harness with the JDK the
  project already uses for `sql` and record the JDK in the test);
- classpath = `processor/target/classes` (for the runtime annotations), the `hipster-sql` artifact,
  and the optional jars the fixture's options need;
- a `DiagnosticCollector` that fails the test on any error and prints the generated source on failure.

This is the check that the generated code is Java 8 source and compiles against the `sql` runtime
(with its approved annotation fix).

## 12. Migration phases

Estimates are for one developer familiar with the code; `Meta` is the bulk.

### Phase 0 â€” Baseline, oracle, decisions (1â€“2 days) â€” **partial**

- Freeze the legacy processor's output/model for the fixture set as the oracle (Â§ 11.3). The basic
  fixture is in place; the frozen oracle is still to be captured.
- D5 is confirmed (Java 25 minimum); JDK 25.0.3 and Maven were verified locally. The approved
  one-line `HipsterSqlUtil.annotation` fix is applied and its regression test
  (`HipsterSqlUtilAnnotationTest`) passes in the `sql` module.
- **Exit:** the basic fixture exists, the oracle is captured, and the scope decisions are accepted.

### Phase 1 â€” Module and build skeleton (2â€“3 days) â€” **done**

- `hipster-sql/hipster-sql-tooling` created, added to the root reactor, with the parent POM's
  Java 25 release, JCodeBuddy/OpenRewrite dependencies, `hipster-sql` provided, and JUnit 5.
- `mvn -o -f hipster-sql/hipster-sql-tooling/pom.xml test` runs on JDK 25; 29 tests green.
- **Exit:** the module builds and tests on JDK 25.

### Phase 2 â€” Model and discovery (4â€“6 days) â€” **partial**

- Done: `EntityModel`/`PropertyModel`, top-level `@HipsterEntity` discovery, getter/boolean rules,
  `skip`/`keepRest`, import table, deterministic FQN sort, unreadable/nested divergences.
- Remaining: `TypeRef`/annotation model, annotated super-interfaces, `package-info` options,
  nested-entity FQN, option precedence, type name oracle, `--model-dump` vs the oracle.
- **Exit:** the model matches the oracle for every fixture, with differences only where Â§ 2.6 says
  the old behavior was wrong (documented).

### Phase 3 â€” Writer and small emitters (3â€“4 days) â€” **partial**

- Done: `JavaSourceWriter` (LF default, exact `blank(n)`, indent, single final newline),
  `ImportCollector`, `<E>Visitor`, CLI `--source-root/--java-out/--check/--version`, formatting and
  compile tests.
- Remaining: `<AllEntitiesInPackage>` (needs `<E>Meta` to exist first), golden byte test for the
  writer, annotation-preservation tests.
- **Exit:** exact newline/annotation tests, compile harness green.

### Phase 4 â€” Core emitters (8â€“12 days) â€” **partial**

- Done: `<E>Immutable` (fields, empty/all-args/copy constructors, getters, both `getValue` forms),
  `<E>Update` (change-set bits, setters, `isEmpty`/`isChanged`/`setChanged`, `setValue`),
  `<E>Builder` (fluent methods, `build`), and a minimal `<E>Meta` + `AllEntitiesInPackage` (columns,
  ordinals, `fromResultSet`/`visitResult`, implementation classes, registry), an optional direct
  Jackson serializer on `Immutable`, `Meta`'s Jackson mapper and Mongo `encode`/`decode`, all
  compiled at `--release 8` and exercised by reflection/H2/Jackson/Mongo where applicable.
- Done: `@Id` primary detection (first `@Id` wins), `equals`/`hashCode` on `Immutable`/`Update`
  keyed by it, and `Meta.getPrimaryColumn`/`entityGetPrimary`.
- Done: skipped-accessor pass-through: a `@HipsterColumn(skip = true)` method is kept out of the
  ordinal/columns/Visitor, while `Immutable` and `Update` still implement its getter (and metadata
  lists it under `skippedAccessors`).
- Done: accessor annotations are recorded as written and copied onto generated `Immutable`/`Update`
  getters (including a fully qualified spelling and its arguments).
- Done: initial values from `@HipsterColumn(initial=...)` and the `genAnnotations` proxy path
  (`.withAnnotations(annotation(...))`), backed by the applied `HipsterSqlUtil.annotation` fix and
  its passing regression test.
- Remaining: annotated-inheritance and package-level options.
- Behavior tests (getters, `getValue`, change sets, builder, `fromResultSet` with H2, JSON, visitor).
- **Exit:** all fixtures compile at Java 8 and behave like the oracle; no unexplained structural
  difference; the `sql` diff is exactly the approved fix + test.

### Phase 5 â€” Metadata, ledger, headers, `--check` (5â€“7 days) â€” **partial**

- Done: per-entity metadata (`format/entity/package/kind/source/sourceHash/table/properties/artifacts`),
  `manifest.json`, append-only ledger (`--ledger-out`, default `<metadata-out>/ledger`), bootstrap /
  append / retired-slot reporting, atomic writes, and `--check` over source + metadata + ledger.
- Done: 29 tests, including a field inserted between existing accessors keeping the old ordinals and
  the new one appended (age stays 2, nickname becomes 6).
- Remaining: options/locations in metadata, the UI consumer fixture, orphan/corrupt-ledger
  integration tests, and the golden metadata schema test.
- **Exit:** a second pass is byte-identical; a reordered interface does not move ordinals; `--check`
  compares `.java` + ledger + metadata; the ledger is stable across a metadata-only change; a
  metadata consumer can render an entity/field/artifact view without parsing Java.

### Phase 6 â€” Options, remaining artifacts, legacy handover (6â€“10 days) â€” **planned**

- `--packages`, `--classpath`, `--config`; the full option matrix; `AllEntitiesInPackage`; the
  remaining behavior tests.
- The external `processor` stays as the legacy reference/oracle; it is not deleted or edited by this
  port. Mark it deprecated in its own repository when the new tool is adopted.
- **Exit:** every planned artifact and option is implemented and tested; the legacy processor is
  documented as legacy, not as the build path.

### Phase 7 â€” Watch/incremental (optional, 3â€“5 days) â€” **planned**

- Add `--watch`/`--incremental` backed by the fs-meta daemon only if a consumer asks. No
  `WatchService`, no new watcher module.
- **Exit:** a save regenerates only affected entities; the generator's own writes do not re-trigger.

## 13. Verification

| Gate                                                                                          | What it proves                                                                           |
| --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| model dump vs the frozen oracle model                                                         | discovery, names, types, flags and options match                                         |
| `CompileGeneratedTest` (`javac`, `source/target 8`, `-proc:none`)                             | generated code compiles against `sql` without a processor                                |
| behavior fixtures (getters, `getValue`, change sets, builder, `fromResultSet`, JSON, visitor) | the generated code *works* the same, which is the parity requirement                     |
| structural diff vs the oracle (members/signatures/supertypes/annotations)                     | no accidental API change; formatting is the only expected difference                     |
| `JavaSourceWriterTest` + `FormattingGoldenTest`                                               | exact EOL, blank lines, annotation layout, no trailing whitespace                        |
| `IdempotenceTest` + `--check`                                                                 | committed output is reproducible                                                         |
| ledger tests (bootstrap, append, reorder, remove, rename)                                     | ordinals never move silently                                                             |
| metadata schema/locations/consumer tests                                                      | a UI can read entity/field/artifact/relations/links without parsing Java, and no document contains source text |
| metadata-vs-ledger split test                                                                 | a metadata-only change does not churn the ledger; a ledger change is a `--check` failure |
| annotation test                                                                               | copied getters carry the source annotations; `genAnnotations` proxies read written values and fill defaults correctly after the approved `sql` fix |
| `git diff -- sql`                                                                             | the `sql` diff is exactly the approved one-line fix + its regression test                |
| package/no-service test                                                                       | no `META-INF/services/javax.annotation.processing.Processor` in the jar                  |

The full repository gate is not run for this change. The implemented slice's gate is
`mvn -o -f hipster-sql/hipster-sql-tooling/pom.xml test` on JDK 25; 29 tests are green (discovery,
Visitor shape, Immutable/Update/Builder shape and behavior, Immutable Jackson serialization,
Meta/registry shape, Meta Jackson mapper + Mongo encode/decode, `fromResultSet`/`visitResult`
against H2, CLI write/check and optional artifact wiring, javac compile,
metadata/ledger write/check, append-only insertion, skipped-accessor semantics, refused removal + explicit renumbering). The `sql` runtime is not
built here; it is consumed from the local repository as `provided`. The plan reports the exact
commands and the JDK used.

## 14. Risks and mitigations

| Risk                                                         | Likelihood | Impact                                                                                   | Mitigation                                                                                 |
| ------------------------------------------------------------ | ---------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| **JDK 25 requirement for the generator**                     | Accepted   | A developer needs JDK 25 to run `processor`; it does not run inside an older `javac`     | D5 confirms Java 25 as JCodeBuddy's minimum; document it in the README and `--version`; generated code stays Java 8; the old annotation-processor artifact remains available if a Java 8 workflow is still needed |
| No real consumer in scope                                    | High       | Fixtures may miss a shape a real entity uses                                             | Build the fixture matrix from the processor's own branches (ID, transient, keepRest, generics, arrays, nested, inheritance, package options, all options); every branch gets a fixture |
| Textual type resolution misses a wildcard/ambiguous name     | High       | A skipped entity or a wrong import                                                       | Name oracle over `--classpath` + class index; `ambiguous_type`/`unresolved_type` reported; `typeAliases`; never guess |
| Unapproved `sql` drift                                       | Medium     | The runtime boundary opens                                                               | D7 allows exactly the one-line `annotation(...)` fix + its test; review `git diff -- sql`; anything else is rejected. The fix is covered by a proxy/default regression test. |
| Metadata schema drifts, or a UI starts re-parsing Java       | Medium     | The model stops being a stable contract; a second parser appears                         | Versioned `format`, FQNs + locations + relations, no source text, a metadata consumer test; the renderer reads JSON, never Java. |
| `sql`'s `javax.lang.model` dependency stays                  | Low        | Runtime carries processor-only API                                                       | Out of scope by D7; the new tooling computes prefixes itself and does not add to it        |
| Generated code modernizes accidentally                       | Low        | Breaks Java 8 consumers                                                                  | Compile harness at `source/target 8`; no `var`/records/switch expressions                  |
| Legacy processor drifts or is edited                         | Medium     | The oracle stops matching the reference                                                  | Do not edit the external processor; freeze its output as the oracle; keep it as the legacy reference |
| Formatting/newline changes are mistaken for behavior changes | Medium     | Review noise                                                                             | Structural + behavior tests are the gate; formatting tests pin the new baseline explicitly |
| JCodeBuddy API churn                                         | Medium     | `hipster-sql-tooling` stops compiling                                                    | Pin the JCodeBuddy version; the module is a consumer, not part of the engine               |
| Optional jars missing for `genJson`/`genMongo` fixtures      | Medium     | Compile matrix fails for the wrong reason                                                | Mark them `provided` in the POM and add them only to the tests that enable the option; report the missing jar clearly |

## 15. JCodeBuddy changes needed

**None are required for the first delivery.** The port is done entirely inside
`hipster-sql-tooling`; these are the pieces worth promoting only if a second consumer appears
(AGENTS Â§ 1.1 says promote, do not copy, when a second place needs it):

1. **`JavaSourceWriter`** â€” implemented in `hipster-sql-tooling` for this slice. If `hipster-entity`
   or a second generator needs it, promote it to `jcodebuddy-core` `engine/source` (next to
   `SourceSplicer`) and make both use the one implementation. Until then, a second copy would be the
   violation.
2. **`CooperativeCodegen`** â€” the `hipster-entity-tooling` implementation is the reference. For this
   step, a small in-module member check is enough because the generated files are a new baseline and
   there is no second consumer. Promote it only when a second project needs the same reconciliation.
3. **`GeneratorPreflight`** â€” the pattern is generic but the class is in `hipster-entity-tooling`.
   The new module can implement its own small version (or omit it while it is the only entry point).
4. **`SourceSplicer.withMembers`** â€” add a blank-line parameter only if the new module starts
   splicing into hand-written types. The port writes whole files, so it does not need this.
5. **`GeneratedCodeMarkers`** â€” use the header shape directly; the new module does not need the parser
   unless `--check` validates headers. A small header reader is sufficient.

Runtime changes are limited to the approved one-line `annotation(Class, Object...)` fix and its
regression test (D7). The `javax.lang.model` overload stays; no other runtime change is part of this
port.

## 16. Out of scope

- **`dao-test`, `mongo`, `test-nodep`, `ioc`** â€” not read, not migrated, not used as fixtures or gates.
- **Any `sql` change beyond the approved one-line annotation fix + test** â€” no other
  `HipsterSqlUtil`, `ColumnMeta`, `EntityMeta`, `EntitySource` or POM changes. The allowed diff is
  exactly the `annotation(Class, Object...)` value fix and its regression test.
- **Migrating existing generated files** â€” the new output is a new starting point; no byte parity, no
  preservation of old generated files, no hand-edit reconciliation of them.
- The old annotation-processing entry point (`AbstractProcessor`, `Filer`, `-A`, the service file) is
  **not ported**; it stays in the external legacy processor and is not part of the new module.
- Runtime API changes, `EntitySource` reflection removal, `javax.lang.model` cleanup.
- HTML reports, the entity browser, and a watch loop in the first delivery (`--watch` is optional
  phase 7).
- A general symbol-solving service; DEC-030 rejects it, and this plan stays text-first.
- Keeping the processor runnable on JDK 8/11/17; D5 accepts the JDK 25 tool requirement.
- Modernizing generated code beyond Java 8.

## Appendix A â€” Processor class â†’ new class

| Processor                                                | New                                                                             |
| -------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `HipsterDaoProcessor` (discovery/options/write/registry) | `HipsterSqlGenerator` + `SourcePass` + `EntityDiscovery` + `AllEntitiesEmitter` |
| `EntityDef`                                              | `EntityModel`                                                                   |
| `Property`                                               | `PropertyModel` + `TypeRef` + `AnnotationModel`                                 |
| `GenOptions`                                             | `GenConfig` (per entity) + `ProjectConfig` (CLI/config)                         |
| `GenImmutable`                                           | `ImmutableEmitter`                                                              |
| `GenBuilder`                                             | `BuilderEmitter`                                                                |
| `GenUpdate`                                              | `UpdateEmitter`                                                                 |
| `GenMeta`                                                | `MetaEmitter` + SQL/Jackson/Mongo/Visitor sections                              |
| `GenVisitor`                                             | `VisitorEmitter`                                                                |
| `HipsterProcessorUtil`                                   | `TypeRef` / `TypeNames` / `ImportCollector`                                     |
| `AnnotationDef` / `GenEnum` / `GenDelta`                 | deleted                                                                         |

## Appendix B â€” Option mapping

| Old                                                 | New                                                                            |
| --------------------------------------------------- | ------------------------------------------------------------------------------ |
| `-Ahipster_proc_jackson`                            | `defaults.genJson` / `--gen-json`                                              |
| `-Ahipster_proc_builder`                            | `defaults.genBuilder` / `--gen-builder`                                        |
| `-Ahipster_proc_annotations`                        | `defaults.genAnnotations` / `--gen-annotations` (off until the `sql` decision) |
| `-Ahipster_proc_visitor`                            | `defaults.genVisitor` / `--gen-visitor`                                        |
| `-Ahipster_proc_update`                             | `defaults.genUpdate` / `--gen-update`                                          |
| `-Ahipster_proc_mongo`                              | `defaults.genMongo` / `--gen-mongo`                                            |
| `-Ahipster_proc_mongo_skip_null`                    | `defaults.mongoSkipNull`                                                       |
| `-Ahipster_proc_mongo_use_field_name`               | `defaults.mongoUseFieldName`                                                   |
| `-Ahipster_proc_sql`                                | `defaults.genSql` / `--gen-sql`                                                |
| `-Ahipster_proc_column_meta_class`                  | `columnMetaClass`                                                              |
| `@HipsterEntity` booleans                           | same key on the type/package; highest precedence                               |
| `@HipsterColumn` / `@Column` / `@Id` / `@Transient` | unchanged markers, read by the pass                                            |

The CLI flag names are illustrative; the JSON5 keys are the stable contract. The exact flag surface
is `--version`'s job to print.

## Appendix C â€” Generated contracts

```java
public final class EImmutable implements E, IEnumGetter [, IDirectSerializerReady] {
    public EImmutable();
    public EImmutable(<all properties, ordinal order>);
    public EImmutable(E v);
    // accessors with copied annotations
    public final <T, X extends Key<T>> T getValue(X column);
    public final Object getValue(int ordinal);
    public boolean equals(Object o);       // when @Id
    public int hashCode();                 // when @Id
    public void serialize(JsonGenerator jgen, SerializerProvider provider) throws IOException, JsonGenerationException; // genJson
}

public class EBuilder extends EUpdate implements E [, IDirectSerializerReady] {
    public EBuilder();
    public EBuilder(E v);
    public EBuilder(<all properties>);
    public EImmutable build();
    public EBuilder <property>(<type> v);  // one per property
}

public class EUpdate implements E, IUpdatable [, IDirectSerializerReady] {
    protected long _changeSet0; ...;       // one per 64 properties
    protected <type> <property>;           // ordinal order
    // getters/setters, setValue, isEmpty/isChanged/setChanged, constructors, equals/hashCode
}

public class EMeta extends EntityMeta<E, ID, C, V>   // or MongoEntityMeta
        [implements WithMapper] {
    public static final Class<E> ENTITY_CLASS;
    public static final String ENTITY_NAME;
    public static final int COLUMN_COUNT;
    public final C<<boxed type>> <property>; // ordinal order
    public EMeta(HipsterSql hipster, int _ordinal);
    // copy methods, fromResultSet/visitResult, decode/encode, getImplClasses, ...
}

public interface EVisitor { void visit(<types...>); }

public class AllEntitiesInPackage {
    public static final Class<? extends IEntityMeta>[] ALL_META;
    public static final Class[] ALL_ENTITIES;
}
```

## Appendix D â€” Inventory (for sizing)

`processor/src/main/java` totals ~2,750 lines:

| File                        | Lines       |
| --------------------------- | ----------: |
| `GenMeta.java`              | 1097        |
| `HipsterDaoProcessor.java`  | 368         |
| `Property.java`             | 265         |
| `GenImmutable.java`         | 248         |
| `GenUpdate.java`            | 206         |
| `GenEnum.java`              | 187 (dead)  |
| `EntityDef.java`            | 107         |
| `GenOptions.java`           | 93          |
| `GenBuilder.java`           | 73          |
| `HipsterProcessorUtil.java` | 48          |
| `GenDelta.java`             | 26 (dead)   |
| `GenVisitor.java`           | 24          |
| `AnnotationDef.java`        | 17 (unused) |

Roughly a 2,000-line emitter/model rewrite plus a writer, ledger, fixtures and tests; the `Meta` half
is more than the other four artifacts combined.

## Appendix E â€” Decisions closed

**D3 is answered:** a freely formatted generated file set is acceptable as the new baseline, and the
baseline must be good to read â€” Â§ 8.4 defines it (LF, four-space indent, explicit blank lines,
preserved annotations, sorted imports, no trailing whitespace) and the golden tests pin it.
**D5 is answered:** all JCodeBuddy tooling has Java 25 as its lowest supported version, so
`processor` is a JDK 25 tool.
**D6 is answered:** LF is the default and canonical EOL; CRLF is opt-in.
**D7 is narrowed:** the one-line `HipsterSqlUtil.annotation(Class, Object...)` fix and its regression
test are approved.
**Ledger:** `--ledger-out` is part of the first delivery; the first pass writes the baseline contract
and the project tracks it. **Metadata** is also in the first delivery, because it is the model for
project-understanding/UI tooling (Â§ 10.2), not merely an audit log.

There are no open decisions in this plan.


