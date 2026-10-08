# hipster-entity-example

The canonical, **generated** example for `hipster-entity`. It is the regression artifact the whole
plan is measured against: the field enums, records, builders and tracking builders under
`src/main/java/.../{person,paymentMethod}/entity/` are generator output, committed to git because the
committed source is the source of truth (DEC-019 / `AGENTS.md` § 1).

## What is generated, and what is hand-written

| Path                                                                    | Owner                                                             |
| ----------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `person/entity/*_`, `person/entity/*Builder*`, `person/entity/*Record*` | **generated** (`--packages` filter)                               |
| `paymentMethod/entity/*PaymentMethod_` (four subclasses)                | **generated**                                                     |
| `paymentMethod/entity/PaymentMethod_.java`                              | **hand-written** — the polymorphic root's discriminator constant and permitted subtypes live here (§ 9/4.9) |
| `paymentMethod/entity/PaymentMethod.java`                               | hand-written marker (not a view; deliberately carries no `@View`) |
| `person/iface/Person.java`, `person/record/Person.java`                 | hand-written documentation samples, included by `architecture/materialization-levels.md` via `<!-- INCLUDE -->`; excluded from generation |
| `example/` (`Auditable`, …)                                             | hand-written field sources, outside the generation filter         |

## How generation is wired

`pom.xml` declares `exec-maven-plugin` executions for
`hr.hrg.hipster.entity.tooling.EntityMetadataGenerator` and
`GeneratorPreflight`, but **neither carries a `<phase>`**: the generator here is a
side tool, not a build step. There is no annotation processing and no compile hook,
so `mvn compile`, `mvn package` and `mvn test` only ever compile the committed
generated source — they never regenerate it.

A pass is run on demand, or continuously in watch mode, with:

```
bun scripts/gen.js            regenerate
bun scripts/gen.js with-tests regenerate and run the entity test set
bun scripts/gen.js watch      regenerate on every save (Ctrl+C to stop)
```

`bun scripts/gen.js` compiles the tooling in the reactor, exports the classpath
Maven resolved for those modules (`dependency:build-classpath`), and runs the
generator with `java -cp`. It needs **no `mvn install` and builds no jar**. See
[`codebuddy.md`](codebuddy.md) § 3 for why `mvn exec:java` is not used.

Only the **first** of those matters for JCodeBuddy to work: a pass is the whole
tool. `watch` is that same pass driven by a file watcher, so generated output keeps
up while you edit. An IDE **sidecar / LSP** — in-editor diagnostics and code
actions — would sit *on top of* watch mode as a user-friendliness expansion; it is
not needed here and nothing in this module depends on one.

The generator's flag surface is unchanged, wherever it is invoked from:

```
<sourceRoot>            src/main/java
<outputDir>             .jcodebuddy/metadata/entity    (the JSON report; ignored by git)
--java-out              src/main/java                  (regenerate the committed source in place)
--packages              hr.hrg.hipster.entityexample.person.entity,
                        hr.hrg.hipster.entityexample.paymentMethod.entity
--validate              run the entity rules before writing; print and continue
--dto-projections       also emit <View>Json for views marked @View(dto = true)
                        (DEC-003/DEC-007 read projections; see § "Read projections" below)
--run-record            .jcodebuddy/metadata/entity/generation.json
```

The report goes to this module's own `.jcodebuddy/`, the marker directory that says "this module
uses JCodeBuddy" — the layout and its track policy are documented in
[`.jcodebuddy/README.md`](.jcodebuddy/README.md). Generated `.java` deliberately does **not** go
there: it stays under `src/main/java` as committed, IDE-navigable source (DEC-019 / `AGENTS.md` § 1).

Before the `--java-out` flag existed, generated Java landed in the *metadata* directory. That is the
one flag an adopter most often omits, and the reason the getting-started guide documents it in its
flag table.

