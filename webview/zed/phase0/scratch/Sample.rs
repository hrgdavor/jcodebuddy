// Phase 0 scratch file for the rust-analyzer adapter override.
//
// The repo's .zed/settings.json points lsp.rust-analyzer.binary.path at node + stub-lsp.mjs, so
// opening this file makes Zed start the stub instead of rust-analyzer. The stub then asks Zed for
// window/showDocument (caret to the marked line) and applies one workspace/applyEdit.

fn main() {
    // CARET TARGET: the stub asks for line 10, column 5 of this file.
    println!("webview phase 0");
}
