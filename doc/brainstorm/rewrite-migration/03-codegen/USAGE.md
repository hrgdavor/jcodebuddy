# Phase 3: Code Generation Tools — Usage Guide

How to call the migrated generators. The entry points below are the ones in the tree; the class names are
the real ones (the plan originally spelled them `OpenRewrite*` for a wrapper layer that was never built —
see `README.md` in this directory).

## Architecture

The generators live in `hipster-entity-tooling` (`hr.hrg.hipster.entity.tooling`) and `jwa-builder`
(`hr.hrg.watch2.builder`). They read an OpenRewrite LST and emit text; there is no JavaParser compatibility
layer and no `project-automation/src/main/java/hr/hrg/rewrite/**` package.

## Entry Points

### Generating A View Interface

```java
List<EntryPoint> entryPoints = ViewInterfaceGenerator.entryPointsFor(GenLevel.BUILDER, "Person");

Result result = ViewInterfaceGenerator.generate(sourceRoot, "com.example.view", "Person", entryPoints);
```

### Generating A View Builder

```java
// nestedRecord: true when the view declares its own matching nested record, so build() targets
// View.Record rather than the emitted one. Pass a DivergenceReporter as a sixth argument to be told
// about a member it would otherwise silently re-add or silently skip.
Result result = ViewBuilderGenerator.generate(outputRoot, "com.example.view", viewMeta, allProperties, false);
```

### Generating A Tracking View Builder

```java
Result result = ViewTrackingBuilderGenerator.generate(outputRoot, "com.example.view", viewMeta, allProperties);
```

### Generating A Field Enum

```java
FieldBoilerplateGenerator generator = FieldBoilerplateGenerator.builder("com.example.view", "Person", properties)
        .withEnumTypeName("PersonField")
        .withDivergenceSink(diagnostics::addAll)   // Consumer<List<String>>
        .build();

generator.generate(outputRoot);
```

### Generating Validation Rules

```java
Result result = ValidationGenerator.generate(outputRoot, "com.example.view", view, properties);
```

### Generating Entity Metadata

```java
EntityMetadataGenerator.generate(sourceRoot, outputRoot, javaOutputRoot);
```

The two-argument overload writes no Java; the three-argument one writes generated source under
`javaOutputRoot` as well.

### Building JWA Records And Builders

`RecordBuilderProcessor` and `ClassMemberProcessor` in `jwa-builder` are the processors behind the
`java-watch-agent` generators (`AccessorGenerator`, `BuilderGenerator`, `ConstructorGenerator`) and the
`jwa-sidecar` text-document service. Call them there rather than duplicating the member analysis.

## Testing

The generators are covered by `hipster-entity-tooling`'s suite:

```bash
scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am -Dmaven.compiler.useIncrementalCompilation=false clean test
```

Relevant test classes: `ViewInterfaceGeneratorTest`, `ViewBuilderGeneratorTest`,
`ViewTrackingBuilderGeneratorTest`, `ViewRecordGeneratorTest`, `ViewAdapterGeneratorTest`,
`ViewMapperGeneratorTest`, `ValidationGeneratorTest`, `FieldBoilerplateGeneratorTest`,
`EntityMetadataGeneratorTest`, `ExampleRegenerationTest`, `FieldEnumLedgerRegenerationTest`,
`CooperativeCodegenTest`.

Two of those are stronger than a unit test and worth knowing before changing a printer:
`ExampleRegenerationTest` regenerates `hipster-entity-example` and compares byte for byte, and
`FieldEnumLedgerRegenerationTest` does the same for the field enums.

## Compliance

All generated code complies with:
- DEC-019: Source-visible wiring
- DEC-020: Cooperative codegen (preserve user edits)
- DEC-021: Generator class-file header
- DEC-022: Refactor-sensitive naming
- DEC-029: Class index by FQN

## Next Steps

Phase 3 is complete. Phase 4's rules live in `hipster-entity-tooling/.../validation/` and in
`java-watch-agent`'s tools; Phase 6, the migration itself, is complete. Phase 5 (the automation engine,
`project-automation/.../automation/`) and Phase 7 (testing and validation) are the phases after that — see
`plans/rewrite-migration/PLAN-SUMMARY.md` § *Phase status*.
