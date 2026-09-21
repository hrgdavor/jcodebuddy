# JavaParser → OpenRewrite: Caveats and Best Practices

**Audience:** whoever continues the Phase 6 migration.
**Status:** written after the first two porting passes (`hipster-entity-tooling`, 19 files).
Everything marked *measured* was established by running code in this repository, not inferred
from documentation. Everything marked *unverified* has not been confirmed and should be.

This document is deliberately separate from `MIGRATION-GUIDE.md`:

| Document | Purpose |
| --- | --- |
| `MIGRATION-GUIDE.md` | The procedure — how to port a file, step by step |
| **this document** | **The traps — what will go wrong, how it fails, and what to do** |
| `Checklist.md` | Which files remain and what each needs |
| `mappings.js` | The type-by-type mapping table |

Read this document **before** porting a file. Every entry here cost real time to find; none of
them is obvious from the OpenRewrite documentation, and most fail *silently* — producing code
that compiles, passes review, and is wrong.

---

## 1. The five ways a port fails silently

Ordered by how much damage they do. "Silent" means: no compile error, no test failure unless
a test happens to cover the exact case, and a plausible-looking result.

### 1.1 The parser recovers from syntax errors — the fail-safe is gone

**Measured.** On F-34's own fixture:

```java
package p;
public enum Broken_ {
    id(java.lang.Long.class;      // broken: unbalanced paren
}
```

`JavaParser.parseInputs` **neither throws, nor attaches a `ParseExceptionResult` marker, nor
returns anything other than a well-formed `J.CompilationUnit` with one enum in it.**

So the check this project's own migration guide prescribes — catch the throw, look for the
marker — *cannot detect this*. A straight port silently loses the guard that `SourceReader`
exists for, which is the path to F-34's silent renumbering of a persisted positional array.

**What to do:** `SourceReader.readText` asks javac (`JavaSyntaxCheck`). Syntax errors only;
type errors are ignored by diagnostic code, because this reader is handed single files whose
dependencies are not on any classpath.

**Why javac is the right tool:** OpenRewrite's Java parser *is* a javac front end, so
`jdk.compiler` is already a dependency and the two cannot disagree about grammar.

**Compare:** JavaParser was error tolerant but *reported* it (`isSuccessful()`); OpenRewrite is
error tolerant and does not. The regression is in the reporting, not the tolerance.

### 1.2 A reused parser must be reset between parse sets

**Measured.** Without `parser.reset()`:

```
IllegalStateException: Call reset() on JavaParser before parsing another set of
source files that have some of the same fully qualified names.
```

This is not an edge case in a generator — the tooling reads a file, writes a new version, and
reads it again, which is the same FQNs twice. Without the reset, **the second read of any type
reports "unparseable"** and the generator refuses to touch its own previous output.

**What to do:** `reset()` in a `finally` around every `parseInputs` call
(`SourceReader.parse` shows the shape). `merge-java/.../ResolvedTypeReader` instead builds a
fresh parser per read and documents why; resetting is the cheaper equivalent for a shared one.

**Symptom if you forget:** 53 test failures, all reading "could not be read as Java", with an
empty problem list — because the thrown exception was being swallowed into "unparseable".

### 1.3 An interface's `extends` clause is in `getImplements()`

**Measured** on `interface PersonEntity extends EntityBase<String>`:

| accessor | value |
| --- | --- |
| `getExtends()` | `null` |
| `getImplements()` | `[EntityBase<String>]` |

The obvious translation of JavaParser's `getExtendedTypes()` is `getExtends()`. It finds **no
supertype for any interface**, so:

- an inheritance-chain walk indexes nothing, every view looks like it derives from nothing;
- a validator then reports **correct** interfaces as unreachable — the failure mode that got
  `ViewInterfaceRule` rewritten three times (19 findings about 19 correct interfaces);
- `MarkerEntityRule` finds no marker at all and reports **clean**, which for a validator is the
  worst possible outcome.

**What to do:** always `TreeQueries.supertypeNames(declaration)`, which reads both clauses.
Never `getExtends()` directly.

### 1.4 One `J.ClassDeclaration` covers five kinds

`getKind()` returns `Class | Enum | Interface | Annotation | Record` — verified against the
pinned source. There is no `J.InterfaceDeclaration`, no `J.RecordDeclaration`, no
`TypeDeclaration` supertype.

