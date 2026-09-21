# Phase 6 — Migration Guide

How to port a file in this repository from JavaParser to OpenRewrite, and how to
tell whether the port is correct.

This guide is the **procedural** half of Phase 6. The other three deliverables are:

| Document | Answers |
| --- | --- |
| [`Checklist.md`](Checklist.md) | Which files, what JavaParser surface, what each needs |
| [`tracker.md`](tracker.md) | What the status of each file is |
| `../../scripts/rewrite-migration/README.md` | How to run the tooling |
| **this guide** | **How to do the port, and how to know it worked** |

The machine-readable mapping table lives in
`scripts/rewrite-migration/mappings.js`; this guide explains how to read it and
what it cannot tell you.

---

## 1. Read this before you port anything

Three facts shape the whole phase, and all three were established by running the
tooling against this checkout rather than assumed.

### 1.1 OpenRewrite has its own class called `JavaParser`

```java
org.openrewrite.java.JavaParser      // OpenRewrite's parser — the destination
com.github.javaparser.JavaParser     // JavaParser's parser  — the source
```

They share a simple name. Consequences:

- A `grep javaparser` (case-insensitive) reports every already-migrated file as
  unmigrated. The whole `project-automation` staging package is that false
  positive.
- The two must never be wildcard-imported together. Import `JavaParser` from
  exactly one of them, or write the other fully qualified.
- `verify-migration.js` uses a word-boundary check precisely for this, and
  `rewrite-migration.test.js` has a regression test for it.

### 1.2 There is no `TypeTree`, `MethodTree`, `ClassTree` or `InterfaceTree`

An earlier phase generated `project-automation/src/main/java/hr/hrg/rewrite/**`
against an OpenRewrite API that **does not exist**. The real names are the `J.*`
node types. If you see one of those invented names, you are looking at code that
has never compiled — see § 6.

### 1.3 The API surface is a tree of `J` types, gathered by visitors

JavaParser and OpenRewrite differ most in the two things this repository's
generators do most: **constructing** trees and **finding** things in them.

| Task | JavaParser | OpenRewrite |
| --- | --- | --- |
| Find nodes | `cu.findAll(X.class)` | `new JavaIsoVisitor<>(){...}.visit(cu, ctx)` |
| Build a tree | `new MethodDeclaration()`, `setName(...)` | `JavaTemplate` or immutable `withXxx(...)` chains |
| Print | `LexicalPreservingPrinter.print(cu)` | `cu.printAll()` — formatting is inherent, then verified |
| Write to disk | `Files.writeString(...)` | `Result` / `printAll()`, then verify idempotency |

The `findAll` → visitor change is why a ported file often ends up *longer*: a
visitor is a class, not a lambda, unless you use `TreeVisitor` helpers.

---

## 2. Dependencies

The root POM already manages OpenRewrite (`openrewrite.version` = `8.90.4`), and
`merge-java` is the working reference. A module that needs to parse adds:

```xml
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-core</artifactId>
</dependency>
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-java</artifactId>
</dependency>
<!--
    rewrite-java is a facade: it needs ONE version-specific parser on the
    classpath. -25 is the newest in 8.90.4 and matches the parent's
    maven.compiler.release=25.
-->
<dependency>
    <groupId>org.openrewrite</groupId>
    <artifactId>rewrite-java-25</artifactId>
    <version>${openrewrite.version}</version>
</dependency>
```

`rewrite-java-25` is pinned locally because the parent's `dependencyManagement`
does not cover the version-specific parsers (`merge-java/pom.xml` explains this).
Copy that comment; it is the answer to "why is this one pinned?".

**Do not remove `javaparser-core` module-wide until the last file in the module
is ported.** Removing it early breaks the allowlisted regression guards
(§ 7), and `verify-migration.js` warns — rather than fails — on a module that still
declares it, because a module can legitimately be mid-migration.

---

## 3. Porting procedure

The checklist already prints this per file; it is repeated once here because the
order is load-bearing.

