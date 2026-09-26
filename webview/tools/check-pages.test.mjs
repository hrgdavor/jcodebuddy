#!/usr/bin/env node
// check-pages.test.mjs — the page verifier's own test.
//
//   node webview/tools/check-pages.test.mjs
//
// WHY THIS EXISTS. The verifier is what stands between a generator and a page full
// of links that look right and go nowhere. A verifier that silently passes is worse
// than none, because it is trusted. So this test builds fixture sites that are wrong
// in exactly the five ways the checker claims to catch, runs the checker on each, and
// asserts both the exit code and the diagnostic. It also runs it on a correct site,
// because a checker that fails everything is equally useless.
//
// No dependencies, no browser: the checker needs neither.
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, relative, resolve } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const CHECKER = resolve(HERE, "..", "kit", "scripts", "check-pages.mjs");
const WORK = mkdtempSync(join(tmpdir(), "check-pages-test-"));

let passed = 0;
let failed = 0;
function check(name, condition, detail = "") {
  if (condition) { passed += 1; console.log(`ok   ${name}`); }
  else { failed += 1; console.log(`FAIL ${name}${detail ? ` — ${detail}` : ""}`); }
}

/** Write a fixture project and return its root. */
function fixture(name, { source, page, doc }) {
  const root = join(WORK, name);
  mkdirSync(join(root, "site"), { recursive: true });
  if (source !== undefined) {
    mkdirSync(join(root, "src"), { recursive: true });
    writeFileSync(join(root, "src", "Example.java"), source, { encoding: "utf8" });
  }
  if (doc !== undefined) {
    mkdirSync(join(root, "doc"), { recursive: true });
    writeFileSync(join(root, "doc", "guide.md"), doc, { encoding: "utf8" });
  }
  writeFileSync(join(root, "site", "index.html"), page, { encoding: "utf8" });
  return root;
}

function run(root, site = "site") {
  // Deliberately not --quiet: the diagnostics are what half these assertions read.
  const result = spawnSync(process.execPath, [
    CHECKER, "--site", join(root, site), "--root", root,
  ], { encoding: "utf8" });
  return { status: result.status, output: `${result.stdout || ""}${result.stderr || ""}` };
}

const BASE_CSS = "";   // an inline stylesheet keeps every fixture self-contained

function page(body, linkBase = "..") {
  return `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>fixture</title>
<style>[data-open]{cursor:pointer}${BASE_CSS}</style></head>
<body data-link-base="${linkBase}">
${body}
<script>document.addEventListener('click', function (event) {
  var link = event.target.closest && event.target.closest('[data-open]');
  if (!link) { return; }
  event.preventDefault();
  if (typeof window.openFile === 'function') { window.openFile(link.dataset.open, link.dataset.line, 1); }
});</script>
</body></html>
`;
}

// ---------------------------------------------------------------- a correct site passes

{
  const root = fixture("good", {
    source: "public class Example {\n    private String firstName;\n}\n",
    page: page(`<span data-open="src/Example.java" data-line="2" data-member="firstName">firstName</span>
<p><a href="index.html">index</a></p>`),
  });
  const result = run(root);
  check("a correct site passes", result.status === 0, `status ${result.status}: ${result.output.trim()}`);
  check("a correct site reports its count", /1 page\(s\), 1 location link\(s\)/.test(result.output), result.output.trim());
}

// ---------------------------------------------------------------- each failure mode

{
  const root = fixture("missing-file", {
    page: page(`<span data-open="src/Nowhere.java" data-line="1">Nowhere</span>`),
  });
  const result = run(root);
  check("an unresolved data-open fails", result.status === 1);
  check("...and says which target is missing", /data-open="src\/Nowhere\.java" does not exist/.test(result.output), result.output.trim());
}

{
  const root = fixture("stale-line", {
    source: "public class Example {\n}\n",
    page: page(`<span data-open="src/Example.java" data-line="99">past the end</span>`),
  });
  const result = run(root);
  check("a line past the end of the file fails", result.status === 1);
  check("...and says the file is shorter", /claims line 99 of a 3-line file/.test(result.output), result.output.trim());
}

{
  const root = fixture("wrong-member", {
    source: "public class Example {\n    private String lastName;\n}\n",
    page: page(`<span data-open="src/Example.java" data-line="2" data-member="firstName">firstName</span>`),
  });
  const result = run(root);
  check("a member that is not on the claimed line fails", result.status === 1);
  check("...and quotes the line it found", /claims "firstName" on line 2/.test(result.output), result.output.trim());
}

{
  const root = fixture("heading-member", {
    doc: "# member\n\nthe member is down here\n",
    page: page(`<span data-open="doc/guide.md" data-line="1" data-member="member">a section</span>`),
  });
  const result = run(root);
  check("a data-member pointing at a Markdown heading fails", result.status === 1);
  check("...and explains which rule it broke", /point at the line that holds the member/.test(result.output), result.output.trim());
}

