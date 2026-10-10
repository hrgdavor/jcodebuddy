# Resolver reference

One folder per registered conflict resolver, each with a README that explains in
detail what the resolver decides and shows **real examples** — every code block
in these documents is included verbatim from the module's test fixtures and
tests. No example is written by hand, so none can drift: the build re-checks
every rendered block against its source (see
[How the examples are sourced](#how-the-examples-are-sourced)).

The resolvers are the ones registered in
[`ConflictResolvers.defaultResolvers()`](../../src/main/java/com/codebuddy/merge/ConflictResolvers.java),
in registration order:

| Resolver                             | Conflict type         | Declared handling | Max evidence  | Sticky | Documentation                                                                            |
| ------------------------------------ | --------------------- | ----------------- | ------------- | ------ | ---------------------------------------------------------------------------------------- |
| `ImportConflictResolver`             | `IMPORT_ADD`          | AUTO              | TEXT_FILE     | no     | [import-conflict-resolver](import-conflict-resolver/README.md)                           |
| `CommentAddConflictResolver`         | `COMMENT_ADD`         | AUTO              | TEXT_LOCAL    | no     | [comment-add-conflict-resolver](comment-add-conflict-resolver/README.md)                 |
| `ConstantAddConflictResolver`        | `CONSTANT_ADD`        | AUTO              | TEXT_FILE     | no     | [constant-add-conflict-resolver](constant-add-conflict-resolver/README.md)               |
| `OverloadAddConflictResolver`        | `OVERLOAD_ADD`        | AUTO              | PROJECT_TYPES | no     | [overload-add-conflict-resolver](overload-add-conflict-resolver/README.md)               |
| `MethodBodyChangeConflictResolver`   | `METHOD_BODY_CHANGE`  | REVIEW            | TEXT_INTRALINE | no     | [method-body-change-conflict-resolver](method-body-change-conflict-resolver/README.md)   |
| `TypeChangeConflictResolver`         | `TYPE_CHANGE`         | REVIEW            | PROJECT_TYPES | no     | [type-change-conflict-resolver](type-change-conflict-resolver/README.md)                 |
| `RenameConflictResolver`             | `VARIABLE_RENAME`     | STICKY            | TEXT_LOCAL    | yes    | [rename-conflict-resolver](rename-conflict-resolver/README.md)                           |
| `PackageChangeConflictResolver`      | `PACKAGE_CHANGE`      | STICKY            | TEXT_LOCAL    | yes    | [package-change-conflict-resolver](package-change-conflict-resolver/README.md)           |
| `StructuralChangeConflictResolver`   | `STRUCTURAL_CHANGE`   | MANUAL            | STRUCTURE     | no     | [structural-change-conflict-resolver](structural-change-conflict-resolver/README.md)     |
| `ApiIncompatibilityConflictResolver` | `API_INCOMPATIBILITY` | MANUAL            | TEXT_LOCAL    | no     | [api-incompatibility-conflict-resolver](api-incompatibility-conflict-resolver/README.md) |
| `MemberAddConflictResolver`          | `MEMBER_ADD`          | AUTO              | PROJECT_TYPES | no     | [member-add-conflict-resolver](member-add-conflict-resolver/README.md)                   |

The *max evidence* column is
[`ConflictResolver.maxAnalysisLevel()`](../../src/main/java/com/codebuddy/merge/ConflictResolver.java): the
strongest [`AnalysisLevel`](../../src/main/java/com/codebuddy/merge/AnalysisLevel.java) a resolution from that
resolver can rest on — `TEXT_LOCAL` (the block's own lines), `TEXT_FILE`, `STRUCTURE`, `PLATFORM_TYPES`, up to
`PROJECT_TYPES` (the project's own types, resolved against a classpath the caller supplied). It is worth reading
as a claim about the resolver's basis rather than as a score, because it decides what happens when several
conflicts claim one block and disagree: an `AUTO` answer that strictly outranks the others applies, but only
where it already accounts for what they were protecting ([DEC-045](../../../doc-hipster-entity/architecture/decisions/DEC-045.md)).
`ApiIncompatibilityConflictResolver` sits at `TEXT_LOCAL` because it compares one line of text, which is precisely
why a structural answer may outrank its objection — and why the number must not be raised without teaching the
resolver to recognise declarations.

The *declared handling* is the classification each
[`ConflictType`](../../src/main/java/com/codebuddy/merge/ConflictType.java)
carries; it is the type's default expectation, not a promise about every
outcome. Individual resolvers legitimately deviate when the evidence says so —
`TypeChangeConflictResolver` answers AUTO when one type provably widens the
other, `ImportConflictResolver` answers REVIEW under the `REMOVAL_WINS` clash
policy, and `OverloadAddConflictResolver` refuses to answer at all without a
type context. Each README states exactly when that happens, with the test that
proves it.

Two things hold for every resolver, enforced by
[`AbstractResolverTest`](../../src/test/java/com/codebuddy/merge/AbstractResolverTest.java):
`resolve()` never returns null — a resolver that declines routes to a MANUAL
fallback that still carries fix paths — and every resolution offers at least
one actionable [`FixPath`](../../src/main/java/com/codebuddy/merge/FixPath.java)
for the reviewer.

The table above is nevertheless never complete. There will always be conflict
shapes no automatic resolver solves: new ones arrive with every base-branch
update, and the [conflict-file tool](../CONFLICT_FILE_TOOL.md) turns only
recurring shapes into new resolvers, one reviewed fixture at a time. Declining
safely is a permanent feature, not a gap to be closed — which is why the next
investment is an analysis and review display for what resolvers *did* decide,
growing into a UI that helps the user resolve the rest (optionally with an LLM
proposing, never applying, solutions). Planned as Phase 13 in the
[implementation plan](../../IMPLEMENTATION_PLAN.md).

---

## How the examples are sourced

Every fenced code block in a resolver README is materialized from a file under
`merge-java/src/test/` through the published
[`@hrg/inject-examples`](https://github.com/hrgdavor/inject-examples) package:
the root `package.json` declares it as a dependency, and two root npm scripts
run its CLI through `npx` — `npm run inject:examples` rewrites the blocks, and
`npm run check:examples` only compares them. Each script makes a single call,
naming the whole `docs/resolvers/` tree and the other marker documents of the
repository: the CLI takes any number of files and directories, expanding a
directory to every `*.md` below it, recursively (run `npm install` once at the
repository root first).

An example starts with an *injection marker*: a line that is nothing but a
markdown link labelled with its own target path, optionally with a
`#name` fragment on the target to inject part of a larger file.
Paths resolve relative to the document holding the marker — the root the package
defaults to — so every marker doubles as a working link to its source. The
fenced code block immediately below the marker holds the current content of that
file or region.
The next block is a live marker, exactly as it appears in the raw markdown of
every README here, materialized from the canonical `IMPORT_ADD` sample:

[../../src/test/java/com/codebuddy/merge/ConflictFixtures.java](../../src/test/java/com/codebuddy/merge/ConflictFixtures.java#import-add-sample)
```java
    static final String IMPORT_ADD_BASE = "import java.util.List;";
    static final String IMPORT_ADD_BRANCH1 = "import java.util.List;\nimport java.math.BigDecimal;";
    static final String IMPORT_ADD_BRANCH2 = "import java.util.List;\nimport java.time.Instant;";
```

A region is a named block inside the source file, delimited by markers the
file's own syntax treats as a comment — in Java test sources and `.java.txt`
fixtures:

```java
//#region import-add-sample
static final String IMPORT_ADD_BASE = "import java.util.List;";
//#endregion
```

Re-materializing every block after editing a fixture or a marked test is one
command from the repository root:

```
npm run inject:examples
```

`npm run check:examples` runs the same comparison without writing and exits
non-zero when a block is stale — the mode for CI. A new resolver page under
`docs/resolvers/` is picked up by both scripts with no further wiring, because
the directory is walked rather than listed.

### The rules this directory enforces

1. **Examples only from test fixtures.** In the per-resolver READMEs, every
   fenced code block must be an injection block — a marker followed by the
   materialized snippet. Hand-written example code is not accepted, because it
   is the copy that goes stale.
2. **Nothing may drift.**
   [`ResolverDocsTest`](../../src/test/java/com/codebuddy/merge/ResolverDocsTest.java)
   runs with the module's test suite and fails the build when a rendered block
   and its source disagree, when a marker points at a missing file or region,
   when a region name is ambiguous within its file, when an included path is
   not test material, when a folder does not correspond to a registered
   resolver (or a registered resolver has no folder), or when a relative link
   is dead. Editing a fixture or a marked test **requires** re-running the
   injection script; the test suite says so with the offending block in the
   failure message.
3. **A new example starts as a new test.** If a README needs an example the
   fixtures do not have, the example is first added to the test sources — a
   three-way fixture under
   [`src/test/resources/fixtures/`](../../src/test/resources/fixtures)
   following [`THREE_WAY_FIXTURES.md`](../THREE_WAY_FIXTURES.md), or a test
   method marked with a `//#region` — validated by an actual passing test, and
   only then included here.

Each resolver README follows the same shape: what the resolver decides, the
canonical sample every resolver test starts from (the per-type regions in
[`ConflictFixtures`](../../src/test/java/com/codebuddy/merge/ConflictFixtures.java)),
worked examples with the tests that validate them, and the cases where the
resolver declines.
