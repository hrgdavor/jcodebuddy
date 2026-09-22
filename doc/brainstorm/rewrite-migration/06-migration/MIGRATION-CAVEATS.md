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

## 1. The eleven ways a port fails silently

Ordered by how much damage they do. "Silent" means: no compile error, no test failure unless
a test happens to cover the exact case, and a plausible-looking result. (§1.6 throws, §1.7 is silent, and §1.8 is a trap in the *old* code;
but it is the fastest way to lose an afternoon, and it is the same class of assumption.)

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
Never `getExtends()` directly. When the **type argument** matters too — `EntityBase<Long>`,
`Identifiable<Long>` — use `TreeQueries.supertypeTypes` (the nodes) or
`TreeQueries.supertypeTexts` (the written form), both of which read both clauses and keep the
arguments that a simple name discards.

**Measured cost of getting this wrong, in the ported hub.** `EntityMetadataGenerator` decided "is
this interface an entity marker?" from `decl.getExtends() instanceof J.ParameterizedType`. Every
marker was therefore *not* a marker, discovery produced zero views, and the pass wrote nothing —
with **no exception, no warning and no divergence**, and `mvn compile` reported success. The
symptom was 154 test failures spread over 27 unrelated test classes (94 assertions, 52
`NoSuchFileException`, 6 `ClassNotFoundException`), because every generator downstream of discovery
had nothing to generate. `EntityMetadataGeneratorTest` was the only place the real cause was
visible, and then only as `expected: <Long> but was: <>`.

That is the shape to look for: **one silent `false` at the head of a pipeline reads as "the whole
feature is unimplemented", not as "one clause was read from the wrong accessor".**

### 1.9 A literal does not round-trip: `J.Literal.toString()` is the *value*

**Verified against the pinned 8.90.4 source** (`org/openrewrite.java.tree.J.Literal`):

```java
@Override
public String toString() {
    return String.valueOf(value);          // NOT the source text
}
```

So `J.Literal.toString()` for the string literal `"[A-Z]{2}"` is `[A-Z]{2}` — the quotes are gone.
JavaParser's expression `toString()` printed source, so **every port that carried an annotation
argument, a default value or a literal through `String.valueOf(node)` changed the meaning of the
text it was carrying.**

The live defect: an author's `@Pattern(regexp = "[A-Z]{2}")` on a view accessor was carried into
the generated record as

```java
@Pattern(regexp = [A-Z]{2})          // illegal start of expression
```

— generated source that does not compile, produced from correct input, because the constraint text
is carried as *text* (`FieldConstraint`) and inserted verbatim.

**What to do:** render expressions with `TreeQueries.expressionText(J)` and types with
`TreeQueries.typeText(J)`; never `String.valueOf(node)` or `node.toString()` on a value you intend
to re-emit. `expressionText` prefers `J.Literal.getValueSource()`, which the parser **always** sets
to the exact source slice (`ReloadableJava25ParserVisitor.visitLiteral`:
`valueSource = source.substring(node.getStartPosition(), endPos)`), and reproduces the canonical
quotes only when it is absent — so `1L`, `0x1f` and text blocks survive too.

`J.Assignment.toString()` and the printer-based `toString()` of *compound* nodes are safe (they go
through `JavaPrinter`); the trap is calling `toString()` on the **leaf** — `J.Literal`,
`J.Identifier`, `J.Empty` — and then assembling the text yourself.

### 1.10 `findAll` reaches nested types where JavaParser's accessors did not

`J.ClassDeclaration.getBody().getStatements()` is the direct-member list;
`TreeQueries.findAll(decl, J.MethodDeclaration.class)` is a **recursive** walk. JavaParser's
`getMethods()`/`getExtendedTypes()`/`getFields()` returned direct members only, so the obvious
translation of any of them is too broad.

The live case: `TreeQueries.methodsOf` was first written as `findAll(declaration, MethodDeclaration)`,
which collected the accessors of a *nested* type as well. Every view in the example declares a
nested record, so a view's property list gained the record's components — the field enum, the
builder and the metadata JSON would all have described fields the view never declared.
`methodsOf` now walks `getBody().getStatements()`.

