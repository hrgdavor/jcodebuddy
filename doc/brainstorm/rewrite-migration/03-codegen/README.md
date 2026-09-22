# Phase 3: Code Generation Tools

Design notes for Phase 3 of the rewrite-migration. **This directory holds notes, not code** — every
generator described here now exists, in place, in the module that owns it.

> **Status: delivered, but not as this directory originally described it** (verified against the tree
> 2026-09-22). The plan below this note proposed a *wrapper layer* in
> `project-automation/src/main/java/hr/hrg/rewrite/tooling/` whose classes kept the JavaParser API
> (`CompilationUnit` return type) while using OpenRewrite underneath, named `OpenRewrite*`. That layer was
> never built: Phase 6 ported each caller to the OpenRewrite API directly, which is why no `OpenRewrite*`
> class and no `hr/hrg/rewrite/**` package exists. The staging package that was to hold them was deleted
> because it did not compile and nothing referenced it.

## Where the generators actually are

| Generator | Home |
| --- | --- |
| `ViewInterfaceGenerator` | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/` |
| `ViewBuilderGenerator`, `ViewTrackingBuilderGenerator` | same |
| `ViewRecordGenerator`, `ViewAdapterGenerator`, `ViewMapperGenerator` | same |
| `ValidationGenerator` | same |
| `FieldBoilerplateGenerator` (field enum) | same |
| `EntityMetadataGenerator` | same |
| `RecordBuilderProcessor`, `ClassMemberProcessor`, `BuilderTransformationEngine` | `jwa-builder/src/main/java/hr/hrg/watch2/builder/` |
| `EnumCompactionCli` and the ported validation rules | `hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/` |

They emit **text**, not a mutated tree: each one reads an OpenRewrite LST for facts and renders the file it
owns, with javac line spans used where a position is needed. The decision, the five printer artefacts it
had to reproduce, and the alternative that was rejected are in
`doc/brainstorm/rewrite-migration/06-migration/MIGRATION-CAVEATS.md` § 4.2.

## Usage

See `USAGE.md` in this directory for the delivered entry points.

## Compliance

All generated code complies with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN
