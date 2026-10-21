# Porting guide

A port starts from one document and three commands. The **rules** are in [`rules.md`](rules.md); the **cases** are
in [`conformance/gitignore-vectors.json`](../conformance/gitignore-vectors.json); the **measurement** is
[`bench/perf.mjs`](../bench/perf.mjs). This page orders the work: each invariant depends on the one before it, and
a port that implements the later ones without the earlier ones will behave plausibly and wrongly.

The three-valued answer is the part ports get wrong most often. **`no-opinion` is not "included"**: the chain
answers `include` only when a negation matched last, and answers `no-opinion` when no `.gitignore` has an opinion,
because the caller (a walker, an indexer, a tool) may have a default of its own.

## The invariants, in required order

Each invariant is stated as behaviour, not as an algorithm: a port that resolves the chain differently still passes
if the behaviour is right. The vector names are the smallest set that fails when the invariant is broken.

### 1. Parse one file at a time

One `.gitignore` file yields a list of patterns **in file order**. Per line: strip a trailing `\r` (CRLF), trim
trailing **unescaped** spaces, skip a blank line or a line whose first byte is `#`, record a leading `!` as a
negation, a trailing `/` as directory-only, a `/` at the start or in the middle as anchored, and keep the bytes
otherwise (escapes are interpreted by the matcher, not by the parser). A lone `!` and a line that reduces to nothing
are not patterns.

Vectors: `a comment line is not a pattern`, `an escaped hash is a literal name`, `an escaped bang is a literal name`,
`a trailing space is trimmed`, `an escaped trailing space is kept`, `a CRLF rule file parses like an LF one`.

### 2. Decide with the deepest file that has an opinion

A `.gitignore` in `d/` governs paths **below** `d`, and every path handed to that file is relative to `d`, with no
leading slash. A path outside a file's directory can never match it, so a directory's own file cannot exclude the
directory itself. Consult the deepest file first; if it has no opinion, fall through to the next shallower one.

Vectors: `deeper file wins over shallower`, `deeper file without an opinion leaves the shallower one`, `literal file
at any depth`, `leading slash anchors to the file's directory`.

### 3. Return three values, not a boolean

`exclude`, `include` (a negation matched last), or `no-opinion` (nothing matched). Do not turn `no-opinion` into
`include` at this layer; a caller that wants a boolean can do that itself, and one that does not is the reason the
third value exists. The conformance runner is exactly such a caller:
[`src/conformance_test.zig`](../src/conformance_test.zig) compares the third value as its own answer.

Vectors: `deeper file without an opinion leaves the shallower one`, `literal does not match a different name`,
`suffix does not match a longer extension`, `trailing slash does not match a file`.

### 4. An excluded directory is not entered

If a pattern excludes a **directory**, nothing below it can be re-included — not even by a `!pattern` in a file
inside that directory, because git never enters the directory and so never reads that file. A walker gets this for
free by pruning; a decide-one-path API must reproduce it by checking the ancestors (each against the chain of *its
own* parent, because a file inside a directory does not govern the directory itself).

Vectors: `a negation cannot re-include inside an excluded directory`, `a directory rule applies to the directory
itself only`.

### 5. The last matching pattern wins

Within one file, the answer comes from the **last** pattern that matches; a negation is not special. `*.log` then
`!keep.log` re-includes `keep.log`; the reverse order excludes it again.

Vectors: `last matching pattern wins`.

### 6. `**` has exactly three forms

- A leading `**/` matches zero or more directories (`**/*.class` matches `Foo.class` and `a/b/Foo.class`).
- A trailing `/**` matches everything **inside** the directory, not the directory itself (`build/**` does not match
  `build`).
- A middle `a/**/b` matches zero or more directories between the segments (`a/b` and `a/x/y/b`).

Consecutive asterisks that are not a segment of their own are just asterisks (`a**b` matches `axxb`, never `a/b`).
A single `*` never crosses `/`.

Vectors: `doublestar prefix applies at any depth`, `doublestar prefix also matches at the top`, `trailing doublestar
needs a segment`, `trailing doublestar matches inside`, `doublestar in the middle spans segments`, `doublestar in
the middle matches zero segments`, `a star does not cross a slash`.