**Rule:** a JavaParser member accessor (`getMethods`, `getFields`, `getConstructors`,
`getExtendedTypes`/`getImplementedTypes`, `getMembers`) maps to a walk over
`getBody().getStatements()`. `findAll` is the replacement for `findAll(X.class)`, which really was
recursive — matching them up by name rather than by reach is what goes wrong.

### 1.11 The printer differs, and the difference is load-bearing

JavaParser's pretty printer separates type arguments with a **bare comma**; OpenRewrite's printer
puts a **space** after it:

| written | JavaParser `asString()` | OpenRewrite `toString()` |
| --- | --- | --- |
| `Map<String, List<Long>>` | `Map<String,List<Long>>` | `Map<String, List<Long>>` |

This is not cosmetic in this repository, for two independent reasons:

1. **The committed example is compared byte-for-byte.** `ExampleRegenerationTest` regenerates the
   example module and fails on any diff, and `ViewRecordGeneratorTest` asserts the declared type is
   emitted *as written* (`header.contains("Map<String,List<Long>>")`).
2. **The generator recognises its own previous output by that text** (DEC-020's implicit block
   detection). A printer difference therefore reads as "the user edited this member": the pass
   reported `generated_member_diverged` for `PersonSummaryBuilder.metadata`, `.set` and both
   tracking counterparts, and `ExampleDivergenceReportTest` failed on "no unplanned divergence may
   appear in a default pass".

**What to do:** `TreeQueries.typeText(type)` normalises the type-argument separator once, for the
same reason `expressionText` exists — the emitters must not each rediscover it. A port that changes
one comma can break the cooperative-codegen contract *and* the byte-identical gate at the same
time.

**Generalisable lesson:** when replacing a printer, diff its output on the shapes the project
actually emits (`Map<String, List<Long>>`, arrays, wildcards, nested generics) before trusting it.
"Both print Java" is not the property being relied on; "both print *this* Java identically" is.

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

### 1.6 Absent lists are `null`, not empty

**Measured the hard way:** 191 test failures from one bare for-each.

JavaParser's `NodeList` was always non-null, so `decl.getTypeParameters().forEach(...)` was safe
and idiomatic. Several LST accessors instead return **`null`** when the construct is absent:

| accessor | empty case |
| --- | --- |
| `J.ClassDeclaration.getTypeParameters()` | **null** |
| `J.MethodDeclaration.getTypeParameters()` | **null** |
| `J.ClassDeclaration.getImplements()` | **null** |
| `J.Annotation.getArguments()` | **null** (bare `@Foo` vs `@Foo()`) |
| `J.ClassDeclaration.getPrimaryConstructor()` | **null** (not a record) |

Only some return empty lists. There is no rule you can guess from the name — the verified shapes
are recorded in `mappings.js` per type, and the safe habit is to null-check before iterating:

```java
List<J.TypeParameter> parameters = declaration.getTypeParameters();
if (parameters != null) { for (J.TypeParameter p : parameters) { ... } }
```

**Also note the label differs from the type:** `J.TypeParameter.getName()` returns a `TypeTree`,
not a `String`. `toString()` on it includes the bounds (`T extends Foo`), so a set of parameter
*names* built that way contains the whole clause and matches nothing. Use
`TreeQueries.simpleTypeName(parameter.getName())`.

### 1.7 A value that looks present but means "empty" — `J.Empty`

**This one produced a real defect**, not just a confusing failure: `@View(addons = {})` reported
**one phantom addon named `Empty`** and no diagnostic.

An empty *initialiser*, like an empty parameter list, is held as a single **`J.Empty` placeholder**
rather than an empty list. Chasing the collection for elements therefore yields one node that is a
real child, prints as `Empty`, and has no name to read. The same placeholder appears in at least
three positions:

| construct | LST shape | populated shape |
| --- | --- | --- |
| `@Foo()` / `@Foo(addons = {})` | one `J.Empty` | assignments / `J.NewArray` elements |
| no-argument method | `getParameters()` = one `J.Empty` | `J.VariableDeclarations` entries |
| empty array initialiser | one `J.Empty` | the elements |

