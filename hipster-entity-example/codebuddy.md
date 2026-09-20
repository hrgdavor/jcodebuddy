# `codebuddy.md` — driving JCodeBuddy in `hipster-entity-example`

This is the **module runbook** for JCodeBuddy in this module. Read it before you
edit anything under `src/main/java` here, and before you run a build whose whole
point is to regenerate something.

Two other files are authorities this one does not replace:

| File                                             | What it owns                                         |
| ------------------------------------------------ | ---------------------------------------------------- |
| [`../AGENTS.md`](../AGENTS.md)                   | repo-wide rules: source-visible wiring (§ 1), cooperative codegen, the `.jcodebuddy/` layout rule (§ 2) |
| [`.jcodebuddy/README.md`](.jcodebuddy/README.md) | the output layout and its git track policy (DEC-026) |

`hipster-entity-example` is currently the **only** JCodeBuddy-converted module in
the repository, so it is also the module where every setup question gets answered
first. Section 6 lists what setup now guards against, and what is still rough.

---

## 0. The output model — what goes where

JCodeBuddy is a **cooperative** generator. Its **main output is Java source, and
it lands in the normal source tree** — inside or right next to the hand-written
file it belongs to. This module regenerates *in place* under `src/main/java`; a
project that prefers to keep the two apart can route generated source to
`src/generated/java` instead (the rare variant). Either way it is ordinary,
committed, IDE-navigable source — DEC-019 / `AGENTS.md` § 1.

`.jcodebuddy/` is **not source output**. It is the module's auxiliary metadata
root, and each subdirectory holds a different kind of *non-source* artifact:

| Subdirectory                           | Holds                                                        | Git policy                                                |
| -------------------------------------- | ------------------------------------------------------------ | --------------------------------------------------------- |
| `metadata/entity/`                     | `<Marker>.metadata.json` — the machine-readable entity model, for tooling consumers: JS tooling rendering HTML reports or interactive views of the codebase, schema validators, query builders. Plus `generation.json`, the last pass's run record (generator revision, the artifact its classes came from, roots, flags, counts) | ignored by default; opt in as a contract (DEC-026) |
| `metadata/watch/`, `metadata/project/` | indexes and checksum/mtime caches (`metadata.db`, `index.fury`) used by the watch agent and whole-project passes to detect offline changes and skip a full rescan | **must stay ignored** — machine-local, large, regenerable |
| `context/`, `reports/`                 | human-read material: module specs, gate and baseline records | tracked                                                   |
| `agent-state/`                         | scratch: run logs, probes, temporary copies                  | ignored                                                   |

The rule in one line: **generated code goes to `src/`; what JCodeBuddy *knows*
about the module goes to `.jcodebuddy/`.** No generated `.java` belongs here — a
`*.metadata.json` is not a source file, and a `.java` under `.jcodebuddy/` is
always a mistake (see § 6.1).

---

## 1. Which JCodeBuddy parts this module actually uses

JCodeBuddy here is a **side tool**. Nothing below is bound to a Maven phase, and
there is no annotation processing and no compile hook: `mvn compile`,
`mvn package` and `mvn test` compile the committed generated source and do
nothing else. A pass is started by a person or a script — § 3 is how.

