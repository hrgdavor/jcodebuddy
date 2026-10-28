# Zed integration — a survey and a decision

**Status: 2026-10-28 — survey written; items 1, 2 and 4 answered. Item 3 (ACP) waits for step 5.3's verdict, so
the decision below is provisional on that one point.** This is plan step 8.5; it is a survey, not an integration
plan, and it deliberately ends in "not now" rather than "possible".

Zed is the one editor in the suite that is neither JetBrains nor VS Code. It is also the only one with a
first-class agent protocol (ACP), which is why step 5.3 spikes ACP and 8.4 runs it. The question here is different:
what does a person using Zed get from JCodeBuddy once that spike has an answer?

## 1. What Zed can host today

| Surface                    | What Zed provides | What it means for us |
| -------------------------- | ----------------- | -------------------- |
| **Extension features**     | `extension.toml` extensions provide [languages, debuggers, themes, icon themes, snippets and MCP servers](https://zed.dev/docs/extensions/developing-extensions); procedural parts are Rust compiled to `wasm32-wasip2`. | There is **no extension feature for an arbitrary page or panel**. A generated report page cannot be mounted inside Zed by an extension. |
| **Extension capabilities** | Extensions may run processes (`process:exec`), download files and install npm packages, subject to [granted capabilities](https://zed.dev/docs/extensions/capabilities). | An extension could start `webviewd` or a task, but that is a process launcher with a Rust/WASM build and a capability grant — not a page host, and worse than a shell command for the same result. |
| **Markdown preview**       | Zed has native Markdown support, and the 1.20 line added a [Markdown preview](https://zed.dev/releases/preview/1.20.0) (the suite targets 1.21). | Our report pages are **HTML**, not Markdown, so the preview does not render them. A Markdown-only report would be a different generator output, not a Zed feature. |
| **CLI**                    | `zed file`, `zed file:42`, `zed file:42:10`; the CLI also opens `zed://`, `file://` and `ssh://` URLs ([CLI reference](https://zed.dev/docs/reference/cli)). | This is the **fallback that already exists**: `ZedCliHost` opens a file in a running Zed. Phase 0 measured that Zed 1.21 on Windows refuses the `path:line:column` form, so the CLI opens the file but cannot place a caret (see [`PHASE0-ZED-FINDINGS.md`](../PHASE0-ZED-FINDINGS.md)). |
| **LSP**                    | Zed is an LSP client. Its `window/showDocument` handler applies a selection, which is the only route that places a caret on Zed — Phase 0 observed it. | This is the **caret path that already exists**: `webviewd`'s `LspHost` asks the sidecar Zed spawns to send `window/showDocument`. The sidecar owns the LSP connection; the page never speaks LSP. |
| **ACP**                    | Zed hosts External Agents over the [Agent Client Protocol](https://zed.dev/docs/ai/external-agents); a custom agent is an `agent_servers` entry with `type: "custom"`, `command`, `args` and `env`. | ACP gives an **agent panel**, not a page. It cannot render a report page, and an ACP agent that opens a page is still a browser opening a page. |

The consequence is narrow: **Zed can host our pages only in a browser**, beside the editor, exactly as the
current `webviewd` story already does. It cannot host a page in a panel, and no extension capability changes that.

## 2. What needs an extension, and what that costs

An extension is a Git repository with an `extension.toml` and, for a language server, a Rust crate built for
`wasm32-wasip2` ([developing extensions](https://zed.dev/docs/extensions/developing-extensions)). To be useful for
us it would have to do one of three things, and none of them is a page host:

1. **Register the existing sidecar as a language server** — the LSP registration that `webview-jetbrains` already
   performs for IntelliJ. This is the only part likely to port unchanged, and it is already exercised by the
   sidecar's own Zed path; it does not require a new page surface.
2. **Run a task that starts `webviewd`** — a `process:exec` use of an extension to do what a terminal command does,
   with a Rust/WASM build and an IDE-specific manifest to maintain. The cost is a second Zed-specific build and a
   capability grant for a launcher, and the benefit is that a person does not have to run one command.
3. **Open a page beside the code** — the thing an extension cannot do. Zed's extension features are languages,
   debuggers, themes, snippets and MCP servers; there is no webview/panel feature to implement.

**Decision consequence:** an extension is not worth its cost for (1) alone, since the sidecar path already works
without it, and (2) is a convenience a shell alias or task can provide. There is no shape (3) to build.

## 3. The ACP path — pending step 5.3

ACP is the one surface that is genuinely new, and it is deliberately not decided here. Step 5.3 runs the spike:
register `webviewd --acp` as a custom agent (`agent_servers` with `type: "custom"`), answer `initialize` /
`session/new` / `session/prompt`, and expose one tool that calls the same command layer as `/api/v1`. The verdict
is the input this item needs.

What is already clear and does not depend on the verdict:

- **ACP cannot render the page.** It is an agent-panel surface ([External Agents](https://zed.dev/docs/ai/external-agents)),
  and the standalone page rule (root `AGENTS.md` § 2) is unchanged: a report page must still open from `file://`
  with nothing running, and the browser remains the page's home.
- **An ACP bridge, if taken, belongs beside the command layer**, not in a page: the same tool that `/api/v1`
  exposes, offered to an agent. That is step 5.3's own scope, and if the verdict is positive the integration is a
  separate plan of its own, not a page host.
- **ACP is not an extension.** Extension-provided agents are deprecated in favour of the ACP Registry and custom
  `agent_servers` entries ([External Agents](https://zed.dev/docs/ai/external-agents)), so no Rust/WASM extension
  is needed for this path either.

## 4. Our side of the boundary

The maintainer's rule — **a UI must be usable standalone even without webview** (root `AGENTS.md` § 2) — decides
the shape:

- The generated report page stays a `file://` document with nothing running; it does not depend on Zed.
- `webviewd` remains the local host that serves the page and moves the caret: `LspHost` for Zed's caret (the
  sidecar's `window/showDocument`), `ZedCliHost` as the measured fallback that opens the file (and refuses a
  caret it cannot place). No new Zed-specific host is introduced.
- If ACP is taken, it is an enhancement on top of the same command layer, never the thing that makes a page work.
- A capability `jsx6`/`nodditor` lacks is reported there and fixed there; nothing in this survey asks the page to
  work around one.

## Decision (2026-10-28)

**No Zed extension and no new Zed page host. The supported Zed experience is the existing browser + `webviewd`
path: open the report page in a browser beside Zed; the sidecar's LSP `window/showDocument` moves the caret, and
`ZedCliHost` opens the file when the LSP path is not attached.** ACP is **pending step 5.3**: if the spike says
yes, the ACP bridge is scheduled as its own steps beside the command layer; if it says no, that verdict is recorded
there and no Zed work is scheduled.

This is deliberately a "not now" for a native page host, with the reason named: **the surface does not exist**, and
the two surfaces that do exist are already implemented and measured.

## Sources

- [Developing Extensions](https://zed.dev/docs/extensions/developing-extensions) — extension features, Rust/WASM,
  `extension.toml`.
- [Extension Capabilities](https://zed.dev/docs/extensions/capabilities) — `process:exec`, downloads, npm.
- [External Agents](https://zed.dev/docs/ai/external-agents) — ACP, `agent_servers`, the registry, and the
  deprecation of extension-provided agents.
- [CLI reference](https://zed.dev/docs/reference/cli) — `zed file:line:column`, `file://` and `ssh://` URLs.
- [Zed 1.20 preview release](https://zed.dev/releases/preview/1.20.0) — the Markdown preview addition.
- [CLI issue #8482](https://github.com/zed-industries/zed/issues/8482) — the open request for a general
  open-file-via-URL surface; the CLI is the supported route today.
- [`PHASE0-ZED-FINDINGS.md`](../PHASE0-ZED-FINDINGS.md) — the measured Zed 1.21 behaviour this survey relies on.
