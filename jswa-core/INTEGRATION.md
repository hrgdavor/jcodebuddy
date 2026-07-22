# JSWA Multi-Editor Integration Guide

The JSWA Sidecar is a Language Server (LSP) that can be used by any editor supporting the Language Server Protocol.

## LSP Server Information
- **Executable**: `bun` (or `bun.exe` on Windows)
- **Main Script**: `jswa-core/index.ts`
- **Recommended Command**: `bun run /path/to/jswa-core/index.ts`

---

## 1. Visual Studio Code
Use the provided `vscode-jswa` extension.
1. Build: `cd vscode-jswa && npm install`
2. Configuration: Set `jswa.bun.path` in `settings.json` if Bun is not in your PATH.

---

## 2. IntelliJ IDEA
Use the provided `intellij-jswa` module.
1. Build: `cd intellij-jswa && ./gradlew buildPlugin`
2. Install: `Settings > Plugins > Install Plugin from Disk`
3. Configuration: The plugin provides an LSP bridge for JS/TS/JSX/TSX files.

---

## 3. Zed Editor
Add the following to your `settings.json` in Zed:

```json
{
  "lsp": {
    "jswa-sidecar": {
      "binary": {
        "path": "bun",
        "arguments": ["run", "/absolute/path/to/java_watch2/jswa-core/index.ts"]
      }
    }
  },
  "languages": {
    "JavaScript": {
      "language_servers": ["jswa-sidecar", "!typescript-language-server"]
    },
    "TypeScript": {
      "language_servers": ["jswa-sidecar", "!typescript-language-server"]
    }
  }
}
```

---

## 4. Neovim (using nvim-lspconfig)
Add this to your `init.lua`:

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

if not configs.jswa_sidecar then
  configs.jswa_sidecar = {
    default_config = {
      cmd = { 'bun', 'run', '/absolute/path/to/java_watch2/jswa-core/index.ts' },
      filetypes = { 'javascript', 'typescript', 'javascriptreact', 'typescriptreact' },
      root_dir = lspconfig.util.root_pattern('package.json', '.git'),
      settings = {},
    },
  }
end

lspconfig.jswa_sidecar.setup({})
```

---

## 5. Other Editors (Sublime, Emacs, etc.)
Configure your LSP client to launch:
`bun run /path/to/jswa-core/index.ts`
The server uses standard JSON-RPC over stdin/stdout.