So `instanceof ClassOrInterfaceDeclaration` cannot be translated mechanically. Dropping the
kind test still compiles and starts matching records and enums:

```java
// WRONG: matches records, enums and annotations too
for (var d : TreeQueries.typeDeclarations(cu)) { if (d.getSimpleName().endsWith("Entity")) ... }

// RIGHT
for (var d : TreeQueries.interfaces(cu)) { ... }
```

**Where it bites:** anything that branches on "is this a record/enum/interface" — a wrong answer
changes generated output rather than failing to compile. `GenLevelResolver` decides which builder
levels get emitted from exactly this test.

**Related shapes:**
- a record's components live on `getPrimaryConstructor()`, not a record accessor;
- enum constants are `J.EnumValue` entries inside a `J.EnumValueSet` **statement** in the class
  body — not a constant list on the declaration, and the **order is persisted numerically**, so
  an off-by-one silently renumbers stored data (`EnumCompactionCli`, `EnumConstantOrderChecker`);
- a supertype is a `J.Identifier` when bare but a `J.ParameterizedType` when generic.

### 1.5 Comments are not nodes; the DEC-021 header moves

JavaParser had `cu.getAllComments()`. The LST has **no comment nodes** — comment text is a prefix
on the tree that follows.

**Measured shape of the DEC-021 two-line header:**

```java
// {@link com.example.Foo} Description.
// {enabled:true, entityFieldEnum: true}
package com.example;
```

Both lines arrive as **one** `TextComment` on the **compilation unit's** `getPrefix().getComments()`,
whose text contains an **embedded newline**. The `package` declaration's own prefix is empty.

Two traps follow:

1. You must **split the comment text on line breaks** to find the config line.
2. `{@link …}` also starts with `{`, so a naive "first line starting with `{`" returns the
   Javadoc tag and produces `malformed_generator_header` on a perfectly good header. Skip `{@`.

**Worst case:** `CooperativeCodegen` (DEC-020) recognises its own previously generated output by
structural shape, using ranges and comments. Port it **last**, with the three-state test set
(pristine / tweaked / deleted) on both sides.

---

## 2. Model differences that change code shape

Not traps exactly — they are unavoidable — but each one is a rewrite rather than a rename, and
knowing which is which is what makes estimates honest.

| Task | JavaParser | OpenRewrite | Nature of the change |
| --- | --- | --- | --- |
| Find nodes | `cu.findAll(X.class)` | visitor, or `TreeQueries.findAll` | mechanical |
| Parent access | `node.getParentNode()` | **none** — use a cursor or an explicit stack | rewrite |
| Ancestry for an FQN | walk `getParentNode()` | capture during traversal | rewrite |
| Line numbers | `getName().getBegin().line` | **none** — javac `LineMap` | rewrite |
| Build a tree | `new X()` + `setY()` | immutable `withY()`, or `JavaTemplate` | rewrite |
| Print | `LexicalPreservingPrinter` | `printAll()`; formatting is inherent | **deletion** |
| Package name | `getPackageDeclaration().map(...)` | `TreeQueries.packageName(cu)` | mechanical |
| Class members | `decl.getMembers()` | `getBody().getStatements()`, filtered | rewrite |
| One field | `FieldDeclaration` (+ N variables) | `J.VariableDeclarations` (+ N `J.VariableDeclarator`) | rewrite |
| Constructor | `ConstructorDeclaration` | `J.MethodDeclaration` with `isConstructor()` | mechanical-ish |
| Modifiers | `Modifier.Keyword` enum | ordered `List<J.Modifier>`; `hasModifier(...)` | mechanical |
| Empty parameter list | empty `NodeList` | a single **`J.Empty`** placeholder | **silent trap** |
| Literals | one class per literal type | one `J.Literal` + `JavaPrimitiveType` | mechanical |
| Annotation member | `MemberValuePair` | `J.Assignment`; a single arg is normalised to `value` | mechanical |

**Two entries deserve emphasis:**

- **`J.Empty` for an empty parameter list.** `method.getParameters().isEmpty()` is **false** for a
  no-arg method. Code that filters "no-argument accessors" this way silently matches nothing.
  `TreeQueries.hasNoParameters` exists for this.
