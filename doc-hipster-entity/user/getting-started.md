# Getting Started with hipster-entity

> This page covers the concepts and the recorded build command. For a
> complete, step-by-step walkthrough that adds `hipster-entity` to a
> **new** project, see
> [Getting started in a new project](getting-started-new-project.md).

## 1. Add the dependency

The modules are published under the `hr.hrg.jcodebuddy` group id at
version `1.0-SNAPSHOT` (the version declared by the reactor's parent
POM). The real artifact ids are:

| Artifact | What it gives you |
|---|---|
| `hipster-entity-api` | `EntityBase`, `ViewReader`, `ViewWriter`, `FieldDef`, `ViewMeta`, `@View`, `@FieldSource`, `GenLevel` — contracts only |
| `hipster-entity-core` | the ordinal-array runtime: `EntityReadArray`, `EntityUpdateArray`, `EntityUpdateTrackingArray`, the `EEnumSet` family, `ViewChangeTracking`, `ArrayBackedViewProxyFactory` |
| `hipster-entity-tooling` | the generator (`EntityMetadataGenerator`) and the R1 order checker |
| `hipster-entity-jackson` | Jackson 3 integration (`EntityJacksonMapper`) |
| `hipster-entity-test` | integration fixtures used by the other modules' tests |

A minimal Maven dependency on the contracts and the runtime:

```xml
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-api</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>hr.hrg.jcodebuddy</groupId>
  <artifactId>hipster-entity-core</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

Add `hipster-entity-jackson` when you need JSON, and
`hipster-entity-tooling` when you need to *run* the generator. The
tooling module is a dev-time dependency — see the Dev-Time Only
Guarantee in the root [`README.md`](../../README.md) and
[DEC-W003](../../doc/architecture/decisions-watch/DEC-W003.md): it
must not become a transitive dependency of a runtime module. The
tooling module's own README is
[`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md).

## 2. Define a simple entity interface

Create an interface for your entity shape. The root interface extends
`EntityBase<ID>`; each view extends the root and declares record-style
accessors (the accessor name **is** the field name):

```java
package example.person;

import hr.hrg.hipster.entity.api.EntityBase;

public interface PersonEntity extends EntityBase<Long> {
}

public interface PersonSummary extends PersonEntity {
    Long id();
    String firstName();
    String lastName();
    String email();
}
```

## 3. Build and test with `scripts/mvn-jdk25.cmd`

The **recorded, verified** way to build and test this repository is the
wrapper script. Called with no arguments it runs the Hipster-Entity
test set with Maven 3.9 and JDK 25 (the root POM requires
`maven.compiler.release=25`, so both the Maven JVM and the forked
surefire JVM must be JDK 25):

```bat
scripts\mvn-jdk25.cmd
```

