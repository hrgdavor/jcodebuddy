# `entity-html` — the HTML entity index renderer

Renders one self-contained HTML page from a module's generator JSON metadata and its class index:
every entity, every artifact generated from it, and every field clickable through to the exact
source line of every artifact. The page is meant to be opened inside the IDE — in the JetBrains
**WebView Explorer** tool window — but it is an ordinary file that any browser can open.

This is the renderer DEC-027 decides on: **the JSON metadata is the model, Bun JavaScript renders
the page, and the Java generators never emit HTML.** A fact that should appear on the page is added
to `toJson` in `EntityMetadataGenerator`; it is never scraped into the renderer. Since DEC-029 that
model is two files — a document and the module's class index — and every field's locations are
recorded by the pass rather than reconstructed here. The page itself is unchanged by DEC-029: only
the spelling of a file reference changed, and the table that reference resolves through.

```
scripts\entity-html.cmd                  # the example module, default paths
bun run scripts/entity-html/index.js     # the same thing, without the wrapper
bun test                                 # from scripts/: the renderer's own tests
```

## Where the output goes

`<module>/.jcodebuddy/metadata/entity/index.html` — beside the JSON it renders (DEC-026's derived,
git-ignored `metadata/` subtree). `scripts\gen.cmd` renders it as the last step of a generation
pass. A machine without Bun prints a skip line and the pass still succeeds; a page whose link check
fails makes the pass fail, because a link to the wrong line is worse than no link.

## Flags

| Flag | Meaning | Default |
| --- | --- | --- |
| `--module <dir>` | module directory to render | `hipster-entity-example` |
| `--metadata <dir>` | directory holding `<Marker>.metadata.json` | `<module>/.jcodebuddy/metadata/entity` |
| `--source-root <dir>` | Java source root used to resolve link targets | `<module>/src/main/java` |
| `--link-base <dir>` | directory link paths are relative to | the module directory |
| `--out <file>` | output file | `<metadata>/index.html` |
| `--packages <a.b,c.d>` | only views in these packages | every view in the metadata |
| `--title <text>` | page title | `<module> — entity reference` |
| `--bridge-port <n>` | `webview/webview-jetbrains` HTTP bridge port for the browser fallback; `0` disables it | `18881` |
| `--soft` | exit 0 even when the link check rejects a candidate | off |
| `--quiet` | print only the output path and the link-check summary | off |
| `--version`, `--help` | identity / usage, no rendering | — |

Exit codes: `0` success, `1` usage or I/O error, or an unverified link (unless `--soft`).

## What it reads from the metadata and the index

Everything about the model, from two inputs per module: each `<Marker>.metadata.json` and the
module's class index `.jcodebuddy/index/classes.json` (DEC-029). A document names a file by the
**fully qualified name** of the type that file declares; the class index is the one place that
states the path behind it. A document written while DEC-028's `files.json` existed — short readable
ids plus a `fileIndex` pointer — still renders, because the renderer reads that table too, for one
revision.

```jsonc
// <Marker>.metadata.json (abridged) — FQNs, never paths
{ "entityName": "Person", "package": "hr.hrg.hipster.entityexample.person.entity",
  "markerInterface": "Person", "idType": "Long",
  "markerFile": "hr.hrg.hipster.entityexample.person.entity.Person",  // an FQN, not a path
  "markerLine": 14,
  "classIndex": "../../index/classes.json",   // the only path-like value in the document
  "views": [ { "name": "PersonSummary", "lineNumber": 13,
               "file": "hr.hrg.hipster.entityexample.person.entity.PersonSummary",
               "artifacts": [ { "id": 0, "name": "PersonSummary", "kind": "interface",
                                "file": "hr.hrg.hipster.entityexample.person.entity.PersonSummary",
                                "line": 14, "generated": false, "own": true } ] } ] }
```

