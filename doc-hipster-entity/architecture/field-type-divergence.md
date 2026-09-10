# Field Type Divergence Across Views

## Overview

In the hipster-entity model, multiple views of the same entity can declare a field with the same name but a **different Java return type**. This document analyses when this is useful, when it is dangerous, and what validations can mitigate the risks.

## How the generator handles it today

| Aspect                    | Behaviour                                                                                                          |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Per-view `properties[]`   | Each view keeps its own declared type — no conflict.                                                               |
| Entity-wide `allFields[]` | One primary `type` per field name, with `typeByView` map showing type per view.                                    |
| Primary type selection    | Non-derived (`COLUMN`, `JOINED`) takes priority over `DERIVED`. Among equal priority, first-encountered type wins. |
| Generated enums           | Each `<View>Property` enum independently reflects that view's type — no cross-view interference.                   |

---

## Pros of allowing type divergence

### 1. API shape freedom
External REST/GraphQL DTOs can expose a friendlier or serialization-safe type while the internal view uses the native type.

```java
// Internal view
interface PersonSummary extends PersonEntity {
    LocalDate birthDate();   // native type
}

// API DTO
interface PersonApiDto extends PersonEntity {
    String birthDate();      // ISO-8601 string for JSON
}
```

### 2. Read-vs-write asymmetry
Input forms commonly accept raw/string types; query views return parsed types.

```java
// Write form
interface PersonCreateForm {
    String salary();         // user enters text
}

// Read view
interface PersonSummary extends PersonEntity {
    BigDecimal salary();     // stored as decimal
}
```

### 3. Projection flexibility
A summary view may pre-format or aggregate data that a detail view exposes in its native form.

```java
// Summary — pre-formatted
interface PersonSummary extends PersonEntity {
    String fullName();       // "John Doe"
}

// Details — raw components
interface PersonDetails extends PersonEntity {
    String firstName();
    String lastName();
}
```
(Here the field names differ, which is the preferred pattern — see guidelines below.)

### 4. Version evolution
When evolving a field type (e.g. `Long` → `UUID`), newer views can adopt the new type while legacy views remain unchanged during a migration window.

### 5. Derived vs stored duality
A field can appear as COLUMN in a full DB view and DERIVED (computed differently) in a lightweight summary view, with type differences reflecting the computation output.

---

## Cons and pitfalls

### 1. Java compilation conflict in extends chains
**Severity: BLOCKING**

If view A declares `Integer age()` and view B declares `String age()`, and any third interface extends both A and B, Java will refuse to compile:

```
error: 'age()' in A clashes with 'age()' in B; incompatible return type
```

This is a hard language constraint. Divergent types can only exist on **sibling** views (both extend the entity marker, neither extends the other).

### 2. Entity-wide metadata ambiguity
The `allFields` section picks a primary type. A schema generator or ORM mapper consuming `allFields` sees one type and may generate incorrect DDL, column mapping, or serialization code.

**Mitigation:** Always check `typeByView` when types diverge. The `hasTypeDivergence()` method on `EntityFieldMeta` can be queried programmatically.

### 3. Generic mapper/reader confusion
`ViewReader.get(field)` returns `Object`. If codegen assumes a single type per field name (e.g. generating typed getters), views with divergent types will produce wrong casts at runtime.

### 4. Testing complexity
Each view needs its own integration test for type compatibility. Shared test helpers that assume a uniform type per field name break.

### 5. Developer confusion
Same name suggests same semantics. When `age` is `Integer` in one view and `String` in another, reviewers must understand the reason. Undocumented divergence leads to bugs.

### 6. Serialization surprises
Jackson, Gson, or other JSON libraries bound to different view DTOs may parse the same JSON field into incompatible types, causing runtime errors or silent data loss.

---

## Real-world patterns where divergence already exists

### JPA / Hibernate projections
Spring Data JPA projection interfaces routinely use different return types for the same column. A `NativeQuery` can return `Object[]`, while a projection interface returns `String`. The column name is the same; the Java type is view-specific.

### GraphQL schema stitching
A single DB column (`price DECIMAL`) may appear as `Float` in a lightweight GraphQL type and `Money` (custom scalar) in a detailed type. Tools like DGS and Netflix Relay handle per-type resolvers for the same field name.

### Protobuf / gRPC message variants
Different `.proto` messages can represent the same domain field as `int64`, `string`, or a `Timestamp` wrapper. Code generators emit separate message classes; the field name is shared but the type is message-specific.

### OpenAPI schema discriminated unions
An API may expose `status` as an `integer` (HTTP code) in one response schema and as a `string` (human-readable label) in another.

### CQRS read models
Command side uses `UUID` for correlation IDs; query side projects the same column as `String` for ease of display. Different read models = different types.

---

## Use cases: allowed divergence

