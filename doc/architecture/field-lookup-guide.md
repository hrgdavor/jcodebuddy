# Implementing field-name-to-ordinal dispatch

> **This guide applies to:** deserializers, serializers, format adapters, projection bridges, and
> any other code that must map a field name string to a position in the values array.
>
> **Mandatory rule — DEC-016:** Never build a `HashMap<String, Integer>` (or any other dynamic
> name→ordinal structure) inside a method or parse loop. Use `ViewMeta.forName` + ordinal indexing
> instead. See [DEC-016](../architecture/decisions/DEC-016.md) for the full rationale.

---

## Why this matters

The field schema for a view is **fully known at compile time**. Every `ViewMeta` implementation
carries a `FieldNameMapper` whose `forName(String name)` method is a **generated `switch`
statement** (see [DEC-015](../architecture/decisions/DEC-015.md)). That switch is:

- **O(1)** — no hash computation, no bucket look-up, no collision chains.
- **Zero allocation** — returns an existing enum constant or `null`.
- **JIT-transparent** — the JIT sees a simple branch, not a virtual dispatch chain through
  `HashMap.get → HashMap.getNode → key.hashCode → key.equals`.

A per-call `HashMap<String, Integer>` discards all of these properties and adds:
- allocation of the map itself;
- one `hashCode` + one `put` per field during setup;
- one `hashCode` + one `get` per incoming JSON field (or equivalent token).

On a six-field view processed 10 million times per second, that is hundreds of millions of
unnecessary hash operations per second.

---

## Quick reference

| Do | Don't |
|----|-------|
| Pre-build `readers[]` once per instance | Rebuild `readers[]` every call |
| `meta.forName(name)` in parse loop | `HashMap.get(name)` in parse loop |
| Capture `field.ordinal()` once | Call `forName` twice for the same field |
| `private static final TypeReference<…> REF = new TypeReference<>(){}` | `new TypeReference<>(){}` inside any method |
| `p.skipChildren()` for unknown fields | `throw` for unknown fields |

---

## Pattern 1 — Generic deserializer using `ViewMeta`

Use this pattern whenever you are writing code that works with any view via a `ViewMeta<V,F>`
reference (e.g. `EntityJacksonViewDeserializer`).

```java
public final class MyViewDeserializer<V, F extends Enum<F> & FieldDef> {

    private final ViewMeta<V, F> meta;
    private final ValueReader[]  readers;   // pre-built once per instance

    public MyViewDeserializer(ViewMeta<V, F> meta) {
        this.meta    = meta;
        int n        = meta.fieldCount();
        this.readers = new ValueReader[n];
        for (int i = 0; i < n; i++) {
            this.readers[i] = readerFor(meta.fieldTypeAt(i));
        }
    }

    public V deserialize(JsonParser p) throws IOException {
        Object[] values = new Object[meta.fieldCount()];

        // ... advance to START_OBJECT ...

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String name = p.getCurrentName();
            p.nextToken();

            F field = meta.forName(name);       // ← generated switch, O(1), zero alloc
            if (field != null) {
                int ord = field.ordinal();       // ← capture once; never call forName again
                values[ord] = readers[ord].read(p);
            } else {
                p.skipChildren();               // ← unknown field — skip, never throw
            }
        }

        return meta.create(values);
    }
}
```

**Key points:**

- `readers[]` is created in the constructor — **once per `MyViewDeserializer` instance**.
- `meta.forName(name)` is the only name→ordinal dispatch. No map, no iteration.
- `field.ordinal()` is assigned to a local `int ord`. It is used twice (read + store) but the
  call to `forName` happens exactly once.

---

## Pattern 2 — Boilerplate deserializer (hardcoded switch)

When a generator emits a view-specific deserializer, the field names are literals. The `switch`
statement **is** the O(1) lookup — no `forName` call needed at all.

```java
// switch directly on the TOKEN name — no map, no forName
switch (name) {
    case "id"             -> values[0] = p.currentToken() == JsonToken.VALUE_NULL ? null : p.getLongValue();
    case "firstName"      -> values[1] = p.currentToken() == JsonToken.VALUE_NULL ? null : p.getText();
    case "lastName"       -> values[2] = p.currentToken() == JsonToken.VALUE_NULL ? null : p.getText();
    case "age"            -> values[3] = p.currentToken() == JsonToken.VALUE_NULL ? null : Integer.valueOf(p.getIntValue());
    case "departmentName" -> values[4] = p.currentToken() == JsonToken.VALUE_NULL ? null : p.getText();
    case "metadata"       -> values[5] = p.currentToken() == JsonToken.VALUE_NULL ? null
                                         : p.getCodec().readValue(p, METADATA_TYPE_REF);
    default               -> p.skipChildren();
}
```

