# Record builder generation

How `jwa-builder` completes a Java record with a fluent builder — meaning
`@GenerateBuilder` on a record, a pass over the file, and a
`User.builder().name("Alice").build()` at the call site afterwards.

This is the surviving builder guide. An earlier one
(`java-watch-agent/record builder.md`) was an AI chat transcript written against
the API the rewrite migration removed; it was retired in Phase 8 and points here.

## The problem

Traditional code generators treat source files as throwaway output and overwrite
whole blocks. That causes four things a developer notices immediately:

1. **Formatting loss** — indentation, blank lines and trailing comments are
   normalised into the generator's style.
2. **Loss of custom logic** — a validation check a developer added inside a
   setter is gone on the next pass.
3. **Messy diffs** — an entire class is deleted and re-inserted, so git history
   shows a rewrite where one method changed.
4. **Lost work on failure** — a generator that half-understands a file rewrites
   it from that partial understanding.

## The rule: surgical updates

The builder is **generated as text and spliced into the record's own span**.
Everything outside that span — imports, other types, the rest of the record —
is byte-identical by construction, because it is never printed, only copied.

Two properties fall out of that, and they are the reason the design is what it
is rather than a reprint:

- **Nothing reformats the file.** There is no print step at all. A printer
  normalises whitespace, and during the migration it was even observed to change
  the comma inside a generic type argument (`Map<String, List<Long>>` vs
  `Map<String,Long>`), which would have rewritten every generic member in the
  tree and made the generator think the user had edited them.
- **Members a developer wrote are preserved verbatim**, comment included,
  because the splicer only ever inserts its own text.

### How recognition works

A pass must be **idempotent**: running it on every save must not produce a second
`Builder` class. The processor does not look for a marker comment. It looks for
its **previous output by name and structure** — the `builder()` and `toBuilder()`
entry points and the nested `Builder` class — and replaces exactly that span. A
user opts back into regeneration by **deleting** the block; the next pass emits a
fresh one at the canonical location.

### Idempotence and synchronization

Within the generated `Builder`, the pass synchronizes rather than replaces:

- a **field** or **fluent setter** that already exists is left alone, so custom
  validation or transformation logic inside a setter survives;
- **`build()` is the one exception** — it is regenerated on every pass, because
  it must match the record's current component list or the file does not
  compile. A stale `build()` is a compile error, not a style question.

This is the behaviour an earlier design conversation asked for ("it is more
complicated but nicer if builder is updated instead of replaced"), and it is what
DEC-020's preserve-by-shape rule requires.

## What a pass emits

For a record with a component list, the output is

```java
public record User(String name, int age) {

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() { return new Builder().name(this.name()).age(this.age()); }

    public static class Builder {
        private String name;
        private int age;

        public Builder name(String name) { this.name = name; return this; }
        public Builder age(int age) { this.age = age; return this; }

        public User build() { return new User(name, age); }
    }
}
```

- **`static Builder builder()`** — the entry point for a new instance.
- **`Builder toBuilder()`** — pre-filled from the current record, which is how an
  immutable record is "modified": `user.toBuilder().age(31).build()`.
- **the nested `Builder`** — one private field and one fluent setter per
  component, then `build()`, in that order.

Member **ordering and indentation are part of the contract**, not a detail: the
nested type is indented relative to the record (one engine step beyond the
record's own indent), which is what `RecordBuilderFormattingTest` pins.

## The classes, and what each owns

| Class | Owns |
|---|---|
| `RecordBuilderProcessor` | Which record in the text is the target, and what it declares: `target(source, line)` (nearest record within five lines of the caret, else the first), `recordOnLine(source, line)` (exact line, for a code action), `annotatedRecords(source)` (`@GenerateBuilder` records with their name lines), `Component` (a component's declared type and name), `complete(recordText, recordName, components)`. |
| `LineLookup` | The one fact the tree cannot supply: **positions**, from javac's line map. `spanOf(source, simpleName)` gives the record's `Span` (start/end offset, name line, start/end line). |
| `SourceSplicer` | The text: `withBuilder(source, recordName, components, indent)`. |
| `BuilderTransformationEngine` | The editor-facing entry point: `generate(uri, source, line)` returns a `TransformationResult` carrying the `CodeEdit` a sidecar or agent applies. |
| `ClassMemberProcessor` | The sibling operation on plain classes — `withAccessors`, `withBuilder`, `withConstructors` over a `Target` built by `target(source, line)`. |

### Two behaviours worth knowing

- **A record javac cannot locate is skipped, not guessed at.** `target` and
  `annotatedRecords` treat a `null` span as "not found"; the fallback to the
  first record in the file applies only to a record that was located. An
  unreadable file must not be rewritten.
- **The line is the record's *name* line**, never the declaration's start. For an
  annotated record those differ, and the name is what an editor scrolls to.

## Why there is no parser field

Each read builds its own OpenRewrite parser rather than sharing one. A parser
caches the sources it has parsed and refuses a second set declaring the same
fully qualified names — which is exactly what completing a record and then
completing it again produces. The cost of one parser construction per edit is
small next to a file read, and it removes a whole class of intermittent failure
that a shared instance needs a `reset()` protocol to avoid.

## The annotation

`@GenerateBuilder` marks a record for the editor workflow. Both spellings are
accepted, exactly as before the rewrite:

```java
import hr.hrg.watch2.builder.api.GenerateBuilder;

@GenerateBuilder
public record User(String name, int age) { }
```

```java
@hr.hrg.watch2.builder.api.GenerateBuilder
public record User(String name, int age) { }
```

The marker lives in the lightweight `jwa-builder-api` module, so a project that
only wants the annotations never pulls in the transformation engine — see
[`jwa-sidecar/README.md`](../webview/jwa-sidecar/README.md) for the two-module pattern.

## Why the previous implementation was replaced

The JavaParser-era version mutated a live tree (`record.addMethod(...)`,
`builder.getFields().stream()...forEach(FieldDeclaration::remove)`) and relied on
`LexicalPreservingPrinter` to write the result back without disturbing the rest of
the file. That printer existed because it reformats; this module used it to
protect everything *outside* the record, then still had to re-indent the record's
own text line by line.

An OpenRewrite tree is **immutable** — there is no `addMethod` and no `remove()`
to mutate through — so none of that survives. What replaced it is the design
above rather than a stopgap:

- **the output is generated, not edited.** Both "update" paths in the old code
  existed only to keep node identity stable so the printer would emit each member
  once. Generating the builder deterministically from the component list produces
  the same text with no identity bookkeeping, no sweep for stale members and no
  reordering pass;
- **formatting becomes the generator's**, applied where the text is built;
- **nothing builds a tree.** A builder is *appended* to a record, so text
  generation is the direct expression of the intent; a `withXxx` chain would have
  to reconstruct every existing member just to keep it.

## Running it

The module's tests are the specification: `RecordBuilderProcessorTest` (selection,
component reading, the generated text), `RecordBuilderFormattingTest` (member
grouping, indentation relative to the record, byte-preservation) and
`ClassMemberProcessorTest` (the sibling class operation).

## See also

- [`doc_knowledge/code.graph.md`](../doc_knowledge/code.graph.md) — reading,
  querying and splicing source, and why positions come from javac.
- [`README.java_watch_2.md`](../README.java_watch_2.md) — where `jwa-builder`
  sits in the Java Watch ecosystem.
- [`jwa-sidecar/README.md`](../webview/jwa-sidecar/README.md) — the `-api` /
  implementation split, and how the sidecar loads a worker module.