The build **regenerates nothing.** The generator is a side tool — no annotation
processing, no compile hook, and no lifecycle binding — so `compile`,
`package` and `test` only compile the generated source already committed
under `src/main/java`. The pass that produces it is
[section 4](#4-run-the-generator), and it is always an explicit step.

The wrapper with no arguments is exactly:

```text
mvn -o -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example -am test
```

The script accepts an explicit goal or flag set as well, and fills in
the module list for you:

```bat
scripts\mvn-jdk25.cmd hipster-entity install
scripts\mvn-jdk25.cmd -o -pl hipster-entity-example -am test
```

`JCODEBUDDY_JDK25` (default `C:\Program Files\Java\jdk-25`) and
`JCODEBUDDY_MVN` (default `D:\programs\mvn\bin\mvn.cmd`) select the JDK
and the Maven launcher.

## 4. Run the generator

This is the **primary path, and it is always explicit**: nothing in the
build runs the generator, so a pass is a manual run or a watch loop. In
this repository the one-command way is
[`scripts\gen.cmd`](../../scripts/gen.cmd):

```bat
scripts\gen.cmd              rem regenerate (compile-only: no jars, no install)
scripts\gen.cmd with-tests   rem regenerate, then run the entity test set
scripts\gen.cmd watch        rem regenerate on every save (Ctrl+C stops it)
```

The script compiles the tooling in the reactor, exports the classpath with
Maven's `dependency:build-classpath` (which maps a reactor dependency to
that module's `target/classes` directory), and runs the generator with a
plain `java -cp`. **No `mvn install` and no jar are needed**, and nothing
is put into the local repository. It also runs `GeneratorPreflight` first,
so a classpath that points at an older tooling fails before anything is
written.

The entry point it runs is `EntityMetadataGenerator`:

```text
java -cp "<hipster-entity-tooling classes + its dependencies>" \
     hr.hrg.hipster.entity.tooling.EntityMetadataGenerator \
     <source-root|java-source-file> <output-dir> [--java-out <dir>] \
     [--packages a.b,c.d] [--adapters] [--validate] [--run-record <file>]
```

(The same class is the shaded tooling jar's `Main-Class`, so
`java -jar hipster-entity-tooling.jar …` is an equivalent form for anyone
who already has a jar.)

- The first positional argument is the source root, or a single `.java`
  file — in which case the tool searches upward for `src/main/java` or
  `src/test/java` to derive the root.
- The second is the output directory for the metadata JSON.
- `--java-out <dir>` is where the generated `.java` goes. Without it,
  generated source is written into positional 2 — the *metadata*
  directory — which is almost never what you want; point it at the
  source root so the files land next to the view.
- `--packages a.b,c.d` restricts **generation** to those packages (it
  does not restrict indexing, so cross-package supertypes and addons
  stay resolvable). Omitting it generates everything.
- `--adapters` additionally emits the positional JDBC adapter and
  binder classes next to each view. This one is **[draft/exploration,
  opt-in]**: SQL generation is not a supported generator, it runs only
  when you ask for it, and the default pass emits none of it. See
  [the JDBC row adapter pattern](patterns/jdbc-row-adapter.md) for the
  hand-written shape, which needs no generator.
- When the first argument is a `.java` file, the generated Java
  boilerplate is written **back into the source tree**, using the
  underscore-suffix convention (`PersonSummary` → `PersonSummary_`).

The same entry point also hosts the R1 order checker as a subcommand
(see [`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md)
and [DEC-023](../architecture/decisions/DEC-023.md)):

```text
java -jar hipster-entity-tooling.jar enum-order --repo <path> --baseline <git-ref> [--target <ref>] [--strict]
```

## 5. Inspect the generated metadata

The generated enum implements `FieldDef` and carries a `ViewMeta`
instance plus a `forName(String)` lookup:

```java
PersonSummary_.forName("firstName");
PersonSummary_.META.fieldCount();
```

The enum's header names the view it was generated from, which is what
makes an IDE rename of the view reach the generated code:

```java
// {@link hr.hrg.hipster.entityexample.person.entity.PersonSummary} Field metadata for the PersonSummary view.
// {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
public enum PersonSummary_ implements FieldDef { ... }
```

## 6. Use the generated view metadata

The generated metadata makes it easy to build generic adapters and
serializers without reflection. The enum ordinal is the positional
index into the backing array:

```java
ViewMeta<PersonSummary, PersonSummary_> meta = PersonSummary_.META;
Object[] values = new Object[meta.fieldCount()];

values[PersonSummary_.firstName.ordinal()] = "Alice";
values[PersonSummary_.lastName.ordinal()] = "Smith";
values[PersonSummary_.email.ordinal()] = "alice@example.com";

PersonSummary summary = meta.create(values);
```

## 7. Next steps

- [Getting started in a new project](getting-started-new-project.md) —
  the full add-dependency → write-interface → generate → build →
  serialize walkthrough.
- [`hipster-entity-tooling/README.md`](../../hipster-entity-tooling/README.md) —
  the generator's outputs, its CLI, the naming contract, and the R1
  order contract.
- [Core Concepts](core-concepts.md)
- [Materialization Guide](materialization-guide.md) — what each
  `GenLevel` gives you.
- [The Ordinal Array Contract](patterns/ordinal-array-contract.md)
- [JSON and Jackson setup](patterns/jackson-setup.md)
- [FAQ](faq.md)
