> **An earlier attempt, moved under `webview/` on 2026-10-02.** This directory is a previous take on what `webview/jwa-sidecar` and the webview hosts do today, which is why it now lives here rather than at the repository root. It is kept only until plan step **3.0p** audits it against the current implementation and **3.0q** merges what is worth keeping or deletes it with a reason - do not build on it, and do not assume it reflects the current bridge, without reading those two steps.

# jswa-core

ideas: 
- https://github.com/cmstead/js-codeformer
- https://github.com/slonoed/jsref - maybe refactor for sth faster than babylon, maybe not
- https://martinfowler.com/articles/codemods-api-refactoring.html

To install dependencies:

```bash
bun install
```

To run:

```bash
bun run index.ts
```

This project was created using `bun init` in bun v1.3.10. [Bun](https://bun.com) is a fast all-in-one JavaScript runtime.