**`classpathScope` must be `compile`** for the `exec:java` goal form. `exec:java` defaults to the
runtime scope, which excludes `provided` dependencies — and the tooling is `provided` so it never
becomes a transitive runtime dependency of an application (`AGENTS.md` § 2). Without
`<classpathScope>compile</classpathScope>` the generator is not on the exec classpath at all.
(`bun scripts/gen.js` does not use `exec:java`, so this does not apply to it.)

**Turning generation off** is no longer a thing you do: generation never runs during a build, and the
old `-Djcodebuddy.entity.codegen.skip=true` property has been removed along with the lifecycle
bindings it skipped. Compile, and the committed output is used as-is:

```
bun scripts/mvn-jdk25.js -o -pl hipster-entity-example -am compile
```

**Generation is fail-safe about the example.** `ExampleRegenerationTest` copies the committed tree into
a temp directory, runs the pass in place there, and asserts the result is byte-identical to what is
committed — so a generator change that alters the example fails the tooling's tests instead of quietly
rewriting it. It calls the generator API directly, so it never depended on the removed Maven binding.
Regenerate and commit in the same change when that test goes red.

## Reading the module as a page

```
bun scripts/entity-html/index.js
```

renders `.jcodebuddy/metadata/entity/index.html` from the metadata JSON the last pass wrote: every
entity, every artifact generated from it, and every field clickable through to the exact source line
of every artifact — the accessor in the view, the constant in the field enum, the setter and field in
each builder, the record component, the ordinal switch arm, the `@FieldSource` line. Open it in
IntelliJ with the plugin's WebView Explorer tool window (right-click the file in the Project view →
**Open in WebView Explorer**).

The page is Bun JavaScript reading the JSON; the Java generator does not emit HTML, and every link it
writes is verified against the file and line it points at (DEC-027). `bun scripts/gen.js` renders it as
the last step of a pass — [`codebuddy.md`](codebuddy.md) § 3.10 has the details, and
[`.jcodebuddy/context/entity-html-index.md`](.jcodebuddy/context/entity-html-index.md) records what
this module expects it to show.

## Read projections (DEC-003 / DEC-007)

`PersonDto` is the worked example of the projection + DTO marker pattern (plan step 6.5): a read
contract for a SQL or NoSQL query result whose purpose is to reach JSON without building the entity.
It carries `@View(dto = true)`, and the committed
[`PersonDtoJson`](src/main/java/hr/hrg/hipster/entityexample/person/entity/PersonDtoJson.java) is what
the generator emits for it: one compiled field write per field, on the view's own accessors —

```java
gen.writeNumberProperty("id", source.id());
gen.writeStringProperty("firstName", source.firstName());
```

— so there is no reflection and, unlike every other generated serializer here, **no positional array**
in between. The ordinal serializers walk `ViewReader.get(ordinal)`, which a SQL row or a Mongo document
is not; this path goes straight from the result to the response.

Two conditions enable it, and neither alone emits anything: the pass carries `--dto-projections` (it is
in this module's generator invocation, and in the POM's `exec:java@hipster-entity-generate`), **and** the
view carries the marker. A project that never passes the flag receives no Jackson-importing writer for
any view; a view with no marker gains nothing from the flag. The marker is an annotation attribute
rather than a name suffix so a rename refactor cannot silently change whether a view is a projection.

## Running the demo

```
bun scripts/run-demo.js
```

It builds and runs `hr.hrg.hipster.entityexample.person.PersonDemo`, which prints six sections:
a row array becomes a read view; the view serializes to JSON; a tracking builder records one change;
the changed fields with the caller's own comparison; the change set as JSON; and a no-op write
changing nothing. There is **no SQL leg** — SQL generation is the tooling's draft/opt-in feature
(`--adapters`, off by default), and this example does not enable it.

`PaymentMethodController` is the polymorphic half: it dispatches on the generated discriminator
constants with a direct-call `switch` (DEC-019 — no reflective dispatch), and the base
`PaymentMethod_` enum it reads stays hand-written.
