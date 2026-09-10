# Generator Freezing Brainstorm

## Overview

Generator freezing is a safety mechanism for generated source code. It allows developers to mark classes, methods, fields, and eventually regions inside method bodies as "frozen" so the generator avoids updating those parts.

This is useful when:
- the generator produces problematic or unstable edits,
- developers want to preserve manual fixes while the generator is being improved,
- a generated area must be stabilized in a legacy or partial-adoption scenario,
- there is an unexpected use case the generator does not yet support.

## Goals

- Support explicit freeze markers in source code.
- Preserve manually edited code in frozen regions.
- Prevent generator rewrite cycles around buggy outputs.
- Keep the syntax simple and extensible.
- Make the mechanism safe for generated and hand-written code to coexist.

## Candidate Freeze Marker Forms

### 1. Annotation

A dedicated annotation is the most explicit form.

Example:

```java
@GeneratedFrozen
public interface PersonView { ... }
```

Or on a method:

```java
@GeneratedFrozen
String fullName();
```

Pros:
- Explicit semantic meaning.
- Easy to detect with JavaParser.
- Can be supported in source-model metadata.
- Works well for type-level and method-level freeze control.

Cons:
- Requires an annotation class to be available on the source path.
- Slightly more invasive than comments.

### 2. Comment

A comment marker is low-friction and easy to add without dependency changes.

```java
// generator:freeze
String fullName();
```

Pros:
- No compile-time dependency required.
- Works with any generator workflow.
- Good for quick, temporary freezes.

Cons:
- Less structured than an annotation.
- More fragile if the comment parser is not robust.

### 3. Region markers

A region-style marker can scope larger areas and support future method-body freezes.

```java
//#region generator freeze
public String formattedName() {
    return firstName + " " + lastName;
}
//#endregion
```

### 4. Expected-fix metadata

Freeze markers may include optional expected-fix metadata so tooling can track the freeze and alert when the expected resolution date passes.

Annotation example:

```java
@GeneratedFrozen(until = "2026-05-01")
String fullName();
```

Comment example:

```java
// generator:freeze until=2026-05-01
String fullName();
```

Region example:

```java
//#region generator freeze until=2026-05-01
public String formattedName() {
    return firstName + " " + lastName;
}
//#endregion
```

This metadata is informational and should not change freeze preservation semantics; it is only used for alerts and review guidance.

Pros:
- Supports partial regions inside methods in the future.
- Works well for multi-line blocks.

Cons:
- Requires parser support for region comments.
- Still a comment-based convention.

## Freeze Scope

Freeze markers should apply to:

- complete generated class/interface declarations,
- individual generated methods,
- generated fields or enum constants,
- optionally a whole file,
- future extension: specific method-body regions.

The generator should treat a frozen element as manually managed and avoid replacing or regenerating it.

## Semantics

When the generator detects a freeze marker on an element it intends to manage, it should:

1. preserve the marked element as-is.
2. skip any automatic rewrite or regeneration for that element.
3. preserve related surrounding structure with minimal edits.
4. optionally emit a diagnostic explaining that the element was frozen.

If the marker carries expected-fix metadata, tooling should also record the due date and alert when that date is reached or passed.

For example, a frozen view method should remain untouched even if generator metadata changes elsewhere.

## Use Cases

### Bug workaround

If the generator mis-handles a field, a freeze marker lets the developer lock the affected method or type while the bug is fixed.

### Manual override

When generated code must be customized for a one-off case, freezing prevents the generator from regressing the manual change.

### Partial adoption

During incremental migration, some generated classes may be frozen while others are still managed by the generator.

### Safety valve

If an unexpected input causes generator churn, developers can freeze the problem area and continue using the rest of the generated artifacts.

## Implementation ideas

### Preferred marker strategy

Use a hybrid strategy:
- `@GeneratedFrozen` for explicit freeze semantics,
- `// generator:freeze` for ad-hoc freezing,
- `//#region generator freeze` / `//#endregion` for future multi-line region support.

This gives both structure and friction-free fallback.

### Detection

The generator can detect freeze markers with JavaParser by scanning:
- annotations on declarations,
- comments attached to declarations,
- special region comment pairs.

### Diagnostic output

The generator should report when it skips a frozen element, for example:

- `INFO: Skipping generation of PersonSummary.fullName() because it is marked frozen.`
- `WARN: Class PersonView is frozen; generated property metadata may be incomplete.`

### Preservation policy

A frozen element should be preserved unchanged. The generator may still update unfrozen siblings in the same file.

If a class is frozen, the generator should not rewrite the file at all unless it can safely preserve every frozen member.

## Open questions

- Should freezing be opt-in only for generated code, or also allowed on hand-written code?
- Which marker should be canonical: annotation or comment?
- How should region markers interact with existing formatting and comment placement?
- Should the freeze marker also prevent metadata generation for the frozen element, or only source rewrite?
- If a frozen element is removed manually, should the generator recreate it or honor the removal?

## Next step

Define a concrete architecture contract for freeze marker semantics and implement a generator pass that recognizes and honors the markers.
