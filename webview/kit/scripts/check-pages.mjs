#!/usr/bin/env node
// check-pages.mjs — verify a generated page before it is committed.
//
//   node scripts/check-pages.mjs [--site <dir>] [--root <dir>] [--page <substr>] [--quiet]
//
// A page is a build output, so test it like one. This checker knows nothing about your
// project except two directories, and it is meant to be kept in your build:
//
//   --site   the directory the pages live in (default: the examples beside this script)
//   --root   the project root the pages link into (default: the directory holding the site)
//   --page   check only pages whose path contains this substring (repeatable)
//
// Five checks, and every one of them exists because it has a silent failure mode:
//
//   1. every script parses        — each inline <script> and each referenced .js asset,
//                                   plus every local asset a page references must exist
//   2. every data-open resolves   — against the page's OWN link base, and where
//                                   data-member is claimed the member must be at or after
//                                   the claimed line
//   3. every claimed line exists  — data-line beyond the end of the file is a stale link
//   4. every page link resolves   — each local href lands on a file on disk
//   5. nothing is loaded off-box  — no https:// asset, no CSS @import, no absolute path
//
// The output is explicit `ok` / `FAIL` lines and a final count, and the exit code is
// non-zero when anything failed. Nothing is mocked and no browser is needed; the
// browser half of verifying a page (does it highlight?) is `examples/smoke-test.mjs`.
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync, mkdirSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { tmpdir } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const KIT = resolve(HERE, "..");

// ---------------------------------------------------------------- arguments

const args = process.argv.slice(2);
const pages = [];
let site = null;
let root = null;
let quiet = false;

for (let index = 0; index < args.length; index += 1) {
  const flag = args[index];
  if (flag === "--site") { site = resolve(args[index += 1]); }
  else if (flag === "--root") { root = resolve(args[index += 1]); }
  else if (flag === "--page") { pages.push(args[index += 1]); }
  else if (flag === "--quiet") { quiet = true; }
  else if (flag === "--help" || flag === "-h") {
    console.log("usage: node scripts/check-pages.mjs [--site <dir>] [--root <dir>] [--page <substr>] [--quiet]");
    process.exit(0);
  } else {
    console.error(`unknown argument: ${flag}`);
    process.exit(2);
  }
}

site = site || join(KIT, "examples", "with-assets");
root = root || dirname(site);

if (!existsSync(site)) { console.error(`no such site directory: ${site}`); process.exit(2); }
if (!existsSync(root)) { console.error(`no such project root: ${root}`); process.exit(2); }

const SKIP_DIRS = new Set(["node_modules", ".git", "target", "build", "out", ".jcodebuddy"]);

let failures = 0;
const pass = (message) => { if (!quiet) console.log(`  ok   ${message}`); };
const fail = (message) => { failures += 1; console.log(`  FAIL ${message}`); };
const note = (message) => console.log(`  note ${message}`);
const section = (title) => { if (!quiet) console.log(`\n${title}`); };

// ---------------------------------------------------------------- HTML scanning

/** HTML comments are not content: they hold templates, samples and disabled markup. */
const stripComments = (html) => html.replace(/<!--[\s\S]*?-->/g, "");

/** `<pre>` blocks are demo content, never wiring — a code sample may contain anything. */
const stripSamples = (html) => html.replace(/<pre\b[\s\S]*?<\/pre>/gi, "");

/** Every tag with its raw attribute text. */
function tags(html, name) {
  const found = [];
  const re = new RegExp(`<${name}\\b((?:"[^"]*"|'[^']*'|[^>"'])*?)\\/?>`, "gi");
  let match;
  while ((match = re.exec(html)) !== null) found.push({ raw: match[0], attrs: attributes(match[1]) });
  return found;
}

function attributes(raw) {
  const attrs = {};
  const re = /([a-zA-Z_:][\w:.-]*)\s*=\s*"([^"]*)"/g;
  let match;
  while ((match = re.exec(raw)) !== null) attrs[match[1].toLowerCase()] = match[2];
  return attrs;
}

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

const allPages = walk(site)
  .filter((file) => /\.html?$/i.test(file))
  .filter((file) => pages.length === 0 || pages.some((needle) => file.includes(needle)))
  .sort();

if (allPages.length === 0) { console.error(`no HTML pages under ${site}`); process.exit(2); }

/**
 * The project root a page links into.
 *
 * Two spellings, and the script tag wins, exactly as the kit's client resolves it:
 *   - `data-link-base` on a `<script src="…">`  -> resolved against the SCRIPT's own URL,
 *     so one value is correct for a page at every depth;
 *   - `data-link-base` on `<body>`              -> resolved against the page.
 */
