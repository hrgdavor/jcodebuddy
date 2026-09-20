# Jackson Setup

## Goal

Use `hipster-entity` views with Jackson for three things: writing a full view, reading a full view,
and writing a **change set** — the JSON shape of a partial update.

## When to use

Use this guide when you want to read or write JSON using generated view metadata, in particular
when you want to send only the fields that changed rather than the whole row.

## Dependency

The library modules are built against **Jackson 3** (`tools.jackson.*`, not
`com.fasterxml.jackson.databind.*`). The versions in use are the ones the root POM pins, so take the
dependency without a version:

```xml
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-jackson</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

A consuming project whose views are `BUILDER_TRACKED` or `BUILDER_ALL` needs
`hipster-entity-core` as well, because the tracking contract lives there (see the note below).

## Serialize a whole view

```java
ViewMeta<PersonSummary, PersonSummary_> meta = PersonSummary_.META;
ViewReader view = /* a read proxy or a record */;

StringWriter out = new StringWriter();
EntityJacksonMapper.toJson(meta, view, out);
```

Serialization is metadata-driven and allocation-light: it walks `meta.fieldValues()` and calls
`ViewReader.get(ordinal)`, so it needs no reflection and no per-call name lookup.

## Deserialize a whole view

```java
PersonSummary summary = EntityJacksonMapper.fromJson(meta, parser);
```

`fromJson` builds a one-off `EntityJacksonViewDeserializer`. For a hot loop, construct the
deserializer once and call `deserialize(parser)` per document — its parse loop is zero-allocation
and resolves names through the generated `forName` switch, never a `HashMap` (DEC-016).

**Null policy (S4).** A field absent from the payload is simply left `null` in the backing array,
and `create(Object[])` accepts it. This is deliberate for `DERIVED` and `JOINED` fields, which never
arrive over the wire; there is no "missing required field" rejection in this release. A field that
is present as an explicit JSON `null` is also accepted.

## Write only what changed

This is the reason the tracking contract exists. Given any `ViewChangeTracking` source — the
generated `<View>BuilderTracking` or an updatable proxy from
`ArrayBackedViewProxyFactory.createUpdatable` — write a change set:

```java
StringWriter patch = new StringWriter();
EntityJacksonMapper.toJsonChanges(PersonSummary_.META, tracking, patch);
// {"firstName":"Grace"}
```

The document is a **JSON Merge Patch of current values only**. There is no
"with previous values included" mode: `toJsonChanges(meta, tracking, writer)`
is the whole API, and `EntityJacksonChangeSerializer.serialize(tracking, gen)`
takes no `includePrevious` flag. The tracker keeps no previous value, so the
library has nothing to put in a `previous` member — an audit-style
old&nbsp;→&nbsp;new document is a comparison of **two instances**, which the
caller builds from the baseline view it created the mutable from:

```java
PersonSummary baseline = view;                      // what `tracking` was built from
tracking.firstName("Grace");

tracking.changedValues().forEach(change ->          // the new half: from the tracker
        System.out.println(change.fieldName() + " is now " + change.current()));

System.out.println("{\"firstName\":{\"previous\":\"" + baseline.firstName()
        + "\",\"current\":\"" + tracking.firstName() + "\"}}");
// {"firstName":{"previous":"Ada","current":"Grace"}}   — built by the caller
```

Three rules make this shape predictable:

| Rule | Why |
|---|---|
| A field that did **not** change is not written at all | S4: absence means "leave it alone", which is exactly what a partial `UPDATE` needs. It is never an explicit `null`. |
| A changed field whose value is `null` **is** written, as `null` | "absent" and "changed to null" are different instructions; only the second clears a column. |
| Fields are written in **ordinal order**, and a retired (`retired() == true`) field is skipped | the order matches the field enum, so output is deterministic; a tombstone is never written (R1.4). |

`EntityJacksonMapper.changeSerializer(meta)` returns the reusable
`EntityJacksonChangeSerializer` when you want to keep one instance per view.

## Registering with an `ObjectMapper`

```java
ObjectMapper mapper = new ObjectMapper();
mapper = EntityJacksonMapper.registerModule(mapper, PersonSummary_.META);
```

That gives an `ObjectMapper` the same view serde without any per-call wiring. The
`EntityJacksonViewJsonSerializer` / `EntityJacksonViewJsonDeserializer` pair is the thin adapter
those registrations use; both are reachable code, exercised end to end from
`hipster-entity-test`.

## Tracked views need `hipster-entity-core`

`ViewChangeTracking` lives in `hr.hrg.hipster.entity.core`, not in `hipster-entity-api`. Generated
tracking builders therefore import from `core`, and their `META`'s creator function uses
`ArrayBackedViewProxyFactory`. That module-layering choice is deliberate and documented in
`../../architecture/decisions/` — see the S3 decision, which defers moving the contract to `api`.

## See also

- [Ordinal array contract](ordinal-array-contract.md) — the positional layout the deserializer fills
- [JDBC row adapter](jdbc-row-adapter.md) — the same idea for `ResultSet`
- [Getting started](../getting-started.md)
