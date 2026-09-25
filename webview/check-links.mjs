// Verify every relative link inside webview/ resolves on disk.
//
//   node webview/check-links.mjs           # summary + every broken link
//   node webview/check-links.mjs --quiet   # summary only
//
// Covers the two documents, both plugin READMEs (including their Further
// reading links out to the rest of the repository), the examples README, and
// this folder's README.
//
// A link is checked on its file part; a #fragment is not resolved to a heading.
// External URLs, mailto: and pure in-page anchors are skipped. Derived output
// (.jcodebuddy/) and dependency trees are never part of the content.
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { dirname, join, resolve, relative } from "node:path";

const ROOT = process.cwd();
const QUIET = process.argv.includes("--quiet");

const SKIP_DIRS = new Set([
  "node_modules", "build", "out", "target", ".gradle", ".idea",
  ".intellijPlatform", ".kotlin", ".vscode-test", ".jcodebuddy",
]);

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

/**
 * Remove the places where `](…)` is not a link: fenced samples, inline code and
 * HTML comments. A document that *documents* link syntax contains `](` in prose
 * — a table cell reading `` `assets/nav-client.js` ``, or the ellipsis in a
 * diagram — and reporting those as broken trains a reader to ignore the checker.
 */
function stripNonLinks(text) {
  return text
    .replace(/^[ \t]*(```|~~~)[\s\S]*?^[ \t]*\1[ \t]*$/gm, "")
    .replace(/`[^`\n]*`/g, "")
    .replace(/<!--[\s\S]*?-->/g, "");
}

const files = walk(join(ROOT, "webview")).filter((f) => /\.(md|markdown)$/i.test(f));

const broken = [];
let totalLinks = 0;

for (const file of files) {
  const text = stripNonLinks(readFileSync(file, "utf8"));
  const label = relative(ROOT, file).replace(/\\/g, "/");

  const candidates = [
    ...[...text.matchAll(/\]\(\s*([^)\s]+?)\s*\)/g)].map((m) => m[1]),
    ...[...text.matchAll(/^\[[^\]]+\]:\s*(\S+)/gm)].map((m) => m[1]),
  ];

  for (const raw of candidates) {
    const target = raw.replace(/^<|>$/g, "");
    if (/^[a-z][a-z0-9+.-]*:/i.test(target)) continue;   // http:, https:, mailto:, data:
    if (target.startsWith("#") || target.startsWith("//")) continue;
    const pathPart = target.split("#")[0].split("?")[0];
    if (!pathPart) continue;

    const absolute = decodeURIComponent(pathPart).startsWith("/")
      ? join(ROOT, decodeURIComponent(pathPart))
      : resolve(dirname(file), decodeURIComponent(pathPart));

    totalLinks += 1;
    if (!existsSync(absolute)) {
      broken.push(`${label}  ->  ${raw}  =  ${relative(ROOT, absolute).replace(/\\/g, "/")}`);
    }
  }
}

console.log(`${files.length} markdown file(s), ${totalLinks} relative link(s) in webview/.`);
if (broken.length === 0) {
  console.log("ALL LINKS RESOLVE");
  process.exit(0);
}
if (!QUIET) for (const line of broken) console.log(`  BROKEN  ${line}`);
console.log(`\n${broken.length} BROKEN LINK(S)`);
process.exit(1);
