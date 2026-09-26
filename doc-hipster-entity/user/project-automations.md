# Project automations: a module in *your* project

How to give a project automations — a generator pass, a code action, a report — without a classpath scan and
without a jar someone else built. The rule behind this page is
[DEC-031](../architecture/decisions/DEC-031-project-automations-are-living-code.md): **an automation is living
code in the project it automates**, compiled by that project's own build, visible in code review.

Read this page with [`getting-started-new-project.md`](getting-started-new-project.md), which covers the module
conversion itself. This page is about the automation half: where the code lives, how it gets onto a host's
classpath, and what to do when you would rather copy an example than write from scratch.

---

## 1. What an automation module is

| | |
| --- | --- |
| **Where it lives** | `src/main/java` of a module in the project it automates — never a jar a host loads at runtime, never `META-INF/services`, never annotation scanning (DEC-031 § Decision 1–2) |
| **What it declares** | the markers the user writes in ordinary code (`@GenerateBuilder` and anything like it), in its own `-api` module if the project wants the markers callable from code that must not depend on the implementation |
| **What it does** | the transformation, the code action, the report — as plain Java that a stock IDE can follow from entry point to leaf (DEC-019) |
| **Where its output goes** | `.jcodebuddy/` in the module it applies to: `metadata/` for derived output, `context/` for specs, `reports/` for run records, `agent-state/` for scratch. **Generated `.java` does not go there** — it stays under `src/main/java` (DEC-026) |
| **How it is run** | by the project's own script or build step, with the module's classes and dependencies on the classpath — the shape [`scripts/gen.js`](../../scripts/gen.js) already uses for this repository's generator (`dependency:build-classpath` into a file, then `java -cp <module>/target/classes:<exported> <MainClass>`) |

A host that wants a project's automations gets them **on its classpath when it is launched**
(DEC-031 § Decision 2). There is no second way.

## 2. Bootstrap: two routes, both producing source you own

### Route A — copy an example (works today)

This repository's own automation is the working example: `project-automation/` (the automation code) and
`hipster-entity-example/` (a converted module that applies it, with its `.jcodebuddy/` tree). Copy the shape, not
the entity-specific content:

1. **Copy the module skeleton**: the automation module's `pom.xml`, its `src/main/java` package layout, and — if
   the project splits markers from implementation — its `-api` module. Register both in the parent POM's
   `<modules>`, and give any module-to-module dependency an explicit `<version>${project.version}</version>` **or**
   a `dependencyManagement` entry in the parent: a version-less internal dependency resolves under Maven 4 and
   fails under Maven 3, which is exactly how this repository's own gate broke for a while.
2. **Copy the `.jcodebuddy/` skeleton** into the module that will apply the automation, and read
   [`hipster-entity-example/.jcodebuddy/README.md`](../../hipster-entity-example/.jcodebuddy/README.md) for what
   each subdirectory is for (DEC-026). Do not copy a `.jcodebuddy/` into a module that does not apply automation:
   the directory *means* "this module applies `project-automation`".
3. **Rename the markers and the generator** to your domain, then delete the generated blocks you do not want.
   Deleting a block is how you ask for it to be regenerated (DEC-020); the generator recognises its own output by
   shape, not by a marker comment, so your edits inside a block survive a pass.
4. **Point the run at your module** — the one command in the project that runs the pass (in this repository,
   `bun scripts/gen.js`) with your module's source root, metadata root, packages and `--java-out` pointing at the
   same `src/main/java` (see [`getting-started-new-project.md`](getting-started-new-project.md) § *the flags*,
   which lists every flag and why `--java-out` is the one that matters).

### Route B — generate a starting stub (planned, not built)

`DEC-031`'s follow-up list names a stub generator: a few initial requirements in, a compiling automation module
out — POM, an example automation, and the generated registration. **It does not exist yet**, and this page will
not pretend otherwise. Until it does, Route A is the way, and the generator is the right thing to build: the
question it asks ("which markers, which languages, which automation kinds") is the question that decides which
skeleton files are needed.

## 3. How a host gets your automations

Two shapes are possible, and the choice is deliberately **not** made by this page's author:

**Shape 1 — the project's own entry point calls the host's library (recommended, and the only one DEC-031
permits).** Your module has a `main` (generated, committed, editable) that constructs your automations by direct
construction — a call a stock IDE can navigate — and passes them to the host's start-up API:

```java
// Generated into YOUR project; edit freely, delete to regen (DEC-020/021).
public static void main(String[] args) {
    var automations = List.of(new SyncBuilderAutomation(), new ReportAutomation());
    SidecarApp.run(args, automations);        // the host's library entry point
}
```

The host never searches for classes, so there is nothing to scan and nothing to secure against: your launcher
names them. This requires the host to expose `run(args, automations)` (the sidecar's launch path, one of DEC-031's
three follow-ups — **not built yet**).

**Shape 2 — the project's build puts the module on the host's classpath.** A generated launch command that
spreads the sidecar's jar, your module's `target/classes` and its exported dependency classpath:

```console
java -cp "<sidecar.jar><sep><your-module>/target/classes<sep><exported-deps>" <host-main>
```

This is the shape [`scripts/gen.js`](../../scripts/gen.js) uses for the generator pass, so it needs no new
mechanism — only a generated, committed launcher per project, and the host must then still be told *which*
automations to run, which is what Shape 1's registration class provides.

Either way the automation is **visible**: it appears in `git diff`, in code review, and in an IDE's find-usages.

## 4. What is not built yet

Listed so nobody plans around them, and mirrored in
[DEC-031](../architecture/decisions/DEC-031-project-automations-are-living-code.md):

| Item | State |
| --- | --- |
| The stub generator (Route B) | not built |
| The host's `run(args, automations)` entry point (Shape 1) | not built |
| A generated, committed launcher per project (Shape 2) | not built |
| A canonical example to copy, documented as such | **this page points at the repository's own modules**, which is a real example but not yet written as a copy-me template |
| `jwa-sidecar.txt` (the classloader route this replaced) | withdrawn, not to be built (DEC-031) |
