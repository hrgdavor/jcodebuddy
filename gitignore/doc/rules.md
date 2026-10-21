# The rules, as implemented

This is the rules table the conformance vectors cite. A rule is git's; the implementation is
[`src/gitignore.zig`](../src/gitignore.zig), the **three-valued** answer (`include`, `exclude`, `no-opinion`) is
[`src/ignore.zig`](../src/ignore.zig), and every row below names the vectors in
[`conformance/gitignore-vectors.json`](../conformance/gitignore-vectors.json) that fail if the rule is broken.
The runner that proves the table is [`src/conformance_test.zig`](../src/conformance_test.zig).

The **basis** is this implementation, not the authority: when a vector and the code disagree, git's own answer
decides. The one case where the two sides genuinely disagreed so far — an unterminated `[` — was checked against
`git check-ignore` before the vector was corrected, and the correction is recorded in the commit (see
[Character classes](#character-classes)).

## One path, three answers

A matcher answers about **one path against one file's rules**. The **chain** turns that into the answer a caller
needs, and there are three possible answers:

- **exclude** — the deepest `.gitignore` with an opinion excludes the path.
- **include** — the deepest `.gitignore` with an opinion re-includes it (`!pattern` matched last).
- **no-opinion** — no rule file has an opinion. This is **not** "included": a walker, indexer or caller may have
  a default of its own, and collapsing the third value loses that.

The vectors pin the chain at
[`src/ignore.zig`](../src/ignore.zig#L393) (`test "the deepest .gitignore wins, and a negation re-includes"`).

## Matching within a segment

| Pattern form          | Meaning                                                                                 | Vectors that pin it                                                            | Test                                                                                               |
| --------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| `name`                | A literal name. Without a `/`, it matches the **final segment** of a path at any depth. | `literal file at any depth`, `literal does not match a different name`, `a name with a slash cannot be matched by a bare name` | [`a name-only pattern matches a basename at any depth`](../src/gitignore.zig#L593) |
| `*`                   | Any run of bytes **within one segment**. It never crosses a `/`.                        | `a star does not cross a slash`                                                | [`a pattern with a single star never crosses a separator`](../src/gitignore.zig#L738)              |
| `?`                   | Exactly one byte within a segment.                                                      | `a question mark matches one byte`, `a question mark does not match two bytes` | [`a question mark matches exactly one byte`](../src/gitignore.zig#L759)                            |
| `[…]`, `[!…]`, `[^…]` | One byte from a class; `!` or `^` negates it.                                           | `a character class matches one of its members`, `a character class with a closing bracket first`, `an unterminated class matches nothing` | [`character classes, a closing bracket first, and an unterminated one`](../src/gitignore.zig#L687) |
| `*.ext`               | The **suffix** form: an unanchored pattern whose only wildcard is the leading `*` and whose rest is literal. Matches when the final segment ends with `.ext`. | `suffix matches the final segment`, `suffix does not match a longer extension` | [`a name-only pattern matches a basename at any depth`](../src/gitignore.zig#L593) |
| `a**b`                | Consecutive asterisks that are **not a segment of their own** are just asterisks, within one segment. | `a star does not cross a slash`                                  | [`the three ** forms follow git's rules`](../src/gitignore.zig#L655)                               |

## Anchoring

A pattern is **anchored** when it has a `/` at the start or in the middle; otherwise it matches the final segment
at any depth. The leading `/` itself is not part of the pattern.

| Pattern form | Meaning                                                                   | Vectors that pin it                                                                       | Test                                                                                         |
| ------------ | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `/name`      | Anchored to the directory holding the `.gitignore`, not to the scan root. | `leading slash anchors to the file's directory`, `leading slash matches at its own level` | [`a leading slash anchors a pattern to the file's own directory`](../src/gitignore.zig#L602) |
| `dir/name`   | A separator anywhere also anchors; the whole relative path must match.    | `pattern with a slash is anchored`, `anchored pattern does not match deeper`              | [`a leading slash anchors a pattern to the file's own directory`](../src/gitignore.zig#L602) |

## Directory-only

A trailing `/` means **directories only**. Like git, a file *inside* such a directory also answers "excluded",
which is what makes pruning a directory equivalent to ignoring the whole subtree.

| Pattern form                     | Meaning                                                                             | Vectors that pin it                                         | Test                                                                                      |
| -------------------------------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `name/`                          | Matches a directory called `name`, not a file of that name.                         | `trailing slash matches a directory`, `trailing slash does not match a file`, `a directory rule applies to the directory itself only` | [`a trailing slash matches directories only`](../src/gitignore.zig#L618) |
| `name/` and an excluded ancestor | Anything below an excluded directory is excluded, and the walker does not enter it. | `a negation cannot re-include inside an excluded directory` | [`a negated child inside an excluded directory stays ignored`](../src/gitignore.zig#L644) |

## The three `**` forms

`**` is special **only as a whole segment**. The three forms are git's:

| Pattern form | Meaning                                                                                          | Vectors that pin it                                                                         | Test                                                                 |
| ------------ | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `**/name`    | Any depth, **zero or more** directories. A leading `**/` is normalised to the unanchored `name`, which already means that. | `doublestar prefix applies at any depth`, `doublestar prefix also matches at the top` | [`the three ** forms follow git's rules`](../src/gitignore.zig#L655) |
| `name/**`    | Everything **inside** `name`, and not `name` itself: a trailing `**` needs at least one segment. | `trailing doublestar needs a segment`, `trailing doublestar matches inside`                 | [`the three ** forms follow git's rules`](../src/gitignore.zig#L655) |
| `a/**/b`     | Zero or more directories between two segments, so both `a/b` and `a/x/y/b` match.                | `doublestar in the middle spans segments`, `doublestar in the middle matches zero segments` | [`the three ** forms follow git's rules`](../src/gitignore.zig#L655) |

`a**b`, `***` and any other run that is not a segment of its own is a run of asterisks inside one segment.

## Negation and precedence

Within one file the **last matching pattern wins**, and a negation is not special: put `!keep.log` before `*.log`
and the exclusion wins. Across directories, the **deepest file with an opinion** wins, and a file with no matching
pattern is skipped rather than treated as "include".

| Rule                                 | Meaning                                                                         | Vectors that pin it                                         | Test                                                                                              |
| ------------------------------------ | ------------------------------------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| Last match wins                      | `*.log` then `!keep.log` re-includes; the reverse order excludes.               | `last matching pattern wins`                                | [`the last matching pattern wins, so a trailing negation re-includes`](../src/gitignore.zig#L628) |
| Deeper file wins                     | A `.gitignore` below another, with an opinion, overrides it.                    | `deeper file wins over shallower`                           | [`the deepest .gitignore wins, and a negation re-includes`](../src/ignore.zig#L393)               |
| No opinion falls through             | A deeper file that matches nothing leaves the shallower file's answer standing. | `deeper file without an opinion leaves the shallower one`   | [`the deepest .gitignore wins, and a negation re-includes`](../src/ignore.zig#L393)               |
| An excluded directory is not entered | A `!` pattern below an excluded directory never re-includes anything.           | `a negation cannot re-include inside an excluded directory` | [`a negated child inside an excluded directory stays ignored`](../src/gitignore.zig#L644)         |

## Escapes and whitespace

| Pattern form       | Meaning                                                                                | Vectors that pin it                                                      | Test                                                                                       |
| ------------------ | -------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| `\#name`, `\!name` | A backslash makes a leading `#` or `!` literal; the backslash is not part of the name. | `an escaped hash is a literal name`, `an escaped bang is a literal name` | [`a backslash makes a leading # or ! literal`](../src/gitignore.zig#L704)                  |
| `# comment`        | A line whose first byte is `#` is a comment, not a pattern (unless escaped).           | `a comment line is not a pattern`                                        | [`comments, blank lines, trailing spaces and CRLF are handled`](../src/gitignore.zig#L715) |
| `name` plus spaces | Trailing **spaces** are trimmed; only a trailing space escaped with `\` survives. Tabs are part of the pattern. | `a trailing space is trimmed`, `an escaped trailing space is kept` | [`comments, blank lines, trailing spaces and CRLF are handled`](../src/gitignore.zig#L715) |
| CRLF               | A `\r` before the `\n` is stripped, so a CRLF checkout parses like an LF one.          | `a CRLF rule file parses like an LF one`                                 | [`comments, blank lines, trailing spaces and CRLF are handled`](../src/gitignore.zig#L715) |

## Character classes

A class is `[…]`, `[!…]` or `[^…]`. Two details are easy to get wrong and are pinned by vectors:

- **`]` as the first member is literal**, not the terminator. `[]]x` is "the class holding `]`, then `x`", and it
  matches `]x`. git's `wildmatch` and POSIX agree.
- **An unterminated class matches nothing.** git's `wildmatch` reports `ABORT_ALL` for an unclosed bracket
  expression, so `[abc` does **not** match a file literally named `[abc`. This is a correction: the first version
  of this matcher, and the earliest ports, treated the `[` as a literal `[`. The vector `an unterminated class
  matches nothing` was verified against `git check-ignore` (git 2.54) before it changed, and
  [`matchClass`](../src/gitignore.zig#L499) now follows git.

`[!]` (a negated empty class) and `[]` match nothing, as git does. A trailing `-` in a class is not treated as a
range by this implementation (no vector pins that edge; git's own behaviour here is the open item).

## What is read, and what is not

- **Read:** the `.gitignore` file in each directory, from the scan root down to the path's directory. One file's
  patterns are scoped to that directory: a path outside it can never match, so a directory's own file cannot
  exclude the directory itself.
- **Not read:** `.git/info/exclude`, a global `core.excludesFile`, `.gitignore` files above the scan root, and the
  contents of an excluded directory (the walker does not enter it). The corpus generator
  [`conformance/from-git-check-ignore.mjs`](../conformance/from-git-check-ignore.mjs) skips any case whose verdict
  comes from one of those files, so a generated corpus stays inside this contract.
- **Not a `.gitignore` rule:** `.gitmodules`, `.gitattributes` and git's sparse-checkout files are outside the
  matcher; they have their own semantics.

## Case sensitivity (a stated decision)

Matching is **case-sensitive on every platform**, including Windows and macOS. This is deliberate and it is the
one place a port may differ from a stock `git` on a case-insensitive filesystem (where `core.ignorecase` can make
git fold case): a trio is written on one machine and read on another, so the ports must agree with **each other**
before they agree with a particular filesystem. Keep it case-sensitive, and if a port needs case folding, it is a
different matcher with its own vectors.

## The parser's classes

The matcher classifies every pattern once, at parse time, because the class decides the cost of every later lookup:

| Class     | When                                                  | Later lookup                                           |
| --------- | ----------------------------------------------------- | ------------------------------------------------------ |
| `literal` | No wildcard (`*`, `?`, `[` or `\`).                   | `std.mem.eql`, or a map hit for an unanchored literal. |
| `suffix`  | `*.…` where the part after the `*` holds no wildcard. | `std.mem.endsWith` on the final segment.               |
| `glob`    | Everything else.                                      | The segment dynamic program in `globMatch`.            |

These classes are an **implementation detail, not extra rules**: moving a pattern between classes must not change
any answer, and the vectors are what prove it. The comment on
[`Rule`](../src/gitignore.zig#L57) and the classification in
[`Rules.parse`](../src/gitignore.zig#L92) are the authoritative statement of the split.
