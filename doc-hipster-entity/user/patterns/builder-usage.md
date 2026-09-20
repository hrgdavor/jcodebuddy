# Builder Usage

## Goal

Use generated builders to create or update entity views with a fluent API.

## When to use

Use builders when you want a convenient, typed way to construct views and track changed fields.

## Example

A generated builder has exactly two constructors — a **no-arg** one and a **copy constructor from
the view** — plus the `toBuilder()` entry point the generator adds to the view interface. There is no
static `create()`:

```java
// From an existing view: the copy constructor carries every current value across,
// which is what makes a later comparison against that view meaningful.
PersonSummaryBuilder builder = new PersonSummaryBuilder(existingView);

PersonSummary summary = builder
        .firstName("Alice")
        .lastName("Smith")
        .email("alice@example.com")
        .build();

// Or the entry point the generator emits as a default method on the view:
PersonSummaryBuilder viaView = existingView.toBuilder();
```

The setters are emitted for **writable** fields only — `@FieldSource(kind = COLUMN)` — so a
`DERIVED` or `JOINED` accessor has no setter, no `set(int, …)` arm and no `set(String, …)` arm
(rule S1). `set(int, Object)` throws for a non-writable ordinal; `set(String, Object)` returns the
ordinal it wrote, or `-1` for a name the view does not have, or for one that exists but is not
writable.

## Track an update

For a tracked update the pair is `view.toBuilderTracking()` / `new PersonSummaryBuilderTracking(view)`:
the baseline is the view you pass in, the tracker records only the ordinals you actually change, and
`changedValues()` reports the current value of each. The old value is **not** kept — no previous-value
API exists — so a comparison is your own, against the baseline instance you still hold. See
[the getting-started guide](../getting-started-new-project.md) § 6 and DEC-012's revision section.

## When to choose a builder

Use a builder when:

- you want fluent construction instead of manual array-based creation
- you need to build partial updates or patch payloads
- you want generated helper methods instead of hand-written setters

## See also

- [Materialization Guide](../materialization-guide.md)
- [Core Concepts](../core-concepts.md)