```sh
# 1. See what the file needs, and read the mapping for each type.
bun run scripts/rewrite-migration/migrate-file.js <path>

# 2. Record a baseline so the edit can be attributed.
bun run scripts/rewrite-migration/migrate-file.js --baseline <path>

# 3. Edit.

# 4. The module gate. `clean` is not optional.
cmd /c "scripts\mvn-jdk25.cmd -o -pl <module> -am -Dmaven.compiler.useIncrementalCompilation=false clean test"

# 5. Confirm the JavaParser surface is gone.
bun run scripts/rewrite-migration/migrate-file.js --after <path>

# 6. Update tracker.md, then run the phase gate.
bun run scripts/rewrite-migration/verify-migration.js
```

### Why `clean` is not optional

This repository already records the failure mode as note F-47: Maven's
incremental check can decide a module's sources are up to date and skip the
compile, so a broken revision passes on the previous revision's class files. It
is not hypothetical — during this phase the staging breakage in § 6 was found
**only** because a `clean compile` was run after a plain `compile` had reported
`BUILD SUCCESS` with `Nothing to compile - all classes are up to date`.

### Mapping-confidence vocabulary

Every mapping is marked `verified` or `inferred`, and the difference decides how
much you may trust it:

- **`verified`** — the OpenRewrite side is either used in this repository today
  (`merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java`) or was
  checked against the pinned upstream source for `8.90.4`. The mapping cites
  which.
- **`inferred`** — the documented OpenRewrite type for the concept, but **nothing
  in this repository exercises it**. Confirm it before relying on it. An
  `inferred` mapping is a hypothesis you are being asked to test, not a fact.

If a type is not in the table at all, `migrate-file.js` prints `UNMAPPED`. Add it
to `mappings.js` before porting — that is how the table stays complete for the
next file.

---

## 4. The four traps

These are the mappings where a port can **compile and produce wrong output**,
which is strictly worse than one that fails to compile. They are why this phase
is not a mechanical rename.

### 4.1 Records, enums, interfaces and annotations are one class

Verified against the pinned source: `J.ClassDeclaration.getKind()` returns a
`Kind.Type` whose values are exactly

```
Class, Enum, Interface, Annotation, Record
```

**One class, five kinds.** There is no `J.RecordDeclaration`, no
`J.EnumDeclaration`, and no `TypeDeclaration` supertype. So:

```java
// JavaParser
if (decl instanceof RecordDeclaration) { ... }
if (decl instanceof ClassOrInterfaceDeclaration coid && coid.isInterface()) { ... }

// OpenRewrite
if (decl instanceof J.ClassDeclaration cd
        && cd.getKind() == J.ClassDeclaration.Kind.Type.Record) { ... }
if (decl instanceof J.ClassDeclaration cd
        && cd.getKind() == J.ClassDeclaration.Kind.Type.Interface) { ... }
```

A record's components live on `getPrimaryConstructor()`, not on a
record-specific accessor.

**Why it matters here:** `GenLevelResolver`, `TypeLiterals`, `jwa-builder` and
`jwa-sidecar` all branch on the declaration type. A sloppy port that collapses
"is a record" into "is a class" changes which builder levels are generated — a
silent wrong-output failure.

### 4.2 Enum constants are values in a set, and their order is persisted

```java
// JavaParser
cu.findAll(EnumConstantDeclaration.class)   // in order

// OpenRewrite
// J.EnumValueSet (a Statement) inside J.ClassDeclaration.getBody().getStatements(),
// holding J.EnumValue entries; each name is a J.Identifier, not a String.
```

`EnumCompactionCli` and `EnumConstantOrderChecker` derive an ordering that is
written out as a **persisted positional array**. An off-by-one in the constant
list does not fail to compile; it renumbers stored data. The gate for these two
files is the compaction round-trip test, not a compile:

```
hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java
```

### 4.3 One field declaration can be several variables

