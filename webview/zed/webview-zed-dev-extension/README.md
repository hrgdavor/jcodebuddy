# `webview-zed-dev-extension` — the registration that makes Zed able to start the sidecar

This is a **registration extension, not a plugin with UI**. It exists because of a Phase 0 measurement:

> On Zed 1.21.0 the `lsp` key in `settings.json` accepts only names that an **extension or a built-in adapter**
> registered. `"lsp": {"webview-sidecar": {"binary": …}}` on its own starts nothing — an arbitrary name is
> silently ignored ([zed#52653](https://github.com/zed-industries/zed/issues/52653), closed *not planned*).
> The measurement, and the one shape that does work (overriding a registered name), are in
> [`../../PHASE0-ZED-FINDINGS.md`](../../PHASE0-ZED-FINDINGS.md) § A′.

So this crate supplies the missing registration (`extension.toml` → `[language_servers.webview-sidecar]`) plus a
best-effort default command. Everything after that is the documented, already-verified mechanism: Zed applies
`lsp.webview-sidecar.binary` **over** whatever this crate returns, so a user can point it at any build without
touching Rust.

## What it does, and what it deliberately does not

| Does | Does not |
| --- | --- |
| Registers the language-server name `webview-sidecar` and binds it to the Java language | Render anything — a Zed extension cannot create a panel or a webview (plan §D4) |
| Returns a command for Zed to spawn: `JCB_WEBVIEW_SIDECAR` → `webviewd --lsp` on `PATH` → `JCB_WEBVIEW_JAVA` / `$JAVA_HOME/bin/java` `-jar webview/jwa-sidecar/target/jwa-sidecar.jar` | Own the port, or open one itself: the **sidecar** owns the port and serves the page (plan §D1) |
| — | Use `process:exec`: this crate never spawns a process, Zed does, so no `[[capabilities]]` grant is needed |

## Install it as a dev extension

1. `zed: extensions` (Ctrl+Shift+X) → **Install Dev Extension** → choose this directory
   (`webview/zed/webview-zed-dev-extension`). Zed compiles the crate itself with `cargo` for the
   `wasm32-wasip2` target; it is also known to build here with
   `cargo build --release --target wasm32-wasip2` (a 132 KB `.wasm`).
2. Open a **Java** file in the project. Zed's log (`zed: open log`) should show
   `starting language server webview-sidecar`. Nothing happens for non-Java files: the registration names the
   Java language, which the **Java extension** provides, so that extension has to be installed for this one to
   have anything to attach to.
3. Build the sidecar first if you have not: `mvnd -pl webview/jwa-sidecar -am package` produces
   `webview/jwa-sidecar/target/jwa-sidecar.jar`.

### Point it at a JDK the jar can actually run on

The sidecar is compiled for **Java 25**, while this machine's `PATH` `java` is 1.8 and `JAVA_HOME` is 21, so the
default chain fails with a version error and the extension's error message names both fixes. In practice set the
binary explicitly (this is the mechanism Phase 0 proved, and it needs no rebuild):

```jsonc
// settings.json — see settings-snippet.json in this directory
"lsp": {
  "webview-sidecar": {
    "binary": {
      "path": "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe",
      "arguments": ["-jar", "D:\\wrk\\java\\jcodebuddy\\webview\\jwa-sidecar\\target\\jwa-sidecar.jar"]
    }
  }
}
```

Two environment variables do the same job for a checkout that should work without settings:
`JCB_WEBVIEW_SIDECAR` (a binary to run with `--lsp`) and `JCB_WEBVIEW_JAVA` (the `java` executable to use).

## Constraints worth knowing before relying on it

- **Zed's worktree-trust gate runs first.** Zed logs `Waiting for worktree "…" to be trusted, before starting
  language server …`; nothing starts until the worktree is trusted, so no host may assume the LSP channel is
  live the moment a project opens (plan §6.1 already treats it as a capability, not a given).
- **One more server, not a replacement.** This adds to a language's server list, so `jdtls` keeps working. It is
  *not* the adapter-hijack trick — that one works too (Phase 0 §A) but would displace the project's real Java
  language server.
- **To serve another language**, add it to `extension.toml`:
  `languages = ["Java", "TypeScript"]`, and make sure that language's name matches what its own extension
  declares (a name only an extension can provide, which is the whole point of this file).
- **A `null` answer is not enough for `experimental/runnables`.** Zed asks Rust-scoped servers for it and treats
  a non-array answer as an error (Phase 0 §A); the sidecar's LSP face must answer shaped responses.
- Verified against Zed **1.21.0** on Windows. Dev extensions are experimental: Zed recompiles this crate on
  install and marks the published version, if any, as overridden.
