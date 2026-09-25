# Phase 0 scratch — the Zed experiments behind `PHASE0-ZED-FINDINGS.md`

These four files are the apparatus for Phase 0 of [the plan](../../PLAN-webview-suite.md); the results are in
[PHASE0-ZED-FINDINGS.md](../../PHASE0-ZED-FINDINGS.md). Nothing here is production code and nothing in the build
reads it.

| File | What it is |
| --- | --- |
| `stub-lsp.mjs` | A minimal LSP server over stdio. On `initialized` it sends `window/showDocument` (with a selection and `takeFocus`) and then `workspace/applyEdit`, logging the client's own `initialize` capabilities and both responses to a file. |
| `self-test-client.mjs` | A synthetic LSP *client* that plays Zed's part, so the stub can be proven correct with no editor attached. 5 checks; exit code 0 means pass. |
| `probe-zed-db.mjs` | Reads Zed's sqlite databases (read-only) and prints rows mentioning a needle — used to prove the edit reached Zed's buffer while the file on disk was untouched. |
| `scratch/Sample.{java,md,rs}` | Disposable targets. `Sample.rs` is the one the successful run used. |

The one dangerous part of the apparatus is **not** committed: the project-local `.zed/settings.json` that pointed
a registered adapter at the stub. It is reproduced from `settings-snippet.json` below and **must be deleted
afterwards**, because it displaces the real language server for whichever language it names.

## Running it

```powershell
# the stub itself, no editor involved
node webview/zed/phase0/self-test-client.mjs

# an isolated Zed instance (its own database, extensions and logs; the user's Zed is untouched)
# 1. copy settings-snippet.json to <repo>\.zed\settings.json and substitute <REPO> (and note: it must be BOM-free)
# 2. launch it on this repository, naming a file the overridden adapter owns
& "C:\Users\hrg\AppData\Local\Programs\Zed\bin\Zed.exe" --user-data-dir <REPO>\target\zed-phase0 `
    <REPO> <REPO>\webview\zed\phase0\scratch\Sample.rs
# 3. read the wire evidence, then Zed's own side
Get-Content <REPO>\target\phase0-stub-rs.log
Get-Content <REPO>\target\zed-phase0\logs\Zed.log -Tail 20
# 4. and what Zed persisted for the buffer
node target/phase0-selections2.mjs <REPO>\target\zed-phase0 "Sample"

# 5. delete <REPO>\.zed\settings.json when finished
```

Two traps worth knowing before repeating this: a **UTF-8 BOM** in `.zed/settings.json` makes Zed discard the
whole file (`Failed to set local settings … expected value at line 1 column 1`), and `lsp.<name>` is honoured
**only** for names an extension or a built-in adapter has registered — an arbitrary name is silently ignored.
Both are documented in the findings note.