```java
// JavaParser: one node for `int a, b;`
FieldDeclaration fd = ...;                       // fd.getVariables().size() == 2

// OpenRewrite: one J.VariableDeclarations carrying two J.VariableDeclarator
J.VariableDeclarations vd = ...;                 // vd.getVariables().size() == 2
```

The counts agree, but the **cardinality of the outer node changes**: code that
counted `FieldDeclaration`s to decide "one field per column" counts correctly
today and would undercount after a port that treated the two as interchangeable.
There is no `J.FieldDeclaration`.

### 4.4 Comments are not nodes, and ranges are not lines

The two highest-risk mappings in the table, and they land on the two
highest-risk files.

| JavaParser | OpenRewrite |
| --- | --- |
| `com.github.javaparser.ast.comments.Comment` — a positioned node | comment **text** attached as a prefix to the following tree; no standalone comment node |
| `com.github.javaparser.Range` — line/column | character **offsets** on the tree; line numbers must be derived from the `SourceFile` text |

**Why it matters here:** `CooperativeCodegen` implements DEC-020 block
preservation. It recognises its own previously generated output **by structural
shape**, and it uses ranges and comments to decide what is present. A location
that shifts by one line changes which generated block is considered present — and
the failure mode is overwriting a hand-edited generated block.

Port these two files **last**, with the DEC-020 three-state behaviour
(present-and-pristine, present-and-tweaked, deleted) covered by a test on each
side. The checklist gives per-file steps.

### 4.5 The failure signal for an unreadable file

Not a mapping, but the property `SourceReader` exists to protect, so it belongs
with the traps.

JavaParser is **error tolerant**: a broken file still yields a *partial* unit, and
treating "the parse returned something" as "the file is readable" is wrong in a
way that is invisible. Notes F-34 and F-23 record what that cost: a syntax error
inside an enum's constant list produced a unit with **no constants**, the ledger
planner read that as "a fresh enum", and rebuilt the constant list — a silent
renumbering of a persisted array, reached by the code written to prevent it.

**OpenRewrite is worse, and this is the most important thing this pass found.** It
also recovers, but more quietly: on F-34's own fixture
(`id(java.lang.Long.class;` inside an enum) `parseInputs` **neither throws, nor
attaches a `ParseExceptionResult` marker, nor returns anything but a well-formed
`J.CompilationUnit` containing one enum**. Measured, not assumed.

So the two checks this guide previously recommended — catch the throw, check the
marker — **do not detect this class of breakage**. A straight port silently loses
the guard, which is exactly the silent-renumbering path F-34 exists to block.

The fix that works asks javac, which is already on the classpath because
OpenRewrite's Java parser *is* a javac front end:

```java
var task = (JavacTask) compiler.getTask(
        null, manager, diagnostics, List.of("-proc:none", "-nowarn"),
        null, List.of(new SourceFileObject(source)));
task.parse();                       // grammar only: no classpath, no analysis
```

Only **syntax** errors are treated as unreadable; type errors are ignored by
diagnostic code, because this reader is handed single files and fragments whose
dependencies are not on any classpath — reporting "cannot find symbol" as "the
file is unreadable" would reject most of the tree. `JavaSyntaxCheck` implements
this and fails safe toward "not well-formed" on any doubt.

The other two checks are still required, and `SourceReader.readText` applies all
three:

```java
try (var parsed = parser.parseInputs(
        List.of(input(code, inputPath)), sourceRoot, analysisContext())) {

    parsed.forEach(sourceFile -> {
        // (1) the parser's own complaint, as a marker — necessary but NOT sufficient
        sourceFile.getMarkers().findAll(ParseExceptionResult.class)
            .forEach(error -> failures.add(error.getExceptionType() + ": " + error.getMessage()));

        // (2) not a compilation unit at all
        if (sourceFile instanceof J.CompilationUnit cu) {
            // good
        } else if (failures.isEmpty()) {
            failures.add("the source could not be parsed");
        }
    });
} catch (RuntimeException e) {
    failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
}
// (3) recoverable-but-broken syntax: ask javac (see above)
```

