# markdown-view — Markdown as a clickable page

Renders a module's Markdown files into HTML pages whose code identifiers and internal links **open the
file and line in the IDE**, plus an index page that links them together.

```bash
# every Markdown file of the example module
bun run scripts/markdown-view/index.js --module hipster-entity-example

# one or two of them, checking every link against the tree as it goes
bun run scripts/markdown-view/index.js --module hipster-entity-example \
  --only codebuddy.md,README.md --verify
```

Output: one self-contained HTML file per Markdown file plus `index.html`, in
`<module>/.jcodebuddy/agent-state/markdown-view/` by default (derived, git-ignored, safe to delete).

```
hipster-entity-example/.jcodebuddy/agent-state/markdown-view/
  index.html                 <- start here: one card per document
  README.html
  codebuddy.html
  .jcodebuddy/index/README.html    <- the directory structure is preserved
```

Open `index.html` in the IDE's webview (right-click → **Open in WebView Explorer**, `Ctrl+Alt+Shift+W`)
and a click jumps to the file and line. Open it in a browser and the same click falls back to the IDE's
HTTP bridge, or copies `path:line` to the clipboard.

---

## What it is for

Two things, and the second is the point:

1. **A usable view of this repository's prose.** `codebuddy.md` is 739 lines about classes that exist in
   this tree; reading it as a page where `PersonSummaryBuilder` is a link is better than reading it as
   text.
2. **A worked example and a starting point.** It is the smallest complete custom document in the
   repository: ~500 lines across three files, no dependencies, no bundler. Copy it, change
   `resolveTarget`, and you have a viewer for your own source of truth — a coverage report, a review
   dashboard, a trace viewer, a spec that references code.

The contract it speaks is documented in **[`webview/doc/webview-link-api.md`](../../webview/doc/webview-link-api.md)**.
Read that first if you are writing your own; this file is about *this* implementation.

---

## The three files

| File | What it owns |
| --- | --- |
| `open-file.js` | the **client half**: the click handler, the fallback ladder, the stylesheet, the attribute escaping. Copy this file into any custom page and it works. |
| `render.js` | the **document half**: a small Markdown renderer and the link resolver, including the class-index lookup. |
| `index.js` | the **CLI half**: discovery, the page shell, the index page, and `--verify`. |
| `markdown-view.test.js` | the tests: the line-suffix parser, the link resolver, the Markdown renderer, and a full run whose links are all verified. |

### `open-file.js` — the client

`openFileClientScript({ bridgePort })` returns the `<script>` body to inline:

```
window.openFile(target, line, column)   /* the IDE webview injected this -> use it */
  else  hidden iframe -> http://127.0.0.1:<port>/open?filePath=…&line=…&column=1
  else  navigator.clipboard.writeText("path:line") + a toast
```

It also writes the bridge status into `#bridge-status`, so a reader can see *why* a click did or did not
do something. The full rules, including `data-link-base`, are in
[`webview/doc/webview-link-api.md`](../../webview/doc/webview-link-api.md) § 4–5.

### `render.js` — the document

**The resolver is the interesting part.** Given the text of a link or a code span, `resolveTarget`
returns a target only when it can point at something real:

| In the Markdown | Resolves to |
| --- | --- |
| `` `src/main/java/.../PersonSummary.java` `` | that file, line 1 |
| `` `PersonSummary` `` — a type the class index knows | its declaring file and **its declaration line** |
| `[the builder](src/main/java/.../PersonSummaryBuilder.java#L48)` | that file, line 48 |
| `` `scripts/gen.cmd` `` or `` `../AGENTS.md` `` | the file, resolved against the module **or** the repository root |
| `` `.java` ``, `` `src/main/java/…` ``, `` `some_prose_word` `` | **nothing** — left as plain text |

That last row is the rule that matters: **resolve or leave alone**. A document generator that linkifies
everything path-shaped produces a page of links that look right and go nowhere, which is worse than plain
text. The two-root search (module first, then repository) is what lets a module document reference
`scripts/gen.cmd` without knowing how deep it is; the class index search is what lets prose name a *class*
without a path at all.

`classIndexFrom` reads `.jcodebuddy/index/classes.json` (DEC-029). A missing index is not an error — the
tool then links only explicit paths, and says how many types it found, which is `0`:

```
Rendered 13 document(s), 165 link(s) (class index: 42 type(s)) -> hipster-entity-example/.jcodebuddy/agent-state/markdown-view
```

### `index.js` — the CLI

Discovery walks the module and skips derived directories (`target/`, `node_modules/`, `.git/`, …). Each
document becomes `<name>.html` at the same relative path, so a link between two rendered documents still
works after rendering. The page shell carries `data-link-base` (the relative path from that page back to
the module root) and `data-bridge-port`.

`--verify` checks every link it emitted:

* the target must exist (the file or directory);
* when a link names a member **and** its line came from the class index, that line must contain the member.

A line the *document* gave explicitly (`…/PersonSummary.java:48`) is not checked for a member: the tool
cannot know which member the author meant, and checking it would fail correct links. A broken link fails
the run with exit code 1, exactly as the entity page's renderer does (DEC-027 § 4.2).

---

## Making your own

Three steps, in the order that keeps you honest:

1. **Decide where a location comes from.** A path is easy. A *name* needs a table — this tool uses the
   class index, but anything with a name-to-location mapping works (a coverage report, an OpenAPI spec, a
   trace file).
2. **Write `data-open` and `data-line`** on whatever element you like, and use
   `openFileClientScript()` from `open-file.js` inlined into your page. Do not link anything you cannot
   resolve.
3. **Verify before you ship.** Walk the links you emitted and check the file exists and, where you claim a
   member, that the line contains it. `verifyLinks` in `index.js` is 40 lines; copy it.

### Rules that are not negotiable

* **The page must be one self-contained file.** No `<script src>`, no CDN, no `node_modules` at view time
  (DEC-027 § 2) — it must open inside the JetBrains JCEF webview with no network.
* **No absolute paths in the artifact.** The page computes the absolute path the IDE needs from
  `data-link-base` and its own `location`, so a clone, a branch switch or a moved tree still works.
* **Guard the call.** `window.openFile` is absent in a browser; calling it unguarded throws a `TypeError`
  and kills every later click in that page.
* **A dead-looking link is worse than a visible path.** When there is no bridge, copy the location and say
  so, or render it as text.

---

## Tests

```bash
cd scripts ; bun test markdown-view
```

They assert the line-suffix parser (`#L14`, `:14`, a Windows drive letter that is not a line), the resolver
(a path, a type name, a directory, prose that must *not* resolve), the Markdown renderer (headings with
anchors, tables, fences that are never inline-processed, task lists), and a full run over the example
module in which every emitted link is verified against the tree.
