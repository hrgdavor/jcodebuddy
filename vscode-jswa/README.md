# JSWA VS Code Extension

Interactive JavaScript/TypeScript Sidecar for Signals and Boilerplate reduction.

## Features
- **LSP Integration**: Powered by [jswa-core](../jswa-core) running on Bun.
- **Signals Support**: snippets and validation for custom signal patterns.
- **High Performance**: Instant startup and low memory footprint thanks to Bun.

## Configuration

This extension follows standard discovery best practices. You can configure the Bun runtime via:

1. **Extension Setting**: Set `jswa.bun.path` in your VS Code `settings.json` to the absolute path of your Bun executable (or directory containing it).
2. **System Path**: As a fallback, it will attempt to use `bun` or `bun.exe` from your system `PATH`.

Example `settings.json`:
```json
{
    "jswa.bun.path": "C:\\Users\\MyUser\\.bun\\bin\\bun.exe"
}
```

## Development

1. Open this folder in VS Code.
2. Run `npm install`.
3. Press `F5` to start a new VS Code window with the extension loaded.
4. Open a JS/TS project. The JSWA Sidecar will start automatically when a `.ts` or `.js` file is opened.
