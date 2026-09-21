# Version maintenance — what needs updating, and when

This module has an **external, half-yearly update obligation**. It is not
housekeeping: the parser it depends on is tied to a JDK release, so the stack has
to be moved deliberately rather than left to drift.

Written because the Java 21 → 25 move was discoverable only by chance. For a period
this module carried a `maven.compiler.release=21` override because the pinned
OpenRewrite version had no parser for Java 25 — while a newer OpenRewrite release
had shipped one roughly twenty minor versions earlier. Nothing was broken; nobody
had looked.

---

## Why there is a cadence at all

OpenRewrite's Java parser does not implement Java parsing itself. It drives the
JDK's own `javac` through internal, version-specific APIs
(`com.sun.tools.javac.*`). Two consequences follow, and both bite:

1. **One parser module per JDK release.** The published set is `rewrite-java-8`,
   `-11`, `-17`, `-21`, `-25` — each corresponding to a JDK line. There is no
   version-agnostic parser and no parser that spans versions.
2. **A parser must run on the JDK it was built for.** `rewrite-java-25` is compiled
   at class file major version 69 and needs a JDK 25 runtime; running
   `rewrite-java-21` on JDK 25 fails outright with
   `NoClassDefFoundError: com/sun/tools/javac/code/Type$UnknownType`.

So the JDK version determines the parser module, and updating the JDK without
updating the parser — or the reverse — breaks the build.

**Roughly every six months**, a new JDK ships, and OpenRewrite publishes a matching
parser module shortly afterwards. That is the trigger.

---

## The verification trail from the last upgrade

Useful as a worked example of the cadence, and of how to look:

| Fact | Value |
|---|---|
| `rewrite-java-25` first published | OpenRewrite **8.61.3**, 2025-09-03 |
| Version this repo was previously pinned to | **8.40.1** — twenty minor releases earlier, so no Java 25 parser |
| Latest OpenRewrite release at the time of writing | **8.90.4** |
| No parser for Java 26 yet | `rewrite-java-26` returned 404 |
| `rewrite-java-next` | a single release, 8.83.0 — worth watching |

---

## What to bump, and where

| Artifact | Where | Notes |
|---|---|---|
| `openrewrite.version` | **root `pom.xml`** `<dependencyManagement>` | The parent only *manages* it. Bumping here covers `rewrite-core`, `rewrite-java`, `rewrite-maven` |
| `openrewrite.version` | **`merge-java/pom.xml`** | Pinned separately because the version-specific parser module is **not** managed by the parent |
| `rewrite-java-NN` | **`merge-java/pom.xml`** | Swap the artifact when the JDK level moves |
| `maven.compiler.release` | root `pom.xml` | Currently 25. No module-level override should be needed — if one appears, that is a signal something is stale |
| `jgit.version` | `merge-java/pom.xml` | Not managed by the parent; independent of the JDK cadence |
| `mockito.version` | `merge-java/pom.xml` | Ditto |
| `junit5.version` | root `pom.xml` | Ditto |

Nothing else in the repository consumes OpenRewrite. The `org.openrewrite` imports
under `doc/brainstorm/rewrite-migration/` and `plans/rewrite-migration/` are
reference material with no `pom.xml`, so they are not compiled and cannot break.

---

## The update procedure

### 1. Find the current versions

- Latest OpenRewrite release: `https://repo.maven.apache.org/maven2/org/openrewrite/rewrite-java/maven-metadata.xml`
- Which parser modules exist: `https://repo.maven.apache.org/maven2/org/openrewrite/` — look for `rewrite-java-NN`
- First release of a new parser: fetch that module's own `maven-metadata.xml` and read the oldest entry

### 2. Establish the target

The parser module must match the JDK the module builds and runs on. If the parent
targets JDK *N*, the parser is `rewrite-java-N`, and OpenRewrite must be new enough
to publish it.

### 3. Bump the four places above, then verify

Do **not** trust a green build alone. Two checks matter:

**a. Confirm the compiled level and the parser actually on the classpath.**

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"     # match the parser
mvn -f merge-java/pom.xml clean test

# compiled at the expected level?  65 = Java 21, 69 = Java 25
javap -v -cp merge-java/target/classes com.codebuddy.merge.Region | Select-String "major version"

# is the version-specific parser really there?
jar tf "$env:USERPROFILE\.m2\repository\org\openrewrite\rewrite-java-25\8.90.4\rewrite-java-25-8.90.4.jar" |
  Select-String "Java25Parser"
