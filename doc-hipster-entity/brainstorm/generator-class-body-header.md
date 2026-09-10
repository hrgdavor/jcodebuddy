# Generator Class-File Header — Short Id, JSON5 Config, Per-File Options

> **Status:** proposal, captured for [DEC-021](../architecture/decisions/DEC-021.md).

The full decision (rationale, alternatives, consequences, acceptance
criteria) lives in
[`doc/architecture/decisions/DEC-021.md`](../architecture/decisions/DEC-021.md).
This file is the **practical reference** for generator authors: the
exact header shape, the JSON5 subset, and the rules for adding a
config key.

## The header at a glance

```java
// {@link hr.hrg.codebuddy.RecordBuilder} Generate builder for the Order record.
// {enabled:true, blockMarker: "implicit"}

package com.example.order;

import ...

public final class OrderBuilder { ... }
```

Two line comments, above the `package` declaration:

1. **First line** — `// {@link <fqn>} <one-line description>.`
   Short, ≤ 120 characters, ends with a period. The `{@link}`
   target is the generator's main entry-point class, so any IDE
   that supports the syntax renders it as a clickable link to the
   generator's source.
2. **Second line** — `// {<json5 config>}`. A single-line JSON5
   object literal that the user can edit to customise the
   generator for this file only.

## Why the header is above the imports (and not in the class body)

The earlier draft of DEC-021 put the header in a class-body block
comment. That was the wrong shape: it occupied vertical space where
the reader expects class-level detail, was the first thing they
saw, and competed with the generator's own block-level cooperation
per DEC-020. The two-line header above the imports is:

- small enough not to compete with the generator's content,
- located where the reader's eye does not expect class-level
  detail,
- separated from the rest of the file by the `package` /
  `import` block, which is a natural visual delimiter,
- easy to delete-and-regenerate (the user removes the two lines
  and the generator re-emits them).

## The JSON5 subset

The second line is a JSON5 object literal. The project supports a
**pinned subset** of JSON5 features, mapped to specific Jackson
`JsonReadFeature`s. The table below is the **single source of
truth** for what the parser accepts.

| JSON5 feature                                | Jackson `JsonReadFeature`                                |
| -------------------------------------------- | -------------------------------------------------------- |
| Single-quoted strings (`'foo'`)              | `ALLOW_SINGLE_QUOTES`                                    |
| Unquoted field names (`{foo: 1}`)            | `ALLOW_UNQUOTED_FIELD_NAMES`                             |
| Trailing commas (`{a:1, b:2,}`)              | `ALLOW_TRAILING_COMMA`                                   |
| Leading decimal point (`.8675`)              | `ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS`                |
| Leading plus sign for numbers (`+42`)        | `ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS`                    |
| Non-numeric numbers (`Infinity`, `NaN`, hex) | `ALLOW_NON_NUMERIC_NUMBERS`                              |
| Backslash escaping of any character          | `ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER`                 |

Anything outside this table (multi-line strings, comments inside
the JSON5 blob, etc.) is **not** in the subset and the parser
MUST emit a diagnostic. The codegen module SHOULD provide a
shared helper that builds a Jackson `ObjectMapper` with exactly
these features enabled, and nothing else.

## Adding a config key

A new key requires three things from the generator author:

1. **The key** — a valid Java identifier, lowercase with
   underscores between words. Once shipped, the key MUST keep
   the same meaning across generator versions.
2. **The default** — the project-wide default value. Recorded
   in the generator's README and surfaced via the diagnostic
   on "this value differs from the project default".
3. **A reader and a validator** — every generator MUST expose
   a way to read the header and validate the value. The
   generator SHOULD use the codegen-module helper for the
   common parse + validate steps and extend it with
   key-specific validation.

A new key's default value is whatever the generator currently
emits. Changing the default for a future generator version is
fine; the header records the value at the time of the last
regen, so the user can always tell whether a file has been
customised.

## The `enabled` knob

Every generator MUST accept this option (default `true`).
Setting it to `false` tells the generator to leave the file
alone (equivalent to a per-file freeze). The generator:

- Does not rewrite the file's class body, imports, package
  declaration, or header on the next regeneration pass.
- Emits a single diagnostic the first time it would have
  otherwise emitted into the file.
- Preserves the existing header so the disabled state is
  documented in the file itself.

The user opts back into generator output by setting the value
back to `true` or by deleting the header.

## Cooperativity with the header

The header is generated source and follows the cooperative
preservation rules from DEC-020:

- **First line (description)**: the generator may update it on
  a regen pass. If the user has edited it, the generator MAY
  preserve the user's edit, but is not required to.