- **The `value` normalisation.** `@Foo(Bar.class)` becomes an assignment *named* `value`, where
  JavaParser left it nameless. A reader keyed by pair name must handle it or miss the common
  single-argument case.

---

## 3. Best practices that worked

### 3.1 Put the repeated queries in one helper, and leave construction out of it

`TreeQueries` exists because every ported rule needed the same three things: a `findAll`
replacement, a package name, and a kind test. Scattering that across a dozen rules buries the
rules in traversal code.

It **deliberately builds nothing**. The generator port must stay free to mix `JavaTemplate` with
`withXxx` construction, and a helper that owned construction would force that choice before the
generators are understood. A query returns plain `J` subtypes, which both accept.

### 3.2 Trace the ancestry with an explicit stack, not the cursor path

Two attempts to read the ancestry out of `getCursor().getPath()` / `getPathAsStream()` produced
**wrong chains** — the order is undocumented and measured to be neither root-first nor leaf-first.
A `Deque` the visitor pushes and pops has one correct order by construction
(`TreeQueries.typesWithEnclosing`).

### 3.3 One bridge per un-ported consumer, named for its lifetime

While a module is half-ported, a ported class is often consumed by an un-ported one. Two rules:

1. **Name the accessor for its lifetime, not its function.** `SourceReader.portingParser()`, not a
   general-purpose `parser()`. It carries no `@Deprecated` — that reads as "use the replacement
   instead", and there is no replacement, only files that have not been ported yet.
2. **Bridge by translation, never by duplicating logic.** `ViewAnnotationReader.parse(JavaParser)`
   routes into the same attribute rules; `ClassIndex.addTypes(JavaParser unit)` delegates to the
   same `addTypes(List<TypeFacts>)`. A second implementation is how the two parsers start
   disagreeing about one file.

Every bridge must be listed against the file whose port deletes it. The Phase 6 checklist tracks
them by name.

### 3.4 Preserve the *contract*, change only the tree type

`SourceReader.Read` kept `readable()` and `unparseable()` exactly as they were. Five call sites
depend on the distinction between "no file" and "broken file" — not on the parser. Only the unit
type moved. That is why the port did not touch those call sites' logic.

### 3.5 Fail toward preservation, and say what was not done

`reportUnparseable` names the action *not* taken: "inspect the file by hand: this pass did NOT
preserve the append-only ledger (R1)". A pass over an unreadable file must not look like a pass
over a fresh one. Keep this property in every ported validator.

### 3.6 When a fact is unavailable, return "unknown" — never a guess

`lineOf` returns `-1`, which DEC-029 already defines as "line unknown". An off-by-one line points
a reader at the wrong code while looking authoritative, and DEC-028 **verifies report links
against the line they point at**. A wrong line is strictly worse than an absent one.

### 3.7 Test on both sides of a semantic change

The tests that caught the real bugs in these passes were:
- the **F-34 fixture** (broken enum) — caught the lost fail-safe;
- **`TypeFactsTest`** — caught the kind-collapse and the line work;
- **`ViewInterfaceRuleTest`** — caught the `getImplements()` trap;
- **`EnumConstantOrderCheckerTest`** — caught the header-comment parse;
- **`SourceReaderTest.thePortingBridgeAgreesWithTheLstPath`** — keeps the bridge honest.

For `CooperativeCodegen`, the equivalent is the DEC-020 three-state set. It does not exist yet.

---

## 4. Open gaps

Recorded so they are not mistaken for finished work.

### 4.1 Line numbers are not implemented

`TreeQueries.lineOf` returns `-1`. The JavaParser version answered
`getName().getBegin().line`, and DEC-029's `TypeFacts.line` documents the value.

**What works:** `JavaSyntaxCheck.typeNameLines(source)` returns the correct positions, verified:

```
Marked  -> line 4   (declaration starts line 3 on @Deprecated — the NAME's line, which is the point)
Shape   -> line 2
Circle  -> line 3, enclosing [Shape]
```

**What is unfinished:** matching an LST declaration to its entry. Three attempts produced
confidently wrong lines — the `Shape` / `Shape.Circle` pair share a chain prefix in a way that
defeated a simple-name comparison, a chain comparison, and both together. The current state
returns `-1` rather than anything wrong.