| JSON | Used for |
| --- | --- |
| `index/classes.json` — `format`, `hash`, `classes` (FQN → `path`, `kind`, `modifiers`, `line`, `depth`, `enclosing?`, `generated?`, `size`, `checksum`, `hashCalculatedAt`) | the path behind every FQN the page links with. Located from the module root the CLI already knows **and** cross-checked against each document's `classIndex` pointer; `format` must be one this renderer knows, and `index/files.json` is read as the one-revision legacy name |
| `entityName`, `package`, `markerInterface`, `idType`, `markerFile`, `markerLine` | the entity section's header — marker, package, identity type, and the marker's own file (an FQN) and declaration line |
| `classIndex` | the pointer to the class index, relative to the document — the second route to the table (the legacy `fileIndex` is read the same way) |
| `views[].name`, `gen`, `addons`, `extends`, `discriminatorField` | one view section per view, its `GenLevel` chip and its declaration |
| `views[].file`, `views[].lineNumber` | the view's file FQN and declaration line — the link behind its name |
| `views[].properties[]` — `name`, `type`, `lineNumber`, `file`, `fieldKind`, `column`, `relation`, `expression` | a view's own accessors and its `@FieldSource` facts, and the declaring file (an FQN) of each |
| `views[].artifacts[]` — `id`, `name`, `kind`, `file`, `line`, `generated`, `own`, `header?` | the artifacts that belong to a view: its aspects, their labels and descriptions, and the foreign declaring interfaces its fields reference (`own: false`); `file` is an FQN |
| `views[].fields[]` — `name`, `ordinal`, `type`, `fieldKind`, `column`, `relation`, `expression`, `at` | each field's ordinal (DEC-023's ledger order), its type, its chips, and **every location it has**: `at` is artifact id → role → line |
| `allFields[]` — `name`, `type`/`typeByView`, `lineNumber`, `file`, `fieldKind`, `column`, `relation`, `expression`, `views[]` | each field's type as one view sees it, which views expose it, and **the declaring file's FQN** (`null` when that file is outside the module — the inherited `id`) |
| `generation.json` | the footer's run record: generator, version, status, packages |

Every path in the class index is **relative to the module root** (`src/main/java/…`), which is
exactly the base the page's link base is set to, so a resolved path is used as a link path verbatim.

**The page carries paths, never content.** A cell holds a file, a line and a role — never a copy of a
Java file. A report that quoted the source would be a second, stale copy of the tree, and the file is
one click away instead. The only `<pre>` on the page is the renderer's own diagnostics block (the
divergence list, or the link-check line); `entity-html.test.js` asserts both.

## Discovery reads the model; the scan only verifies a link

Discovery is metadata. The artifacts and every member line come from `views[].artifacts[]` and
`views[].fields[].at`, so the renderer does **not** find an artifact by naming convention or infer a
member line from a pattern. Columns are the `(artifact, role)` pairs a field's `at` map actually
contains, ordered by the renderer's own `ROLE_ORDER` constant — `accessor`, `annotation`,
`enum-constant`, `record-component`, `setter`, `field`, `ordinal-slot`, `name-slot` — and never by
the JSON's insertion order, which is the *document's* order and exists so two runs are
byte-identical. The two orders differ on purpose; neither should be changed to match the other. The
column set, the labels and the link targets are unchanged by DEC-028, and unchanged by DEC-029 as
well: the page looks the same, it just resolves an FQN instead of an id.

The one thing still resolved from source is a **check**, and it is mandatory (DEC-027 § 4.2):

- **Every link is verified.** A cell is written only if the target file exists **and** the line
  contains the member; the `field source` role is verified by `@FieldSource` on that line instead,
  since that line legitimately names no member. A candidate that fails is dropped and reported as
  `kind=html_link_stale` in DEC-022's format, and the run exits 1 unless `--soft`. `sources.js` is
  kept for exactly this: it is the reader that knows how to check a line for a member.