### 7. Escapes, whitespace and character classes

A backslash makes the next byte literal (`\#`, `\!`, `\*`), a trailing escaped space survives trimming, and a
`[...]` class follows git's rules: `]` as the first member is literal, `[!…]`/`[^…]` negates, and an **unterminated**
class matches nothing (git aborts the pattern; it is not a literal `[`). An unterminated class is the one rule where
the first version of this project and the earliest ports were wrong, and it was corrected against
`git check-ignore` — so a port that finds itself disagreeing here should check git before trusting either side.

Vectors: `a character class matches one of its members`, `a character class with a closing bracket first`, `an
unterminated class matches nothing`, `a question mark matches one byte`, `a question mark does not match two bytes`,
`an escaped hash is a literal name`, `an escaped bang is a literal name`, `an escaped trailing space is kept`.

### 8. Case sensitivity is a decision, and this project chose sensitive

Match case-sensitively on every platform, including Windows and macOS. A trio is written on one machine and read on
another, so the ports must agree with **each other** before they agree with a filesystem whose `core.ignorecase`
folds case. There is no vector for this one: a case would have to state its platform, and the decision is that the
answer is the same on all of them. If your port must fold case for a platform, that is a different matcher with its
own vectors, not an exception inside this one.

## Measure yourself

The point of the bench is that a port's "fast" is a number on the same bytes as every other port's. Do this in
order:

1. **Build the CLI contract.** `<port> --rules <file.gitignore> --paths <file>` prints one line per input path, in
   input order: `include`, `exclude` or `no-opinion`. Nothing else on stdout. Support `--dirs <file>` (one `0`/`1`
   per path) so directory-only rules can be measured without a second format, and exit non-zero on a usage error.
   `readFileSync`-style reading is fine; the contract is the bytes in and the lines out, not how you load them. The
   reference is [`src/decide_main.zig`](../src/decide_main.zig), and the acceptance row is the one
   `bench/perf.mjs` prints: the **verdict count must equal the path count**.
2. **Build release.** A Debug build measures the language runtime, not the design. The reference numbers are
   `-Doptimize=ReleaseFast`; say which build a row used.
3. **Run the workloads.** From `gitignore/`:

   ```text
   bun bench/perf.mjs --emit
   bun bench/perf.mjs --port "<your command> --rules {rules} --paths {paths}" --label <port> [--repeat 5]
   ```

   `--port` is split on spaces and `{rules}`/`{paths}` are substituted per workload; `--label` names the row. The
   best of `--repeat` runs is reported, with ns per decision.
4. **Put the row in your README** (this project's table is in [`../README.md`](../README.md)) and **name the
   workload your port is worst at**, plus why. A row that is uniformly good is less useful than a row that says
   "worst at `deep`, because the path depth is paid per ancestor" — that sentence is what the next port reads.
5. **Do not quote the historical figures as current ones.** The 4,700 ms recursive-matcher pass, the 1,466 ms
   superlinear `decide` and the 2.97× anchored-`**` pass came from a real 3,900-entry tree and earlier designs.
   The synthetic workloads reproduce their **shape** at a size a gate can afford; quote your own numbers with the
   tree, the build and the date.

## Report back

- **A port that is faster is a finding to be adopted.** Send the shape — the data structure, the pruning rule, the
  representation — not a dependency on your project. A speed-up that only works in one language is still worth a
  README row, and if it is portable it becomes a vector plus a change here.
- **A port that fails a vector is either a bug or a vector to fix.** Do not decide by voting: reproduce the case in
  a scratch git repository and ask git itself (`git init`, write the `.gitignore`, `git check-ignore -v --no-index
  -- <path>`). The answer is the authority; the commit records which side changed and why. The unterminated-class
  correction in [`rules.md`](rules.md#character-classes) is the worked example.
- **A corpus from a checkout** is more cases for free: run
  [`conformance/from-git-check-ignore.mjs`](../conformance/from-git-check-ignore.mjs) on a real repository and run
  the resulting vectors with `zig build test -Dvectors=<file>`. Cases carry names and rules, never the contents of
  ignored files, and a case taken from git's own test suite must carry git's licence and be attributed.
