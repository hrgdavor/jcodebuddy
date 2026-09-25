// Verify every relative Markdown link in the repository resolves on disk.
//
//   node check-repo-links.mjs            # summary + every broken link
//   node check-repo-links.mjs --list     # also list files with no links
//
// Skips derived output (.jcodebuddy/, node_modules, build dirs, .git) and
// external URLs, mailto: and pure in-page anchors. A link with a #fragment is
// checked on its file part; the fragment is not resolved to a heading.
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, relative, dirname, resolve } from "node:path";

const ROOT = process.cwd();
const LIST = process.argv.includes("--list");

const SKIP_DIRS = new Set([
  ".git", "node_modules", "build", "out", "target", ".gradle", ".idea",
  ".intellijPlatform", ".kotlin", ".mvn-local-repo", ".mvnd-home", ".vscode-test",
  // Agent workspaces: .kilo holds plans and git worktrees, which are separate
  // checkouts with their own (often pre-existing) link state. Not this repo's content.
  ".kilo", ".jcodebuddy",
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
 * Remove the parts of a Markdown file where `](…)` is not a link.
 *
 * A document that *documents* link syntax contains the characters `](` inside
 * prose and fenced samples — `data-link-base` on a `<script …></script>`, or a
 * table cell reading `` `assets/nav-client.js` ``, or the ellipsis `…` in a
 * diagram. A naive scan reports those as broken links, which trains a reader to
 * ignore the checker. Fences, inline code and HTML comments are therefore
 * removed before the scan.
 */
function stripNonLinks(text) {
  return text
    .replace(/^[ \t]*(```|~~~)[\s\S]*?^[ \t]*\1[ \t]*$/gm, "")   // fenced blocks
    .replace(/`[^`\n]*`/g, "")                                    // inline code spans
    .replace(/<!--[\s\S]*?-->/g, "");                             // HTML comments
}

const files = walk(ROOT).filter((f) => {
  if (!/\.(md|markdown)$/i.test(f)) return false;
  if (f.includes(".jcodebuddy/") || f.includes(".jcodebuddy\\")) return false;
  return true;
});

const broken = [];
let totalLinks = 0;
let filesWithLinks = 0;

for (const file of files) {
  const text = stripNonLinks(readFileSync(file, "utf8"));
  const label = relative(ROOT, file).replace(/\\/g, "/");
  let links = 0;

  // Markdown links, reference definitions and HTML href/src inside the file.
  const candidates = [
    ...[...text.matchAll(/\]\(\s*([^)\s]+?)\s*\)/g)].map((m) => m[1]),
    ...[...text.matchAll(/^\[[^\]]+\]:\s*(\S+)/gm)].map((m) => m[1]),
    ...[...text.matchAll(/\b(?:href|src)="([^"]+)"/g)].map((m) => m[1]),
  ];

  for (const raw of candidates) {
    const target = raw.replace(/^<|>$/g, "");
    if (/^[a-z][a-z0-9+.-]*:/i.test(target)) continue;   // http:, https:, mailto:, data:
    if (target.startsWith("#")) continue;                 // in-page anchor
    if (target.startsWith("//")) continue;                // protocol-relative

    const pathPart = target.split("#")[0].split("?")[0];
    if (!pathPart) continue;
    // Decode the few escapes that appear in paths, then resolve.
    const decoded = decodeURIComponent(pathPart);
    const absolute = decoded.startsWith("/")
      ? join(ROOT, decoded)
      : resolve(dirname(file), decoded);

    links += 1;
    totalLinks += 1;
    if (!existsSync(absolute)) broken.push({ file: label, target: raw, resolved: relative(ROOT, absolute).replace(/\\/g, "/") });
  }

  if (links > 0) filesWithLinks += 1;
  else if (LIST) console.log(`  no links: ${label}`);
}

console.log(`\n${files.length} markdown file(s) scanned; ${filesWithLinks} contain ${totalLinks} relative link(s).`);
if (broken.length === 0) {
  console.log("ALL RELATIVE LINKS RESOLVE");
  process.exit(0);
}
console.log(`\n${broken.length} BROKEN:\n`);
for (const entry of broken) console.log(`  ${entry.file}\n      -> ${entry.target}\n      = ${entry.resolved}`);
process.exit(1);
