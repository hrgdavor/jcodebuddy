// Every relative link in the new documents must resolve. Run from the repo root:
//   node webview/examples/check-docs.mjs
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve, relative } from "node:path";

const ROOT = process.cwd();
const DOCS = [
  "webview/README.md",
  "webview/doc/webview-page-authoring.md",
  "webview/examples/README.md",
];

let broken = 0;
let checked = 0;

for (const doc of DOCS) {
  const file = resolve(ROOT, doc);
  if (!existsSync(file)) { console.log(`MISSING document: ${doc}`); broken += 1; continue; }
  const text = readFileSync(file, "utf8");
  const links = [...text.matchAll(/\]\(([^)\s]+)\)/g)].map((m) => m[1]);

  for (const link of links) {
    if (/^[a-z]+:/i.test(link) || link.startsWith("#")) continue;   // external URL or in-page anchor
    const target = resolve(dirname(file), link.split("#")[0]);
    checked += 1;
    if (!existsSync(target)) {
      console.log(`BROKEN  ${doc}  ->  ${link}`);
      broken += 1;
    }
  }
  console.log(`${doc}: ${links.length} link(s) checked`);
}

console.log(broken === 0 ? `\nALL ${checked} DOC LINKS OK` : `\n${broken} BROKEN`);
process.exit(broken === 0 ? 0 : 1);
