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
`classes.json` and its `imports.json` sidecar. Editing or regenerating metadata is enough to refresh the
report; no page rebuild or model rebuild is needed. It shows the type and edge count, the cycles sorted by
size, every type with a source link and line, and the highest fan-in/fan-out types. Filtering and cycle
selection are jsx6 page state.

## Interpretation

A type appears in a cycle when it is in an SCC with more than one type.
A large SCC means scheduling cannot split that set without a semantic break; compile it as one ECJ batch,
or introduce an interface/stub between the cycle members.
Fan-in identifies the type many others need; fan-out identifies the type that pulls many others in.
The page is for inspection; it does not modify source.

The playground runs this report for `core` + `chat-app` from `bun run pages`; it is linked as
**Dependency cycles** on the project dashboard.
