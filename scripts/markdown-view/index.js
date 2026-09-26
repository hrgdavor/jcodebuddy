#!/usr/bin/env bun
/**
 * Render every Markdown file of a module into **one self-contained, clickable page**, and write an index
 * page that links them together.
 *
 *     bun run scripts/markdown-view/index.js --module hipster-entity-example
 *     bun run scripts/markdown-view/index.js --module hipster-entity-example --only codebuddy.md,README.md
 *     bun run scripts/markdown-view/index.js --module hipster-entity-example --verify
 *
 * Why it exists: it is the smallest complete example of a **custom document** that navigates the project,
 * and a place to play. Read it together with `webview/kit/doc/contract.md` (the contract) and `README.md`
 * beside it (how the pieces fit). Nothing here is specific to Markdown: the three things a document needs
 * are "resolve a target to `path:line`", "put that on the element as `data-open`/`data-line`", and "emit
 * one file with the click handler inlined".
 *
 * Flags:
 *   --module <dir>       module under the repository root (default: the only one with a .jcodebuddy/)
 *   --out <dir>          output directory (default: <module>/.jcodebuddy/agent-state/markdown-view)
 *   --only <a.md,b.md>   render only these, module-relative
 *   --verify             check every link against the tree and fail on a broken one
 *   --bridge-port <n>    the IDE HTTP bridge port baked into the pages (0 disables it)
 *   --quiet              print only the summary line
 *   --help
 */
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'fs';
import { dirname, join, relative, resolve, sep } from 'path';
import { fileURLToPath } from 'url';

import { DEFAULT_BRIDGE_PORT, PAGE_STYLE, escapeHtml, openFileClientScript } from './open-file.js';
import { classIndexFrom, renderMarkdown } from './render.js';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..', '..');

/** Directories whose Markdown is derived, vendored or generated — never a document a human wrote. */
const SKIP_DIRS = new Set(['node_modules', 'target', 'build', 'dist', 'out', '.git', '.idea', 'coverage']);

/** Where this tool's own output lands, module-relative. Derived, and ignored like the rest of agent-state. */
const DEFAULT_OUT = '.jcodebuddy/agent-state/markdown-view';

const HELP = `markdown-view — render Markdown as clickable pages that navigate the project

Usage:
  bun run scripts/markdown-view/index.js --module <dir> [options]

Options:
  --module <dir>       module under the repository root (default: the only one with a .jcodebuddy/)
  --out <dir>          output directory (default: ${DEFAULT_OUT})
  --only <a.md,b.md>   render only these, module-relative
  --verify             check every emitted link against the tree; a broken link fails the run
  --bridge-port <n>    IDE HTTP bridge port baked into the pages (default ${DEFAULT_BRIDGE_PORT}; 0 disables)
  --quiet              print only the summary line
  --help               this text

The output is one self-contained HTML file per Markdown file plus an index page. It needs no server, no
bundler and no network at view time. Open it in the IDE webview
(right-click -> Open in WebView Explorer) and a click jumps to the file and line; open it in a browser and
it falls back to the IDE's HTTP bridge, or copies the location.
`;

/** Parse the command line. Throws on anything it does not understand rather than guessing. */
export function parseArgs(argv) {
  const options = {
    module: null,
    out: null,
    only: [],
    verify: false,
    bridgePort: DEFAULT_BRIDGE_PORT,
    quiet: false,
    help: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      const next = argv[++i];
      if (next === undefined) {
        throw new Error(`${arg} needs a value`);
      }
      return next;
    };
    if (arg === '--module') options.module = value();
    else if (arg.startsWith('--module=')) options.module = arg.slice('--module='.length);
    else if (arg === '--out') options.out = value();
    else if (arg.startsWith('--out=')) options.out = arg.slice('--out='.length);
    else if (arg === '--only') options.only = value().split(',').map((name) => name.trim()).filter(Boolean);
    else if (arg.startsWith('--only=')) options.only = arg.slice('--only='.length).split(',').map((n) => n.trim()).filter(Boolean);
    else if (arg === '--verify') options.verify = true;
    else if (arg === '--bridge-port') options.bridgePort = Number(value());
    else if (arg.startsWith('--bridge-port=')) options.bridgePort = Number(arg.slice('--bridge-port='.length));
    else if (arg === '--quiet') options.quiet = true;
    else if (arg === '--help' || arg === '-h') options.help = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  if (options.bridgePort !== 0 && !Number.isFinite(options.bridgePort)) {
    throw new Error('--bridge-port must be a number, or 0 to disable the fallback');
  }
  return options;
}