function linkBaseOf(html, pageFile) {
  for (const script of tags(html, "script")) {
    if (!script.attrs.src || script.attrs["data-link-base"] === undefined) continue;
    const scriptFile = resolve(dirname(pageFile), script.attrs.src);
    return resolve(dirname(scriptFile), script.attrs["data-link-base"] || ".");
  }
  const body = tags(html, "body").find((tag) => tag.attrs["data-link-base"] !== undefined);
  if (body) return resolve(dirname(pageFile), body.attrs["data-link-base"] || ".");
  return dirname(pageFile);
}

const isAbsolute = (target) =>
  /^[A-Za-z]:[\\/]/.test(target) || target.startsWith("/") || target.includes("://");

/**
 * A URL, and not a path that merely contains a colon.
 *
 * `foo:12` is a line reference and `src/Example.java` is a path; treating either as a
 * URL is how a checker stops checking. Only an explicit scheme separator, a mailto:
 * or a data: URL counts.
 */
const isUrl = (target) =>
  /^[a-z][a-z0-9+.-]*:\/\//i.test(target) || /^(mailto|data|tel):/i.test(target);

/** A page-relative target -> the absolute path the host is asked for. */
function absoluteTarget(target, base) {
  if (!target || isAbsolute(target)) return target;
  const joined = resolve(base, decodeURIComponent(target.split("?")[0].split("#")[0]));
  return joined;
}

// ---------------------------------------------------------------- 1. scripts and assets

section("1. every script parses, every local asset exists");

const scratch = join(tmpdir(), `check-pages-${process.pid}`);
mkdirSync(scratch, { recursive: true });

const seenScripts = new Set();

