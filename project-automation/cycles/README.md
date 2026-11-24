# Dependency-cycle report

A class-level cycle report built from JCodeBuddy metadata. It answers the compiler question we hit
with the 3,041-file module: **which types form a strongly connected component and therefore cannot be
compiled in separate batches without stubs or an architectural break?**

## Model

The input is the committed class index, not Java source:

- `.jcodebuddy/index/classes.json` — type kind, path, line, relations, members, parameter types;
- `.jcodebuddy/index/imports.json` beside it — the imports of each declaring file.

The builder resolves those names to classes that exist in the same index (including the same package) and
runs Tarjan's SCC algorithm. Unresolved or library types remain external; the page does not invent edges.

## Run

```sh
bun project-automation/cycles/build.js \
  --index core/.jcodebuddy/index/classes.json \
  --index chat-app/.jcodebuddy/index/classes.json \
  --link-base . \
  --out .jcodebuddy/app/cycles.html
```

Options:

| option              | meaning                                                             |
| ------------------- | ------------------------------------------------------------------- |
| `--index <file>`    | class index to include; repeat for a multi-module report            |
| `--link-base <dir>` | project root the page is served from                                |
| `--out <file>`      | output HTML file; default `<link-base>/.jcodebuddy/app/cycles.html` |
| `--title <text>`    | page heading                                                        |

The page is a **jsx6** page, like the entity browser and compiler console. The build writes the shell and
bundle; the cycle model is computed in the browser from the current metadata on every load:

```text
cycles.html        shell with the metadata index URLs
cycles.js          jsx6 bundle
```

Open it through a host (`/app/cycles.html`), not as a `file://` document: the page fetches each
`classes.json` and its `imports.json` sidecar through the host's read-only `/source/` namespace, using the
host-injected page token. Editing or regenerating metadata is enough to refresh the report, with the
**Refresh metadata** button or the `jcodebuddy:metadata-changed` event; no page rebuild or model rebuild is
needed. A refresh that finds different cycles pulses the button and leaves the current view untouched until
the user applies it.

The graph uses nodditor's measured block boxes: blocks are added first, their title/meta are marked
with `ne-drag` / `ne-nodrag`, and only after nodditor's `ResizeObserver` publishes each box's `size`
does the page place the columns and rows and draw the connectors. The title is the drag handle; the
metadata line and the eight ports are non-drag areas.

The graph's **out** port is the type/group that owns the dependency: the edge leaves it because it
references the target. The **in** port is the target: another unit depends on it. So

```text
A out ─────────► B in     means "A depends on B"
```

This is a static compile dependency, not runtime data flow. `in` is therefore “used by” and `out` is
“depends on”. The four sides are only line-routing choices; every side carries one in and one out port.

The **Maximize** control in the legend opens the graph over the app area. In a normal browser it uses
the real HTML Popover API (`popover="manual"`, `showPopover()` / `hidePopover()`). When the page is
served by the JCEF bridge (`window.openFile` / `window.__jcbWebViewBridge`), it uses the same mounted
graph element with a CSS fixed overlay instead, because JCEF's top layer does not reliably repaint the
whole browser surface. The inline graph is never reparented, so the nodditor editor is not recreated or
disconnected.

Selecting a cycle shows its internal report, not a source file: a nodditor graph of the cycle, the
internal dependency list, and the simple loops found in it. The graph is capped at 60 nodes; the counts
and lists cover the whole SCC.

## Grouping and build analysis

The **Group by** selector switches the same report between:

- **classes** — the exact type graph;
- **packages** — one node per package, weighted by the class edges between them;
- **modules** — one node per module.

The group modes expose the architecture decisions a cycle report otherwise hides:

- **Cut candidates** inside a cycle are sorted by the weight of the internal edge (how many class
  dependencies it represents) and how many simple loops use it. A thin edge is the cheapest place to
  introduce an interface or split a class.
- **Low-weight grouping boundaries** lists the thinnest cross-group edges, including edges that are not
  in a cycle. Those are the first candidates for an interface or a module split.
- **Compile layers** is the SCC-condensed graph in topological order. Each layer can start after the
  previous one, and the groups inside a layer are parallel build opportunities. A large single cycle
  appears as one unit because it cannot be split without a semantic break.
- **Highest fan-in / fan-out** remains the hot-path view: a high fan-out is a source-like hub, a high
  fan-in is a sink-like dependency. A type such as `ChatAppStore` can be hot without being cyclic, which
  is exactly why it is shown separately from the SCC list.

The **Dependencies** view is the other half: it lists every type/group whose **transitive dependency
count** reaches a threshold (default 10), whether or not it is in a cycle. Selecting one shows its direct
dependencies, its reachable dependency set and a nodditor graph of the direct neighbourhood. This is how a
high-fan-out but acyclic hub such as `ChatAppStore` is inspected. Clicking a fan-in/fan-out entry opens
this view for that unit.

The mode and selected dependency are URL parameters for sharing:

```text
/app/cycles.html?mode=packages
/app/cycles.html?mode=module
/app/cycles.html?node=demo.chat.chatapp.ChatAppStore
```

## Interpretation

A type appears in a cycle when it is in an SCC with more than one type.
A large SCC means scheduling cannot split that set without a semantic break; compile it as one ECJ batch,
or introduce an interface/stub between the cycle members.
Fan-in identifies the type many others need; fan-out identifies the type that pulls many others in.
The page is for inspection; it does not modify source.

The playground runs this report for `core` + `chat-app` from `bun run pages`; it is linked as
**Dependency cycles** on the project dashboard.