**What to do:** treat `J.Empty` as "no entry", not as "an entry whose name could not be read".
Skipping it silently is right; diagnosing it produces a false report on correct source. Existing
helpers do this already — `TreeQueries.hasNoParameters`, and the `J.Empty` guard in
`ViewAnnotationReader.parseAddonNames` that this defect added.

**Why it is worth its own entry:** the failure is *plausible*. `Empty` looked like a class name and
flowed into a generated `addons` list without anything complaining, and the test that caught it
(`ViewAnnotationReaderTest.emptyAddonsListParses`) was pre-existing — the port was the first time
that shape was exercised on the LST path.

### 1.8 `LexicalPreservingPrinter`'s fallback was the common path, not the rare one

A trap in the *old* code that only surfaced when porting it — worth recording because "we are
replacing the printer" sounds like a lateral move until you check which branch actually ran.

`ViewInterfaceGenerator` adds a `default` method to a developer's own interface. It set up
`LexicalPreservingPrinter` to protect the surrounding formatting, and caught the printer's refusal:

```java
try { out = LexicalPreservingPrinter.print(cu); }
catch (RuntimeException e) { out = cu.toString(); }   // <- this branch, for this construct
```

**The printer cannot place an added `default` modifier** — it fails with *"Not supported
keywordDEFAULT"* — which is precisely the construct this generator exists to emit. So the fallback was
not a safety net for an exotic case; it was the normal path, and adding one method **reformatted the
whole hand-written file**.

The port removed the dilemma rather than choosing a side: an LST is immutable, so the member cannot be
added to a tree at all, and `SourceSplicer` inserts the generated text before the interface's closing
brace. Everything else is byte-identical by construction, which is the property the printer was there
to provide and could not.

**Generalisable lesson:** when replacing a mechanism, check *which branch of it actually runs in
production*. A `catch` that reads as defensive can be the hot path, and the "equivalent" replacement
may then be a strict improvement rather than a lateral move. `ViewInterfaceGeneratorTest` now pins the
property (byte-preservation of hand-written formatting, idempotence, and insertion inside the
interface rather than after a trailing type).

---

## 2. Model differences that change code shape

Not traps exactly — they are unavoidable — but each one is a rewrite rather than a rename, and
knowing which is which is what makes estimates honest.

| Task | JavaParser | OpenRewrite | Nature of the change |
| --- | --- | --- | --- |
| Find nodes | `cu.findAll(X.class)` | visitor, or `TreeQueries.findAll` | mechanical |
| Parent access | `node.getParentNode()` | **none** — use a cursor or an explicit stack | rewrite |
| Ancestry for an FQN | walk `getParentNode()` | capture during traversal | rewrite |
| Line numbers | `getName().getBegin().line` **and** `getBegin().line` | **none** — javac `LineMap`, and the two differ | rewrite |
| Build a tree | `new X()` + `setY()` | immutable `withY()`, or `JavaTemplate` | rewrite |
| Print | `LexicalPreservingPrinter` | `printAll()`; formatting is inherent | **deletion** |
| Print a *type* | `asString()` — bare comma between arguments | `toString()` — comma **and space** | **silent trap** |
| Package name | `getPackageDeclaration().map(...)` | `TreeQueries.packageName(cu)` | mechanical |
| Class members | `decl.getMembers()` | `getBody().getStatements()`, filtered | rewrite |
| One field | `FieldDeclaration` (+ N variables) | `J.VariableDeclarations` (+ N `J.VariableDeclarator`) | rewrite |
| Constructor | `ConstructorDeclaration` | `J.MethodDeclaration` with `isConstructor()` | mechanical-ish |
| Modifiers | `Modifier.Keyword` enum | ordered `List<J.Modifier>`; `hasModifier(...)` | mechanical |
| Empty parameter list | empty `NodeList` | a single **`J.Empty`** placeholder | **silent trap** |
| Literals | one class per literal type | one `J.Literal`; `toString()` is the **value** | **silent trap** |
| Enum constants | `EnumDeclaration.getEntries()` | one `J.EnumValueSet` statement holding `J.EnumValue`s | mechanical |
| Annotation member | `MemberValuePair` | `J.Assignment`; a single arg is normalised to `value` | mechanical |

**Three entries deserve emphasis:**

