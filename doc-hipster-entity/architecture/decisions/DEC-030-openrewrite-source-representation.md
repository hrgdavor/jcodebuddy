# DEC-030: OpenRewrite as the source representation

- Status: Accepted
- Date: 2026-09-22
- Owners: project
- Related docs: [DEC-009 — Source-visible generation strategy](DEC-009.md), [DEC-019 — Source-Visible, IDE-Navigable Wiring](DEC-019.md), [DEC-020 — Cooperative codegen — preserve user-tweaked generated blocks](DEC-020.md), [DEC-021 — Generator class-file header](DEC-021.md), [DEC-022 — Refactor-sensitivity rules for generated code and divergence reporting](DEC-022.md), [DEC-023 — R1, field enums are append-only ordinal ledgers](DEC-023.md), [DEC-029 — The module class index](DEC-029.md), [`AGENTS.md`](../../../AGENTS.md) § 2, [`doc_knowledge/code.graph.md`](../../../doc_knowledge/code.graph.md), [`hipster-entity-tooling/README.md`](../../../hipster-entity-tooling/README.md), [`plans/rewrite-migration/`](../../../plans/rewrite-migration/README.md)
- Supersedes: the representation choice implied by [DEC-009](DEC-009.md) § Decision ("JavaParser-based source analysis as primary path") — the *strategy* (source analysis in preference to annotation processing) stands; only the library that implements it changes
- Superseded by: -

## Context

DEC-009 chose source analysis over annotation processing as this project's
generation strategy. It named the library that would do the analysis, and for
several phases that library was JavaParser.

The library has since been replaced end to end: no module declares
`com.github.javaparser`, all 34 files in the migration queue are ported, and the
migration's own gate reports `RESULT: PASS`
([`plans/rewrite-migration/`](../../../plans/rewrite-migration/README.md)). What
that leaves is a rule living nowhere: the representation is now an
OpenRewrite Lossless Semantic Tree, and the only documents recording it are
source files' javadoc and an `AGENTS.md` bullet that used to mandate the
library it replaced.

**A rule recorded only as an absence is a weak place for a rule to live.** This
decision writes down what the code already does, so the next contributor reads a
decision rather than inferring one from a missing import — and so a future
proposal to add a second parser has something to argue against.

### What the replacement actually is

An OpenRewrite **LST** is an immutable, type-attributed tree. Three of its
properties shape every decision below, and each cost real time to discover
during the migration:

1. **It has no positions.** No line, no column, no offset on any node. Every
   question that used to be `node.getBegin().line` is now answered elsewhere.
2. **It is immutable.** There is no `addMethod` and no `remove()` to mutate
   through; changing a tree means producing a new one.
3. **It recovers from syntax errors.** For some broken input it neither throws
   nor attaches a `ParseExceptionResult` — it returns a well-formed
   `J.CompilationUnit` with the broken part silently absent.

Property 3 is the one that caused a defect rather than an inconvenience, and it
is the reason the read path is what it is.

## Decision

### 1. The representation is the OpenRewrite LST

Java source is read, queried and represented as
`org.openrewrite.java.tree.J` nodes, obtained from
`org.openrewrite.java.JavaParser`. One parser per module, not one per call site:
the tooling shares a single instance and resets it between reads.

No second Java parser is added to the build for any purpose. OpenRewrite's Java
parser is a javac front end, so the JDK's own compiler is already available for
anything the LST cannot answer (see § 2) — and a second parser would be a second
grammar that can disagree with the first.

### 2. Validity is javac's question, and "a parse produced a result" is never the test

**A file javac recovers from is not a readable file.** Because the LST recovers
silently (Context § 3), the read path asks `javax.tools.JavaCompiler` — through
`JavaSyntaxCheck` — whether the text is valid Java, and treats that as the
verdict.

The read contract is therefore two channels, and they are not redundant:

| Channel | Meaning | How a caller uses it |
| --- | --- | --- |
| `SourceReader.Read.readable()` | the verdict | **branch on this** |
| `SourceReader.problemsIn(String)` | the parser's own words | show a human |

