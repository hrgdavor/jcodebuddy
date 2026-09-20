# hipster-entity-example

The canonical, **generated** example for `hipster-entity`. It is the regression artifact the whole
plan is measured against: the field enums, records, builders and tracking builders under
`src/main/java/.../{person,paymentMethod}/entity/` are generator output, committed to git because the
committed source is the source of truth (DEC-019 / `AGENTS.md` § 1).

## What is generated, and what is hand-written

| Path | Owner |
|---|---|
| `person/entity/*_`, `person/entity/*Builder*`, `person/entity/*Record*` | **generated** (`--packages` filter) |
| `paymentMethod/entity/*PaymentMethod_` (four subclasses) | **generated** |
| `paymentMethod/entity/PaymentMethod_.java` | **hand-written** — the polymorphic root's discriminator constant and permitted subtypes live here (§ 9/4.9) |
| `paymentMethod/entity/PaymentMethod.java` | hand-written marker (not a view; deliberately carries no `@View`) |
| `person/iface/Person.java`, `person/record/Person.java` | hand-written documentation samples, included by `architecture/materialization-levels.md` via `<!-- INCLUDE -->`; excluded from generation |
| `example/` (`Auditable`, …) | hand-written field sources, outside the generation filter |

## How generation is wired

`pom.xml` binds `exec-maven-plugin` to `generate-sources`, invoking
`hr.hrg.hipster.entity.tooling.EntityMetadataGenerator` with its ordinary CLI flags:

```
<sourceRoot>            src/main/java
<outputDir>             .jcodebuddy/metadata/entity    (the JSON report; ignored by git)
--java-out              src/main/java                  (regenerate the committed source in place)
--packages              hr.hrg.hipster.entityexample.person.entity,
                        hr.hrg.hipster.entityexample.paymentMethod.entity
--validate              run the entity rules before writing; print and continue
```

The report goes to this module's own `.jcodebuddy/`, the marker directory that says "this module
uses JCodeBuddy" — the layout and its track policy are documented in
[`.jcodebuddy/README.md`](.jcodebuddy/README.md). Generated `.java` deliberately does **not** go
there: it stays under `src/main/java` as committed, IDE-navigable source (DEC-019 / `AGENTS.md` § 1).

Before the `--java-out` flag existed, generated Java landed in the *metadata* directory. That is the
one flag an adopter most often omits, and the reason the getting-started guide documents it in its
flag table.

**`classpathScope` must be `compile`.** `exec:java` defaults to the runtime scope, which excludes
`provided` dependencies — and the tooling is `provided` so it never becomes a transitive runtime
dependency of an application (`AGENTS.md` § 2). Without `<classpathScope>compile</classpathScope>` the
generator is not on the exec classpath at all.

**Turning generation off.** `-Djcodebuddy.entity.codegen.skip=true` skips the execution, which is what
you want when you only need to compile or inspect the committed output:

```
scripts\mvn-jdk25.cmd -o -pl hipster-entity-example -am "-Djcodebuddy.entity.codegen.skip=true" package
```

**Generation is fail-safe about the example.** `ExampleRegenerationTest` copies the committed tree into
a temp directory, runs the pass in place there, and asserts the result is byte-identical to what is
committed — so a generator change that alters the example fails the tooling's tests instead of quietly
rewriting it. Regenerate and commit in the same change when that test goes red.

## Running the demo

```
scripts\run-demo.cmd
```

It builds and runs `hr.hrg.hipster.entityexample.person.PersonDemo`, which prints six sections:
a row array becomes a read view; the view serializes to JSON; a tracking builder records one change;
the changed fields with the caller's own comparison; the change set as JSON; and a no-op write
changing nothing. There is **no SQL leg** — SQL generation is the tooling's draft/opt-in feature
(`--adapters`, off by default), and this example does not enable it.

`PaymentMethodController` is the polymorphic half: it dispatches on the generated discriminator
constants with a direct-call `switch` (DEC-019 — no reflective dispatch), and the base
`PaymentMethod_` enum it reads stays hand-written.
