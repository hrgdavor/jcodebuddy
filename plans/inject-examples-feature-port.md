# Port report: finishing the move from `update-doc-includes.js` to the published injection CLI

> **Status (2026-09-23): the port happened.** The tool now lives as its own
> project, packaged as `@hrg/inject-examples` (`cli.mjs` + `index.mjs`), and
> implements the recommendations below: `--lenient` with a non-zero exit on
> skips (F3), fence-aware marker discovery (F5), strict unique region names,
> EOL/normalisation handling, gitignore-aware skipping, `--dry-run`,
> `--root`, exit-code contract. `~suffix` short paths (F1) and mid-line
> directives stay retired; of glob CLI patterns (F2) only the directory half
> landed, in `@hrg/inject-examples` 1.0.1 — the CLI now takes any number of
> files and directories, expanding a directory to every `*.md` below it,
> recursively, and exits with the worst code of the run (patterns such as
> `docs/**/*.md` are still the shell's job). This
> repository consumes the **published** package: the root `package.json`
> declares `@hrg/inject-examples` as an npm dependency and its `inject:examples`
> / `check:examples` scripts run the package CLI through `npx` in one call over
> the whole docs tree (the repository has no wrapper of its own; the vendored
> `test-fixtures.js` is deleted).
> The rest of this file is the original handoff, kept for reference.

**Audience:** an agent working in a project that already contains the *new* injection
mechanism (`scripts/inject-examples.mjs` + root `test-fixtures.js`) but not the old one.
**Mission:** port the old mechanism's remaining features into the new script — or consciously
retire them — so no document or workflow depends on anything the new mechanism cannot do.
**Rule:** the old script (`scripts/update-doc-includes.js`, Bun-based) is *not* to be copied;
everything below specifies its behaviour precisely enough to reimplement against the new
architecture. Each feature ends with a recommendation and acceptance criteria.

---

## 1. State of the new mechanism (as shipped, and as it must stay)

Recap of the contract the port must not break:

- **Marker** = a line whose *trimmed form* is exactly `[label](target)` where `label` equals
  the target's path (a leading `./` on either side is ignored) and the target carries either
  no fragment or a `#region:<name>` fragment. Anything else — different label, plain anchor,
  inline link inside a sentence — is prose and must stay untouched. Because label = path, every
  marker renders as a working link to its source; that property is a feature, preserve it.
- **Path resolution** (in `test-fixtures.js`, `resolveFile`): relative to the markdown file's
  directory first, then to the repository root. Must be an existing regular file.
- **Content semantics** (`resolveMarker`): whole file = text with `\r\n` normalised to `\n` and
  exactly one trailing newline dropped; region = the lines *strictly between* the first
  start-marker match and the next end marker. The directive lines themselves are never content.
- **Region marker regexes in sources** — start:
  `^\s*(?://|/\*+|<!--|#)\s*#?region\s+<NAME>\b` with `<NAME>` regex-escaped; end:
  `^\s*(?:\/\/|\/\*+|<!--|#)\s*#?endregion\b`. (The old script accepted the same forms minus
  the bare `#` prefix; the new one is a superset.)
- **Fence handling** (`injectInto`): after the marker, blank lines are skipped; the opening
  fence is the first line `startsWith('```')`; the closing fence is the next such line; content
  between them is replaced. Any other non-blank line between marker and fence is an error.
- **CLI**: positional targets are files or directories (directories recurse over `*.md`,
  skipping `node_modules` and dot-directories); no target = the root `README.md`; `--check`
  compares without writing and exits 1 if anything is stale; duplicate marker lines within one
  file are an error (the injector would only ever serve the first); rewrites preserve the
  document's own EOL (detect `\r\n`, split on `/\r?\n/`, join with the detected EOL).
- **Failure mode**: every problem throws, exit code 1, the run aborts before writing the
  offending file. This strictness is deliberate — see §3.

The split of responsibilities: `test-fixtures.js` owns marker discovery (`findMarkers`),
content resolution (`resolveMarker`) and the repo root; `inject-examples.mjs` owns CLI, target
expansion, fence splicing and reporting. Keep that split when porting.

## 2. What was just done in the source repository (the migration log)

This is the work the port completes; recorded so the receiving agent knows what "migrated"
looked like in practice and what it surfaced:

1. **Implemented `test-fixtures.js`** (it was referenced by the new script but missing) with
   `findMarkers` / `resolveMarker` / `ROOT` per §1.
2. **Generalised `inject-examples.mjs`** from a single hard-coded root `README.md` to
   positional file/directory targets, per-file duplicate-marker rejection, aggregate `--check`,
   fixed usage text. Default (no args → root README) preserved.
3. **Converted 82 include directives** in `merge-java/docs/resolvers/**/*.md` (11 documents)
   from the old form — an HTML-comment line `<!-- INCLUDE:repo/root/path#region -->` above the
   fence — to marker lines with *document-relative* paths, e.g. a marker whose label and target
   are both `../../../src/test/java/.../TypeChangeConflictResolverTest.java`, target carrying
   `#region:adopts-wider-type`. Conversion was a throwaway script: regex-match the directive
   line, split the ref at the **last** `#`, resolve the source against the repo root, rewrite as
   document-relative, assert no duplicate marker lines per file. Source-side region markers
   (`//#region name` / `//#endregion` comments in Java tests and fixtures) needed **no change** —
   both mechanisms read the same region syntax.
4. **Converted the last old-format document** (`doc-hipster-entity/architecture/
   materialization-levels.md`), which used the old `~suffix` short-path feature (see F1) — its
   two directives became explicit relative-path markers into `hipster-entity-example`.
5. **Verified byte-equality**: re-injection reported `0 updated` everywhere (84 markers, all
   "ok") — proof the new content semantics reproduce the old ones exactly — and `--check`
   passes on all targets.
6. **Ported EOL preservation** into the new script (the old one rewrote with the document's own
   EOL; the new one initially joined with `\n` — fixed, see §1).
7. **Key discovery — the old script's short paths were silently broken**: `~iface/Person.java`
   matched both the real file and a copy under `.kilo/worktrees/...` (the root `.gitignore`
   ignores only `.kilo/plans`, and the old gitignore matcher was a simple segment/prefix test).
   The old script responded by WARN-ing on stderr, keeping the block unchanged, printing
   **"All blocks are up-to-date"** and exiting **0**. Two documentation blocks were frozen and
   nothing failed. After migration to explicit paths they resolve and verify again.
8. **Kept the old script, deprecated**: it has zero consumers now; it survives only because an
   architecture decision record (DEC-027) cites it as historical evidence. It carries a header
   comment saying: superseded, do not add new INCLUDE directives.
9. **Enforcement**: in the source repo a JUnit test (`merge-java`'s `ResolverDocsTest`) mirrors
   the script's semantics in the Java build (marker parse rules, resolution order, region
   uniqueness, byte-equality of every rendered block, "every fenced block in a resolver README
   is an injection block", dead-link check). If the target project has any mirror test like
   this, **every semantic change below must land in the script and the mirror test together**.

## 3. Features to port

For each: the old behaviour (exact), the design question for the new architecture, a
recommendation, and acceptance criteria. Recommendations are opinions formed from the migration
above — the failure modes are facts.

### F1 — `~suffix` short-path lookup

**Old behaviour.** A reference starting with `~` (e.g. `~record/Person.java`) triggered a
repo-wide suffix search: build (and cache) an index of every file by walking the repo from the
root, skipping `.git` always and gitignored paths per the *root* `.gitignore` only — patterns
trimmed, comments dropped, trailing `/` stripped; a path was ignored when any of its segments
equalled a pattern, or the whole relative path equalled a pattern or started with `pattern/`.
An index entry matched when its repo-relative path equalled the suffix or ended with
`/` + suffix (backslashes normalised to `/`). Exactly one match → used. More than one → WARN
listing all matches, then treated as not found. Zero → not found. "Not found" fed the old
tolerant mode (F3): block kept, exit 0.

**Why it broke.** The segment/prefix gitignore approximation missed `.kilo/worktrees`
(only `.kilo/plans` is ignored), so worktree copies of the same source made every short path
ambiguous → permanently frozen blocks behind a green run (§2.7).

**Design question for the new format.** A marker's label must equal its path, and markers are
meant to render as working links. A `~suffix` marker (`[~record/Person.java](...)`) is not a
link to anything — the format collides with the feature.

**Recommendation: retire it.** Prefer explicit document-relative or root-relative paths, as the
migration did; they are unambiguous, they render as links, and the resolution order already
covers both. If the target project genuinely needs suffix search, port it as a *separate
lookup tool* (e.g. a `--resolve ~suffix` helper that prints the explicit path to paste into a
marker), never inside marker syntax — and then: require a unique match, **error** (exit 1) on
ambiguity listing every candidate, build the candidate list from `git ls-files --cached
--others --exclude-standard` (correct ignore semantics) with a plain recursive walk as
fallback that skips `.git`, `node_modules`, dot-directories and anything a real gitignore
matcher excludes.

**Acceptance.** No marker syntax change; documents that previously needed short paths carry
explicit paths; if the lookup helper is ported: unique suffix → prints one path; ambiguous →
non-zero exit naming all candidates; no silent fallback.

### F2 — Glob CLI patterns

**Old behaviour.** Any argument containing `*`, `?` or `{` was expanded with Bun's
`Glob.scanSync({ cwd: repoRoot, absolute: true })`; other arguments were plain file paths.
Zero matches overall → print "No files matched the given pattern(s).", exit 0. The everyday
invocation was `bun scripts/update-doc-includes.js "docs/**/*.md"` from the repo root.

**Design question.** The new script must stay dependency-free plain Node — Bun's `Glob` is not
available. Node ≥ 22 offers `fs.globSync` (status: experimental; check the target project's
Node line and whether the experimental warning is acceptable). Directory targets already cover
the dominant use case ("everything under this tree").

**Recommendation.** Support the documented patterns with the least machinery: keep directory
recursion as the workhorse and add pattern arguments only if the project's docs use them.
Cheapest correct implementation without `fs.globSync`: walk from the pattern's longest
glob-free prefix directory and filter with a translated regex — escape regex metacharacters,
then `**/` → `(?:.*/)?`, `*` → `[^/]*`, `?` → `[^/]`, `{a,b}` → `(?:a|b)` — matching against
the repo-relative path with `/` separators. Decide and document the no-match policy: the old
exit-0 is another silent-success shape; recommend exit 1 with "no files matched: <pattern>".

**Acceptance.** `"docs/**/*.md"` from the repo root selects exactly the same file set as the
equivalent directory target(s); a pattern matching nothing fails loudly; file and directory
arguments behave unchanged; no new dependencies.

### F3 — Tolerant failure mode (`WARN` + keep + continue)

**Old behaviour.** Missing markdown file → error + exit 1 (that one was strict). Everything
else — source not found, region not found, no endregion, no fence after the directive,
unterminated fence — → `WARN` on stderr, existing block kept unchanged, `skipped` counter up,
processing continued; final summary counted only *updated* blocks and the process exited 0 even
when skips occurred ("All blocks are up-to-date." was printed with four WARNs pending — the
masking bug behind §2.7).

**Design question.** Strictness is what surfaced the frozen blocks, so the default must stay
strict. But a batch run over a big tree aborting on the first dead include is annoying when the
goal is "refresh everything that *can* be refreshed".

**Recommendation.** Port as an opt-in `--lenient` flag: resolution/fence failures WARN (naming
document, line, marker and cause), the block is kept, the run continues; the summary reports
`skipped` next to `updated`; **exit code is 1 whenever skipped > 0**, in both normal and
`--check` mode — never reproduce "green run with warnings only on stderr". Keep duplicate
markers and malformed fences around markers strict even under `--lenient` (they indicate a
broken document, not a broken include).

**Acceptance.** One dead + three good markers in a file: default → aborts, writes nothing,
exit 1; `--lenient` → three blocks refreshed, dead one reported with its line number, exit 1;
`--lenient --check` → no writes, same reporting and exit code. A fully healthy run exits 0 in
all modes.

### F4 — Fence flexibility (tildes, indentation, tick-run matching)

**Old behaviour.** Opening fence: first line after the directive (blanks skipped) matching
`^\s*(`{3,}|~{3,})` — leading whitespace allowed, three or more backticks *or* tildes; the
matched token (e.g. `` ``` `` or `~~~~`) was captured. Closing fence: the first later line
whose **trimmed form equals that exact token** — so a bare fence closes, the token length must
match, and an info string on the closing line (`` ```java `` as a closer) did *not* close the
block (it produced "unterminated code fence" WARN). Fence content = lines strictly between.

**New behaviour today.** Both fences are `line.startsWith('```')` at column 0: no tildes, no
indentation, no tick-length matching; any line starting with three backticks closes.

**Recommendation.** Port the old rules into `injectInto` (and keep them consistent with
whatever `--check` compares): opening `^\s*(`{3,}|~{3,})`, capture the token; closing = first
line with `trim() === token`. This also hardens content: a block fenced with four backticks can
then contain three-backtick lines (nested fence examples) safely — the new column-0 rule cannot.

**Acceptance.** A `~~~`-fenced block injects correctly; an indented fence injects correctly;
a ```` ```` ````-fenced block containing a `` ``` `` line keeps that line as content; a block
whose closing fence carries an info string is reported as unterminated (error, or WARN+keep
under `--lenient`), not silently mis-spliced; existing `` ``` `` blocks behave identically.

### F5 — Directive recognised anywhere in a line (fence-blindness)

**Old behaviour.** The directive regex `/<!--\s*INCLUDE:(.+?)\s*-->/` was applied with
`line.match` — it fired anywhere in a line, including inside fenced code blocks and mid-sentence.
Documents therefore had to entity-escape any *documentation about* the syntax (the source
repo's index README showed it as `&lt;!--&nbsp;INCLUDE:...--&gt;`), because a literal example
inside a fence would be processed as a real directive.

**Recommendation: retire the mid-line matching** — the whole-line marker rule is stricter,
visible, and doubles as a link; mid-line matching has no upside in the new format. **But port
the underlying problem as a fix:** today `findMarkers` is *also* fence-blind, so a document
that shows an example marker line inside a fenced block would have it treated as a real marker
(and, being strict, fail the whole run on a path that does not exist). Make `findMarkers`
fence-aware: scan with a fence state machine (use the F4 open/close rules; outside a block, a
fence-opening line enters it, the matching close exits), and only recognise markers on lines
that are *outside* fenced blocks. Then documentation may freely show marker examples in fences.

**Acceptance.** A fenced block containing a self-labelled-link line pointing at a nonexistent
path is ignored (run succeeds, block untouched); the same line outside any fence is a marker
and fails resolution as before; all existing markers (which live outside fences) are unaffected;
if a mirror test exists, its marker scan gains the same fence-awareness.

### F6 — Third path-resolution fallback (strip leading `../`)

**Old behaviour.** After document-relative and root-relative both missed, leading `../`
segments were stripped (`^(?:\.\.[\\/])+`) and the remainder retried from the repo root —
covering docs that referenced sibling modules with the wrong number of `../`.

**Recommendation.** Port it as a third candidate in `resolveFile` (document-relative →
root-relative → stripped-from-root), but make failures *name all tried locations* in the error
message, so the crutch never hides a simply-wrong path. Optionally warn when only the stripped
candidate hits ("marker path depth is wrong; it resolved via the fallback").

**Acceptance.** From a doc two levels deep, a marker with three `../` segments resolves via
the fallback; a truly missing file errors listing all three attempted paths.

## 4. Retired on purpose — do not port

- **Bun dependency** (`import { Glob } from 'bun'`): the new script runs on plain Node.
- **Mid-line directives** (F5): whole-line markers only.
- **WARN-and-exit-0** (F3): every failure mode must be able to fail the run; `--lenient`
  continues but still exits non-zero when something was skipped.
- **"All blocks are up-to-date" printed while blocks were skipped**: the summary must never
  claim success when any block was left untouched for a *failure* reason.
- **Region-name ambiguity tolerated silently**: the old script took the first start match for a
  duplicated region name. The library may keep first-match semantics, but the source repo
  enforced *uniqueness of every referenced region name per file* in its mirror test (an
  ambiguous name means a doc can silently shadow a second region — the same class of bug as
  `adopts-wider-type` prefix-matching `adopts-wider-type-from-branch2` through the `\b` in the
  start regex). Reproduce that gate in whatever enforcement the target project has, or make
  `resolveMarker` throw when a referenced name matches more than one start.

## 5. Verification checklist (run all, in order)

1. **Idempotence**: run the injector twice over every target; second run reports all "ok",
   0 updated, exit 0.
2. **Round-trip fidelity**: for a sample of migrated blocks, content equals
   `resolveMarker(marker, docDir)` byte-for-byte (this is what `--check` asserts — run it).
3. **Per-feature acceptance criteria** from §3 (F1–F6 as ported).
4. **CRLF**: a CRLF markdown file with one marker → after injection the file is still uniformly
   CRLF, including the injected lines.
5. **Strict/lenient matrix**: dead marker × {default, `--lenient`, `--check`, both} → exit codes
   and write behaviour per F3.
6. **Fence matrix**: `` ``` ``, `~~~`, indented, four-tick-with-nested-three-tick, unterminated
   → per F4.
7. **Fence-awareness**: example marker line inside a fence is inert → per F5.
8. **Mirror tests**: if the project has a build-side mirror of the script semantics (like the
   source repo's `ResolverDocsTest`), it is updated in the same change and the full build is
   green; the mirror and the script must not be allowed to disagree — that disagreement is
   exactly the drift both mechanisms exist to prevent.

## 6. Appendix — the old script's exact rules (reference for reimplementation)

Recorded because the old file is not present in the target project:

- Directive: `/<!--\s*INCLUDE:(.+?)\s*-->/` via `line.match` (first match per line, anywhere in
  the line); the directive line itself was always kept in the output.
- Ref parsing: split at the **last** `#`; empty suffix → no region.
- Resolution order: document-dir-relative → repo-root-relative → strip leading `../` →
  repo-root-relative; `~` prefix short-circuited to the suffix index (F1); `existsSync` only
  (directories could "resolve" — the new script's regular-file check is an improvement, keep it).
- Whole-file snippet: `text.replace(/\r?\n$/, '')` — one trailing newline removed, interior
  CRLF *not* normalised (the new script normalises to LF; keep the new behaviour, it is what
  the migrated blocks were verified against).
- Region snippet: `text.split(/\r?\n/)`, start regex
  `^\s*(?:\/\/|/\*+|<!--)\s*#?region\s+<escaped NAME>\b` (first match), end regex
  `^\s*(?:\/\/|\/\*+|<!--)\s*#?endregion\b` (first match after start), content = lines strictly
  between, joined with `\n`.
- Fences: see F4. Blank lines between directive and opening fence were copied through.
- Output: EOL detected from the document (`includes('\r\n')`), everything rejoined with it;
  file written only when changed.
- Exit codes: 0 in all non-fatal cases (including skips — the bug); 1 only for missing
  markdown file or no arguments (usage text).
- Usage/help: no arguments → usage text + exit 1. The new script's no-argument behaviour
  (default to root `README.md`) is intentional and stays.
