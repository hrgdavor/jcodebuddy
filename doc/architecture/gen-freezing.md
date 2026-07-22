# Generator Freezing Architecture

## Purpose

Generator freezing is a formal generator control mechanism. It establishes how the metadata/code generator should treat source elements that must not be rewritten automatically.

A freeze marker signals that a generated class, method, field, or region has been manually stabilized and must remain unchanged until the marker is removed.

## Scope

The generator freezing architecture covers:
- generated interface and class declarations,
- generated methods and fields,
- generated view property declarations,
- future method-body region support.

It does not alter runtime semantics or generated metadata contracts; it only affects the generator's source rewrite behavior.

## Approved freeze markers

The generator MUST support the following markers:

### 1. Annotation marker

```java
@GeneratedFrozen
public interface PersonSummary { ... }
```

Or on a method/field:

```java
@GeneratedFrozen
String computedValue();
```

The annotation may be defined in the generator module or as a small shared source-only marker. The generator resolves it by name and does not require runtime processing.

### 2. Comment marker

```java
// generator:freeze
String fullName();
```

This comment-based marker is valid for any declaration the generator manages. It is intended for quick, low-friction freezes.

### 3. Region marker

```java
//#region generator freeze
public String formatName() {
    return firstName + " " + lastName;
}
//#endregion
```

Region markers are valid for future multi-line blocks and method-body freeze semantics. For now, they are supported at the file/comment level.

### 4. Expected-fix metadata

Freeze markers may carry optional expected-fix metadata that indicates when a fix is expected to be available. Example forms:

```java
@GeneratedFrozen(until = "2026-05-01")
String computedValue();
```

```java
// generator:freeze until=2026-05-01
String computedValue();
```

```java
//#region generator freeze until=2026-05-01
...
//#endregion
```

This metadata is advisory only: it does not change the freeze preservation semantics, but it does instruct tooling to emit a review or expiry warning once the expected-fix date is reached.

## Semantics

When a managed element is annotated or commented as frozen, the generator MUST:

1. preserve the element exactly as it exists in source.
2. skip regeneration or rewrite of that element.
3. leave unfrozen elements in the same source file eligible for normal generation.
4. emit an informational diagnostic when a frozen element is skipped.
5. if expected-fix metadata is present, record the due date and allow tooling to emit a review advisory once that date is reached.

If an element is frozen by a file-level marker, the generator SHOULD treat the entire file as immutable and avoid applying any automated edits.

If a frozen element is removed manually, the generator SHOULD NOT recreate it automatically unless the surrounding contract explicitly requires it for correctness. In practice, this means the generator must be conservative and prefer leaving a file untouched if it cannot safely determine the developer intent.

## Marker precedence and granularity

- A method-level freeze marker overrides generator intent for that method.
- A class/interface-level freeze marker overrides generator intent for all nested members, unless a member is explicitly unfrozen by a future policy.
- Region markers are more granular and apply only to the code covered by the region.

The generator MUST NOT assume that frozen elements are generated identical to its own output. Freeze markers are a portability mechanism, not a validation mechanism.

## Diagnostics and UX

The generator MUST produce clear diagnostics when a freeze marker is encountered.

Suggested diagnostic types:
- `INFO` — `Skipping generator update of PersonSummary.fullName() because it is frozen.`
- `WARN` — `PersonSummary method fullName() is frozen; generated metadata may be stale if the interface contract changed.`
- `WARN` — `PersonSummary.fullName() freeze is overdue; expected fix date 2026-05-01 has passed.`
- `ERROR` — only when the generator cannot safely preserve a frozen file while applying other required edits.

The generator SHOULD include the marker text in diagnostics to help developers locate frozen code.

## Implementation constraints

- The generator may use JavaParser comment attachment and AST annotations to detect freeze markers.
- It must support both annotations and comments in the same source tree.
- It may treat `@GeneratedFrozen` as a marker annotation only; it must not require that the annotation be retained or processed at runtime.
- The marker syntax must be stable enough to be recognized even if the source file contains other unrelated comments.

## Manual edit workflow

1. The developer adds a freeze marker to the generated element.
2. The generator preserves the frozen element on subsequent runs.
3. The developer may edit the frozen code manually.
4. When the generator is fixed or the freeze is no longer needed, the developer removes the marker.
5. The generator resumes normal generation for the element.

This workflow ensures that problematic generated code can be insulated temporarily without blocking broader generation.

## Example markers

### Annotation

```java
@GeneratedFrozen
public interface PersonSummary extends PersonEntity {
    String firstName();
}
```

### Comment

```java
// generator:freeze
String firstName();
```

### Region

```java
//#region generator freeze
String firstName();
String lastName();
//#endregion
```

## Notes

- Freezing is a generator-level safeguard, not a replacement for stable generator semantics.
- The architecture intentionally allows a manual freeze marker to coexist with generated metadata that is still produced for the same entity.
- The marker should be easy to remove once the generator is corrected.