- **Second line (config)**: the generator MUST preserve user
  edits verbatim. The generator may update a key's value if
  the user has reset it to the default, or if the
  project-wide default has changed (with a diagnostic).
- **Deleted header**: the generator treats the absence of both
  lines as a request to re-emit a fresh header with
  project-wide defaults.

## Worked example: a generated file that has been customised

```java
// {@link hr.hrg.codebuddy.RpcDispatcher} JSON-RPC dispatcher for the user-facing API.
// {enabled:true, blockMarker: "strict", maxMethods: 128}

package com.example.api;

import ...

public final class RpcDispatcher { ... }
```

The user changed `blockMarker` to `"strict"` and `maxMethods` to
`128` for this file only. The generator still uses implicit
detection and the 64-method default for every other file it
emits, but for *this* file it uses strict `// generator:begin` /
`// generator:end` markers and accepts up to 128 methods. The user
can see at a glance that this file has been customised (the
values differ from the project defaults), and the diagnostic on
the next regen pass re-confirms it.

## Patterns to make per-file options usable

These are the patterns the project has explored or intends to
adopt so the class-file header is **reliable** in practice.
Every generator is expected to follow them.

### 1. Publish the full config schema in the generator's README

Each generator's README lists every config key, its value
format, its default, and a one-line description. A user who
opens the file and reads the header should be able to find the
full schema in one click (the `{@link …}` reference points at
the generator; the generator's README is its documentation).

### 2. Validate on every regen pass

The generator MUST validate the parsed config on every regen
pass. Invalid values are diagnostics, not silent fallbacks.
Unknown keys are diagnostics, but the generator MUST preserve
them in the regenerated header so a downgrade to the previous
generator version still works.

### 3. Emit a diagnostic on every non-default value

The generator SHOULD emit a diagnostic listing every config
key whose current value differs from the project-wide default,
so the user can see at a glance which files have been
customised. A summary format the LSP / CI can parse:

```
[generator:customized] <generatorId> / <fileName>
  <key1> = <value1>  (default: <default1>)
  <key2> = <value2>  (default: <default2>)
```

### 4. Provide a "reset to default" code action

The LSP / IDE sidecar SHOULD offer a code action for each
config key in the header that resets the value to the default.
This makes it cheap to undo a customisation when the user
decides the project default is fine after all.

### 5. Provide a JSON5 parser helper in the codegen module

A small helper that builds a Jackson `ObjectMapper` with the
features in the table above enabled, parses the second line,
and returns a typed config object. Each generator extends the
typed config with its own keys. The helper handles the JSON5
feature negotiation; individual generators handle their own
validation.

### 6. Header is the first thing the user sees

The header MUST be the first non-blank lines of the file,
above the `package` declaration. The LSP / IDE sidecar SHOULD
show a hover for the `{@link …}` reference that opens the
generator's source, and a hover for the JSON5 blob that lists
the available keys and their defaults.

### 7. Don't put secrets in the header

The header is plain text in the source file and is intended
to be checked into git. Secrets, credentials, and per-file
overrides that should not be checked in are not what this
mechanism is for.

### 8. Header composes with DEC-020 cooperation

If the user deletes only the second line, the generator
preserves the first line and re-emits a fresh second line
with project defaults. If the user deletes the first line,
the generator re-emits both lines. If the user deletes both
lines, the generator re-emits both. The cooperative rules
from DEC-020 govern the header as a whole.

## Open questions

> `<!-- TODO/EXPLORE: how to surface the "this file is
> customised" diagnostic in the LSP without making the
> notification noisy on large code bases. A per-file
> summary, on demand? -->`

> `<!-- TODO/EXPLORE: how to handle a header whose
> `{@link …}` reference points at a generator class that
> no longer exists (the generator was removed from the
> project). The header becomes a stranded comment; the
> user has to delete it manually. A diagnostic that detects
> the missing link and offers "delete the header" as a
> code action. -->`

> `<!-- TODO/EXPLORE: how to handle a header whose JSON5
> blob uses a feature outside the pinned subset. The parser
> SHOULD emit a diagnostic that names the missing Jackson
> feature and points the user at this document. -->`

> `<!-- TODO/EXPLORE: how to coordinate per-file config
> across multiple files when the user wants the same
> customisation on a set of files (e.g. "all dispatchers
> should use strict markers"). A pattern on top of the
> per-file config, not a replacement. -->`

> `<!-- TODO/EXPLORE: should the parser tolerate a missing
> second line, and treat that as "all defaults"? Or should
> it emit a diagnostic asking the user to add the second
> line? -->`