### 4.6 A parser must be reset between parse sets

`parseInputs` on a reused parser throws:

```
IllegalStateException: Call reset() on JavaParser before parsing another set of
source files that have some of the same fully qualified names.
```

This is not an edge case in a generator: the tooling reads a file, writes a new
version, and reads it again — the same FQNs twice. Every `readText` must call
`parser.reset()` in a `finally`, or the second read of any type reports
"unparseable" and the generator refuses to touch its own previous output.
`merge-java/.../ResolvedTypeReader` builds a fresh parser per read for the same
reason; resetting is the cheaper equivalent for a shared instance.

### 4.7 An interface's `extends` clause is in `getImplements()`

Measured on `interface PersonEntity extends EntityBase<String>`:

| accessor | value |
| --- | --- |
| `getExtends()` | `null` |
| `getImplements()` | `[EntityBase<String>]` |

A port that reads `getExtends()` — the obvious translation of JavaParser's
`getExtendedTypes()` — finds **no supertype for any interface**. The rule then
sees every view as deriving from nothing and reports correct interfaces as
unreachable. `TreeQueries.supertypeNames` reads both clauses and is the only
sanctioned way to ask.

Two related shapes, both silent if missed: a supertype is a `J.Identifier` when
bare but a `J.ParameterizedType` when generic (`EntityBase<Long>`), and the
DEC-021 header comment is a **single** `TextComment` on the compilation unit's
prefix whose text contains an embedded newline — so splitting it on line breaks
is required, and the `{@link …}` first line must be rejected as non-JSON even
though it also starts with `{`.


---

## 5. Finding and building trees

### Finding: `findAll` becomes a visitor

```java
// JavaParser
List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);

// OpenRewrite
List<J.MethodDeclaration> methods = new ArrayList<>();
new JavaIsoVisitor<ExecutionContext>() {
    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration m, ExecutionContext ctx) {
        methods.add(m);
        return super.visitMethodDeclaration(m, ctx);
    }
}.visit(cu, new InMemoryExecutionContext());
```

Prefer a visitor over hand-rolled parent walks. JavaParser's `Node.getParentNode()`
becomes cursor access, which is a larger change than it looks.

