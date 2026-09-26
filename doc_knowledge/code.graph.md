# Reading and writing Java source

How this repository gets a Java tree, asks it questions, and writes Java back —
and why each of those three is a different mechanism.

This page is the guide `AGENTS.md` § 2 points at. It is written against the code
as it is, so every class named here is the one to open; the migration that put it
in this shape is recorded in
[`plans/rewrite-migration/`](../plans/rewrite-migration/README.md) and
[`doc/brainstorm/rewrite-migration/`](../doc/brainstorm/rewrite-migration/)
and is **not** repeated here.

The decision behind the shape is
[DEC-030](../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md).

---

## The one-paragraph version

Read with `SourceReader`, which parses through OpenRewrite and asks **javac**
whether the text is valid Java. Query the tree with `TreeQueries`. Ask **javac**,
not the tree, where anything is. Write by **splicing text** (`SourceSplicer`),
never by reprinting a tree. The tree is read for its shape and then discarded.

Three mechanisms, deliberately not one, because OpenRewrite's Lossless Semantic
Tree is good at representing Java and has no opinion at all about positions or
formatting — and those are the two things a cooperative generator needs most.

---

## Reading: `SourceReader`

```java
SourceReader.Read read = SourceReader.read(Path.of("PersonSummary.java"));
if (!read.readable()) {
    // Keep what is on disk. Do NOT regenerate from a partial understanding.
    SourceReader.reportUnparseable(divergences, kind, location, cause, "NOT rewrite the file");
    return;
}
J.CompilationUnit unit = read.unit();
```

`SourceReader.read(Path)` and `readText(String)` both return a
`SourceReader.Read`, and the record is the whole contract:

| Member | Meaning |
|---|---|
| `readable()` | **the verdict** — `unit != null`, i.e. this file parsed as Java |
| `unit()` | the `J.CompilationUnit`, or `null` |
| `unparseable()` | the file exists and could not be read (a boolean component) |
| `SourceReader.problemsIn(String)` | the parser's own words — **detail, not a verdict** |

### The two channels are not redundant, and the second one lies

`problemsIn(...)` can be **empty for a file that `readable()` refuses**. That is
measured, not theoretical: OpenRewrite *recovers* from some syntax errors, so it
neither throws nor attaches a `ParseExceptionResult` marker — it returns a
well-formed `J.CompilationUnit` with the broken part silently absent. The
recorded case is F-34: a syntax error inside an enum's constant list produced a
unit with **no constants**, the R1 ledger planner read that as "a fresh enum" and
rebuilt the constant list from the resolved fields — a silent renumbering of a
persisted positional array.

So the rule is: **treat `readable()` as the verdict and `problemsIn(...)` as
detail.** A caller that gates on "there were no problems" accepts the F-34 case.
`SourceReaderTest.OtherEntryPoints` pins the pair.

### Why javac is in the read path

The recovery above is invisible from the tree, so the question the old parser
answered with `isSuccessful()` has to be asked of something that still knows the
difference. `SourceReader` asks `JavaSyntaxCheck.isSyntacticallyValid(source)`.

That costs no dependency: OpenRewrite's Java parser **is** a javac front end, so
javac is already on the classpath and cannot drift from the parser's grammar.
Syntax only — type errors are ignored by diagnostic code, because a reader handed
a single file has none of its dependencies on a classpath.

### The fail-safe direction

"Could not be read" is **always** the safe answer. A generator that cannot read a
file must preserve what is on disk and report a divergence, never treat the file
as fresh. `SourceReader.reportUnparseable(...)` exists to make that the single
call a caller writes, and it names what was **not** done — "this pass did NOT
rewrite the file" — because that consequence is what the reader has to act on.

### Reading a fragment

`readFragmentUnit(String)` reads an expression such as `new Class<?>[0]` by
wrapping it in the smallest compilation unit that holds one
(`class $Fragment { Object $value = <fragment>; }`). There is no standalone
expression parser in OpenRewrite, and the wrapper is print-idempotent Java so
nothing has to be relaxed for it.

