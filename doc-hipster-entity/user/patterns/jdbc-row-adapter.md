# JDBC Row Adapter

> **Status: the hand-written pattern below is guidance; the GENERATED SQL classes are a draft /
> exploration, and opt-in.**
>
> Everything on this page up to *What the generated adapter will look like* is a hand-written
> pattern: one small, reflection-free method you can put in your own project today, driven by the
> generated `ViewMeta`. It needs nothing from the tooling.
>
> The generated `<View>RowAdapter` / `<View>Binder` pair described in the last section is **not a
> supported generator**. It is emitted only when a project asks for it with the tooling's
> `--adapters` flag, the default pass emits none of it, no property or profile enables it
> implicitly, the example project does not enable it, and its emitted API may change or be
> withdrawn. **Any future SQL support must keep that shape: opt-in, never a default.** See
> `hipster-entity-tooling/README.md` for the flag and the opt-in rule.

## Goal

Show a complete, reflection-free reference adapter that turns a JDBC `ResultSet` row into the
positional `Object[]` the
[ordinal array contract](ordinal-array-contract.md) requires.

The whole adapter is one method of about thirty lines. It is driven entirely by the generated
`ViewMeta`, so the same code serves every view in the project.

## When to use

Use this shape when you read a view directly from SQL — with plain `java.sql`, a connection pool,
or a hand-rolled query builder. If you use a mapper framework instead, that framework is your
adapter and this document is still worth reading for the three rules it states: tolerate `null`
everywhere, never write a `retired()` field, and resolve column names through `FieldDef.column()`.

## The reference adapter

```java
import hr.hrg.hipster.entity.api.ViewMeta;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Fills a positional values array from one result-set row.
 * Reflection-free: every lookup is a generated switch or a direct index.
 */
public final class ResultSetRowAdapter {

    /**
     * @param rs   the result set, already positioned on the row to read
     * @param meta the generated metadata of the view being built
     * @return a values array with {@code values[f.ordinal()]} holding the value of field {@code f}
     */
    public static Object[] fromResultSet(ResultSet rs, ViewMeta<?, ?> meta) throws SQLException {
        int fieldCount = meta.fieldCount();
        Object[] values = new Object[fieldCount];

        for (int i = 0; i < fieldCount; i++) {
            Object value = rs.getObject(i + 1);      // JDBC columns are 1-based, ordinals are 0-based
            values[i] = rs.wasNull() ? null : value; // a SQL NULL must stay a null slot
        }
        return values;
    }
}
```

Thirty lines including the Javadoc, no reflection, no `Map<String, Method>`, no per-call
allocation beyond the array itself and whatever the driver returns from `getObject`.