- **`J.Empty` for an empty parameter list.** `method.getParameters().isEmpty()` is **false** for a
  no-arg method. Code that filters "no-argument accessors" this way silently matches nothing.
  `TreeQueries.hasNoParameters` exists for this.
- **`J.Literal`.** One class where JavaParser had one per literal type, and it does not round-trip:
  see § 1.9.
- **Two line numbers, not one.** JavaParser exposed both a declaration's start (annotations
  included) and its name's line, and this codebase recorded *both* for different consumers: the
  metadata view line is the declaration's (`EntityMetadataGeneratorTest` pins line 8 for a `@View`
  on line 8 above an interface on line 9) while the class index and location rows want the name's
  line. `TreeQueries.declarationLineOf` and `TreeQueries.lineOf` answer the two questions
  separately, and `JavaSyntaxCheck.TypePosition` carries both.
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

### 4.1 ~~Line numbers are not implemented~~ — RESOLVED

*(Was an open gap; kept because the failure mode is instructive and the new tests are named
after it.)*

`TreeQueries.lineOf` now returns real line numbers. The recipe, since the LST cannot supply
this and the next generator may need the same:

1. `JavaSyntaxCheck.typeNameLines(source)` returns `(simpleName, enclosingNames, line)` per
   declaration, using javac's `LineMap` over the same text.
2. Match an LST declaration to its entry on **both** the simple name **and** the enclosing chain.

**The bug that cost three attempts.** The chain key alone is not enough. For

```java
interface Shape { record Circle(double r) {} }
```

the javac entries are `(Shape, [])` and `(Circle, [Shape])`. The natural-looking formulation
builds the lookup key by appending the declaration's own name — giving `(Shape, [Shape])` for the
outer declaration — and then compares that against **each entry's `enclosingNames`**. `Circle`'s
entry is `(Circle, [Shape])`, whose chain matches, so the match fires on the *name* `Circle` while
answering for `Shape`: **the outer interface reported the inner record's line.**

Two other wrong answers were produced on the way and are worth avoiding:

- comparing only the simple name — `Shape`/`Shape.Circle` collide on it;
- reading the ancestry from `getCursor().getPath()` / `getPathAsStream()` — the order is
  undocumented and measured to be neither root-first nor leaf-first, so the chain was silently
  reversed for some declarations.

**Tests that pin it** (both are deliberate regression guards, not incidental coverage):
- `SourceReaderTest.javacPositionsResolveTheNameLine` — the name's line, not the annotation's;
- `SourceReaderTest.javacPositionsCarryTheEnclosingChain` — the chain shape;
- `TypeFactsTest.nestedDeclarationLinesResolveWithoutCollision` — the collision case above.

### 4.2 The emission strategy — RESOLVED: text, and the printer is part of the contract

*(Was the phase's open decision. Kept because the reasoning is what a future emitter needs, and because
the resolution corrects the original framing.)*

`JavaTemplate` vs `withXxx` construction was the original framing. Porting the module resolved it more
precisely than that, because the answer depends on whether a file **owns whole files** or **edits
text** — and, for two of them, on something the framing did not anticipate: **the printer's output is
itself a committed contract.**

| what the file does | how the port emits | why |
| --- | --- | --- |
| builds a file it owns from a model (`ValidationGenerator`, `ViewRecordGenerator`, the builders, `EntityMetadataGenerator`, `FieldBoilerplateGenerator`) | **text** — `StringBuilder`, then `SourceSplicer` where a piece lands inside hand-written source | the committed example is compared byte-for-byte (`ExampleRegenerationTest`), and the generator recognises its own previous output by that text (DEC-020). Text it wrote is text it can match. |
| adds a member to a developer's file (`ViewInterfaceGenerator`) | **text splice** before the closing brace | the LST is immutable, and `LexicalPreservingPrinter` could not place a `default` modifier at all (see § 1.8) |
| reads a previous revision and carries members through | **text slice by javac offset** (`TreeQueries.memberText`) | a print round-trip normalises the developer's formatting; DEC-020 requires verbatim |
| **deletes** members from a generated file (`EnumCompactionCli`) | **text slice by javac offset** (`TreeQueries.memberTextSpan`, `TreeQueries.caseSpans`) | there is no `remove` on an immutable tree, and deleting a span is what `entry.remove()` meant |