| Part                                                                                        | Lives in                                                                 | Wired here by                              | What it does for this module                                                    |
| ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------ | ------------------------------------------------------------------------------- |
| **Entity generator** — `hr.hrg.hipster.entity.tooling.EntityMetadataGenerator`              | `hipster-entity-tooling`                                                 | `scripts\gen.cmd` (primary), or the POM's phase-less `exec:java` goal `hipster-entity-generate` | parses the hand-written view interfaces and **rewrites 15 generator-owned `.java` files in place under `src/main/java`** — the main output — then writes the entity metadata JSON into `.jcodebuddy/metadata/entity/`, plus `generation.json` through `--run-record` (§ 0, § 5) |
| **Tooling preflight** — `hr.hrg.hipster.entity.tooling.GeneratorPreflight`                  | `hipster-entity-tooling`                                                 | `scripts\gen.cmd` runs it before every pass; also the phase-less `exec:java` goal `hipster-entity-preflight` | refuses to start a pass when the tooling on the classpath is too old to understand the flags the pass passes. An old tooling silently treats `--java-out` as a positional argument and writes generated Java into the metadata directory; this turns that into a loud failure before anything is written. See § 6.1 |
| **Generation filter** — `--packages …`                                                      | tooling CLI flag                                                         | POM argument, used by `gen.cmd` and the goal | restricts *generation* to `…person.entity` and `…paymentMethod.entity`; indexing is still whole-tree, so cross-package supertypes and addons resolve. `example/`, `person/iface`, `person/record` therefore stay hand-written |
| **`--java-out <dir>`**                                                                      | tooling CLI flag                                                         | POM argument (`src/main/java`)             | generated Java goes back next to the view it belongs to, not into the `.jcodebuddy/` metadata directory. This is what makes the generated source **committed** — DEC-019 / `AGENTS.md` § 1 |
| **Entity rules validator** — `--validate` (bare = `REPORT`)                                 | `hipster-entity-tooling` → `…tooling.validation.EntityRulesValidator`    | POM argument                               | runs the registered rules (`MarkerEntityRule`, `ViewInterfaceRule`, `ViewAnnotationRule`, `AuditableRule`, `EntityFieldEnumOrderRule`) *before* writing, prints every issue, and continues. A clean example prints `Validation: no issues in …` |
| **Divergence reporter** — DEC-022 shape `kind, location, cause, current, canonical, action` | `…tooling.DivergenceReporter`                                            | every pass                                 | explains what the pass did or declined to do. A clean pass over this module prints **3 informational** lines (`addon_field_collision` ×2, `nested_record_reused`) — that is the steady state, not a defect |
| **Cooperative codegen** — DEC-020 (recognise by shape, preserve user edits) + DEC-021 (the two-line class-file header) | tooling emitters | every generated file here | the header line 1 (`// {@link …}`) is what makes generated classes reachable from the view in a stock IDE; the JSON5 line 2 carries `enabled` (set `false` to freeze a file) and `entityFieldEnum:true` (marks an R1 ledger) |
| **R1 field-enum ledger** — DEC-023                                                          | tooling + `…tooling.validation.EnumConstantOrderChecker`                 | every `*_.java`                            | the constant list is an append-only ordinal layout; the new constant is appended, a removed field is tombstoned, never reordered |
| **`.jcodebuddy/` marker + layout** — DEC-026                                                | this module                                                              | the directory itself                       | the module's auxiliary metadata root: the entity model JSON that tooling consumers read, the watch/project indexes and checksum caches, human notes, and scratch. **Never generated source** — see § 0 |
| **Runtime half** — `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson`    | separate modules                                                         | `pom.xml` `<dependencies>` (compile scope) | what the generated code compiles against. These are **not** dev-time: they ship |
| **Live watcher** — `EntityRegenerationWatcher`                                              | `project-automation`                                                     | `scripts\gen.cmd watch` (§ 3.5)            | the dev-time loop that regenerates on save. It takes the same flags; `project-automation` is not a dependency of this module, and the watch classpath is exported separately |

**Deliberately *not* used here**, so that a reader does not go looking:

- `--adapters` (the draft JDBC `<View>RowAdapter` / `<View>Binder` pair) — strictly opt-in, and the example does not opt in, so no committed example depends on the emitted shape;
- `--mapper Src:Tgt` — no view-to-view mapper is requested;
- the `validate`, `enum-order` and `enum-compact` subcommands — available, run by hand (see [`../hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md)), not part of this module's build;
- an IDE **sidecar / LSP** — optional user-friendliness on top of watch mode, not required for any part of this module's generation, and not wired up here;
- `project-automation`'s `MetadataAnalysisRunner` / metadata server / MCP server — a different subsystem (`metadata-server`, `metadata-mcp-server`), unrelated to entity generation;
- `hipster-ioc` tooling — a different generator family.

The hand-written consumers worth looking at, which exercise the generated API:
`person/PersonDemo.java`, `person/PersonController.java`,
`paymentMethod/PaymentMethodController.java`.

---

## 2. Prerequisites

| Need   | Default                        | Override           |
| ------ | ------------------------------ | ------------------ |
| JDK 25 | `C:\Program Files\Java\jdk-25` | `JCODEBUDDY_JDK25` |
| Maven  | `D:\programs\mvn\bin\mvn.cmd`  | `JCODEBUDDY_MVN`   |

Both are consumed by [`../scripts/mvn-jdk25.cmd`](../scripts/mvn-jdk25.cmd), which
sets `JAVA_HOME` for the Maven JVM. **JDK 25 is required on both JVMs** — the root
POM pins `maven.compiler.release=25`, and `.mvn/jvm.config` cannot select a JDK.

There is **no CI** in this repository. The gate below runs when you run it.

---

## 3. How to run

JCodeBuddy is a **side tool**. No part of it is bound to a Maven phase, so the
ordinary build and the generator are two separate things:

- **the build** compiles this module and runs its tests; it never regenerates
  anything (verified: a plain `mvn compile` does not invoke the generator at all);
- **a pass** rewrites the generated source; you start it yourself, once with
  `scripts\gen.cmd`, or continuously with `scripts\gen.cmd watch`.

A pass needs **no `mvn install` and builds no jar**: `scripts\gen.cmd` compiles
the tooling in the reactor and asks Maven for the classpath the reactor itself
resolved (see § 6.5 for why the obvious `mvn exec:java` cannot do this).

### How it gets triggered — only the first layer is required

| Layer | What it is | Needed? |
| ----- | ---------- | ------- |
| **The pass** — `scripts\gen.cmd`, § 3.1 | the generator, run when you ask | **Required.** This is the whole tool. |
| **Watch mode** — `scripts\gen.cmd watch`, § 3.5 | that same pass, driven by a file watcher, so it regenerates after each save | Optional; the normal development loop. Still just the generator. |
| **Sidecar / LSP** | IDE integration *on top of* watch mode: in-editor diagnostics, code actions, hover for the class-file header, divergence warnings | **A user-friendliness expansion only.** Nothing here needs one, and none is wired up for this generator today. |

The boundary between the last two rows is the point worth remembering: **watch
mode belongs to JCodeBuddy, not to a sidecar.** A sidecar consumes what watch mode
already produces; it never replaces it, and removing it leaves the tool complete.

### 3.1 Regenerate — the one command

```bat
scripts\gen.cmd
```

The recommended entry point, and the one to reach for by default. It compiles the
tooling plus this module, exports the classpath, runs the preflight, runs the
generator, pipes the pass to `.jcodebuddy/agent-state/gen.log`, and prints just
the lines that matter — the preflight, the resolved root, where the Java went, the
validation result, the divergence count, and the build status.

A pass prints `Generating only for packages: …`, `Writing generated java to: …`,
`Validation: no issues in …`, rewrites the 15 generated files in place and the
metadata JSON, and leaves `git status` clean when nothing changed.

### 3.2 Regenerate *and* verify

```bat
scripts\gen.cmd with-tests
```

Regenerates, then runs the entity test set. The test that matters for the
generated tree is `ExampleRegenerationTest`: it regenerates a **copy** of the
committed tree and asserts the result is byte-identical. If it goes red, the fix
is **regenerate and commit**, not "adjust the test" (§ 4.3).

### 3.3 Verify only that regeneration is byte-identical

```bat
scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=ExampleRegenerationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

9 tests, ~5 s. The fastest answer to "did my change to a view break the generated
tree?", and it does **not** regenerate. Run from PowerShell it needs the
`cmd /c '…'` wrapper — § 3.9.

### 3.4 The recorded gate — test only, and it no longer regenerates

```bat
scripts\mvn-jdk25.cmd
```

`mvn -o -pl <six hipster-entity modules> -am clean test`. This is the gate: it
compiles everything and runs the tests, `ExampleRegenerationTest` included. It
does **not** regenerate, because nothing is bound to the lifecycle any more — so
run § 3.2 (or § 3.1 and then the gate) when you have changed a view or the
generator.

### 3.5 Live watch — regenerate on every save

```bat
scripts\gen.cmd watch
```

Regenerates now, then keeps watching `src/main/java` and regenerates after each
save. It is a long-running foreground process; Ctrl+C stops it.

[`EntityRegenerationWatcher`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/entity/EntityRegenerationWatcher.java)
is the loop behind it: it watches `*.java`, debounces a batch, and regenerates
when the *content* actually differs from what the last pass produced — a content
check rather than a timing flag, so a save that changes nothing does nothing and
the watcher never feeds on its own output. The default metadata directory (its
`--report-dir` flag) is resolved by walking up to the nearest `.jcodebuddy/`, i.e.
this module's `.jcodebuddy/metadata/entity`.

`project-automation` is **not** a dependency of this module, and its classpath is
exported separately by `gen.cmd watch`, so a build here never depends on it.

### 3.6 Compile without regenerating

There is nothing to switch off any more: generation never runs on a build. A plain
compile is all you need.

```bat
scripts\mvn-jdk25.cmd -o -pl hipster-entity-example -am compile
```

(The old `-Djcodebuddy.entity.codegen.skip=true` property existed only to skip
lifecycle-bound executions and has been removed with them.)

### 3.7 Ask which generator is on the classpath

```bat
scripts\gen.cmd
```

Every pass prints its identity as its first output — name, revision, and **where
its classes came from** — so the pass log already answers "which tooling ran?".
`--run-record` goes further and stores it (§ 5.6).

A path under `~/.m2` means the local repository's copy is being used rather than
the working tree; `…/hipster-entity-tooling/target/classes` is the reactor's own
output, which is what `scripts\gen.cmd` always produces. To ask the question
without generating anything, run the generator with `--version` on the exported
classpath (see § 3.8).

### 3.8 Hand-run the generator CLI with an exported classpath

When you want to see the generator's own output in your terminal, or pass a flag
that `gen.cmd` does not, export the classpath once and call it directly:

```bat
scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am compile dependency:build-classpath "-Dmdep.outputFile=hipster-entity-example\.jcodebuddy\agent-state\gen-classpath.txt"
```

then

```bat
set /p CP=< hipster-entity-example\.jcodebuddy\agent-state\gen-classpath.txt
java -cp "hipster-entity-tooling\target\classes;%CP%" ^
     hr.hrg.hipster.entity.tooling.EntityMetadataGenerator ^
     hipster-entity-example\src\main\java ^
     hipster-entity-example\.jcodebuddy\metadata\entity ^
     --java-out hipster-entity-example\src\main\java ^
     --packages hr.hrg.hipster.entityexample.person.entity,hr.hrg.hipster.entityexample.paymentMethod.entity ^
     --validate ^
     --run-record hipster-entity-example\.jcodebuddy\metadata\entity\generation.json
```

Verified: same flag surface as the POM goal and `gen.cmd`, same output — generated
`.java` back into `src/main/java`, JSON only in the metadata directory. Note the
`hipster-entity-tooling\target\classes` prefix: `dependency:build-classpath`
lists a module's *dependencies*, not its own output. Do **not** hand-build the
classpath by globbing the local Maven repository — see the "Reading source
outside Maven" note in
[`../hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md)
(an old JavaParser silently parses nothing, and the only symptom is missing
files).

### 3.9 The `-D` quoting rule (Windows)

`cmd.exe` splits an **unquoted** batch argument at `=` and `.` *before* a `.cmd`
file sees it, so an unquoted `-Dtest=SomeTest` reaches the script as two tokens
(`-Dtest`, `SomeTest`). `mvn-jdk25.cmd` therefore **refuses to run** (exit 2) on
any `-D` token without an `=` rather than silently dropping the `-pl` list and
building the whole reactor. Consequences:

- quote the property at the call site: `"-DskipTests=true"`;
- always give boolean properties an explicit value;
- from PowerShell, wrap the whole thing: `cmd /c 'scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=X" "-Dsurefire.failIfNoSpecifiedTests=false"'`.

---

## 4. Regenerate: what is input, what is output, what is yours

### 4.1 Input — hand-written, never overwritten

The view interfaces and their markers, read with JavaParser:

```
src/main/java/hr/hrg/hipster/entityexample/person/entity/       Person, PersonSummary, PersonDetails, PersonDto,
                                                               PersonUpdateForm, PersonUpdatableView, PersonCreateForm,
                                                               PersonAuditable
src/main/java/hr/hrg/hipster/entityexample/paymentMethod/entity/ PaymentMethod (sealed marker), PaymentMethodAuditable,
                                                               CreditCard…, PayPal…, BankTransfer…, Crypto…
src/main/java/hr/hrg/hipster/entityexample/example/             Auditable (the addon/marker source),
                                                               *AuditableProperty (legacy hand-written enums, outside the filter)
src/main/java/hr/hrg/hipster/entityexample/person/iface/        Person  (documentation sample, outside the filter)
src/main/java/hr/hrg/hipster/entityexample/person/record/       Person  (documentation sample, outside the filter)
```

### 4.2 Output — generated, committed, navigable

Exactly the files carrying the DEC-021 two-line header (`// {@link …}` +
`// {enabled:…}`), all of them in-place under `src/main/java`:

| Package                | Files |
| ---------------------- | ----- |
| `person.entity`        | `PersonAuditable_`, `PersonCreateForm_`, `PersonCreateFormRecord`, `PersonDetails_`, `PersonDto_`, `PersonSummary_`, `PersonSummaryBuilder`, `PersonSummaryBuilderTracking`, `PersonUpdatableView_`, `PersonUpdateForm_` |
| `paymentMethod.entity` | `BankTransferPaymentMethod_`, `CreditCardPaymentMethod_`, `CryptoPaymentMethod_`, `PaymentMethodAuditable_`, `PayPalPaymentMethod_` |

`PersonSummary` is the only `BUILDER_ALL` view here, so it is the only one with a
plain and a tracking builder; the rest are `META` (field enum only) or have a
hand-declared nested record / `Write` surface.

This module uses the **in-place** form only. A project that prefers a separate
output root points `--java-out` at it (the rare `src/generated/java` variant), and
the same files then appear under `<output-root>/<package path>/` with the same
DEC-021 header. Nothing in this module is generated that way.

**Hand-written and *not* regenerated**, even though the name looks generated:
`paymentMethod/entity/PaymentMethod_.java` (the polymorphic root's discriminator
constant and its permitted subtypes) and `paymentMethod/entity/PaymentMethod.java`
(the marker). The generator recognises the root enum and preserves it
(`polymorphic_root_enum_preserved`).

### 4.3 The regeneration loop

1. edit the **view interface** (`PersonDetails.java`) — add, rename or delete an accessor;
2. run 3.2 (or 3.1);
3. review the diff; commit the interface **and** the regenerated output in one change.

What the generator will do, and refuse to do:

- a **new** accessor appends its constant at the **end** of `<View>_` — never next
  to its declaration in the interface (R1, DEC-023). Never hand-sort the constant list;
- a **deleted** accessor leaves a **tombstone** constant (`@Deprecated`,
  `retired() == true`); writers skip it. Do not delete it by hand;
- a member you edited by hand inside a generated file is recognised by shape and
  **preserved** unless you delete it (DEC-020). Deleting it is how you opt back
  into regeneration — the generator emits a fresh one at the canonical location;
- to take a whole generated file under manual control, set `enabled:false` in its
  header line 2 (DEC-021). That is the per-file equivalent of a freeze marker.

If `ExampleRegenerationTest` goes red after a generator change, the fix is
**regenerate and commit**, not "adjust the test".

---

## 5. The metadata JSON: where it is, and how to delete + regenerate

This is the `.jcodebuddy/metadata/entity/` half of § 0 — the auxiliary artifact,
not the generated source.

### 5.1 Where

```
hipster-entity-example/.jcodebuddy/metadata/entity/
├── Auditable.metadata.json       (the example/ marker and its addon views)
├── PaymentMethod.metadata.json   (the polymorphic family)
├── Person.metadata.json          (the person family)
└── README.md                     (tracked; explains the subtree)
```

One file **per entity marker**, named `<Marker>.metadata.json` — not one per view.
It contains the marker/package/id type, one entry per view (`gen`, `extends`,
`addons`, `discriminatorField`, properties with `fieldKind`/`expression`/`relation`),
and an `allFields` array with `typeByView` type descriptors.

### 5.2 Who reads it

**The Java generator does not read it back.** A generation pass re-parses the view
interfaces with JavaParser; the only reader in the tree today is the tooling's own
test (`EntityMetadataGeneratorTest` round-trips it through `fromJson`). That is why
deleting it is safe — it cannot change a single byte of generated Java.

It is **not** a throwaway build log, though. It is the machine-readable model of
this module's entities, and it exists for **other** consumers: JS tooling that
renders HTML reports or interactive views of the codebase, and — per
[`../doc-hipster-entity/brainstorm/entity-metadata-generator.md`](../doc-hipster-entity/brainstorm/entity-metadata-generator.md) —
schema validators and query builders that need cross-cutting answers such as "all
COLUMN fields of Person" or "which views expose field X". Those consumers read
`allFields`, which is exactly what the per-view lists cannot answer.

So the file is only as fresh as the last generation pass. If a tool consumes it,
regenerate first (§ 5.3) rather than trusting whatever is on disk.

### 5.3 Track policy

The entity JSON is **ignored by default**: `.jcodebuddy/.gitignore` ignores
`metadata/**` and re-includes only the `README.md` files, so deleting it never
shows up in `git status`. The sibling `metadata/watch/` and `metadata/project/`
subtrees — the indexes and the checksum/mtime caches — **must stay ignored**:
they are machine-local and regenerable, and committing them is pure churn.

A project that wants the entity JSON reviewed in pull requests opts that subtree
back in with a `!` rule (the exact rule is in
[`.jcodebuddy/README.md`](.jcodebuddy/README.md) and DEC-026) — the sibling cache
subtrees are deliberately not part of that opt-in. Note what the default rules
protect against even then: because only `README.md` files and the re-included
subtree are tracked, a `.java` that an old hand-run pass dropped into the metadata
directory still cannot be committed by that opt-in — see § 6.1 for why that case
is real.

### 5.4 Delete and regenerate — the verified recipe

```powershell
Remove-Item hipster-entity-example\.jcodebuddy\metadata\entity\*.metadata.json
scripts\gen.cmd
```

Verified end to end: with the three files deleted, a pass recreates all three, two
consecutive passes produce **byte-identical** JSON (checked by hash), and
`git status` stays clean. Note that only a **pass** recreates them: the recorded
gate (`scripts\mvn-jdk25.cmd`) compiles and tests, and no longer regenerates
anything, because nothing is bound to the lifecycle any more (§ 3.4). A pass that
*cannot* regenerate — a classpath pointing at an older tooling — fails instead of
pretending (§ 6.1), so "the files are missing and the build is green" is no longer
a state you can be in.

### 5.5 Full reset (delete the generated Java too)

To prove regeneration from scratch — for example before reviewing a generator
change — delete the 15 generated files listed in § 4.2 (the ones with the DEC-021
header), keep the hand-written ones from § 4.1, then regenerate. Expect the
committed content back byte for byte; that is exactly what
`ExampleRegenerationTest` asserts. **Do not** delete
`paymentMethod/entity/PaymentMethod_.java` — it is hand-written.

### 5.6 `generation.json` — the run record

Every pass also writes `.jcodebuddy/metadata/entity/generation.json`, because the
pass passes `--run-record`:

```json
{"generator":"hipster-entity-generator","version":"1.0-SNAPSHOT",
 "classpath":"file:/…/hipster-entity-tooling/target/classes/",
 "status":"ok","startedAt":"…","durationMs":599,
 "sourceRoot":"…/src/main/java","reportDir":"…/.jcodebuddy/metadata/entity",
 "javaOut":"…/src/main/java","packages":["…person.entity","…paymentMethod.entity"],
 "mappers":[],"adapters":false,"validate":"REPORT",
 "validationIssues":0,"divergences":["kind=…"]}
```

The `classpath` field is the answer to "which tooling produced this tree?":
`…/hipster-entity-tooling/target/classes/` is the working tree's own output, while a
path under `~/.m2` means the local repository's copy was used. `scripts\gen.cmd`
always produces the former.

It is the machine-readable answer to "what generated this tree, with which tooling,
and what did it report?" — the question a reviewer of a regenerated diff asks, and
the one a mis-generated pass used to leave unanswerable. It is rewritten by every
pass (including a **failed** one, where `status` is `failed` and `failure` carries
the reason), so read it as state, not as history. It is ignored by git, like the
entity JSON beside it.

---

## 6. Setup notes: what is guarded, and what is still rough

### 6.1 The wrong-tooling trap — **fixed, and now a loud failure**

**What it was.** A pass could run the *wrong* generator: an older tooling build,
usually whatever Maven had installed in `~/.m2`, rather than the revision in the
working tree. The run printed `BUILD SUCCESS`, refreshed the three JSON files — and
then:

- no `Generating only for packages: …`, no `Writing generated java to: …`, no
  `Validation: …` line (the current generator prints all three);
- the committed `src/main/java` tree was **not** regenerated at all;
- legacy `*.java` files appeared at `.jcodebuddy/metadata/entity/hr/hrg/hipster/entityexample/…`
  — including `Write_.java`, which `ExampleRegenerationTest` explicitly asserts
  must **not** exist.

**Cause.** An older tooling predates `--java-out` and `--packages`. It does not
*reject* the flags it does not know: it treats them as positional arguments and
writes generated Java into positional argument 2 — the metadata directory. It was
nasty because it was *silent and plausible*: the run succeeded, the JSON files got
fresher timestamps, and the pollution was git-ignored, so `git status` stayed
clean. The original instance was caused by a `generate-sources` binding that ran
*before* `compile`, so the reactor had not built the tooling at all and Maven
resolved the `provided` dependency from `~/.m2`; that binding is gone, but any
hand-assembled classpath can still point at an old tooling, which is why the guards
stay.

**What now stops it.** Two guards, at different levels:

| Guard                                                     | Where                                             | What it catches |
| --------------------------------------------------------- | ------------------------------------------------- | --------------- |
| `GeneratorPreflight` — run by `scripts\gen.cmd` before every pass, and available as the phase-less `exec:java` goal `hipster-entity-preflight` | `hipster-entity-tooling` | a tooling too old for the flags the pass passes. The class does **not exist** in an old build, so the run fails **on the missing class, before anything is written**. A flag could not do this job: an old tooling does not know it and swallows it as another positional argument |
| `EntityMetadataGenerator.rejectJavaOutputUnderJcodebuddy` | the tooling, before the first write of every pass | the write itself — at every entry point: the CLI, `scripts\gen.cmd`, the POM's `exec:java` goal, and `EntityRegenerationWatcher` |

**What you see now** (measured, with the same outdated `~/.m2` jar that produced the
original trap, invoking the explicit exec goal):

```
[INFO] --- exec:3.6.3:java (hipster-entity-preflight) @ hipster-entity-example ---
java.lang.ClassNotFoundException: hr.hrg.hipster.entity.tooling.GeneratorPreflight
[INFO] BUILD FAILURE
```

— and zero files written into the metadata directory. The fix is to run the pass
from the working tree instead:

```bat
scripts\gen.cmd
```

**Why there is no lifecycle phase any more.** `generate-sources` was the phase used
when generation was bound into the build, chosen because generated code must exist
before the compiler reads it. It also created the trap above, because that phase
runs *before* `compile` and Maven then resolves the tooling from `~/.m2`.
`process-classes` (after `compile`) was rejected too: a build would regenerate
sources it had already compiled, so that run's classes would not match the working
tree. This project resolves the tension by removing the binding altogether —
generated source is **committed**, so the compiler always has it, and the tool that
writes it runs beside the build rather than inside it.

### 6.2 Running a pass

Use `scripts\gen.cmd` (§ 3.1): it always works, needs no `mvn install`, builds no
jar, and prints what the pass did. `scripts\gen.cmd with-tests` adds the test set,
`scripts\gen.cmd watch` runs it live. If you ran an old broken pass before this was
guarded, remove its debris with
`Remove-Item -Recurse hipster-entity-example\.jcodebuddy\metadata\entity\hr`.

### 6.3 Classpath scope is load-bearing in the exec-goal form

This applies to the **explicit `exec:java` goal**, not to `scripts\gen.cmd` (which
never uses `exec:java` — § 6.5).

`exec:java` defaults to the *runtime* classpath scope, which excludes `provided`
dependencies — and the tooling is `provided` on purpose (it must never become a
runtime dependency). Without `<classpathScope>compile</classpathScope>` in
`pom.xml`, neither the preflight nor the generator is on the exec classpath, and
the invocation fails with a class-not-found rather than anything that says "scope".
Both executions therefore carry it.

### 6.4 `-D` properties still need quoting gymnastics from PowerShell

See § 3.9. The shortcut's behaviour (refuse rather than degrade) is right; the
ergonomics from PowerShell are not, and this one is **not** fixed.
`cmd /c '… "-Dx=y" …'` is the workaround. A `.ps1` companion to
`scripts\mvn-jdk25.cmd` is the obvious next step.

### 6.5 Why `scripts\gen.cmd` does not use `mvn exec:java`

Maven's `exec:java` looks like the obvious way to run the generator, and it is the
wrong tool for a pass in a multi-module reactor. Two reasons, both measured:

1. **A direct goal invocation runs on every module.** `mvn exec:java@hipster-entity-generate`
   executes the goal on *every project in the reactor*, and fails on the parent and
   on any module without that execution:
   `The parameters 'mainClass' ... are missing or invalid`. Maven has no per-module
   selector for a direct goal — `-pl` narrows which modules are built, not which
   modules a directly invoked goal runs on.
2. **It resolves the tooling from `~/.m2`, not the reactor.** `exec:java` resolves
   the `provided` tooling dependency as an *artifact*, so it runs whatever jar is
   installed locally rather than the classes in the working tree. That is exactly
   the wrong-tooling trap of § 6.1, and the preflight is what catches it.

Maven's official answer for "use the reactor's classes without `install`" is
[`dependency:build-classpath`](https://maven.apache.org/plugins/maven-dependency-plugin/build-classpath-mojo.html),
which maps a reactor dependency to that module's `target/classes` directory. That is
what `scripts\gen.cmd` uses, and it is why the pass needs **no jar and no
`mvn install`**:

```bat
scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling,hipster-entity-example -am compile ^
    dependency:build-classpath "-Dmdep.outputFile=<abs path>"
java -cp "hipster-entity-tooling\target\classes;<the exported classpath>" ^
     hr.hrg.hipster.entity.tooling.EntityMetadataGenerator <args…>
```

Two details worth knowing if you write your own invocation:

- the output file must be an **absolute** path, because `dependency:build-classpath`
  runs per module and a relative path is resolved against each module's base
  directory;
- the exported file lists a module's **dependencies**, not its own output, so the
  entry point's own `target/classes` has to be prepended (both `gen.cmd` paths do
  this).

Because the compile step is incremental, a re-run after an edit costs one
incremental compile plus one JVM start. If the tooling and the example are already
compiled, the `java -cp` command alone is the whole pass (§ 3.8).

### 6.6 Smaller notes

- The `Generating only for packages: […]` line still prints in **set order** rather
  than the order given on the command line (`Set.copyOf`), so it differs run to run.
  Cosmetic, still open.
- `doc/README.md` in this module still documents the package
  `hr.hrg.hipster.entity.paymentMethod`; the code lives in
  `hr.hrg.hipster.entityexample.paymentMethod`. Stale sample documentation, still open.
- ~~"There is no single command that says 'regenerate and tell me what changed'"~~ —
  `scripts\gen.cmd` prints the pass summary, and `generation.json` (§ 5.6) keeps it.

---

## 7. Pointers

- [`../AGENTS.md`](../AGENTS.md) — the repo-wide rules this module is generated under.
- [`.jcodebuddy/README.md`](.jcodebuddy/README.md) — output layout and track policy.
- [`README.md`](README.md) — this module's own generated-vs-hand-written table and demo notes.
- [`../hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md) — generator CLI, `GenLevel` ladder, naming contract, R1 order contract.
- [`../project-automation/README.md`](../project-automation/README.md) — the dev-time orchestrator and the regeneration watcher.
- [`../doc-hipster-entity/user/getting-started-new-project.md`](../doc-hipster-entity/user/getting-started-new-project.md) — the same workflow for a brand-new project.
- [`../doc-hipster-entity/architecture/decisions/DEC-026.md`](../doc-hipster-entity/architecture/decisions/DEC-026.md) — `.jcodebuddy/`, per-module and per-purpose.