/** The module directory: the named one, or the only child of the repository root that has a `.jcodebuddy/`. */
export function resolveModule(name) {
  if (name) {
    const candidate = resolve(REPO_ROOT, name);
    if (!existsSync(candidate) || !statSync(candidate).isDirectory()) {
      throw new Error(`no module directory at ${candidate}`);
    }
    return candidate;
  }
  const modules = readdirSync(REPO_ROOT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => join(REPO_ROOT, entry.name))
    .filter((directory) => existsSync(join(directory, '.jcodebuddy')));
  if (modules.length !== 1) {
    throw new Error(`expected exactly one module with a .jcodebuddy/ under ${REPO_ROOT}, found `
      + `${modules.length}: ${modules.map((m) => relative(REPO_ROOT, m)).join(', ')}. Pass --module.`);
  }
  return modules[0];
}

/** Every Markdown file of the module, module-relative and sorted, skipping derived directories. */
export function findMarkdown(moduleRoot) {
  const found = [];
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.name.startsWith('.') && entry.isDirectory() && entry.name !== '.jcodebuddy') {
        continue;
      }
      const full = join(directory, entry.name);
      if (entry.isDirectory()) {
        if (!SKIP_DIRS.has(entry.name)) {
          walk(full);
        }
      } else if (entry.name.toLowerCase().endsWith('.md')) {
        found.push(relative(moduleRoot, full).split(sep).join('/'));
      }
    }
  };
  walk(moduleRoot);
  return found.sort();
}

/**
 * Where a target may live, and how it is spelled once it is found.
 *
 * Two roots are searched, in order, and the order decides the spelling:
 *
 *  1. **the module root** — `src/main/java/...`, `.jcodebuddy/...`. This is what the page's link base is,
 *     so a link written this way is short and stays valid when the tree moves;
 *  2. **the repository root** — `scripts/...`, `doc/...`, a sibling module. Module prose genuinely points
 *     at these (this repository's `codebuddy.md` does), and refusing them would leave half of it unlinked.
 *
 * A candidate that escapes its root (`../../etc/passwd`) is rejected: a link out of the project is not a
 * project location, and the emitter would have no base to spell it from.
 */
export function resolveOpenable(target, moduleRoot) {
  if (!target || target.includes('…') || /\s/.test(target)) {
    return null;   // an elided path (`src/main/java/…`) or a sentence, not a target
  }
  const wantsDirectory = target.endsWith('/');
  for (const base of [moduleRoot, REPO_ROOT]) {
    const absolute = resolve(base, target);
    if (absolute !== base && !absolute.startsWith(base + sep)) {
      continue;
    }
    if (!existsSync(absolute)) {
      continue;
    }
    const isDirectory = statSync(absolute).isDirectory();
    // A directory is a target only when the author asked for one with a trailing slash. Prose is full of
    // bare lowercase words (`hipster-entity`), and one of those happening to name a directory would turn a
    // noun into a link to a folder, which is not where a reader wants to land.
    if (isDirectory && !wantsDirectory) {
      continue;
    }
    return { open: target, absolute, isDirectory, moduleRelative: base === moduleRoot };
  }
  return null;
}

/** Parse JSON without throwing, for the optional class indexes. */
function readJson(file) {
  try {
    return readFileSync(file, 'utf8');
  } catch (error) {
    return null;
  }
}

/**
 * The class index this tool consults: the module's own, with the repository's behind it.
 *
 * A type named in prose may belong to either — a module document is mostly about its own types, but it
 * also names types from sibling modules — and the module's table must win on a collision, which is why the
 * merge is ordered rather than a plain union.
 */
export function loadIndex(moduleRoot) {
  const moduleName = moduleRoot.slice(moduleRoot.lastIndexOf(sep) + 1);
  const moduleIndex = classIndexFrom(
    readJson(join(moduleRoot, '.jcodebuddy', 'index', 'classes.json')), moduleName);
  const repoIndex = classIndexFrom(
    readJson(join(REPO_ROOT, '.jcodebuddy', 'index', 'classes.json')), moduleName);
  return moduleIndex.withFallback(repoIndex);
}

/** `a/b/c.html` -> the relative path from that file's directory to the module root. */
function linkBaseFor(moduleRelativeOutput) {
  const depth = moduleRelativeOutput.split('/').length - 1;
  return depth === 0 ? '.' : Array(depth).fill('..').join('/');
}

/**
 * Render one Markdown document into a full page.
 *
 * @returns {{html: string, links: Array, headings: Array}}
 */