`problemsIn(...)` can be **empty for a file `readable()` refuses**, so a caller
that gates on "there were no problems" accepts exactly the defect this rule
exists to prevent: F-34, where a syntax error inside an enum's constant list
produced a unit with no constants, the R1 ledger planner read that as "a fresh
enum", and a persisted positional array was silently renumbered.

**Fail-safe direction: "could not be read" is always the safe answer.** A
generator that cannot read a file preserves what is on disk and reports a
divergence; it never treats the file as fresh.

Javac is asked about **syntax only**. Type errors are ignored by diagnostic code,
because a reader handed a single file has none of its dependencies on a
classpath.

### 3. Positions come from javac, never from the tree

Since the LST has no positions (Context § 1), every line and offset is obtained
from javac's line map over the same text: `com.sun.source.util.JavacTask` and
`LineMap`, wrapped by `JavaSyntaxCheck` and queried through `TreeQueries`.

A node is matched to its position by **`(simpleName, enclosingChain)`**, with the
chain outermost-first. Both halves are required: matching on the chain alone
returns a nested type's line for its enclosing type.

Two lines are different facts and are kept as different queries:

- the **declaration's** line (annotations included) — what the metadata records;
- the **name's** line — what a reader's editor scrolls to for the member.

### 4. Writing is text splicing, not tree printing

Generators **splice generated text into the original source** (`SourceSplicer`).
They do not build a tree and print it.

This is not a workaround for immutability; it is the direct expression of
DEC-020's rule that a developer's member is preserved **verbatim**, comment and
indentation included. A printer normalises whitespace — during the migration it
was observed to change the comma inside a generic type argument
(`Map<String, List<Long>>` → `Map<String,List<Long>>`), which would have
rewritten every generic member in the tree and made the generator read its own
output as a user edit.

Everything outside the spliced span is byte-identical **by construction**, which
is a stronger guarantee than a lexical-preserving printer could offer: a printer
promises to preserve what it can, whereas a splice never touches those bytes at
all.

Recognition of a generator's previous output stays with DEC-020: by structural
shape, not by a marker comment.

### 5. The language level is the parser artifact

There is no parser configuration and no language-level call. The level **is** the
version-specific parser artifact on the classpath
(`org.openrewrite:rewrite-java-25`, pinned where it is used), which is the same
release the root POM's `maven.compiler.release` sets.

A parser below the project's level cannot read the generator's own output
(records, switch expressions, `sealed`). The recorded failure is F-23: an old
parser made five example files generate **nothing**, with no error at all and a
missing file as the only symptom.

### 6. `TreeQueries` is the query surface, and it builds nothing

`TreeQueries` owns traversal, kind tests, member reads, supertype reads,
annotation reads, type/expression text and the position queries. It deliberately
does not construct nodes: a helper that owned construction would force the
emission-strategy choice on every caller at once.

The kind tests are named methods (`interfaces`, `classes`, `records`, `enums`,
`annotations`) rather than a predicate at each call site, because one
`J.ClassDeclaration` covers five kinds — a query that means "find the
interfaces" and is translated to "find the class declarations" **compiles and
starts returning records**.

## Alternatives considered

### Keep JavaParser

Rejected: it is what the migration removed, and the reasons are on the record in
[`doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md`](../../../doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md).
The decisive one for this decision is not its error tolerance but its
**formatting model**: `LexicalPreservingPrinter` refuses operations (adding a
`default` modifier, for one), and its fallback reformats the file — which is
incompatible with DEC-020 § 7.

### Two parsers — OpenRewrite for generation, JavaParser for positions

Rejected. This was tempting during the migration, because the LST's missing
positions are the single most disruptive difference, and JavaParser answers
position questions in one call. It fails on two counts:

- **Two grammars that can disagree.** A file JavaParser reads and OpenRewrite
  recovers from (or vice versa) produces a position for a tree node that does
  not exist, or none for one that does.
- **It re-introduces the dependency the migration removed**, and with it a second
  language-level setting to keep aligned with `maven.compiler.release`. The old
  failure mode — an artifact below the project's level silently parsing nothing —
  comes back, now in only half the pipeline.