`TreeQueries` stays neutral, as it was designed to: it constructs nothing, so a future emitter can still
choose `JavaTemplate`.

**The two files that forced the decision, and what they cost.** `FieldBoilerplateGenerator` built a whole
`CompilationUnit` with JavaParser (`addEnum`, `addEntry`, `addMember`, `setJavadocComment`,
`parseExpression`) and printed it through `PrettyPrinterConfiguration` with two textual fix-ups.
`EnumCompactionCli` mutated a parsed enum (`entry.remove()`) and printed it. Re-emitting them as text
turned out to mean reproducing **the printer's artefacts**, each of which is now asserted by a committed
file or a test:

- **the enum layout switches at five constants.** Up to five the printer aligns constants horizontally
  (`PaymentMethodAuditable_`: `, createdAt(...)`), past five it puts each separator on its own line
  (`PersonSummary_`: `,` then `firstName(...)`). It also switches as soon as any constant carries a
  comment, which a tombstone's javadoc is. `FieldBoilerplateGenerator.MAX_HORIZONTAL_CONSTANTS` is that
  default.
- **`@Override()` keeps its empty parentheses**, and a constant's overrides are separated by blank lines.
- **a constant's separating comma sits on its own line** when the constant has a class body, and inline
  when it does not.
- **the generic comma has two spellings in the same pass** (§ 1.11): declarations use the compact
  `Map<String,List<Long>>`, while a creator-body *cast* uses `Map<String, List<Long>>`. Both are pinned by
  `ViewRecordGeneratorTest`, in the same class, four lines apart — which is how the split was found.
- **the DEC-021 header uses `\n` while the body uses the platform separator**, so a generated file
  genuinely mixes line endings (110 CRLF and 2 LF in `PersonSummary_.java`). Reproduced, not normalised,
  because the example gate compares bytes.