export function renderDocument(markdown, options) {
  const { documentPath, moduleRoot, index, outModuleRelative, bridgePort } = options;
  const directory = documentPath.includes('/') ? documentPath.slice(0, documentPath.lastIndexOf('/')) : '';
  const links = [];

  /**
   * Whether a resolved target is something this document may link to.
   *
   * The rule is "resolve or leave alone": a target is openable only when the file (or directory) exists.
   * Without this the tool linkifies prose that merely looks like a path — `.java`, `src/main/java` as a
   * phrase, a `…` elision — and every one of those is a dead link in a page whose whole point is that its
   * links work. The class index is consulted first so a generated artifact the pass wrote is openable even
   * though the file is derived.
   */
  const isOpenable = (candidate) => resolveOpenable(candidate, moduleRoot) !== null;

  const makeLink = (target, text, kind, title) => {
    const record = {
      open: target.open,
      line: target.line,
      member: target.member ?? '',
      role: target.role ?? '',
      from: documentPath,
      kind,
      fromIndex: target.fromIndex === true,
    };
    links.push(record);
    const attributes = `data-open="${escapeHtml(target.open)}" data-line="${target.line}"`
      + (target.member ? ` data-member="${escapeHtml(target.member)}"` : '')
      + (target.role ? ` data-role="${escapeHtml(target.role)}"` : '')
      + ` title="${escapeHtml(title || ('open ' + (target.member || target.open) + ' at line ' + target.line))}"`;
    const body = kind === 'code' ? `<code>${escapeHtml(text)}</code>` : escapeHtml(text);
    return `<a ${attributes}>${body}</a>`;
  };

  const { html, headings } = renderMarkdown(markdown, { directory, index, isOpenable, makeLink });  const base = linkBaseFor(outModuleRelative);
  const title = headings.find((heading) => heading.level === 1)?.text ?? documentPath;

  const nav = headings.length === 0 ? '' : `<nav id="nav"><div class="nav-title">On this page</div>`
    + headings.map((heading) => `<a class="lvl-${Math.min(heading.level, 3)}" href="#${heading.id}">`
      + `${escapeHtml(heading.text)}</a>`).join('')
    + '</nav>';

  const page = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(title)}</title>
<style>${PAGE_STYLE}</style>
</head>
<body data-link-base="${escapeHtml(base)}" data-bridge-port="${escapeHtml(bridgePort)}">
<header class="top">
  <h1>${escapeHtml(title)} <span>&#183; ${escapeHtml(index.moduleName)}</span></h1>
  <div class="top-sub">
    <span>${escapeHtml(documentPath)}</span>
    <span>${links.length} link(s)</span>
    <span id="bridge-status" class="pill">IDE bridge: checking&#8230;</span>
  </div>
</header>
<div id="layout">
${nav}
<main>
${html}
<p class="source-note">Rendered from <code>${escapeHtml(documentPath)}</code> by
<code>scripts/markdown-view</code>. A click on a code identifier or a link opens the file and line in the
IDE; see <code>webview/kit/doc/contract.md</code> for the contract.</p>
</main>
</div>
<footer class="bottom">Generated by <code>scripts/markdown-view/index.js</code> — derived output, safe to
delete and regenerate. Links resolve through the module's class index
(<code>.jcodebuddy/index/classes.json</code>, DEC-029).</footer>
<script>${openFileClientScript({ bridgePort })}</script>
</body>
</html>
`;
  return { html: page, links, headings };
}

/** The index page: one card per rendered document, linking to it and listing its top-level sections. */
export function renderContents(documents, options) {
  const { moduleName, bridgePort } = options;
  const cards = documents.map((document) => `
  <article class="view">
    <h2><a href="${escapeHtml(document.out)}">${escapeHtml(document.title)}</a></h2>
    <p class="aspect-path">${escapeHtml(document.path)} &#183; ${document.links} link(s)</p>
    <ul>${document.headings.filter((heading) => heading.level === 2).slice(0, 8)
      .map((heading) => `<li><a href="${escapeHtml(document.out)}#${heading.id}">${escapeHtml(heading.text)}</a></li>`)
      .join('')}</ul>
  </article>`).join('');

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${escapeHtml(moduleName)} — documents</title>
<style>${PAGE_STYLE}
.view { background: var(--surface); border: 1px solid var(--border); border-radius: 10px;
  padding: .8rem 1rem; margin-bottom: .9rem; }
.view h2 { margin: 0 0 .2rem; border: none; font-size: 1.05rem; }
.view ul { margin: .4rem 0 0 1.1rem; }
main { max-width: 46rem; }
</style>
</head>
<body data-link-base="." data-bridge-port="${escapeHtml(bridgePort)}">
<header class="top">
  <h1>${escapeHtml(moduleName)} <span>&#183; documents</span></h1>
  <div class="top-sub"><span>${documents.length} document(s)</span>
  <span id="bridge-status" class="pill">IDE bridge: checking&#8230;</span></div>
</header>
<div id="layout"><main>${cards}
<p class="source-note">Rendered by <code>scripts/markdown-view/index.js</code> from the module's Markdown.
Click a document to read it; inside a document, a click on a code identifier or an internal link opens the
file and line in the IDE.</p>
</main></div>
<script>${openFileClientScript({ bridgePort })}</script>
</body>
</html>
`;
}