Unlike the whole-file path this method **throws** on a fragment it cannot parse.
The wrapper is generated here, so a failure means the caller handed in something
that is not an expression — a caller bug, not a file on disk.

### The parser is shared, and reset

One parser instance, built once (`SourceReader.PARSER`), because constructing one
resolves and reads a classpath. A parser caches the sources it parsed and refuses
a second set declaring the same fully qualified names, so every read ends with
`PARSER.reset()` in a `finally`. Without it, the *second* read of any type fails
and every generator sees "unparseable" for its own previous output.

A caller that needs several revisions of one file to attribute must build its own
parser per read instead; `merge-java`'s `ResolvedTypeReader` is that caller and
documents the same wall.

### The language level is the artifact, not a setting

There is no `ParserConfiguration` and no language-level call. The level **is** the
parser artifact on the classpath (`rewrite-java-25`, pinned in
`hipster-entity-tooling/pom.xml`), which is the same release the root POM's
`maven.compiler.release` sets. Two regressions ride on that:

- a parser below the project's level cannot read the generator's own output
  (switch expressions, records, `sealed`) — the F-23 failure, where an old parser
  made five example files generate **nothing** with no error at all;
- `readText` therefore insists on a real `J.CompilationUnit` rather than accepting
  any `SourceFile`.

---

## Positions: javac, never the tree

An OpenRewrite node exposes **no position at all**. Not a line, not a column, not
an offset. Everything that used to be `node.getBegin().line` is now a question
asked of the file's text, and `JavaSyntaxCheck` owns the one javac line map that
answers it.

`JavaSyntaxCheck.inspect(source)` returns a `FileCheck` carrying `types()`,
`methods()`, `annotations()`, `members()` and `spans()`. `TreeQueries` wraps it in
the typed queries a caller actually wants:

| Query | Answers |
|---|---|
| `TreeQueries.lineOf(declaration, source)` | the line the declaration's **name** sits on |
| `TreeQueries.declarationLineOf(declaration, source)` | the line the declaration **begins** on — annotations included |
| `TreeQueries.lineOfChained` / `declarationLineOfChained` | the same for a nested type, given the enclosing names outermost-first |
| `TreeQueries.methodLineOf(method, ownerName, source)` | a method name's line, keyed by owner **and** arity |
| `TreeQueries.annotationLineOf(declaringType, ownerName, name, source)` | an annotation's own line |
| `TreeQueries.memberLineOf(ownerDisplayName, name, role, source)` | an enum constant, record component or field name |

`-1` means "unknown", and DEC-029 already reads that as such.

### The two lines are different facts

For

```java
@View(name = "PersonSummary")
public interface PersonSummary { …
```

the annotation is on line 8 and the declaration begins on line 8, while the
*name* is on line 9. Both answers are recorded somewhere and conflating them is a
defect:

- a metadata record's view line is the **declaration's** — annotations included,
  which is the behaviour the generator has to reproduce;
- a class-index row or a location row wants the **name's** line, which is where a
  reader's editor scrolls to for the member itself.

### Position lookup is a matching problem, and it was wrong three times

`JavaSyntaxCheck` hands back a flat list of positions. Matching a tree node to its
position uses the key **`(simpleName, enclosingChain)`** — and both halves are
required:

- Matching on the chain alone hits the wrong declaration. For
  `interface Shape { record Circle {} }` the javac entries are `(Shape, [])` and
  `(Circle, [Shape])`; an outer query whose key appends its own name
  (`(Shape, [Shape])`) matches `(Circle, [Shape])` and returns the inner record's
  line for the outer interface. A confidently wrong line is worse than none.
- The enclosing chain itself must be **outermost-first**. `ArrayDeque`'s iterator
  starts at the head and `push` inserts at the head, so the obvious copy of the
  visitor's stack is innermost-first; two levels hid it, because `[Shape]` is its
  own reverse. `TreeQueries.reversedCopy` is why it is right.

Three further cases are pinned by `JavaSyntaxCheckTest` because each one stole a
line:

1. a **reversed enclosing chain** (above);
2. `@Foo` / `Outer.Foo` **stealing a name line**;
3. a **string literal** stealing one.