for (const page of allPages) {
  const label = relative(root, page).replace(/\\/g, "/");
  const html = stripComments(readFileSync(page, "utf8"));

  // Inline scripts: written out and handed to the runtime's own parser.
  const inline = [...html.matchAll(/<script\b((?:"[^"]*"|'[^']*'|[^>"'])*?)>([\s\S]*?)<\/script>/gi)];
  inline.forEach((match, index) => {
    if (attributes(match[1]).src) return;
    const code = match[2].trim();
    if (!code) return;
    const file = join(scratch, `${label.replace(/\W+/g, "-")}-${index}.mjs`);
    writeFileSync(file, code);
    const result = spawnSync(process.execPath, ["--check", file], { encoding: "utf8" });
    if (result.status === 0) pass(`${label}: inline <script> #${index + 1} parses`);
    else fail(`${label}: inline <script> #${index + 1}: ${(result.stderr || "").split("\n").slice(1, 4).join(" ").trim()}`);
  });

  // Referenced assets must exist, and a .js asset must parse.
  for (const tag of [...tags(html, "script"), ...tags(html, "link"), ...tags(html, "img")]) {
    const src = tag.attrs.src || tag.attrs.href;
    if (!src || /^(https?:)?\/\//i.test(src) || src.startsWith("data:")) continue;
    const target = resolve(dirname(page), decodeURIComponent(src.split("?")[0].split("#")[0]));
    if (!existsSync(target)) { fail(`${label}: missing asset ${src}`); continue; }
    if (/\.m?js$/i.test(src)) {
      if (seenScripts.has(target)) continue;
      seenScripts.add(target);
      const result = spawnSync(process.execPath, ["--check", target], { encoding: "utf8" });
      if (result.status === 0) pass(`${relative(root, target).replace(/\\/g, "/")} parses`);
      else fail(`${relative(root, target).replace(/\\/g, "/")}: ${(result.stderr || "").split("\n").slice(1, 4).join(" ").trim()}`);
    }
  }
}

// ---------------------------------------------------------------- 2/3. data-open links

section("2. every data-open resolves, and every claimed line is real");

let links = 0;
const lineCache = new Map();

function linesOf(file) {
  if (!lineCache.has(file)) lineCache.set(file, readFileSync(file, "utf8").split(/\r\n|\r|\n/));
  return lineCache.get(file);
}

for (const page of allPages) {
  const label = relative(root, page).replace(/\\/g, "/");
  const html = stripSamples(stripComments(readFileSync(page, "utf8")));
  const base = linkBaseOf(readFileSync(page, "utf8"), page);

  for (const element of elementsWithOpen(html)) {
    links += 1;
    const raw = element.attrs["data-open"];
    const line = Number.parseInt(element.attrs["data-line"] || "1", 10) || 1;
    const member = element.attrs["data-member"] || "";
    const target = absoluteTarget(raw, base);

    if (!target) { fail(`${label}: an element carries data-open with no value`); continue; }
    if (isUrl(target)) {
      // A URL target is navigation, not a project location: nothing to verify on disk.
      pass(`${label}: ${raw} (url target)`);
      continue;
    }
    if (!existsSync(target)) { fail(`${label}: data-open="${raw}" does not exist -> ${target}`); continue; }
    if (!statSync(target).isFile()) { fail(`${label}: data-open="${raw}" is not a file -> ${target}`); continue; }

    const text = linesOf(target);
    if (line > text.length) {
      fail(`${label}: data-open="${raw}" claims line ${line} of a ${text.length}-line file`);
      continue;
    }
    if (member) {
      // The member must be ON the claimed line. Pointing at a section heading
      // instead is the mistake this rejects: it looks right, and it is false the
      // moment anything is inserted above the member. A reader who wants the
      // section should link without `data-member` and a link that says so.
      const claimed = text[line - 1];
      if (!claimed.includes(member)) {
        fail(`${label}: data-open="${raw}" claims "${member}" on line ${line},`
          + ` but that line is: ${claimed.trim().slice(0, 70) || "(blank)"}`);
        continue;
      }
      if (/^\s*#{1,6}\s/.test(claimed)) {
        fail(`${label}: data-open="${raw}":${line} resolves to the heading "${claimed.trim().slice(0, 60)}"`
          + ` — point at the line that holds the member, not at the section title`);
        continue;
      }
      pass(`${label}: ${raw}:${line} names ${member}`);
    } else {
      pass(`${label}: ${raw}:${line}`);
    }
  }
}

/** Every element that carries data-open, with its attributes. */
function elementsWithOpen(html) {
  const found = [];
  const re = /<([a-zA-Z][a-zA-Z0-9-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  let match;
  while ((match = re.exec(html)) !== null) {
    const attrs = attributes(match[2]);
    if (attrs["data-open"] !== undefined) found.push({ tag: match[1], attrs });
  }
  return found;
}

// ---------------------------------------------------------------- 4. ordinary page links

section("4. every ordinary page link resolves on disk");

for (const page of allPages) {
  const label = relative(root, page).replace(/\\/g, "/");
  const html = stripComments(readFileSync(page, "utf8"));
  for (const anchor of tags(html, "a")) {
    const href = anchor.attrs.href;
    if (!href || href.startsWith("#") || /^[a-z][a-z0-9+.-]*:/i.test(href)) continue;
    const target = resolve(dirname(page), decodeURIComponent(href.split("?")[0].split("#")[0]));
    if (existsSync(target)) pass(`${label}: ${href}`);
    else fail(`${label}: href="${href}" does not exist -> ${target}`);
  }
}

// ---------------------------------------------------------------- 5. offline and portable

section("5. nothing is loaded off-box and no absolute path is baked in");

for (const page of allPages) {
  const label = relative(root, page).replace(/\\/g, "/");
  const raw = readFileSync(page, "utf8");
  const html = stripComments(raw);

  // Remote assets: a page must be openable with no server and no network.
  const remote = [...html.matchAll(/<(?:script|link|img)\b[^>]*(?:src|href)\s*=\s*"(https?:)?\/\/[^"]+"/gi)]
    .map((match) => match[0]);
  if (remote.length === 0) pass(`${label}: no remote asset`);
  else for (const tag of remote) fail(`${label}: remote asset ${tag.slice(0, 90)}`);

  if (/@import\s+(?:url\()?["']?https?:/i.test(html)) fail(`${label}: CSS @import from a remote URL`);

  // data-open is the wiring: an absolute path there is wrong on every machine but one.
  for (const element of elementsWithOpen(stripSamples(html))) {
    const target = element.attrs["data-open"];
    if (isAbsolute(target)) fail(`${label}: data-open="${target}" is an absolute path`);
  }

  // A data-link-base that is an absolute path defeats the point of having one.
  for (const tag of [...tags(html, "script"), ...tags(html, "body")]) {
    const value = tag.attrs["data-link-base"];
    if (value !== undefined && isAbsolute(value)) fail(`${label}: data-link-base="${value}" is absolute`);
  }
}

rmSync(scratch, { recursive: true, force: true });

// ---------------------------------------------------------------- result

console.log(`\n${allPages.length} page(s), ${links} location link(s) checked.`);
if (failures === 0) {
  console.log("ALL PAGES OK");
  process.exit(0);
}
console.log(`${failures} FAILURE(S)`);
process.exit(1);