/**
 * Check every emitted link against the tree.
 *
 * Two rules, and the second is deliberately narrow:
 *
 *  * every target must exist on disk, always;
 *  * when a link names a member **and** its line came from the class index (the type's own declaration
 *    line), that line must contain the member. A line a document gave explicitly (`…/PersonSummary.java:48`)
 *    is the document's claim, not ours, and this tool cannot know which member the author meant — checking
 *    it would fail on correct links.
 *
 * This is the same rule the entity page's renderer enforces (DEC-027 § 4.2): a link to the wrong line is
 * worse than no link, because it looks like it worked.
 */
export function verifyLinks(links, moduleRoot) {
  const cache = new Map();
  const problems = [];
  for (const link of links) {
    if (/^https?:/i.test(link.open)) {
      continue;
    }
    const found = resolveOpenable(link.open, moduleRoot);
    if (!found) {
      problems.push(`${link.from}: ${link.open} does not exist`);
      continue;
    }
    if (!link.fromIndex || !link.member) {
      continue;
    }
    if (link.line < 1) {
      problems.push(`${link.from}: ${link.open} has line ${link.line}`);
      continue;
    }
    if (!cache.has(found.absolute)) {
      cache.set(found.absolute, readFileSync(found.absolute, 'utf8').split(/\r\n|\n|\r/));
    }
    const lines = cache.get(found.absolute);
    if (link.line > lines.length) {
      problems.push(`${link.from}: ${link.open}:${link.line} is past the end of the file (${lines.length} lines)`);
      continue;
    }
    if (!new RegExp(`\\b${link.member.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(lines[link.line - 1])) {
      problems.push(`${link.from}: ${link.open}:${link.line} does not contain ${link.member}`);
    }
  }
  return problems;
}

/** Run the tool. Returns the process exit code. */
export function main(argv = process.argv.slice(2)) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    console.error(`[markdown-view] ${error.message}`);
    console.error(HELP);
    return 1;
  }
  if (options.help) {
    console.log(HELP);
    return 0;
  }

  let moduleRoot;
  try {
    moduleRoot = resolveModule(options.module);
  } catch (error) {
    console.error(`[markdown-view] ${error.message}`);
    return 1;
  }

  const outDir = resolve(moduleRoot, options.out ?? DEFAULT_OUT);
  const index = loadIndex(moduleRoot);
  const moduleName = moduleRoot.slice(moduleRoot.lastIndexOf(sep) + 1);
  const files = (options.only.length > 0 ? options.only : findMarkdown(moduleRoot))
    .filter((path) => existsSync(join(moduleRoot, path)));

  if (files.length === 0) {
    console.error(`[markdown-view] no Markdown files found under ${moduleRoot}`);
    return 1;
  }

  const rendered = [];
  const allLinks = [];
  for (const documentPath of files) {
    const markdown = readFileSync(join(moduleRoot, documentPath), 'utf8');
    // `README.md` becomes `README.html`; a nested path keeps its directories, so relative links between
    // rendered documents keep working.
    const outModuleRelative = relative(moduleRoot, outDir).split(sep).join('/') + '/'
      + documentPath.replace(/\.md$/i, '.html');
    const result = renderDocument(markdown, {
      documentPath,
      moduleRoot,
      index,
      outModuleRelative,
      bridgePort: options.bridgePort,
    });
    const outFile = resolve(moduleRoot, outModuleRelative);
    mkdirSync(dirname(outFile), { recursive: true });
    writeFileSync(outFile, result.html);
    rendered.push({
      path: documentPath,
      out: outModuleRelative.slice(relative(moduleRoot, outDir).split(sep).join('/').length + 1),
      title: result.headings.find((heading) => heading.level === 1)?.text ?? documentPath,
      headings: result.headings,
      links: result.links.length,
    });
    allLinks.push(...result.links);
  }

  const contents = renderContents(rendered, { moduleName, bridgePort: options.bridgePort });
  writeFileSync(join(outDir, 'index.html'), contents);

  const problems = options.verify ? verifyLinks(allLinks, moduleRoot) : [];
  if (!options.quiet) {
    console.log(`markdown-view: ${moduleName}`);
    for (const document of rendered) {
      console.log(`  ${document.path} -> ${document.out} (${document.links} link(s))`);
    }
    console.log(`  index.html (${rendered.length} document(s))`);
  }
  console.log(`Rendered ${rendered.length} document(s), ${allLinks.length} link(s)`
    + ` (class index: ${index.size} type(s)) -> ${relative(REPO_ROOT, outDir).split(sep).join('/')}`);
  if (options.verify) {
    if (problems.length > 0) {
      console.error(`[markdown-view] ${problems.length} broken link(s):`);
      for (const problem of problems.slice(0, 40)) {
        console.error(`  ${problem}`);
      }
      return 1;
    }
    console.log(`Link check: ${allLinks.length} links verified, 0 problems`);
  }
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