```

**b. Re-prove type attribution.** This is the one that catches a parser regression,
and the full suite passing does not cover it. Re-add a temporary spike asserting:

- `List<String>` and `java.util.List<java.lang.String>` resolve to **one** canonical
  form — the basis of type-aware overload comparison;
- `List<String>` and `List<Integer>` stay **distinct**;
- a no-arg method renders as an **empty** signature, not as a `J.Empty` tree dump;
- unparsable input reports a **failure**, not an empty method list.

The last upgrade passed all four unchanged, which is what justified raising the
compiler release with no source edits.

### 4. Update this file's table and the README's JDK note

---

## How the parser is selected (verified from source)

`JavaParser.fromJavaVersion()` does **not** scan or auto-discover. It reads
`System.getProperty("java.version")` and probes hardcoded class names **in
descending order, taking the first that exists**:

```
if (version >= 25) try "org.openrewrite.java.Java25Parser"
if (version >= 21) try "org.openrewrite.java.Java21Parser"
if (version >= 17) try "org.openrewrite.java.Java17Parser"
if (version >= 11) try "org.openrewrite.java.Java11Parser"
else               try "org.openrewrite.java.Java8Parser"
```

Each probe is a `Class.forName(className)` plus `getDeclaredMethod("builder")`; a
`ClassNotFoundException` or `NoSuchMethodException` falls through to the next. If
none is found the message lists all five modules.

Three consequences worth knowing:

1. **Selection follows the running JVM, not the source level.** On a JDK 25 runtime
   the probe for `Java25Parser` runs first, so with both `rewrite-java-25` and
   `rewrite-java-21` on the classpath **the 25 parser wins**. Shipping two is
   therefore deterministic rather than ambiguous — but it is still worth shipping
   one, because a parser picked by accident of classpath is a parser nobody tested.
2. **The chosen builder is cached in a static field**, and each call invokes its
   `builder()` method to produce a *fresh* parser. That is why
   `ResolvedTypeReader`'s one-parser-per-read works, and why caching the parser
   instance across reads would not have — the failure it produced
   (*"Call reset() on JavaParser before parsing another set of source files..."*)
   was that cache, not a missing optimisation.
3. **A JDK 8 quirk**: `java.version` reports `1.8.0_…`, so a leading `1` is
   normalised to 8.

### The LTS rule — why non-LTS Java is not a target

The upstream comment states it directly:

> Parsers are compiled only for their matching Java version, given their use of
> internal/unstable APIs. Language features introduced in non-LTS versions are only
> supported from the next LTS version onwards.

So there is no `rewrite-java-22`, `-23` or `-24`, and there never will be. Language
features that landed in those releases become parseable only once a parser exists
for the following LTS. Practically: **this module can never target a non-LTS Java
level for its parser**, and a source file using a Java 22–24 feature is not
parseable by any OpenRewrite release until the Java 25 parser exists.

Related, and worth knowing if `ResolvedTypeReader` ever gets a "not print
idempotent" or odd-path failure again: `sourcePathFromSourceText` derives the path
from a `public class`/`class|interface|enum|record` match, and **falls back to a
nanosecond timestamp when the fragment declares no type** — which is exactly the
case for a conflicting hunk. That is why the path ends up looking like
`A.java\508619281404300.java`.

Source: [`JavaParser.java` on `main`](https://raw.githubusercontent.com/openrewrite/rewrite/main/rewrite-java/src/main/java/org/openrewrite/java/JavaParser.java)
(`JdkParserBuilderCache`).

---

## Traps found the hard way

Record these, because each cost real time:

1. **`rewrite-java` is a facade.** Without a version-specific implementation on the
   classpath, `JavaParser` fails at runtime with *"Unable to create a Java parser
   instance"* — a runtime failure, not a compile error, so the build looks fine.
2. **The facade's parser-selection mechanism is reflection over hardcoded class
   names**, driven by the *running JVM's* `java.version` — it does **not** scan the
   classpath. See "How the parser is selected" above. `javap` on the facade does not
   reveal this, because the class names live inside `JdkParserBuilderCache`, a
   separate class in the same jar; looking for them in `JavaParser.class` finds
   nothing and wrongly suggests the mechanism changed.
3. **A `JavaParser` cannot parse three versions of one file.** It caches parsed
   sources and refuses to parse another set declaring the same fully qualified names
   (*"Call reset() on JavaParser before parsing another set of source files..."*).
   Comparing base/ours/theirs of the same file is exactly that case, so
   `ResolvedTypeReader` builds **one parser per read**. Pooling it looks like an
   obvious optimisation and is wrong.
4. **`org.openrewrite.requirePrintEqualsInput` must be disabled** for this use. It
   guards code *generation*; this module only reads types, and a conflicting hunk is
   a fragment that can never print back to itself.
5. **OpenRewrite models an empty parameter list as a single `J.Empty` placeholder**,
   so a no-arg method must be rendered as an empty signature rather than as the
   placeholder's tree text.
6. **Do not rewrite these files with PowerShell.** `Get-Content`/`Set-Content` in
   this environment silently mangled non-ASCII characters (a changelog em-dash
   became a three-character sequence) and `-Encoding UTF8` added a BOM that broke
   compilation of `.java` files. Use the file-editing tools. If you must script it,
   use `[System.IO.File]::ReadAllText` / `WriteAllText` with
   `New-Object System.Text.UTF8Encoding($false)`.

---

## Checking for drift

A cheap periodic check, worth running when a new JDK lands:

```powershell
# What is the newest OpenRewrite, and does it have a parser for our JDK level?
(Invoke-WebRequest "https://repo.maven.apache.org/maven2/org/openrewrite/rewrite-java/maven-metadata.xml").Content
(Invoke-WebRequest "https://repo.maven.apache.org/maven2/org/openrewrite/rewrite-java-26/maven-metadata.xml" -ErrorAction SilentlyContinue)
```

If a parser module exists for a JDK newer than the one this module targets, that is
the signal to move — do not wait for something to break.

One further note for whoever upgrades: OpenRewrite's own build requires **several
JDKs installed at once**, one per parser module it builds (`8`, `11`, `17`, `21`,
`25`). Our use needs only the one matching our target, so a single JDK 25 is enough
here.