Javac answers the position question with **no new dependency at all**, because
it is already the parser's front end.

### Symbol solving for type resolution

Rejected. The old symbol solver needed a classpath of *sources* that is not
available at generation time — the generator walks a source tree whose
dependencies are not compiled — and no rule in the tooling depends on
attribution. Types are read as **text** where the generator needs a spelling
(`TreeQueries.typeText`), and an unattributable type stays a text name rather
than becoming `null` or a guess.

### `JavaTemplate` or `withXxx(...)` construction for emission

Not rejected — **left open, per call site**, which is why `TreeQueries` builds
nothing. Where a generator produces a whole new member, text is the direct
expression of the intent (an append); a `withXxx` chain would have to reconstruct
every existing member just to keep it. `jwa-builder` and the tooling's
`SourceSplicer` are both on the text side; a generator that needs a template has
that option and must record the choice in its README.

### Generate into a side tree and print the whole file

Rejected for the reason § 4 gives: it reformats hand-written code, so a
regeneration pass produces a whole-file diff and a developer loses formatting
they never touched.

## Consequences

### Positive

- **One representation.** Generated code and queried code are the same nodes, so
  there is no translation layer to keep in step.
- **Formatting is safe by construction.** Everything outside a spliced span is
  byte-identical, so a regeneration pass produces a minimal diff — which is
  DEC-009's "human-readable checked-in source" goal and DEC-020 § 7 satisfied by
  the mechanism rather than by care.
- **One grammar.** The parser and the validity check are the same front end, so
  they cannot disagree about what Java is.
- **No new dependency for positions.** Javac is already there.
- **The fail-safe is enforceable.** `readable()` is a verdict a call site cannot
  forget, and `SourceReader` is the single place a file is opened.

### Negative

- **Every position costs a javac pass, or a cached inspection of one.** A cold
  syntax check is comparable to the parse itself; a second query on the same text
  is effectively free (`syntaxCheckAlreadyInspected` ≈ 9 ns).
- **The match is by name, not by node identity.** `(simpleName,
  enclosingChain)` can be ambiguous in principle, and the first match wins. It
  has been sufficient for every caller, and the rule is stated in one place.
- **A parser must be reset, or built per read.** A shared instance caches parsed
  sources and refuses a second set declaring the same FQNs. Both shapes exist in
  the tree, each with its reason documented at the call site.
- **Type attribution is unavailable from a bare traversal.** A node's `getType()`
  is null unless the parser was given a classpath containing it, so no rule may
  depend on it.

### Follow-up

- Module READMEs publish the naming-contract tables DEC-022 requires; the AST
  rule in `AGENTS.md` § 2 and the guide
  [`doc_knowledge/code.graph.md`](../../../doc_knowledge/code.graph.md) are the
  reader's entry points to this decision.
- The documentation half of the migration is checked by `docs-honest` in
  [`scripts/rewrite-migration/verify-migration.js`](../../../scripts/rewrite-migration/verify-migration.js),
  so a document that teaches a removed API fails the gate rather than being
  discovered later.

## Out of scope

- **Which parser a *consumer* project uses.** This governs this repository's
  tooling and generators.
- **A type-attribution service.** A future symbol-solving need is a new decision
  with its own classpath story.
- **The emission-strategy choice per generator.** Open, as § "Alternatives"
  records; a generator that picks `JavaTemplate` documents it.

## Acceptance criteria

- No module declares a Java parser other than OpenRewrite's.
- `SourceReader` is the only place a file is opened for reading, and it returns a
  verdict-bearing `Read`; no call site branches on `problemsIn(...)`.
- A file javac recovers from is reported as **not readable** by
  `SourceReader.readText`, and a test pins it.
- Every line or offset the tooling publishes is obtained through
  `JavaSyntaxCheck`, and no code reads a position from a tree node.
- A generator that writes Java does so by splicing text, and a test asserts the
  bytes outside the spliced region are unchanged.
- `TreeQueries` contains no node construction.
- A document that teaches a removed parser API fails
  `scripts/rewrite-migration/verify-migration.js`.