The ordinal (`values[0]`, `values[1]`, …) is **hardcoded by the generator** — no runtime ordinal
call is needed because the indices are statically fixed.

---

## Handling complex generic field types (`TypeReference`)

Fields whose Java type is parameterized (e.g. `Map<String, List<Long>>`) require a `TypeReference`
for Jackson to reconstruct full generic type information. The rule is:

> `TypeReference` instances must be **`private static final`** constants — created **once** at
> class load, never inside a method or parse loop.

```java
// ✅ CORRECT — one allocation at class-load, zero per call
private static final TypeReference<Map<String, List<Long>>> METADATA_TYPE_REF =
        new TypeReference<>() {};

// Inside the parse loop — no allocation
case "metadata" -> values[5] = p.currentToken() == JsonToken.VALUE_NULL
        ? null
        : p.getCodec().readValue(p, METADATA_TYPE_REF);
```

```java
// ❌ WRONG — allocates an anonymous class instance on every call
Map<String, List<Long>> m = p.readValueAs(new TypeReference<Map<String, List<Long>>>() {});
```

---

## Anti-patterns to reject in code review and agent output

### ❌ HashMap built per call

```java
// WRONG — allocates a HashMap, computes hash for every field name, on every deserialize call
Map<String, Integer> fieldNameToOrdinal = new HashMap<>(fieldCount * 2);
for (int i = 0; i < fieldCount; i++) {
    fieldNameToOrdinal.put(meta.fieldNameAt(i), i);
}
// ... in parse loop:
Integer ordinal = fieldNameToOrdinal.get(name);
```

**Fix:** Use `meta.forName(name)` in the loop and pre-build `readers[]` in the constructor.

### ❌ Calling `forName` twice

```java
// WRONG — forName called twice for the same field
values[meta.forName(name).ordinal()] = readers[meta.forName(name).ordinal()].read(p);
```

**Fix:**

```java
F field = meta.forName(name);
int ord = field.ordinal();
values[ord] = readers[ord].read(p);
```

### ❌ Linear scan over field name array

```java
// WRONG — O(n) per field, rebuilt per call
for (int i = 0; i < fieldCount; i++) {
    if (meta.fieldNameAt(i).equals(name)) {
        values[i] = readers[i].read(p);
        break;
    }
}
```

**Fix:** Same as above — `meta.forName(name)` is the O(1) alternative.

### ❌ `readValueAs` with inline `TypeReference`

```java
// WRONG — anonymous TypeReference class allocated inside parse loop
metadata = p.readValueAs(new TypeReference<Map<String, List<Long>>>() {});
```

**Fix:** Move `TypeReference` to a `private static final` field.

---

## Checklist for implementors and agents

Before submitting or generating deserializer or field-mapping code, verify:

- [ ] No `new HashMap` (or `new LinkedHashMap`, `new TreeMap`, etc.) appears inside a method that
      is called per entity.
- [ ] `meta.forName(name)` is called **at most once** per field token in the parse loop.
- [ ] The result of `forName(name)` is stored in a local variable before `ordinal()` is called.
- [ ] `readers[]` / `writers[]` are fields of the deserializer class, not local variables.
- [ ] All `TypeReference` usages are `private static final` constants.
- [ ] Unknown fields result in `p.skipChildren()`, not a thrown exception.

---

## References

- [DEC-016 — Field-name-to-ordinal dispatch decision](../architecture/decisions/DEC-016.md)
- [DEC-015 — Generated field metadata method lookup strategy](../architecture/decisions/DEC-015.md)
- [`ViewMeta.forName` Javadoc](../../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/ViewMeta.java)
- [`FieldNameMapper` interface](../../hipster-entity-api/src/main/java/hr/hrg/hipster/entity/api/FieldNameMapper.java)
- [`EntityJacksonViewDeserializer`](../../hipster-entity-jackson/src/main/java/hr/hrg/hipster/entity/jackson/EntityJacksonViewDeserializer.java) — canonical generic implementation
- [`PersonSummaryBoilerplateDeserializer`](../../hipster-entity-jackson/src/test/java/hr/hrg/hipster/entity/jackson/PersonSummaryBoilerplateDeserializer.java) — canonical boilerplate implementation