**The generalisable lesson:** ask "does this file *write* Java?" before estimating a port. Reading code
is a rename; writing code is an emission decision — and if the output is committed and compared
byte-for-byte, **the emitter's printer is part of the contract**, down to its line endings. The
alternative (build with the LST and print with OpenRewrite's printer) was mechanically reasonable and was
rejected for exactly that reason: it would have regenerated every committed field enum with different
bytes. The brief that framed the choice, with both routes and their acceptance criteria, is
[`FOLLOWUP-06-Emission.md`](FOLLOWUP-06-Emission.md).

### 4.3 `project-automation` — RESOLVED by deleting the staging package

*(Was an open gap. Kept because both the diagnosis and the resolution are instructive.)*

**Two syntax errors plus ~200 semantic errors**, and no OpenRewrite dependency.

Fixing the two syntax errors mattered for a non-obvious reason: **a parse error suppresses every
semantic diagnostic in the same compilation**, so javac reported 7 errors and hid the other 200.

With those fixed, the staging package reported **200 errors across 14 files**, naming eight types
that exist nowhere:

```
TypeTree, ClassTree, MethodTree, FieldTree, AnnotationTree, EnumDeclarationTree,
CodeResolver, ViewMetaImpl
```

`CodeResolver` and `ViewMetaImpl` are worth singling out: `git log --all` shows they were **never in
the repository**. The package was not a half-finished migration; it was written against an API that
was imagined rather than read.

**Resolution: deleted** — 24 main files plus 6 staging tests — after two measurements made that the
right answer rather than merely the cheap one:

- **Nothing outside `project-automation` referenced it.** Every `.java` in the tree was scanned; the
  only references were in `doc/` and `plans/` artifacts, which are migration notes.
- **`project-automation`'s own live classes did not reference it either** — the six staging tests were
  the only callers, and they tested the broken package itself.

The behaviour it was reaching for already exists, ported and tested, in `hipster-entity-tooling`. The
module's 13 live sources compile and pass their tests, and its `javaparser-core` dependency went with
the package (nothing else in the module imported JavaParser).

**The generalisable lesson:** when code cannot compile, check *who references it* before estimating
the repair. "200 errors" reads as a rewrite; "200 errors in code nothing calls" is a deletion — and
deleting it unblocked a module that had been failing for an unrelated reason.

### 4.4 `java-watch-agent` did not compile — RESOLVED, and it was two imports and one Jackson 3 call

*(Was the phase's largest blocker: five JavaParser files with no gate to port against. Kept because the
diagnosis was wrong twice before it was right.)*

The first diagnosis was "`FileChange` and `ToolContext` do not exist anywhere in the repository, and
never have". Measured again at the end of the phase, **they do exist** — both are declared *inside*
`ActionTool` (`record FileChange`, `interface ToolContext`), and the file that could not see them was
`ActionToolAdapter`, which uses them **unqualified** in the same package. A nested type is not visible to
another type in its package, so those are two missing imports, and their absence reads as six
"cannot find symbol" errors naming a type that plainly exists.

The second was the Jackson 3 incompatibility, and it was real: `ObjectMapper.enable(...)` does not exist
in `tools.jackson` — a mapper is configured by its builder and is then immutable — so the field
becomes `JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()`.

With those three lines the module **compiles**. That matters for Phase 6 beyond this list: those five
files now have the one gate this module ever had (a compile), so they can be judged at all.

Two consequences worth knowing:

- `java-watch-agent` depended on `javaparser-core` **transitively** through `project-automation`.
  Removing it from that POM surfaced the missing artifact to `java-watch-agent` — a reminder that a
  transitive dependency is a real dependency for anyone who imports the classes. The same thing then
  happened one level down: removing `javaparser-core` from `jwa-builder` (once every file there was
  ported) immediately broke `jwa-sidecar`, which had been using it transitively. That is why the POM
  cleanup is the *last* step of a module's migration rather than a parallel one.
- `metadata-server`'s `MetadataServerTest.httpForyRoundTrip` fails with an HTTP 500 **in isolation**, so
  any `-pl <downstream> -am clean test` reactor run still fails in `metadata-server`. The compile gate
  used here is `-pl <module> -am -DskipTests clean compile`, which is what the module's own (empty) test
  set makes equivalent. A whole-reactor `clean test` also stops earlier, in `java-watch-scp`'s
  `ConfigTest.testSshConfigResolution`, on a **Windows path separator** comparison
  (`C:/Users/…` vs `C:\Users\…`) — also pre-existing, also in a module this phase never touched. Both are
  named here so a later reader does not read them as Phase 6 regressions; the modules this phase did touch
  are green together (`-pl hipster-entity-tooling,jwa-builder,jwa-sidecar -am clean test`) and the whole
  reactor compiles and packages with `-DskipTests`.

### 4.5 Position facts are the port's real cost, and they can be paid once

*(Updated after the whole `hipster-entity-tooling` hub cluster was ported — the estimate below was
right about the cost and wrong about where it lands.)*

Porting `EntityMetadataGenerator` showed where the effort actually goes. The type renames are
mechanical; what is not is that **JavaParser supplied every position fact for free** and the LST
supplies none:

```java
method.getBegin().map(pos -> pos.line)              // declaration line
method.getName().getBegin().map(pos -> pos.line)    // the NAME's line
fs.getBegin().map(pos -> pos.line)                  // an ANNOTATION's line
```

All three feed DEC-028's location payload, and the distinction between the first two is a recorded
defect (F-46): an accessor carrying `@FieldSource` begins on the annotation's line, so conflating them
puts the wrong line in the report.

`JavaSyntaxCheck.inspect(source)` now answers all of it — validity **and** type, method, member and
annotation positions, plus switch-arm lines and member **character spans** — from **one** javac parse,
memoised per source text, with `TreeQueries.lineOfChained`, `declarationLineOfChained`,
`methodLineOf`, `annotationLineOf`, `memberLineOf`, `caseLines` and `memberText` as the queries. Before
the consolidation the validity check and the type-line lookup each built their own
`StandardJavaFileManager`, so a pass over a tree opened two handles per file and ran javac twice for one
answer.

**What the cost actually looked like, measured.** Of the five files ported after the hub, the two that
needed *new* javac facts were not the biggest ones:

| file | lines | new javac facts needed |
| --- | --- | --- |
| `MetadataLocations` | 611 | enum constants, record components, switch-arm lines |
| `CooperativeCodegen` | 399 | member spans, and the attached-comment rule |
| `ClassIndex`, `TypeFacts` | 1030 + 234 | none — the queries already existed |
| `FieldBoilerplateGenerator` | 1007 | **n/a — it writes trees, see § 4.2** |

So the real rule is narrower than "budget by position usage": **budget by whether the file reads
positions, and note that the LST's missing positions are only half the problem — its missing
*positions* are recoverable from javac, but its missing *mutability* is not (§ 4.2).**

**Two traps inside the position work itself**, both measured on JDK 25 and both silent:

- javac reports **one `VariableTree` per declarator**, where JavaParser grouped `private int a, b;` into
  one `FieldDeclaration`. And javac's `getEndPosition` for a field **already includes the terminating
  semicolon**, so `int a; int b;` and `int a, b;` are indistinguishable by offset alone. The span is
  therefore found by scanning to the declaration's own semicolon at nesting depth 0
  (`JavaSyntaxCheck.fieldDeclarationEnd`). Getting this wrong merged six generated fields into one
  member named `id`, which removed the `field` location for every other field — a *missing* fact, so
  the failure surfaced as one failing assertion three layers away.
- A `J.Case`'s rule-arm body is on **`getBody()`**, not `getStatements()` — an old-style `case 0:` arm
  keeps statements, an arrow arm (`case 0 -> x;`, which is what every emitter writes) does not. Reading
  only `getStatements()` found no ordinal slot in any builder: again a missing fact rather than a wrong
  one.

### 4.6 Files still behind a bridge — none

Every `com.github.javaparser` consumer in the migration queue is ported, and the bridges that existed only
to serve them are **deleted**: `SourceReader`'s JavaParser half (`portingParser`, `readJp`, `readJpText`,
`readUnitJp`, `readUnitJpText`, `ReadJp` and the `JPARSER` constant), the `TypeFacts` JavaParser factory,
`ClassIndex`'s `addTypes(JavaParser unit)` overload, `EnumConstantOrderChecker`'s JavaParser
`readHeader`, `ViewAnnotationReader`'s JavaParser `read`/`parse`, `GenLevelResolver`'s JavaParser
`resolve` overloads, `TypeLiterals.typeParameterNamesJp` and `ValidationGenerator`'s JavaParser
`constraintsOn`/`annotationArgumentsJp`. `java-watch-agent`'s `JavaParserFactory` is gone too, and its
three member generators delegate to `jwa-builder`'s `ClassMemberProcessor`.