### UC-1: API DTO type adaptation
**Views:** `PersonSummary` (internal) vs `PersonApiResponse` (external REST)
**Field:** `birthDate`
**Types:** `LocalDate` vs `String`
**Why safe:** Sibling views; no common subtype. A mapper converts between them at the service layer. `@FieldSource(kind = DERIVED)` on the DTO variant documents intent.

### UC-2: Read vs write forms
**Views:** `OrderCreateForm` (write) vs `OrderDetails` (read)
**Field:** `amount`
**Types:** `String` (user input) vs `BigDecimal` (stored)
**Why safe:** Write views are never queried; read views are never written. No common subtype. Validation happens at the controller boundary.

### UC-3: Legacy migration coexistence
**Views:** `PersonV1` vs `PersonV2`
**Field:** `externalId`
**Types:** `Long` vs `UUID`
**Why safe:** Versioned views never extend each other. During migration, both coexist with adapter code. After cutover, the old view is retired.

---

## Use cases: NOT advisable

### Anti-UC-1: Divergence in an extends chain
```java
interface PersonBase extends PersonEntity { Integer score(); }
interface PersonRanked extends PersonBase { String score(); } // COMPILE ERROR
```
**Problem:** Java refuses to compile a sub-interface that narrows or widens the return type incompatibly.
**Recommendation:** Use a different field name (`scoreText`, `scoreLabel`) or convert at the service layer.

### Anti-UC-2: Same type intent, different precision
```java
interface OrderSummary extends OrderEntity { Float total(); }
interface OrderDetails extends OrderEntity { Double total(); }
```
**Problem:** Both represent the same monetary value, but `Float` loses precision. Schema generators pick one; which one is correct? Neither — the real fix is to use `BigDecimal` everywhere.
**Recommendation:** Standardise on the most precise type. Add a `@FieldSource` expression if a view needs rounding.

### Anti-UC-3: Undocumented divergence
```java
interface PersonSummary extends PersonEntity { Integer age(); }
interface PersonDto extends PersonEntity { String age(); }
```
No `@FieldSource`, no documentation. A new developer sees `age` as `Integer` in one view and `String` in another with no explanation.
**Recommendation:** Always annotate divergent fields with `@FieldSource` explaining the reason.

### Anti-UC-4: Collection vs scalar divergence
```java
interface PersonSummary extends PersonEntity { String tag(); }
interface PersonDetails extends PersonEntity { List<String> tags(); }
```
Different name is fine here (`tag` vs `tags`), but if the same name is used (`tags` returning `String` and `List<String>`), consumers have no safe generic handling.
**Recommendation:** Use distinct names when the shape (scalar vs collection) changes.

---

## Recommended validations

These validations can be implemented in the metadata generator or as a separate rule check step.

### V-1: Extends chain type conflict (ERROR)
> If view A extends view B (directly or transitively), and both declare a field with the same name but different type → **emit error**.

This catches issues that Java would also reject, but earlier — at metadata generation time, before compilation. Useful for projects that generate code before compiling.

### V-2: Type divergence warning (WARNING)
> If two sibling views declare the same field name with different types → **emit warning** unless the divergent field has `@FieldSource` annotation.

Rationale: `@FieldSource` documents intent. Without it, divergence is likely accidental.

### V-3: Primitive vs boxed divergence (INFO)
> If one view declares `int age()` and another `Integer age()` → **emit info** suggesting standardisation.

Semantically equivalent but creates unnecessary `typeByView` entries.

### V-4: Incompatible collection shape (ERROR)
> If one view declares `String tags()` and another `List<String> tags()` → **emit error**.

Scalar-vs-collection divergence for the same name is never safe for generic mappers.

### V-5: Derived field without expression (WARNING)
> If a field has `@FieldSource(kind = DERIVED)` but no `expression` → **emit warning**.

Without an expression, code generators cannot reproduce the derivation.

### V-6: Joined field without relation (WARNING)
> If a field has `@FieldSource(kind = JOINED)` but no `relation` → **emit warning**.

Without a relation path, query generators cannot produce the join clause.

### V-7: Type divergence report (REPORT)
> For any entity where `EntityFieldMeta.hasTypeDivergence()` is true, include a `"divergentFields"` section in the JSON listing field name, primary type, and per-view types.

This is already implemented via `typeByView` in the JSON output and `hasTypeDivergence()` on the model.

---

## Summary decision matrix

| Scenario                           | Allowed?    | Requires `@FieldSource`? | Validation               |
| ---------------------------------- | ----------- | ------------------------ | ------------------------ |
| Sibling views, documented intent   | Yes         | Yes                      | V-2 (warning if missing) |
| Extends chain, incompatible return | **No**      | N/A                      | V-1 (error)              |
| Scalar vs collection same name     | **No**      | N/A                      | V-4 (error)              |
| Primitive vs boxed                 | Discouraged | No                       | V-3 (info)               |
| Read vs write form asymmetry       | Yes         | Recommended              | V-2                      |
| Legacy migration coexistence       | Yes         | Yes                      | V-2                      |
| Same precision, different wrapper  | **No**      | N/A                      | V-3 + code review        |

