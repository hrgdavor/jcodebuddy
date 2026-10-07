# Third-party notices — `merge-java`

This module contains code **derived from** the IntelliJ Platform, which JetBrains publishes under the
Apache License 2.0. This file discharges the attribution obligations that licence imposes, and it is the
one place where the provenance of every derived file is recorded.

The port itself — what is taken, what is refused, and why — is documented in
[`docs/JETBRAINS_PORT.md`](docs/JETBRAINS_PORT.md). This file is the legal record; that one is the
engineering record.

---

## 1. Upstream work

|                       |                                                                 |
| --------------------- | --------------------------------------------------------------- |
| Project               | IntelliJ Platform (IntelliJ IDEA Community Edition)             |
| Repository            | <https://github.com/JetBrains/intellij-community>               |
| Copyright             | Copyright (C) JetBrains s.r.o.                                  |
| Licence               | Apache License 2.0                                              |
| **Pinned commit**     | `9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5`                      |
| Commit date           | 2026-10-07                                                      |
| Upstream modules read | `platform/util/diff`, `platform/diff-impl`, `platform/diff-api` |

**The pin is the citation.** Every derived file names the upstream path it came from and this commit
hash, so a reader can retrieve the exact revision the port was written against. Upstream's `master`
moves, so a reference without a hash would not be reproducible — and `JetBrainsAttributionTest` fails if
a derived file's recorded commit differs from the one above.

Upstream licence note: the repository root carries JetBrains' own `LICENSE.txt` (the *JetBrains
Open-Source Build Terms*), which states that the open-source builds **"consist of open source software
subject to the Apache 2.0 License"**. The Apache terms, not that covering document, are the licence of
the source files this module derives from.

## 2. The Apache License 2.0

The full text is bundled beside this file as
[`LICENSE-APACHE-2.0.txt`](LICENSE-APACHE-2.0.txt), which is the copy that satisfies
[Apache 2.0 § 4(a)](https://www.apache.org/licenses/LICENSE-2.0) ("You must give any other recipients of
the Work or Derivative Works a copy of this License"). The canonical source is
<https://www.apache.org/licenses/LICENSE-2.0.txt>.

## 3. Upstream's `NOTICE`

Apache 2.0 § 4(d) requires a derivative work to carry a readable copy of the attribution notices in the
upstream `NOTICE` file, excluding notices that do not pertain to the part used. Upstream's `NOTICE.txt`
is short, and the whole of it pertains, so it is reproduced verbatim:

```text
This software includes code from IntelliJ IDEA
Copyright (C) JetBrains s.r.o.
https://www.jetbrains.com/idea/
```

## 4. Derived files

Each derived file is **translated to Java, reduced to the algorithm, and stripped of the IntelliJ
Platform dependency** — no `com.intellij` type is referenced, and no dependency on the platform is
declared. Under Apache 2.0 § 4(b) each such file carries a prominent notice that it was changed; that
notice is the `@derived` header line the file opens with.

**The list below is complete as of step 4.7**, which creates the package skeleton and the provenance
record. This step establishes the header convention, the enforcement and the attribution records *before*
the first algorithm is translated, so that no file can be translated without them — the alternative,
adding attribution once there is code to attribute, is how a first file comes to be un-attributed.

| Derived file                                             | Upstream file                                                                | Tier    | Changed by |
| -------------------------------------------------------- | ---------------------------------------------------------------------------- | ------- | ---------- |
| `com/codebuddy/merge/jetbrains/package-info.java`        | `platform/util/diff/src/com/intellij/diff/comparison/ComparisonMergeUtil.kt` | —       | Restated as the package's doc comment: the three-tier split, its isolation rule, and what the port deliberately excludes |
| `com/codebuddy/merge/jetbrains/text/package-info.java`   | `platform/util/diff/src/com/intellij/diff/comparison/ComparisonMergeUtil.kt` | `text`  | Restated as the tier's doc comment: the two-pass comparison, and why `TRIM_WHITESPACES` and `IGNORE_WHITESPACES` are different operations |
| `com/codebuddy/merge/jetbrains/merge/package-info.java`  | `platform/util/diff/src/com/intellij/diff/comparison/MergeResolveUtil.kt`    | `merge` | Restated as the tier's doc comment: which upstream steps are **SAFE** and therefore live here, which are **SUGGESTION** and therefore do not |
| `com/codebuddy/merge/jetbrains/JetBrainsProvenance.java` | *(none — `@derived none`)*                                                   | —       | This module's own record of the pinned revision, not a translation. It carries the `@derived none` marker, which is how the attribution rule tells the two cases apart |

Their upstream paths are named in the header of each file as well as here. That duplication is
deliberate: the header travels with the file, and this table is what a reader checks the headers against.
`JetBrainsAttributionTest` fails if the two disagree, and if any derived file is missing from this table.

## 5. What is *not* derived

Most of this module is its own work, and the boundary matters for a reader deciding what the Apache terms
cover:

- `ConflictType`, `ConflictResolver`, `MergeFileTool`, `BranchConflictStore`, `AnalysisLevel`,
  `ResolutionVerifier`, `MergeReportWriter`, the `review/` page and the suggestion channel are
  JCodeBuddy's, not derived.
- The **test vectors** transcribed in [`docs/JETBRAINS_PORT.md`](docs/JETBRAINS_PORT.md) § 11 are facts
  about upstream's behaviour, re-expressed as this module's own fixtures. Facts are not copyrightable;
  the fixtures are written for this repository's harness rather than copied. Where a vector is copied
  verbatim as a string, the fixture records its upstream origin in the file.