If a position comes back `-1` where you expected a number, the key is the first
thing to check.

---

## Querying: `TreeQueries`

`TreeQueries` is the read-only query surface over a `J.CompilationUnit`. It
exists because two things are true of every consumer at once:

1. **The replacement for `cu.findAll(X.class)` is a visitor, not a call.** Every
   `findAll` becomes the same fifteen-line `JavaIsoVisitor`; scattering that
   across a dozen rules buries the rules in traversal code. `TreeQueries.findAll`
   is that boilerplate, once.
2. **One `J.ClassDeclaration` covers five kinds** — `Class`, `Enum`, `Interface`,
   `Annotation`, `Record`. There is no `J.InterfaceDeclaration` and no
   `J.EnumDeclaration`, so a query that means "find the interfaces" and is
   translated to "find the class declarations" **compiles and starts returning
   records**. Each kind gets a named method (`interfaces`, `classes`, `records`,
   `enums`, `annotations`, `typesOfKind`) so the choice is explicit where it is
   made.

### What it deliberately does not do

**Nothing in `TreeQueries` builds a tree.** That is not an oversight: a helper
that owned construction would force the emission-strategy choice on every caller
at once, and a query returning plain `J` subtypes works with both `JavaTemplate`
and immutable `withXxx(...)` construction.

### The traps that read as plausible code

Each of these has a wrong version that compiles:

| Trap | The wrong version | The right version |
|---|---|---|
| An **interface's** `extends` clause is held in `getImplements()`; `getExtends()` is `null` for it | read `getExtends()` alone | `TreeQueries.supertypeTypes` / `supertypeNames` / `supertypeTexts` |
| `getImplements()` and `getTypeParameters()` are **`null** when absent, not empty | bare `for`-each (throws) | null check, as `TreeQueries.typeParameterNames` does |
| An **empty parameter list** is a single `J.Empty` placeholder | `method.getParameters().isEmpty()` | `TreeQueries.hasNoParameters` / `hasOneParameter` |
| `J.MethodDeclaration` covers **constructors** too | `findAll(decl, J.MethodDeclaration.class)` | `TreeQueries.methodsOf` (excludes constructors, and reads only direct members) |
| A **`void`** method's return type is *not* absent — it is a `J.Primitive` of `Primitive.Void` | test for a missing return-type expression | `TreeQueries.isVoidReturn` |
| `J.ClassDeclaration.getExtends()` vs `getImplements()` for a supertype **as a name**: bare is a `J.Identifier`, generic is a `J.ParameterizedType` | read `J.Identifier` only (misses every generic supertype) | `TreeQueries.simpleTypeName` unwraps both |
| `findAll` is **root-inclusive**, and its reach matches the old `findAll` — a caller wanting only top-level types must filter | assume `findAll` starts at children | `TreeQueries.topLevelTypes` |

Two more, for annotation reading, which is where the shapes differ most:

- A **single unnamed argument is normalised to the name `value`**. A source that
  writes `@Foo(Bar.class)` has no name in the text; the tree reports it as an
  assignment to `value`, so `TreeQueries.annotationArg(annotation, "value")`
  succeeds without special-casing the form. A marker annotation has
  `getArguments() == null`, not an empty list, and `@View()` parses to a single
  `J.Empty` argument that names nothing.
- An **enum constant's annotations are not collected** by the kind queries:
  constants are `J.EnumValue` entries inside the class body's statement list, not
  a separate list of declarations.

### Type text: two printers disagree, so the text is normalised here

`TreeQueries.typeText(J)` renders a type the way the old `asString()` did. The
normalisation is not cosmetic:

- the OpenRewrite printer separates type arguments with a **space**
  (`Map<String, List<Long>>`) where the committed generated source has none
  (`Map<String,List<Long>>`). Emitted source is compared **byte-for-byte** against
  the committed example, and worse, a generator recognises its own previous output
  *by that text* — so a printer difference reads as "the user edited this member"
  and reports a divergence for every generic member in the tree.
- `J.Literal.toString()` returns the literal's **value**, so a string literal
  loses its quotes. Carrying that into emitted annotation text produced
  `@Pattern(regexp = [A-Z]{2})` — source that does not compile, from an annotation
  the author wrote correctly. `TreeQueries.expressionText` prefers
  `getValueSource()` when the parser recorded one.

### Ancestry: the tree has no parents

An LST node does not know its parent, so `getParentNode()` has no equivalent.
`TreeQueries.typesWithEnclosing(cu)` captures the ancestry **during** traversal
via an explicit stack it pushes and pops itself, rather than reading
`cursor.getPath()` — whose order is undocumented and was measured to be neither
root-first nor leaf-first. That chain, plus the package name, is all an FQN needs;
no symbol solver is involved and none is available at generation time.

---

## Writing: splice, do not reprint

Generators **splice text into the original source**. They do not build a tree and
print it.

```java
String updated = SourceSplicer.withMembers(source, typeName, members, indent);
```

Why not reprint? Because reprinting reformats. The rule that makes cooperative
codegen work (DEC-020) is that a generator preserves a developer's member
**verbatim** — including the comment above it and the indentation of everything
around it. A printer normalises whitespace; on this migration it even changes the
generic comma above. So the text is sliced out of the original using the offsets
javac recorded, and everything the generator did not touch is byte-identical by
construction.

Where the modules do it:

| Module | Splicer | What it inserts |
|---|---|---|
| `hipster-entity-tooling` | `SourceSplicer.withMembers` | generated members into a hand-written view interface |
| `jwa-builder` | `SourceSplicer.withBuilder` | the `builder()` / `toBuilder()` entry points and the nested `Builder` class into a record |

Recognition of a generator's **previous** output is by name and structure, not by
a marker comment: the generator sees the member it would have emitted and replaces
its span. That is what makes a pass idempotent, and it is why deleting a generated
block is how a user opts back into regeneration.

For a member that must be **removed** (a retired enum constant), the mechanism is
the same idea in reverse: `TreeQueries.memberTextSpan` returns the character span,
and the caller deletes it — an LST node cannot be removed from its parent.

### The one place output is formatted on purpose

`EnumCompactionCli` formats a complete file rather than preserving it. That is the
single case where dropping the old `PrettyPrinterConfiguration` changes the
artifact, so its output diff is reviewed and accepted deliberately.

---

## What a read costs

Measured in Phase 7 with JMH (`benchmarks/latest.json`, interpretation in
[`BenchmarkReport.md`](../doc/brainstorm/rewrite-migration/07-testing/benchmarks/BenchmarkReport.md)).
Absolute baselines only: the alternative implementation is gone, so there is
nothing to compare against.

| Operation | Cost | Note |
|---|---|---|
| `readCommittedView` | ~56 ms | a real view interface from the example |
| `readLargestFileInRepository` | ~165 ms | the largest hand-written file here |
| `coldSyntaxCheckCommittedView` | ~32 ms | ~60% of a cold read; ~0% warm |
| `coldReadLargestFileInRepository` | ~176 ms | ~30–55 ms of it is parsing, the rest is classpath and first-touch cost |
| `syntaxCheckAlreadyInspected` | ~9 ns | the guard is free when the file was already inspected |

**A read is ~30–55 ms and does not scale with file size.** The dominant cost is
per-read setup, not text length — which is why the parser is shared and why the
guard is cheap once a file has been inspected. The full method-level numbers
(`annotationLinesOnTheSyntheticTree`,
`typesWithEnclosingOnARealLargeFile`, …) are in the report.

---

## See also

- [`hipster-entity-tooling/README.md`](../hipster-entity-tooling/README.md) —
  the module that owns all of the above, plus how to run its gate and `jmh`
  profile.
- [`doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md`](../doc-hipster-entity/architecture/decisions/DEC-030-openrewrite-source-representation.md) —
  the decision this page describes.
- [`doc-hipster-entity/architecture/decisions/DEC-020.md`](../doc-hipster-entity/architecture/decisions/DEC-020.md) —
  why a generated block is recognised by shape and preserved verbatim.
- [`doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md`](../doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md) —
  the full trap list from the port itself, including the parser-cache wall.