`javaparser-core` is no longer declared by any module, and the root POM's managed entry and
`<javaparser.version>` property went with it: a managed version with no consumer is how the dependency
comes back without the property that made F-23 (eleven versions in the local repository, one silently too
old) unreachable.

Two guard assertions belonged to the bridge and were dealt with rather than dropped:

- `SourceReaderTest`'s bridge-vs-LST comparison became
  `aCleanFileReadsAndARecoveredSyntaxErrorDoesNot` — the same property (a recovered parse is not a
  readable file), now that there is only one path to compare against;
- `DependencyBoundaryTest.javaParserIsPinnedOnceInTheRootPom` was **retired** with the dependency it
  policed, which is the retirement condition its own javadoc named: the single-sourcing property is
  vacuous when there is no dependency to single-source.

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
| How do I get a declaration's line? | `TreeQueries.lineOf` / `lineOfChained` — javac's `LineMap`, matched on name **and** chain (§ 4.1) |
| How do I find a node's parent? | You do not; capture ancestry during traversal |
| Where do I add a type mapping? | `scripts/rewrite-migration/mappings.js`, marked `verified` or `inferred` |
| Why does a module fail to compile? | Check *who references* the broken code first — it may be dead (see § 4.3) |
| Why can I not gate `java-watch-agent`? | It has never compiled: `FileChange`/`ToolContext` never existed (§ 4.4) |
| How do I size a port? | By **position usage**, not line count: `getBegin().line` has no LST equivalent (§ 4.5) |
| Where is a member's line? | `TreeQueries.methodLineOf` / `annotationLineOf` — one cached javac parse (§ 4.5) |

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
