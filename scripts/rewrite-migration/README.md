# rewrite-migration — migration tooling and gate

Tooling for the [JavaParser → OpenRewrite migration](../../plans/rewrite-migration/README.md).

> **The migration is complete.** Phases 1–7 changed the code: `javaparser-core` is
> declared by no module, all 34 queue files are ported, and this directory's gate
> reports `RESULT: PASS`. Phase 8 — [Documentation](../../plans/rewrite-migration/08-Documentation.md)
> — is the phase this directory serves now, and it is the only one whose subject
> is prose.

## The entry point

```sh
bun run scripts/rewrite-migration/verify-migration.js
```

One command, one verdict. Everything below is either an input to it or a report
rendered from it.

## What the gate checks

| Check | Fails when |
| --- | --- |
| `queue-import-free` | a queue file is marked `complete`/`testing` while still importing the removed library |
| `staging-compiles` | the `hr.hrg.rewrite` staging package is present but its OpenRewrite dependency or its type names are wrong |
| `tracker-agreement` | a queue file has no tracker row, or an unsettled row names a file with no use |
| `tracker-vocabulary` | a row uses an unknown status, or `blocked` gives no reason |
| `curation-coverage` | the scanner found a Java file that `curation.js` neither queues nor exempts |
| `curation-freshness` | `curation.js` names a file that no longer exists, or exempts one that no longer needs it |
| `allowlist-honest` | an allowlisted source file is not `exempt` in the tracker |
| `pom-dependencies` | a queue module still declares `javaparser-core` (**warn**, not fail) |
| **`docs-honest`** | **a live document names the removed library without a recorded reason — or *teaches* the removed API at all** |

`docs-honest` is Phase 8's addition and the reason this gate still runs. The code
was migrated in Phase 6, but nothing in the build fails when a **document**
contradicts the tree: `AGENTS.md` — the file every contributor and every agent
reads first — mandated the removed library for a whole phase, and no test could
see it. The check is described in the next section.

`--strict` treats warnings as failures; exit code 1 on any FAIL, 2 on a usage
error.

## Phase 8: the documentation check (`docs-honest`)

A markdown file has no import list, so the question "does this instruct a reader
to use the removed library" is answered by classification rather than by a grep.

**Three verdicts per mentioning document**, measured by
[`documentation.js`](documentation.js):

1. **A petrified mention** — the package name. Impossible to mistake for anything
   else.
2. **A bare-name mention** — the library in prose, by its product name. Only
   interesting when the same line is *not* also about OpenRewrite, because
   OpenRewrite ships a class with that exact simple name; the context guard is
   the same one the Java scanner applies, for the same reason.
3. **Teaching a removed API** — a call the reader could type, not a name they
   would merely read: the lexical-preserving printer, the static parse facade,
   the symbol solver, the parser configuration and pretty-printer configuration
   classes. This is the verdict that matters: **a document that shows a reader
   how to call the removed API is an instruction to re-add the dependency.**
   The exact identifiers are the `REMOVED_API` list in
   [`documentation.js`](documentation.js), and this README deliberately does not
   spell them out — it is itself a `live` document, and a list of the calls to
   avoid is indistinguishable from a list of calls to make.

**Three classifications are legitimate without an allowlist entry**, and the
scanner derives each from the path alone:

| Kind | What it means | Examples |
| --- | --- | --- |
| `generated` | a script writes it, so a mention is a property of the inputs | `Checklist.md`, `tracker.md`, `TEST-REPORT.md`, `BenchmarkReport.md` |
| `record` | it exists to describe the migration | `doc/brainstorm/rewrite-migration/**`, `plans/rewrite-migration/**` |
| `decision` | a DEC or brainstorm, corrected by an appended note | `doc-hipster-entity/**`, `doc/architecture/decisions-watch/**` |

Everything else is `live`, and a `live` document that mentions the removed
library must be on the **documentation allowlist** in
[`curation.js`](curation.js) with a `reason` and a `deferredTo` — the same
`{reason, deferredTo, status}` shape the source allowlist uses, so a reader
learns one vocabulary instead of two.

