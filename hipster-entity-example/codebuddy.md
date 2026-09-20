# `codebuddy.md` — driving JCodeBuddy in `hipster-entity-example`

This is the **module runbook** for JCodeBuddy in this module. Read it before you
edit anything under `src/main/java` here, and before you run a build whose whole
point is to regenerate something.

Two other files are authorities this one does not replace:

| File                                             | What it owns                                                                                         |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| [`../AGENTS.md`](../AGENTS.md)                   | repo-wide rules: source-visible wiring (§ 1), cooperative codegen, the `.jcodebuddy/` layout rule (§ 2) |
| [`.jcodebuddy/README.md`](.jcodebuddy/README.md) | the output layout and its git track policy (DEC-026)                                                 |

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

| Subdirectory                           | Holds                                                                                                | Git policy                                                |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `metadata/entity/`                     | `<Marker>.metadata.json` — the machine-readable entity model, for tooling consumers: JS tooling rendering HTML reports or interactive views of the codebase, schema validators, query builders. Plus `generation.json`, the last pass's run record (generator revision, the artifact its classes came from, roots, flags, counts) | ignored by default; opt in as a contract (DEC-026)        |
| `metadata/watch/`, `metadata/project/` | indexes and checksum/mtime caches (`metadata.db`, `index.fury`) used by the watch agent and whole-project passes to detect offline changes and skip a full rescan | **must stay ignored** — machine-local, large, regenerable |
| `context/`, `reports/`                 | human-read material: module specs, gate and baseline records                                         | tracked                                                   |
| `agent-state/`                         | scratch: run logs, probes, temporary copies                                                          | ignored                                                   |

The rule in one line: **generated code goes to `src/`; what JCodeBuddy *knows*
about the module goes to `.jcodebuddy/`.** No generated `.java` belongs here — a
`*.metadata.json` is not a source file, and a `.java` under `.jcodebuddy/` is
always a mistake (see § 6.1).

---

## 1. Which JCodeBuddy parts this module actually uses

| Part                                                                                                 | Lives in                                                              | Wired here by                                                                                        | What it does for this module                                                                         |
| ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| **Entity generator** — `hr.hrg.hipster.entity.tooling.EntityMetadataGenerator`                       | `hipster-entity-tooling`                                              | `pom.xml` → `exec-maven-plugin` `exec:java`, execution id `hipster-entity-generate`, phase `generate-sources` | parses the hand-written view interfaces and **rewrites 15 generator-owned `.java` files in place under `src/main/java`** — the main output — then writes the entity metadata JSON into `.jcodebuddy/metadata/entity/`, plus `generation.json` through `--run-record` (§ 0, § 5) |
| **Stale-artifact preflight** — `hr.hrg.hipster.entity.tooling.GeneratorPreflight`                    | `hipster-entity-tooling`                                              | `pom.xml` → a second `exec:java` execution, `hipster-entity-preflight`, running **before** the generator in the same phase | fails the build when the tooling on the classpath is older than this binding, instead of letting it ignore `--java-out` and write generated Java into the metadata directory. See § 6.1 — the class is the canary, because an old artifact cannot run a class it does not contain |
| **Generation filter** — `--packages …`                                                               | tooling CLI flag                                                      | `pom.xml` argument                                                                                   | restricts *generation* to `…person.entity` and `…paymentMethod.entity`; indexing is still whole-tree, so cross-package supertypes and addons resolve. `example/`, `person/iface`, `person/record` therefore stay hand-written |
| **`--java-out <dir>`**                                                                               | tooling CLI flag                                                      | `pom.xml` argument (`src/main/java`)                                                                 | generated Java goes back next to the view it belongs to, not into the `.jcodebuddy/` metadata directory. This is what makes the generated source **committed** — DEC-019 / `AGENTS.md` § 1 |
| **Entity rules validator** — `--validate` (bare = `REPORT`)                                          | `hipster-entity-tooling` → `…tooling.validation.EntityRulesValidator` | `pom.xml` argument                                                                                   | runs the registered rules (`MarkerEntityRule`, `ViewInterfaceRule`, `ViewAnnotationRule`, `AuditableRule`, `EntityFieldEnumOrderRule`) *before* writing, prints every issue, and continues. A clean example prints `Validation: no issues in …` |
| **Divergence reporter** — DEC-022 shape `kind, location, cause, current, canonical, action`          | `…tooling.DivergenceReporter`                                         | every pass                                                                                           | explains what the pass did or declined to do. A clean pass over this module prints **3 informational** lines (`addon_field_collision` ×2, `nested_record_reused`) — that is the steady state, not a defect |
| **Cooperative codegen** — DEC-020 (recognise by shape, preserve user edits) + DEC-021 (the two-line class-file header) | tooling emitters                                                      | every generated file here                                                                            | the header line 1 (`// {@link …}`) is what makes generated classes reachable from the view in a stock IDE; the JSON5 line 2 carries `enabled` (set `false` to freeze a file) and `entityFieldEnum:true` (marks an R1 ledger) |
| **R1 field-enum ledger** — DEC-023                                                                   | tooling + `…tooling.validation.EnumConstantOrderChecker`              | every `*_.java`                                                                                      | the constant list is an append-only ordinal layout; the new constant is appended, a removed field is tombstoned, never reordered |
| **`.jcodebuddy/` marker + layout** — DEC-026                                                         | this module                                                           | the directory itself                                                                                 | the module's auxiliary metadata root: the entity model JSON that tooling consumers read, the watch/project indexes and checksum caches, human notes, and scratch. **Never generated source** — see § 0 |
| **Runtime half** — `hipster-entity-api`, `hipster-entity-core`, `hipster-entity-jackson`             | separate modules                                                      | `pom.xml` `<dependencies>` (compile scope)                                                           | what the generated code compiles against. These are **not** dev-time: they ship                      |
| **Live watcher** — `EntityRegenerationWatcher`                                                       | `project-automation`                                                  | **not wired here** (see § 3.8)                                                                       | the dev-time loop that regenerates on save. It exists in this repo and takes the same flags; this module's build does not use it |

