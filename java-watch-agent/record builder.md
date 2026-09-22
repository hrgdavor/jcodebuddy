# Record builder — retired guide

> **Retired in Phase 8 of the rewrite migration.** This file was an early AI
> chat transcript (February 2026) that designed the record builder against
> `com.github.javaparser`, the source-manipulation library the migration has
> since removed. It taught that API end to end — 21 call sites of a dependency
> the build no longer contains — so it could not be corrected in place: a reader
> following it would re-add the dependency the migration removed, and the gate
> would catch that only after the work was done.

**The surviving guide is
[`docs/RecordBuilderGenerator.md`](../docs/RecordBuilderGenerator.md).** It
kept this file's subject matter — surgical updates, sync rather than replace,
`build()` refreshed on every pass — and rewrote it against the code that
exists: `jwa-builder`'s `RecordBuilderProcessor` (which record in a file is the
target, and what it declares), `SourceSplicer` (the text that gets appended) and
`BuilderTransformationEngine` (the `CodeEdit` the editor applies).

The full original transcript, code samples included, is in this file's git
history:

```sh
git log --follow -- "java-watch-agent/record builder.md"
git show <the-commit-before-the-retirement>:"java-watch-agent/record builder.md"
```

## What the transcript got right, and where it went

Recorded here because the reasoning is worth keeping even though the API is
gone — the *behaviour* was specified by this chat and is what the current
implementation reproduces:

| The chat settled | Where it lives now |
|---|---|
| **Idempotency** — running the update on every save must not duplicate members. The chat's mechanism was to remove the existing `Builder` before adding a new one. | The processor recognises its previous output **by name and structure** and replaces that span, so the mechanism is a splice rather than a sweep of a live tree. |
| **Replacement is too blunt** — the conversation's own follow-up ("it is more complicated but nicer if builder is updated instead of replaced") chose *synchronisation*: add what is missing, leave what a developer wrote alone. | `SourceSplicer.withBuilder` lets a hand-written setter or javadoc inside the `Builder` survive a pass; DEC-020's shape recognition is the rule behind it. |
| **`build()` is the exception** — it must be refreshed rather than left alone, or it stops matching the record's signature and the file does not compile. | `build()` is regenerated on every pass; `RecordBuilderProcessorTest` pins the constructor call and the member grouping. |
| **Minimal diff** — only the record's own text should change, and imports and other classes must be untouched. | Byte-preservation of everything outside the spliced span is asserted by `RecordBuilderFormattingTest` and `ViewInterfaceGeneratorTest`. |
| **Java level** — records and the constructs the builder uses need the language level set on the parser. | There is no language-level setting any more: the level **is** the `rewrite-java-25` artifact on the classpath. See [`doc_knowledge/code.graph.md`](../doc_knowledge/code.graph.md). |

Two spellings from that era are also worth noting, because they are the kind of
detail a reader of old code trips on: the annotation was `@AddBuilder` in the
first draft and is `@GenerateBuilder` today (both the imported form and
`@hr.hrg.watch2.builder.api.GenerateBuilder` are accepted by
`RecordBuilderProcessor.hasGenerateBuilder`), and the chat's "remove the existing
Builder class first" is not what the code does.