The check is **fail-safe in the same direction as the rest of the gate**: a
document it cannot classify is a failure, not a pass. An exemption whose
retirement condition is missing, or whose file no longer mentions the library at
all, is also a failure — a stale permission is how an allowlist stops meaning
anything.

A `live` document that is allowlisted *and* still teaches the removed API is
reported as a **warning**, naming the API and the file, because a reader will
follow it even though the reason for keeping it is recorded.

## The four Phase 6 scripts

The plan (`06-Migration-Checklist.md` § Deliverables) names four shell scripts.
On this checkout they are these four JavaScript entry points; the `.sh` name in
the plan is the only thing that changed.

| Plan name | Here | What it does |
| --- | --- | --- |
| `scan-remaining-javafiles.sh` | `scan-remaining-javafiles.js` | Finds every remaining use in Java source, classifies it, prints a summary |
| `migrate-file.sh` | `migrate-file.js` | Pre-flight for one file: surface, plan steps, baseline hash, after-action check |
| `verify-migration.sh` | `verify-migration.js` | **The gate** (above) |
| `generate-migration-report.sh` | `generate-migration-report.js` | Writes the migration report in the format the plan specifies |

Two more render Phase 7's and Phase 8's evidence:

| Script | What it does |
| --- | --- |
| `generate-test-report.js` | Renders `doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md` from the Surefire XML, the benchmark JSON and a captured gate run |
| `run-tooling-benchmarks.js` | Runs the module's JMH read-path benchmarks on the same JDK 25 the recorded gate uses |

## Usage

```sh
bun run scripts/rewrite-migration/scan-remaining-javafiles.js
bun run scripts/rewrite-migration/migrate-file.js hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java
bun run scripts/rewrite-migration/verify-migration.js
bun run scripts/rewrite-migration/generate-migration-report.js --out /tmp/report.md
```

`--root <dir>` on any of them points the scan at another checkout (default: the
repository this directory lives in, found by walking up to the directory that
contains `plans/rewrite-migration`).

`migrate-file.js` writes a baseline snapshot before you edit and compares
afterwards, so it is the one script with durable state:

```sh
bun run scripts/rewrite-migration/migrate-file.js --baseline <file>   # before editing
bun run scripts/rewrite-migration/migrate-file.js --after    <file>   # after editing
```

## Where "what to do" lives

Three hand-maintained inputs, all real source:

- **`curation.js`** — the judged half: per-file migration knowledge (priority,
  risk, OpenRewrite classes involved, concrete notes), the **source allowlist** of
  files that legitimately keep a mention, and the **documentation allowlist** of
  markdown that does.
- **`documentation.js`** — the measured half for prose: walks every tracked `.md`
  file, classifies it, and reports what it teaches.
- **`tracker.md`** — status per file (`- [x] status: complete`).

`curation.js` carries the reasoning; `tracker.md` carries the state. The
generated `Checklist.md` holds both, and the gate fails when `tracker.md`
disagrees with what is on disk, so a status cannot drift into a comfortable lie.

## The source allowlist

Eleven source files legitimately keep a mention, and the gate reports them as
EXEMPT with the reason rather than either failing or silently dropping them from
the count — because "found nothing" and "found something I chose to ignore" are
different answers and must not be conflated. The groups:

- **OpenRewrite's own class of the same name.** `org.openrewrite.java.JavaParser`
  is the migrated API, and it is why a bare-name test cannot be taken at face
  value. A *Java* file that parses with OpenRewrite has to name that class, and
  this is the scanner's own vocabulary for saying so.
- **Provenance annotations** in the staged ports and in `SourceReaderTest`,
  `ParseGuardTest`, `TreeQueriesTest` and the benchmark: each names the old
  behaviour a current expectation exists to prevent.
- **The destination, not the source.** `merge-java`'s `ResolvedTypeReader` is the
  reference port this migration's verified mappings were evidenced from.

Each entry carries its `reason` and a `deferredTo` naming the condition that
removes it, so the exemption is a recorded decision with an end date rather than
a permanent hole.

## Test

```sh
cd scripts && bun test rewrite-migration/rewrite-migration.test.js
```