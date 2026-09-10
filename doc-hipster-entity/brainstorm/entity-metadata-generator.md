# Entity Metadata Generator

This document describes the new entity metadata JSON generator in `hipster-entity-tooling`.

## Purpose

Generate rich per-entity metadata in JSON format from interface-based entity definitions. This supports additional tooling such as code generation, documentation sites, API contract validation, or UI form/database mapping.

## What it does

`EntityMetadataGenerator` scans a source directory for `.java` interface files and collects:

- entity marker interfaces (ends with `Entity` and extends `EntityBase<ID>`)
- interface inheritance graph
- view interfaces (those extending an entity marker directly or transitively)
- `@View(read = ..., write = ...)` options from `hr.hrg.hipster.entity.api.View`
- `@FieldSource(kind, column, relation, expression)` on individual getter methods
- method properties (parameterless methods with non-void return type)
- primary key type (`ID` type param from `EntityBase<ID>`)
- **entity-wide field summary** — all fields across all views with per-field view membership
### Primitive type rule

- Primitive return types (e.g., `int`) are emitted in generated enums as boxed types in declarations (`java.lang.Integer.class`).
- JSON metadata includes a structured object for primitive types with `type` set to the boxed class (`java.lang.Integer`), an `unboxed` entry (`int`) and `primitive` flag.
### View property enum rule

- Each generated view enum (`<ViewName>Property`) must include:
  - `id()` first (as derived from marker `EntityBase` type)
  - all properties inherited from parent interfaces
  - properties are sorted alphabetically (except id that is always first)
  - no duplicates (child properties should override parent where same name exists)

### Field source classification rule

Fields on view interfaces can be annotated with `@FieldSource` to classify their data origin:

- `FieldKind.COLUMN` (default) — directly mapped to a database column
- `FieldKind.DERIVED` — computed/calculated from other fields (not stored)
- `FieldKind.JOINED` — sourced from a related table via join or sub-query

Optional annotation attributes:
- `column` — explicit DB column name (defaults to method name)
- `relation` — relation path for JOINED fields (e.g. `"department.name"`)
- `expression` — SQL/formula expression for DERIVED fields

When `@FieldSource` is absent, the field defaults to `COLUMN` with the method name as the column name.

Example:

```java
public interface PersonSummary extends PersonEntity {
    String firstName();                                          // COLUMN (default)

    @FieldSource(kind = FieldKind.DERIVED, expression = "YEAR(NOW()) - YEAR(birthDate)")
    Integer age();                                               // DERIVED

    @FieldSource(kind = FieldKind.JOINED, relation = "department.name")
    String departmentName();                                     // JOINED
}
```

### Entity-wide field summary

The generator produces an `allFields` section in the JSON that merges all fields from all views into a single list. Each entry includes:
- field name & type
- `fieldKind` (COLUMN / DERIVED / JOINED)
- optional `column`, `relation`, `expression`
- `views` — list of view names that expose this field

This enables consumers (code generators, schema validators, query builders) to answer cross-cutting questions like:
- "What are ALL columns for Person?" → filter `allFields` by `fieldKind: COLUMN`
- "Which fields need joins?" → filter by `fieldKind: JOINED`
- "Which views include a given field?" → check the `views` array

For each entity marker it produces `<Entity>.metadata.json` in the output dir.

Additionally, for each view interface it creates a companion enum in package output path (e.g. `PersonSummaryProperty`) with entries for each view property, including `propertyName` and `propertyType` (generics preserved). This enables rich compile-time metadata access without reflection.

## Output format

Example `Person.metadata.json`:

```json
{
  "entityName": "Person",
  "package": "hr.hrg.hipster.entity.person",
  "markerInterface": "PersonEntity",
  "idType": "Long",
  "views": [
    {
      "name": "PersonSummary",
      "extends": ["PersonEntity"],
      "read": true,
      "write": false,
      "properties": [
        {"name": "firstName", "type": "String"},
        {"name": "lastName", "type": "String"},
        {"name": "age", "type": {"type": "java.lang.Integer", "unboxed": "int", "primitive": true}, "fieldKind": "DERIVED", "expression": "YEAR(NOW()) - YEAR(birthDate)"},
        {"name": "departmentName", "type": "String", "fieldKind": "JOINED", "relation": "department.name"},
        {
          "name": "metadata",
          "type": {
            "type": "java.util.Map",
            "genericArguments": [
              {"type": "java.lang.String"},
              {
                "type": "java.util.List",
                "genericArguments": [{"type": "java.lang.Long"}]
              }
            ]
          }
        }
      ]
    }
  ],
  "allFields": [
    {"name": "id", "type": "java.lang.Long", "fieldKind": "COLUMN", "views": ["PersonSummary"]},
    {"name": "firstName", "type": "String", "fieldKind": "COLUMN", "views": ["PersonSummary"]},
    {"name": "lastName", "type": "String", "fieldKind": "COLUMN", "views": ["PersonSummary"]},
    {"name": "age", "type": {"type": "java.lang.Integer", "unboxed": "int", "primitive": true}, "fieldKind": "DERIVED", "expression": "YEAR(NOW()) - YEAR(birthDate)", "views": ["PersonSummary"]},
    {"name": "departmentName", "type": "String", "fieldKind": "JOINED", "relation": "department.name", "views": ["PersonSummary"]},
    {"name": "metadata", "type": {"type": "java.util.Map", "genericArguments": ["java.lang.String", {"type": "java.util.List", "genericArguments": ["java.lang.Long"]}]}, "fieldKind": "COLUMN", "views": ["PersonSummary"]}
  ]
}
```

Generated view property enum example (`PersonSummaryProperty.java`):

```java
package hr.hrg.hipster.entity.person;

public enum PersonSummaryProperty {
    firstName(java.lang.String.class),
    lastName(java.lang.String.class),
    metadata(TypeUtils.parameterizedType(java.util.Map.class, java.lang.String.class, TypeUtils.parameterizedType(java.util.List.class, java.lang.Long.class)));

    private final Type propertyType;

    PersonSummaryProperty(Type propertyType) {
        this.propertyType = propertyType;
    }

    public String getPropertyName() {
        return name();
    }

    public Type getPropertyType() {
        return propertyType;
    }

    // Utility moved to hr.hrg.hipster.entity.api.TypeUtils and should be used by generated code.
}
```

## Usage

From root:

```bash
mvnd -pl hipster-entity-tooling -Dtest=EntityMetadataGeneratorTest test
```

Production usage:

```bash
cd hipster-entity-tooling
java -cp target/classes;target/dependency/* hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
  ../hipster-entity-example/src/main/java d:/out/metadata
```

Or call from Java:

```java
EntityMetadataGenerator.generate(Paths.get("../hipster-entity-example/src/main/java"), Paths.get("build/metadata"));
```

## Testing

Covered by `EntityMetadataGeneratorTest`.

## Notes

- Output is simple hand-rolled JSON representation (no additional JSON library dependency).
- Parser uses JavaParser.
- The `EntityMeta` / `ViewMeta` / `EntityFieldMeta` model classes are public and reusable in Java for boilerplate generation (mapper, DAO, query builder) without going through JSON.
- `allFields` provides entity-wide cross-view field metadata for schema generation, validation, and query planning.
- `@FieldSource` annotations are optional; unannotated fields default to `COLUMN`.