**A missing, unrecognised or corrupt index is loud.** When a document carries FQNs but the table
cannot be read — absent, unparsable, an unknown `format`, or a shape this renderer cannot use —
`buildPage` reports `kind=html_index_missing` and renders nothing, because an FQN rendered as if it
were a path is a page full of dead links that looks like it worked. Both routes to the table are
tried: the module root the CLI knows (`<module>/.jcodebuddy/index/classes.json`, with `files.json` as
the one-revision legacy name) and the document's own `classIndex` pointer (or the legacy
`fileIndex`). The module-root route wins, so a wrong pointer cannot silently break the page.

**Earlier spellings still render.** A document written while `files.json` existed — the DEC-028
readable ids plus a `fileIndex` pointer — resolves those ids through that legacy table when it is
present. A document that predates any index at all — no pointer, no `artifacts`, no `fields` — falls
back to the source scan: the view's file is resolved by name (marker package first, then a unique
simple-name match, ambiguity reported), an artifact is found by its DEC-021 header `// {@link <fqn>} …`
plus the naming conventions below, a member's role and line by a line-oriented scan that blanks
comments and string/char literals first (so no line number can move), and the ordinals from the field
enum's constant order, because that enum *is* the ledger. Both fallbacks are compatibility, not a
second supported path.

## Naming contract (DEC-022)

The renderer derives labels from source identifiers and recognises artifacts by name. What it reads
never changes generated Java — the table exists so a reader knows what a rename does to the page.

| Name | Derived from | If the identifier is renamed | Refactor-sensitive? |
| --- | --- | --- | --- |
| artifact → view association | the metadata's `views[].artifacts[]`; the DEC-021 header's `{@link <viewFqn>}` only on the pre-DEC-028 fallback | the generator re-records the inventory on the next pass, and rewrites the header; the page follows it | **no** — a recorded fact or a reference, not a convention |
| `<View>_`, `<View>Record`, `<View>Builder`, `<View>BuilderTracking`, `<View>Validator`, `<View>RowAdapter`, `<View>Binder`, `<View>Mapper` | the view's simple name | the file must be renamed by the same pass that renames the view; until then the fallback convention misses and the artifact is still found by its header | handled by the header path; the convention list exists only for a pre-DEC-028 document |
| view file | the metadata's `views[].file` — an FQN resolved through the class index | the generator re-records the FQN and the index the path, on the next pass; the page follows both | **no** — the FQN and the path are data, and no name is written into committed source |
| field's declaring file | the metadata's `properties[].file` / `allFields[].file` (FQNs) | likewise re-recorded by the generator | **no** |
| view file, only when the metadata predates DEC-028 | the view's simple name, resolved to `<markerPackage>.<name>`, else the unique type with that simple name | the page follows the new name on the next pass | **no** — nothing is written into committed source either way |
| field name | the metadata's field name, matched against members by identifier | the page follows the metadata | **no** |
| column header, role labels, `data-member` | the role vocabulary (`accessor`, `enum constant`, `setter`, …) | nothing — these are explicit page labels, not derived from Java | explicitly **refactor-insensitive** |

Nothing in this renderer is committed, so no rendered name can diverge from canonical source in a
way a reviewer has to reconcile.

## Divergences

Reported on stdout and on the page's footer, in DEC-022's `kind, location, cause, current,
canonical, action` format:

| kind | severity | meaning |
| --- | --- | --- |
| `html_link_stale` | error (exit 1) | a candidate link's target line does not contain the member, or the file/line does not exist — the link is dropped |
| `html_index_missing` | error (exit 1) | a document carries fully qualified type names and the class index cannot be read — absent, unparsable, an unknown `format`, or a shape the renderer cannot use; **nothing is rendered**, because an FQN is not a path |
| `html_view_file_missing` | error | the metadata names a view with no type of that simple name under the source root (the pre-DEC-028 fallback only) |
| `html_view_file_ambiguous` | error | several types share the view's simple name and none is in the marker's package (the pre-DEC-028 fallback only) |
| `html_field_not_in_ledger` | warning | the metadata attributes a field to a view whose field enum has no constant for it; the page shows the row without an ordinal (DEC-023 makes the ledger the ordinal contract) |

