// Every relative link in the kit's own documents must resolve.
//
//   node scripts/check-docs.mjs            (from the kit root, or any directory)
//
// The kit is meant to be copied into another project, so this checker locates its
// documents relative to *itself* and never from the working directory. That way the
// copy in your project checks the copy in your project.
//
// A link is checked on its file part only; a #fragment is not resolved to a heading.
// External URLs, mailto:, data: and pure in-page anchors are skipped, and fenced code
// blocks, inline code and HTML comments are stripped first, because a document that
// *documents* link syntax contains `](` in prose and reporting those as broken trains
// a reader to ignore the checker.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const KIT = resolve(HERE, "..");

const SKIP_DIRS = new Set(["node_modules", ".git", "target", "build", "out"]);

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (/\.(md|markdown)$/i.test(full)) out.push(full);
  }
  return out;
}

function stripNonLinks(text) {
  return text
    .replace(/^[ \t]*(```|~~~)[\s\S]*?^[ \t]*\1[ \t]*$/gm, "")
    .replace(/`[^`\n]*`/g, "")
    .replace(/<!--[\s\S]*?-->/g, "");
}

const files = walk(KIT);
const broken = [];
let total = 0;

for (const file of files) {
  const label = relative(KIT, file).replace(/\\/g, "/");
  const text = stripNonLinks(readFileSync(file, "utf8"));
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

    const decoded = decodeURIComponent(pathPart);
    const absolute = decoded.startsWith("/") ? join(KIT, decoded) : resolve(dirname(file), decoded);
    total += 1;
    if (!existsSync(absolute)) {
      broken.push(`${label}  ->  ${raw}`);
    }
  }
}

console.log(`${files.length} document(s), ${total} relative link(s) in the kit.`);
if (broken.length > 0) for (const line of broken) console.log(`  BROKEN  ${line}`);
console.log(broken.length === 0 ? "ALL DOC LINKS RESOLVE" : `\n${broken.length} BROKEN LINK(S)`);
process.exit(broken.length === 0 ? 0 : 1);