Its one assumption is stated rather than hidden: **the result set's column order is the view's
ordinal order**. That is the arrangement a `hipster-entity` projection should produce — build the
`SELECT` list from `meta.fieldNameAt(i)` / `field.column()` so the query and the array layout are
the same artifact, and never from `SELECT *`. If the order is not yours to control, use the
by-name variant in [A note on `SELECT *`](#a-note-on-select-).

### Driving it

```java
ViewMeta<PersonSummary, PersonSummary_> meta = PersonSummary_.META;

// One column per field, in ordinal order — the SELECT list and the array layout are the same artifact.
try (PreparedStatement ps = connection.prepareStatement("""
        SELECT p.id, p.first_name, p.last_name,
               EXTRACT(YEAR FROM p.birth_date) AS age,
               d.name                          AS department_name,
               CAST(NULL AS VARCHAR)           AS metadata
        FROM person p LEFT JOIN department d ON d.id = p.department_id
        WHERE p.id = ?
        """)) {
    ps.setLong(1, id);
    try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
            Object[] values = ResultSetRowAdapter.fromResultSet(rs, meta);
            PersonSummary person = meta.create(values);
        }
    }
}
```

### When a field has no column

The positional read above works because every ordinal has a column, including the derived and
joined ones. A field that genuinely cannot have one — a value computed downstream, a table this
query does not join — breaks that assumption, and the adapter must not paper over it by shortening
the array.

Two correct responses:

- **Make the query supply the slot.** A derived field is often computable in SQL, which keeps the
  projection and the view aligned. The example above does exactly that: `age` (a `DERIVED` field)
  is computed from `birth_date` and `departmentName` (a `JOINED` field) is selected from the joined
  table, so ordinals 3 and 4 are filled from the row.
- **Leave the slot `null` and keep the array full length.** If the value is computed downstream,
  write `null` at that ordinal — never omit it, and never compact the array around it. The slot's
  existence is what keeps every following field at its correct position; see the contract's rule 4.

Where a query really cannot fill a slot, keep the column in the `SELECT` list and return `NULL` for
it, as the example does for `metadata`. The `SELECT` list must always have one entry per field, in
ordinal order. A projection that emits five columns for a six-field view produces an array that is
too short, and the failure mode is a shifted or truncated value, not an error.

### What each `ViewMeta` accessor is for

The three accessors the adapter leans on, and why each one exists:

| Accessor | Returns | Role in the adapter |
|---|---|---|
| `meta.fieldCount()` | `int` — the number of field constants | The array length. Rule 2 of the contract: `values.length == FieldDef.values().length`. |
| `meta.fieldNameAt(i)` | `String` — the field name at ordinal `i` | The column label to match, when the result set is not guaranteed to be in ordinal order. |
| `meta.fieldTypeAt(i)` | `java.lang.reflect.Type` — the field's declared Java type | Null-policy decisions and diagnostics. **Not** a conversion hint: see [Null tolerance](#null-tolerance) below. |

Note that `fieldNameAt(i)` returns the **field** name (the accessor/enum name), which is not
necessarily the SQL column name. The column name is `field.column()` — see
[Column names come from `FieldDef.column()`](#column-names-come-from-fielddefcolumn) below.

And note that `fieldTypeAt(i)` returns a `reflect.Type`, not a runtime conversion instruction. Do
not build a `Class`-keyed converter table out of it: that is a reflective type-conversion path in
disguise, and it silently mis-converts the fields it does not understand. If a column's JDBC type
does not match the field's declared type, that is a divergence worth reporting, not guessing
around.

### A note on `SELECT *`

The adapter above assumes the result set's column order is the view's ordinal order. That is the
intended arrangement: a projection should emit its `SELECT` list from `meta.fieldNameAt(i)` so the
query and the array layout are the same artifact. Prepare a statement with an explicit column list
in `ViewMeta` order; never rely on `SELECT *`, whose column order is not yours to promise.

If you cannot control the column order — an external query, a `SELECT *` you do not own — do not
index by position. Resolve the mapping once, up front, and write by ordinal:

```java
import hr.hrg.hipster.entity.api.FieldDef;
import hr.hrg.hipster.entity.api.ViewMeta;

import java.sql.ResultSet;
import java.sql.SQLException;

public static Object[] fromResultSetByName(ResultSet rs, ViewMeta<?, ?> meta) throws SQLException {
    int fieldCount = meta.fieldCount();
    Object[] values = new Object[fieldCount];

    for (int ord = 0; ord < fieldCount; ord++) {
        FieldDef field = meta.fieldValues()[ord];
        if (field.retired()) {
            continue;                       // no column is expected for a retired field
        }
        String column = field.column();     // @FieldSource.column() label, or null
        if (column == null) {
            continue;                       // DERIVED / JOINED — the query has no such column
        }
        Object value = rs.getObject(column); // by label: order-independent
        values[ord] = rs.wasNull() ? null : value; // SQL NULL, and a column the row does not carry
    }
    return values;
}
```

The `field.column()` call is the whole column-name resolution, and it is worth dwelling on, because
it is one line of adapter code that deliberately contains no logic.

## Null tolerance is mandatory

**Every slot may legitimately be `null`.** An adapter must never treat a `null` slot as an error,
and must never substitute a default value for it. Three distinct situations produce one:

1. **A `DERIVED` field.** The value is computed from other fields; a result-set adapter usually
   cannot compute it, so it stores `null` and lets the caller fill it in later.
2. **A `JOINED` field.** The value lives in another table that this query did not join. The slot
   still exists; its content is `null`.
3. **A tombstone slot.** A field that has been retired keeps its ordinal (rule R1, recorded in
   [DEC-023](../../architecture/decisions/DEC-023.md)), so its slot
   exists forever. A row written before the column was dropped may still carry a value; a row
   written after it carries nothing. Both are valid array contents.

`META.create(values)` accepts `null` in any of these slots. It does not reject a missing value and
does not substitute a sentinel — a full payload is allowed to be sparse, and this is the same
policy the JSON reader follows: skip what is absent, tolerate `null` for what is present.

Two consequences worth stating plainly:

- `values[i] == null` means "no value available". It never means "unchanged" — the array holds no
  change information at all. Change tracking lives in a different structure entirely; see the
  [ordinal array contract](ordinal-array-contract.md).
- Because a `null` slot is indistinguishable from a field the adapter chose not to fill, use the
  field's `retired()` and `column()` flags — not the value — when deciding what to write. Never
  infer intent from a value being `null`.

## Never write a column for a `retired()` field

`FieldDef.retired()` is `true` only on a tombstone constant. It is the **write-side gate**, and it
exists because the other gates cannot express it:

- A tombstone has no accessor left on the view, so there is no `@FieldSource` for it to carry.
- Therefore `fieldKind()` defaults it to `FieldKind.COLUMN` — apparently writable.
- So a binder that filters on `fieldKind() == COLUMN` alone would silently re-add a dropped column
  to every generated `INSERT`/`UPDATE`, and the insert would fail or write a stale value.

Hence the rule, in one line: **writers skip every field whose `retired()` is `true`.**

```java
// ❌ WRONG — a retired column comes back
if (field.fieldKind() == FieldKind.COLUMN) {
    columns.add(field.column());
}

// ✅ CORRECT — writable and still part of the schema
if (field.fieldKind() == FieldKind.COLUMN && !field.retired()) {
    columns.add(field.column());
}
```

Readers are the mirror image and must stay **tolerant** rather than strict: the slot still exists,
so a reader that receives a value there must accept it and a reader that receives nothing must
accept that too. `retired()` gates writes only, never reads.

## Column names come from `FieldDef.column()`

Resolving a field to a database column name is one call — `field.column()` — and adapters never
re-implement it. The contract that call satisfies:

| Field state | `column()` must return |
|---|---|
| `@FieldSource(column = "person_first")` | `"person_first"` — the explicit label |
| `@FieldSource` with an empty `column` | the field name, i.e. `enum.name()`, which equals the accessor name |
| `@FieldSource(kind = DERIVED / JOINED)` | `null` for the column; the annotation carries `expression()` / `relation()` instead |
| a retired tombstone constant | `null` — a retired field has no column to write |
| a field with no `@FieldSource` and no declared column | no column name is available to the adapter; see the note below |

The annotation's own `column()` is documented as "defaults to the method name when empty", and the
"defaults to the method name" half of that resolution is performed by `FieldDef.column()` rather
than by the annotation, so the rule lives in exactly one place. When an adapter needs a column name
it calls `field.column()` and uses the result; when the result is `null` there is no column to
write, and the adapter skips the field.

> **Note on unannotated fields.** `FieldDef.column()` has a default implementation returning
> `null`, and the resolution above is what a generated constant supplies by *overriding* it — for
> the accessors that carry `@FieldSource`, whose column is either the explicit label or the accessor
> name. A constant that neither carries `@FieldSource` nor overrides `column()` therefore reports
> `null`, and an adapter that skips on `null` will skip it. That is a deliberate "no column is
> declared for this field" answer, not an error: it is the same answer a `DERIVED`/`JOINED` field
> gets, and the same answer a tombstone gets. It is also why `SELECT *` is never the right input —
> an unannotated `COLUMN` field whose column really is its accessor name must declare that fact with
> `@FieldSource`, so the adapter does not have to guess.

Do not write any of the following in an adapter:

```java
// ❌ WRONG — re-implements the resolution
String column = field.column().isEmpty() ? field.name() : field.column();

// ❌ WRONG — inspects the annotation reflectively to find the label
FieldSource fs = viewType.getMethod(field.name()).getAnnotation(FieldSource.class);
String column = fs == null || fs.column().isEmpty() ? field.name() : fs.column();
```

Both are duplicate implementations of a rule that already has an owner, and both will drift from it.

## What the generated adapter looks like (draft / exploration, opt-in)

The tooling contains a **draft** generator that emits a per-view adapter, `<View>RowAdapter`, in the
**same package as the view** — `PersonSummaryRowAdapter` next to `PersonSummary` — plus its
`<View>Binder`. It is the same contract as the generic adapter above, specialized at generation time:

- **Concrete methods, direct dispatch.** `PersonSummaryRowAdapter.fromResultSet(rs, …)` calls
  `rs.getLong(1)`, `rs.getString(2)`, and so on, and writes `values[0]`, `values[1]`, … directly.
  There is no loop over `ViewMeta`, no `fieldTypeAt(i)` dispatch, and no reflection.
- **No `Map<String, Method>`.** Nothing is looked up by name at runtime. The generated method body
  *is* the wiring, so an IDE can navigate from the adapter to the accessor and back.
- **Ordinals are compiled in.** The generated adapter does not need `forName.forName()` or
  `field.ordinal()` in its row loop, because the generator already resolved both — exactly as the
  generated deserializer hardcodes `values[0]`, `values[1]`, … . `forName` remains the sanctioned
  route for the *generic* adapter and for any name-driven path.
- **Same null policy, same skipped columns.** The generated variant must tolerate `null` in every
  slot, and must emit no column at all for a field whose `retired()` is `true`.
- **Column labels still come from `FieldDef.column()`.** Generation reads the constant's resolved
  column name; it does not re-derive it from the annotation.

**This is a sketch, not a contract.** It runs only under the tooling's `--adapters` flag, the default
generation pass emits none of it, the example project does not enable it, and the emitted class and
method shapes may change or be withdrawn. Until SQL support is settled, the generic adapter above is
the supported path, and it is a correct one: it is reflection-free, allocation-light, and driven by
metadata that is generated rather than guessed.

## See also

- [The Ordinal Array Contract](ordinal-array-contract.md) — the contract this adapter implements.
- [Implementing field-name-to-ordinal dispatch](../../architecture/field-lookup-guide.md) — why the
  generic adapter uses `meta.forName` and never a per-call `HashMap` (DEC-016).
- [`FieldDef`](../../../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/FieldDef.java) —
  `fieldKind()`, `column()`, `relation()`, `expression()`, `retired()`.
- [`FieldSource`](../../../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/FieldSource.java) —
  the annotation whose `column()` label `FieldDef.column()` resolves.
- [Core Concepts](../core-concepts.md) — `COLUMN`, `DERIVED`, and `JOINED` field sources.
- [DEC-023 — R1: field enums are append-only ordinal ledgers](../../architecture/decisions/DEC-023.md) —
  the standalone record that the retired-field rule comes from.
- [`decisions/README.md`](../../architecture/decisions/README.md) — the decision index.