`html_field_not_in_ledger` is a ledger/metadata disagreement in the generator surfaced by the
renderer, and it does not fail a pass. It no longer fires on the committed example: the ledger is now
read from the locations the pass recorded, where the old scan's constant regex
(`/^\s*([A-Za-z_$][\w$]*)\s*\(/`) missed a constant the emitter writes as
`, email(java.lang.String.class) {`. The kind stays — the bun test injects a field the ledger does
not carry and asserts the warning.

## The page

- One file. Inline CSS, one inline script, **vanilla JavaScript** — no React / Svelte / Solid / Vue /
  Preact / Lit, no bundler, no `node_modules`, no CDN, no `<script src>`, no image, no network at view
  time (DEC-027 § 3, asserted by `bun test`).
- Filter box (`/`), collapse/expand, and click-to-open on every entity name, aspect card, column
  header and field cell.
- Link targets are `data-open` / `data-line` / `data-member` / `data-role` / `data-where` attributes
  holding paths relative to **one** link base (`data-link-base` on `<body>`), never `href`s and never
  absolute paths. The page resolves the base against its own `location`, so the same file works
  whether the IDE project is the module or the repository above it.
- Opening a link: `window.openFile(path, line, column)` — injected by the JetBrains **WebView
  Explorer** plugin into every page it loads — then the plugin's HTTP bridge
  (`http://127.0.0.1:<port>/open?filePath=…&line=…`, needs the page's origin in
  `webview.explorer.allowedOrigins`), then clipboard plus a toast. The header pill says which one is
  live. **The contract itself — the exact signature, the payload, the HTTP parameters, the security
  model and the fallback ladder — is documented in
  [`webview/doc/webview-link-api.md`](../../webview/doc/webview-link-api.md)**, which is the document to read if you are
  writing another page or another plugin. For a smaller worked example of the same contract, see
  [`scripts/markdown-view/`](../markdown-view/README.md), which renders Markdown this way.
- A cell is empty when the field genuinely has no such slot there — a `DERIVED` field has no setter,
  a `META` view has no builder. The absences are part of the answer, so they are rendered, not
  omitted.

## Tests

`bun test` (from `scripts/`) runs `entity-html.test.js`, which renders the committed example and
asserts: **every page link maps back to a location the metadata records** — the recorded locations
are read from the raw documents and the index, independently of the page model, so a link at a line
no document mentions could only have come from a scan guessing; every link resolves to a line
containing the member it claims; the accessor and the `@FieldSource` line stay distinct; the page
resolves the class index by **both** routes (the module root and the document's `classIndex` pointer)
to the same table, and fails with `html_index_missing` — rendering nothing — when the table is
missing, unparsable or of an unknown `format`; a document that carries the DEC-028 readable ids and
`fileIndex` still renders through the legacy `files.json`; a document with `fileIndex`/`artifacts`/
`fields` stripped (the pre-DEC-028 shape) still renders from the source, with no error-severity
divergence; the page is one self-contained file with framework-free vanilla JS that parses
standalone; no absolute path and no non-loopback URL appears; the page's own link-base resolution
lands on the link base; two runs are byte-identical; the class index plus the three documents stay
inside the size budget, which exists to catch a *shape* regression rather than to police a byte; and
the model groups views under their marker with inherited fields resolved to the declaring interface. It writes into
`hipster-entity-example/.jcodebuddy/agent-state/entity-html-test/` (DEC-026 scratch) and removes it
afterwards — the output must live inside the module, because a path on another Windows drive cannot
be expressed relative to the link base.