{
  const root = fixture("absolute-path", {
    source: "public class Example {\n}\n",
    page: page(`<span data-open="/home/someone/project/src/Example.java" data-line="1">Example</span>`),
  });
  const result = run(root);
  check("an absolute data-open fails", result.status === 1);
  check("...and is reported as absolute", /is an absolute path/.test(result.output), result.output.trim());
}

{
  const root = fixture("remote-asset", {
    page: page(`<span data-open="index.html" data-line="1">self</span>
<script src="https://cdn.example.com/thing.js"></script>`),
  });
  const result = run(root);
  check("a remote asset fails", result.status === 1);
  check("...and is reported as remote", /remote asset/.test(result.output), result.output.trim());
}

{
  const root = fixture("broken-href", {
    source: "public class Example {\n}\n",
    page: page(`<span data-open="src/Example.java" data-line="1">Example</span>
<p><a href="pages/gone.html">a page that is not there</a></p>`),
  });
  const result = run(root);
  check("a local href that does not resolve fails", result.status === 1);
  check("...and names the href", /href="pages\/gone\.html" does not exist/.test(result.output), result.output.trim());
}

{
  const root = fixture("syntax-error", {
    source: "public class Example {\n}\n",
    page: `<!DOCTYPE html><html><head><meta charset="utf-8"></head>
<body data-link-base="..">
<span data-open="src/Example.java" data-line="1">Example</span>
<script>function broken( { </script>
</body></html>`,
  });
  const result = run(root);
  check("an inline script that does not parse fails", result.status === 1);
  check("...and reports the compile error", /inline <script>/.test(result.output), result.output.trim());
}

// ---------------------------------------------------------------- the script-link spelling

{
  // A page whose link base sits on a `<script src>` is what the kit recommends, so the
  // checker must resolve it against the SCRIPT and not the page: one value then serves a
  // page at the site root and a page a folder below it.
  //
  // Two things here are deliberate. The project is nested two folders inside the temp
  // area, because at a temp root `path.resolve` quietly clamps a surplus `..` and a
  // fixture that cannot express the mistake is no test. And the declaration is *derived*
  // from the two directories rather than written out, so the fixture cannot silently
  // drift into testing a wrong value — which is how this test was written the first time.
  const project = join(WORK, "script-base", "a", "b");
  mkdirSync(join(project, "site", "deep"), { recursive: true });
  mkdirSync(join(project, "site", "assets"), { recursive: true });
  mkdirSync(join(project, "src"), { recursive: true });
  writeFileSync(join(project, "src", "Example.java"), "public class Example {\n}\n");
  writeFileSync(join(project, "site", "assets", "client.js"), "(function(){})();\n");

  const assets = join(project, "site", "assets");
  const levels = relative(assets, project).split(/[\\/]/).filter(Boolean).length;
  const declared = Array(levels).fill("..").join("/");
  writeFileSync(join(project, "site", "deep", "page.html"), `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"></head><body>
<span data-open="src/Example.java" data-line="1">Example</span>
<script id="nav-client" src="../assets/client.js" data-link-base="${declared}"></script>
</body></html>
`);

  const result = run(project, "site/deep");
  check(`a script-relative link base (${declared}) resolves from a nested page`,
    result.status === 0, `status ${result.status}: ${result.output.trim()}`);
}

// ------------------------------------------------- a wrong link base is caught

{
  // One level short, which is the mistake a person actually makes. It is caught only
  // because the project is not at a filesystem root: `..` above the root is clamped, so
  // a page that resolves to the same tree by accident would hide the error.
  const project = join(WORK, "short-base", "a", "b");
  mkdirSync(join(project, "site", "assets"), { recursive: true });
  mkdirSync(join(project, "src"), { recursive: true });
  writeFileSync(join(project, "src", "Example.java"), "public class Example {\n}\n");
  writeFileSync(join(project, "site", "assets", "client.js"), "(function(){})();\n");
  const levels = relative(join(project, "site", "assets"), project).split(/[\\/]/).filter(Boolean).length;
  const oneShort = Array(levels - 1).fill("..").join("/");
  writeFileSync(join(project, "site", "page.html"), `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"></head><body>
<span data-open="src/Example.java" data-line="1">Example</span>
<script id="nav-client" src="assets/client.js" data-link-base="${oneShort}"></script>
</body></html>
`);
  const result = run(project, "site");
  check("a link base one level short fails", result.status === 1, `status ${result.status}`);
  check("...and shows the path it resolved to", /does not exist/.test(result.output), result.output.trim());
}

rmSync(WORK, { recursive: true, force: true });

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