**Do not pool a `JavaParser` across parse sets.** A parser caches the sources it
has parsed and refuses a second set declaring the same fully qualified names
("Call reset() on JavaParser before parsing another set of source files that have
some of the same fully qualified names"). Comparing three versions of the *same*
file is exactly that case. `ResolvedTypeReader` documents building one parser per
read and why a cache here would produce a wrong answer.

### Building: pick one strategy per generator, and write it down

The LST is immutable, so `new X()` + `setY(...)` becomes construction plus
`withY(...)`. There are two reasonable ways to build, and mixing them in one
generator is how a generator becomes unmaintainable:

1. **`JavaTemplate`** — write the generated code as a Java string with
   placeholders. Readable, and closest to what a generator is actually doing.
2. **`withXxx` chains** — construct from existing nodes. Precise, verbose.

`merge-java` does neither: it only *reads* trees. So there is no in-repo example
of either strategy yet, and **this is the single largest open decision in the
phase**. Make it once, for the module, before porting
`EntityMetadataGenerator` or `FieldBoilerplateGenerator` — do not decide it
per file.

### Printing and writing

```java
// JavaParser
LexicalPreservingPrinter.setup(parseResult);
LexicalPreservingPrinter.print(cu);

// OpenRewrite
String out = cu.printAll();   // formatting is inherent; no setup step
Files.writeString(file, out);
```

`LexicalPreservingPrinter` disappears — that is the one place the port *removes*
code rather than translating it. The guarantee is different in kind:

- JavaParser preserves by **opt-in**: without the call, it reformats.
- OpenRewrite preserves by **construction**, and then **verifies**: with
  `org.openrewrite.requirePrintEqualsInput` at its default, a tree whose print
  output differs from its input is rejected.

So keep the verification **on** for generation, and disable it **deliberately**
for fragment analysis — a conflicting hunk is a fragment and by definition does
not print back to itself. `ResolvedTypeReader.analysisContext()` is the in-repo
example of the deliberate disable:

```java
InMemoryExecutionContext context = new InMemoryExecutionContext();
context.putMessage("org.openrewrite.requirePrintEqualsInput", false);
```

Note the one artefact this changes: `EnumCompactionCli` prints whole files through
the pretty printer, so its output diff **will** change. Review and accept that
diff deliberately; do not discover it in review.

### Testing a recipe or transformation

OpenRewrite's own harness is the idiomatic tool, and using it is what makes a
port's behaviour comparable:

```java
class MyRecipeTest implements RewriteTest {
    @Test
    void transforms() {
        rewriteRun(
            spec -> spec.recipe(new MyRecipe()),
            java("class A {}", "class A { /* transformed */ }")
        );
    }
}
```

To find out what LST a piece of code actually is, `TreeVisitingPrinter.printTree`
is the fastest path — see the
[Java LST examples](https://docs.openrewrite.org/concepts-and-explanations/lst-examples)
page, which also documents `JavaTemplate` and the debugger approach.

---

## 6. Prerequisites: fix these before porting

`verify-migration.js` currently **fails** on two checks. Both are real, and both
must be cleared before the first file is ported, because a port cannot be verified
against a build that was already broken.

### The staging code does not compile

`project-automation/src/main/java/hr/hrg/rewrite/**` fails `clean compile` with 7
errors. Two are syntax errors, which is why the rest are invisible:

- `OpenRewriteValidationGenerator.java:83` —
  `sb.append("}")\n\n");` — the closing paren of `append(` is inside the string
  literal, so the statement never terminates.
- `OpenRewriteFieldBoilerplateGenerator.java:131` —
  `sb.append("    public String ").append(property.name()).append "() {\n");` —
  the second `.append` is missing its argument parentheses.

Behind them the package references **types that do not exist**
(`hr.hrg.hipster.entity.tooling.TypeTree`, `InterfaceTree`, `MethodTree`,
`ClassTree`; the check reports 10 such references), calls a package-private
`SourceReader.readText()`, and calls `getTypes()` / `addMember()` on JavaParser's
`CompilationUnit` as though it were an LST. `project-automation/pom.xml` also
declares **no OpenRewrite dependency at all**.

This is Phase-0 repair work, not Phase 6 migration work: the package uses no
`com.github.javaparser` at all, so there is nothing to migrate. It is recorded as
prerequisites **P0-1** to **P0-3** in `scripts/rewrite-migration/curation.js` and
in `Checklist.md`.

### The plan's file list is wrong

`plans/rewrite-migration/06-Migration-Checklist.md` § Files to Migrate names
paths that do not exist (`webview-jetbrains/.../HttpBridgeStartupActivity.java`)
and omits six files whose JavaParser use is fully qualified and therefore
invisible to an import-based scan. `Checklist.md` § *Corrections to the plan's
file list* has the full comparison. Use the Checklist, not the plan, as the work
list.

---

## 7. The allowlist

Some files legitimately keep a JavaParser reference and must **not** be ported.
`verify-migration.js` reports them as EXEMPT with their reason rather than
failing or silently dropping them, because "found nothing" and "found something I
chose to ignore" are different answers.

Two are worth understanding before you touch anything near them:

- **`DependencyBoundaryTest.java`** asserts that `javaparser-core` is declared
  with **no version of its own** and that the root POM is the single place the
  version appears. That is the regression guard for note F-23 (the local
  repository holds eleven JavaParser versions; an ad-hoc classpath picked an old
  one; the generator then silently produced **nothing** for five example files).
  It is also the test that polices the `pom-dependencies` warning. Retire it in
  the commit that removes the dependency — not before.
- **`SourceReaderTest.java`** asserts the configured language level is `JAVA_25`.
  When `javaparser-core` goes, re-express it as *"a record, a switch expression
  and a sealed type all parse cleanly"* — that preserves the property without
  naming the library.

Each allowlist entry carries a `deferredTo` naming the condition that removes it.
An entry whose file no longer mentions JavaParser is reported as
"the exemption can be retired" — that is a prompt, not a failure.

---

## 8. Architecture compliance

Phase 6 is where the architecture decisions are cashed in, and two of them are
directly at stake.

| Decision | What the port must preserve |
| --- | --- |
| **DEC-019** — source-visible wiring | Routing and wiring stay navigable Java. The ported staging files carry an `Original JavaParser location:` provenance annotation for exactly this reason; it is the navigational half of the port and must not be "cleaned up". |
| **DEC-020** — cooperative codegen | Block recognition is **structural**, not marker-based. Porting `CooperativeCodegen` without the three-state test set risks overwriting hand-edited generated blocks — the one outcome the decision exists to prevent. |
| **DEC-021** — generator class header | The `{@link <fqn>}` first line is generated from the type's own name and is refactor-sensitive. `TypeLiterals` implements it; keep the emitted header byte-identical. |
| **DEC-022** — refactor-sensitive naming | A name derived from a Java identifier must stay reachable by IDE rename; an explicit API label (JSON-RPC method, audit event) must not be. Node construction is where a derived name can accidentally become a string literal. |
| **DEC-029** — class index by FQN | `ClassIndex` / `TypeFacts` write `.jcodebuddy/index/classes.json`, keyed by FQN. The **JSON shape is the contract** — keep `kind` spellings byte-identical and let only the tree access change. |

Where rule §1 of `AGENTS.md` and the plan conflict, §1 wins. It does not conflict
here: the port moves *towards* committed, navigable source.

---

## 9. Suggested order

Derived from the dependency structure, not from the plan's file list.

1. **Clear P0-1..P0-3** so the module builds.
2. **`SourceReader`** — 33 of 34 queue files read through it. Proving the read
   path alone de-risks everything else.
3. **The small validators** — `AuditableRule`, `EntityRule`, then
   `MarkerEntityRule`, `ViewInterfaceRule`, `ViewAnnotationRule`,
   `EntityFieldEnumOrderRule`. These establish the `J.ClassDeclaration` kind-test
   pattern at low risk.
4. **`JavaParserTool`** (rename it in the same commit) and the annotation readers.
5. **The three `java-watch-agent` tools together** — they share one shape.
6. **`java-watch-agent`/`JavaParserFactory`** and the `jwa-sidecar` and
   `jwa-builder` modules.
7. **The generators** — biggest, and they depend on the emission-strategy
   decision from § 5.
8. **`EnumConstantOrderChecker` / `EnumCompactionCli`** — persisted-order hazard;
   gate on the round-trip test.
9. **`CooperativeCodegen`** last.
10. **The tests**, then remove `javaparser-core` and re-express the allowlisted
    guards.

`Checklist.md` prints the queue in this order with per-file notes; prefer it over
this summary when the two disagree, since it is generated from the tree.

---

## 10. References

- [Java LST examples](https://docs.openrewrite.org/concepts-and-explanations/lst-examples) —
  the `J.*` types, with diagrams. Pinned content for the version in use.
- [Lossless Semantic Trees](https://docs.openrewrite.org/concepts-and-explanations/lossless-semantic-trees)
- [Visitors](https://docs.openrewrite.org/concepts-and-explanations/visitors) and
  [Cursors](https://docs.openrewrite.org/concepts-and-explanations/cursors)
- [JavaTemplate](https://docs.openrewrite.org/concepts-and-explanations/javatemplate)
- [Markers](https://docs.openrewrite.org/concepts-and-explanations/markers) —
  `ParseExceptionResult` and friends
- [Type attribution](https://docs.openrewrite.org/reference/type-attribution) —
  needed for `getMethodType()`; absent in fragment parsing
- In-repo: `merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java` —
  the working reference implementation
- In-repo: `scripts/rewrite-migration/mappings.js` — the mapping table this guide
  explains