**To finish it:** the entry structure already carries `(simpleName, enclosingNames, line)`. The
likely cause is the enclosing chain's representation differing between the visitor's stack and the
javac scanner's. Test with a fixture whose nested type *shares a name with its parent* — that is
the case that breaks naive implementations, and it is already in `TypeFactsTest`.

**Impact while open:** `ClassIndex` rows carry `line = -1`; the HTML report's member links cannot
be verified against a line. `TypeFactsTest` asserts `-1` explicitly, so completing this is a
deliberate change to that expectation.

### 4.2 The emission strategy is still unchosen

`JavaTemplate` vs `withXxx` construction. `TreeQueries` is neutral so either works. Nothing in
this repository builds trees yet — `merge-java` only reads them — so there is no in-repo example
of either. **Decide once, per module, before porting a generator.** Mixing the two styles inside
one generator is how it becomes unmaintainable.

### 4.3 `project-automation` does not compile

Two syntax errors plus semantic errors behind them, and no OpenRewrite dependency. See
prerequisites P0-1..P0-3. Unrelated to the port — that package uses no JavaParser — but it is the
module Phase 5 was supposed to deliver, so Phase 6 cannot claim its prerequisites are met.

### 4.4 Three files remain behind a bridge

`EnumCompactionCli`, `FieldBoilerplateGenerator`, `EntityMetadataGenerator` still parse with
JavaParser through `SourceReader.readJp`. The first two mutate and print trees, which is why they
belong with the emission decision (§ 4.2).

---

## 5. Verification checklist for any port

Run these in order. Each has caught a real defect in this migration.

```sh
# 1. Does it still behave the same? The module gate, and `clean` is NOT optional.
cmd /c "scripts\mvn-jdk25.cmd -o -pl <module> -am -Dmaven.compiler.useIncrementalCompilation=false clean test"

# 2. Is the JavaParser surface actually gone?
bun run scripts/rewrite-migration/migrate-file.js --after <path>

# 3. Does the tree agree with the tracker?
bun run scripts/rewrite-migration/verify-migration.js
```

**Why `clean` is not optional:** this repository records the hazard as note F-47. A non-clean
`compile` reported `BUILD SUCCESS` with *"Nothing to compile - all classes are up to date"* on a
revision whose staging package did not compile at all. During this migration that is precisely how
the `project-automation` breakage stayed invisible.

**A compile is not a proof.** These are behaviour-bearing generators; a port that compiles and
emits different output is worse than one that fails to build. Diff the generated output for at
least one artifact end-to-end before marking anything complete.

---

## 6. Quick reference

| Question | Answer |
| --- | --- |
| Is this file readable? | `SourceReader.readText(source).readable()` — asks javac, not just the parser |
| Why is my second read failing? | You did not `reset()` the parser |
| Why does my interface have no supertype? | `getImplements()`, not `getExtends()` |
| Why does my "interfaces" query return records? | Missing the `getKind()` test |
| Why is my no-arg method filter matching nothing? | Empty parameter list is a single `J.Empty` |
| Where did `getAllComments()` go? | Comment text is a prefix; the DEC-021 header is on the unit's prefix, one `TextComment` |
| How do I build a node? | `JavaTemplate` or `withXxx` — decide per module first |
| How do I get a declaration's line? | Not yet — `lineOf` returns `-1` (§ 4.1) |
| How do I find a node's parent? | You do not; capture ancestry during traversal |
| Where do I add a type mapping? | `scripts/rewrite-migration/mappings.js`, marked `verified` or `inferred` |

---

## 7. Related documents

- `MIGRATION-GUIDE.md` — the procedure and the type-by-type mapping
- `Checklist.md` — the remaining queue, generated from the tree
- `tracker.md` — per-file status (hand-maintained)
- `scripts/rewrite-migration/README.md` — the tooling
- `../../scripts/rewrite-migration/mappings.js` — the mapping table, with evidence per entry
- `merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java` — the working reference
  port
- [Java LST examples](https://docs.openrewrite.org/concepts-and-explanations/lst-examples)
- [Lossless Semantic Trees](https://docs.openrewrite.org/concepts-and-explanations/lossless-semantic-trees)
- [JavaTemplate](https://docs.openrewrite.org/concepts-and-explanations/javatemplate) and
  [Markers](https://docs.openrewrite.org/concepts-and-explanations/markers)