**Deliberately *not* used here**, so that a reader does not go looking:

- `--adapters` (the draft JDBC `<View>RowAdapter` / `<View>Binder` pair) — strictly opt-in, and the example does not opt in, so no committed example depends on the emitted shape;
- `--mapper Src:Tgt` — no view-to-view mapper is requested;
- the `validate`, `enum-order` and `enum-compact` subcommands — available, run by hand (see [`../hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md)), not part of this module's build;
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

### 3.1 Regenerate *and* verify — the recorded gate

```bat
scripts\mvn-jdk25.cmd
```

`mvn -o -pl <six hipster-entity modules> -am clean test`. This regenerates the
example's sources during `generate-sources` and runs, among others,
`ExampleRegenerationTest`, which regenerates a **copy** of the committed tree and
asserts the result is byte-identical. Exit 0 means "regeneration is a no-op".

### 3.2 Regenerate only (fast, no tests) — and the one-command form

```bat
scripts\gen.cmd
```

The wrapper for exactly this job. It runs the safe phase for you (so the tooling is
compiled in the same reactor), pipes the build to
`.jcodebuddy/agent-state/gen.log`, and prints just the lines that matter — the
preflight, the resolved root, where the Java went, the validation result, the
divergence count, and the build status. `scripts\gen.cmd with-tests` regenerates
*and* runs the entity test set. It is the recommended entry point; the equivalent
raw Maven command is:

```bat
scripts\mvn-jdk25.cmd -o -pl hipster-entity-example -am package "-DskipTests=true"
```

Either way the pass prints `Generating only for packages: …`,
`Writing generated java to: …`, `Validation: no issues in …`, rewrites the 15
generated files in place and the metadata JSON, and leaves `git status` clean when
nothing changed. Both are guarded (see § 6.1): a stale tooling build fails the
build instead of silently writing into the wrong directory.

### 3.3 Verify only that regeneration is byte-identical

```bat
scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=ExampleRegenerationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

9 tests, ~5 s. This is the fastest answer to "did my change to a view break the
generated tree?". Run from PowerShell it needs the `cmd /c '…'` wrapper — § 3.9.

### 3.4 Compile without regenerating

```bat
scripts\mvn-jdk25.cmd hipster-entity package "-DskipTests=true" "-Djcodebuddy.entity.codegen.skip=true"
```

`jcodebuddy.entity.codegen.skip=true` is the POM property bound to the exec
plugin's `<skip>` on **both** executions (preflight and generator); use it when you
only want to compile or inspect the committed output.

### 3.5 Ask which generator is on the classpath

```bat
java -jar hipster-entity-tooling\target\hipster-entity-tooling-1.0-SNAPSHOT.jar --version
```

Prints the generator's name, revision, and — the useful part — **the artifact its
classes came from**. A path under `~/.m2` is an installed revision and not
necessarily the one in your working tree; `…/hipster-entity-tooling/target/classes`
or `…/target/hipster-entity-tooling-1.0-SNAPSHOT.jar` is the build's own output.
Every generation pass prints the same line as its first output, so a build log
always answers "which tooling ran?" without a second invocation. `--run-record`
goes further and stores it (see § 5.6).

### 3.6 Run the demo

```bat
scripts\run-demo.cmd
```

Builds and runs `PersonDemo` (row array → view → JSON → tracking builder →
changed fields → change-set JSON → changed columns → no-op write).

### 3.7 Hand-run the generator CLI (off-Maven)

Once the shaded jar exists (`package` builds it):

```bat
java -jar hipster-entity-tooling\target\hipster-entity-tooling-1.0-SNAPSHOT.jar ^
     hipster-entity-example\src\main\java ^
     hipster-entity-example\.jcodebuddy\metadata\entity ^
     --java-out hipster-entity-example\src\main\java ^
     --packages hr.hrg.hipster.entityexample.person.entity,hr.hrg.hipster.entityexample.paymentMethod.entity ^
     --validate
```

Verified: same flag surface as the POM binding, same output — generated `.java`
back into `src/main/java`, JSON only in the metadata directory. Do **not**
hand-build a classpath by globbing the local Maven repository — see the "Reading
source outside Maven" note in
[`../hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md)
(an old JavaParser silently parses nothing, and the only symptom is missing
files).

### 3.8 Live watch (optional, not wired into this module)

[`EntityRegenerationWatcher`](../project-automation/src/main/java/hr/hrg/jcodebuddy/automation/entity/EntityRegenerationWatcher.java)
is the dev-time loop: it watches `*.java`, debounces a batch, and regenerates
when the *content* actually differs from what the last pass produced. Its CLI:

```bat
java -cp project-automation\target\classes ... ^
     hr.hrg.jcodebuddy.automation.entity.EntityRegenerationWatcher ^
     --source hipster-entity-example\src\main\java
```

The default metadata directory (the `--report-dir` flag) is resolved by walking up
to the nearest `.jcodebuddy/`, i.e. this module's `.jcodebuddy/metadata/entity`.
Nothing in this module's build starts it, and `project-automation` is not a
dependency of this module — so a build here never depends on it.

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

| Package                | Files                                                                                                |
| ---------------------- | ---------------------------------------------------------------------------------------------------- |
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

Verified end to end: with the three files deleted, the build recreates all three,
two consecutive passes produce **byte-identical** JSON (checked by hash), and
`git status` stays clean. `scripts\gen.cmd` is the raw Maven command from § 3.2
with a pass summary; the recorded gate (`scripts\mvn-jdk25.cmd`) does the same
thing, because the generator is bound to `generate-sources` and runs on **every**
build. A run that *cannot* regenerate — a stale tooling build — now fails instead of
pretending (§ 6.1), so "the files are missing and the build is green" is no longer a
state you can be in.

### 5.5 Full reset (delete the generated Java too)

To prove regeneration from scratch — for example before reviewing a generator
change — delete the 15 generated files listed in § 4.2 (the ones with the DEC-021
header), keep the hand-written ones from § 4.1, then regenerate. Expect the
committed content back byte for byte; that is exactly what
`ExampleRegenerationTest` asserts. **Do not** delete
`paymentMethod/entity/PaymentMethod_.java` — it is hand-written.

### 5.6 `generation.json` — the run record

Every build also writes `.jcodebuddy/metadata/entity/generation.json`, because the
binding passes `--run-record`:

```json
{"generator":"hipster-entity-generator","version":"1.0-SNAPSHOT",
 "classpath":"file:/…/hipster-entity-tooling/target/hipster-entity-tooling-1.0-SNAPSHOT.jar",
 "status":"ok","startedAt":"…","durationMs":599,
 "sourceRoot":"…/src/main/java","reportDir":"…/.jcodebuddy/metadata/entity",
 "javaOut":"…/src/main/java","packages":["…person.entity","…paymentMethod.entity"],
 "mappers":[],"adapters":false,"validate":"REPORT",
 "validationIssues":0,"divergences":["kind=…"]}
```

It is the machine-readable answer to "what generated this tree, with which tooling,
and what did it report?" — the question a reviewer of a regenerated diff asks, and
the one a mis-generated build used to leave unanswerable. It is rewritten by every
pass (including a **failed** one, where `status` is `failed` and `failure` carries
the reason), so read it as state, not as history. It is ignored by git, like the
entity JSON beside it.

---

## 6. Setup notes: what is guarded, and what is still rough

### 6.1 The stale-artifact trap — **fixed, and now a loud failure**

**What it was.** `scripts\mvn-jdk25.cmd -o -pl hipster-entity-example -am generate-sources`
printed `BUILD SUCCESS`, refreshed the three JSON files — and then:

- no `Generating only for packages: …`, no `Writing generated java to: …`, no
  `Validation: …` line (the current generator prints all three);
- the committed `src/main/java` tree was **not** regenerated at all;
- legacy `*.java` files appeared at `.jcodebuddy/metadata/entity/hr/hrg/hipster/entityexample/…`
  — including `Write_.java`, which `ExampleRegenerationTest` explicitly asserts
  must **not** exist.

**Cause.** The exec binding is bound to `generate-sources`, which runs *before*
`compile`. With `-am`, the `hipster-entity-tooling` module is only taken to
`generate-sources` too, so the reactor produced no tooling artifact and Maven
resolved the `provided` dependency to whatever was installed in `~/.m2`. The jar
there was from the previous revision, whose generator predates `--java-out` and
`--packages`: it ignored both flags and wrote generated Java into positional
argument 2 — the metadata directory. It was nasty because it was *silent and
plausible*: the build succeeded, the JSON files got fresher timestamps, and the
pollution was git-ignored, so `git status` stayed clean.

**What now stops it.** Two guards, at different levels:

| Guard                                                                                                | Where                                             | What it catches                                                                                      |
| ---------------------------------------------------------------------------------------------------- | ------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `hipster-entity-preflight` — `GeneratorPreflight` runs in an exec execution **before** the generator | this module's `pom.xml`                           | a tooling artifact older than the binding. The class does not exist in an old build, so `exec:java` fails the build **on the missing class, before anything is written**. A flag could not do this job: an old artifact does not know it and swallows it as another positional argument |
| `EntityMetadataGenerator.rejectJavaOutputUnderJcodebuddy`                                            | the tooling, before the first write of every pass | the write itself — for a manual run and for `EntityRegenerationWatcher` too, not only for this binding |

**What you see now** (measured, with the same outdated `~/.m2` jar that produced the
original trap):

```
[INFO] --- exec:3.6.3:java (hipster-entity-preflight) @ hipster-entity-example ---
java.lang.ClassNotFoundException: hr.hrg.hipster.entity.tooling.GeneratorPreflight
[INFO] BUILD FAILURE
```

— and zero files written into the metadata directory. The fix is one command:

```bat
scripts\mvn-jdk25.cmd hipster-entity install "-DskipTests=true"
```

**Why the exec binding was *not* moved to `process-classes`.** That was the obvious
alternative and it is the wrong one: `process-classes` runs *after* `compile`, so a
build would regenerate sources it had already compiled, and the main classes of that
run would not match the working tree. `generate-sources` is the correct phase
precisely because generated code must exist before the compiler reads it; the fix
belongs at the artifact-resolution layer, which is where the preflight sits.

### 6.2 The obvious "regenerate only" command

`generate-sources` is the natural thing to type, and it is the one invocation that
used to hit 6.1. Two answers now: `scripts\gen.cmd` (the command that always works,
and prints what the pass did), or the raw `package "-DskipTests=true"` form of § 3.2.
If you ran the old broken command before this was guarded, remove its debris with
`Remove-Item -Recurse hipster-entity-example\.jcodebuddy\metadata\entity\hr`.

### 6.3 Classpath scope is load-bearing and easy to get wrong

`exec:java` defaults to the *runtime* classpath scope, which excludes `provided`
dependencies — and the tooling is `provided` on purpose (it must never become a
runtime dependency). Without `<classpathScope>compile</classpathScope>` in
`pom.xml`, neither the preflight nor the generator is on the exec classpath, and the
build fails with a class-not-found rather than anything that says "scope". Both
executions therefore carry it.

### 6.4 `-D` properties still need quoting gymnastics from PowerShell

See § 3.9. The shortcut's behaviour (refuse rather than degrade) is right; the
ergonomics from PowerShell are not, and this one is **not** fixed.
`cmd /c '… "-Dx=y" …'` is the workaround. A `.ps1` companion to
`scripts\mvn-jdk25.cmd` is the obvious next step.

### 6.5 Smaller notes

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
