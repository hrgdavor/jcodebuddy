#!/usr/bin/env bun
/**
 * `entity-html-index` — renders an interactive HTML reference of a module's entities from the
 * generator's JSON metadata, with every field clickable through to the exact source line of every
 * generated aspect (DEC-027).
 *
 * Usage:
 *
 *   bun run scripts/entity-html/index.js                         # hipster-entity-example
 *   bun run scripts/entity-html/index.js --module <dir>
 *   bun run scripts/entity-html/index.js --metadata <dir> --source-root <dir> --link-base <dir> --out <file>
 *   bun run scripts/entity-html/index.js --packages a.b,c.d --bridge-port 18881 --soft
 *
 * The default invocation is deliberately zero-configuration for this repository: the module's
 * `.jcodebuddy/metadata/entity` directory holds the JSON a generator pass writes, `src/main/java` is
 * the source root, the module root is the link base, and the page lands beside the JSON as
 * `index.html` (DEC-026 keeps derived reports inside that subtree, where a normal build never
 * regenerates or commits them).
 *
 * Exit codes: 0 success, 1 a usage/IO error or an unverified link (unless `--soft`).
 */
import { existsSync, mkdirSync, writeFileSync, statSync } from 'fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'path';
import { fileURLToPath } from 'url';
import { loadMetadata } from './metadata.js';
import { scanSources } from './sources.js';
import { buildPage } from './links.js';
import { renderPage } from './render.js';

const VERSION = '1.0';
const DEFAULT_MODULE = 'hipster-entity-example';
const DEFAULT_BRIDGE_PORT = 18881;
const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..', '..');

/** Every option, so a caller (the CLI, a test, a watcher) may pass only what it wants to change. */
const DEFAULTS = Object.freeze({
  module: DEFAULT_MODULE,
  metadata: null,
  sourceRoot: null,
  linkBase: null,
  out: null,
  title: null,
  packages: [],
  bridgePort: DEFAULT_BRIDGE_PORT,
  soft: false,
  quiet: false,
});

const HELP = `entity-html-index ${VERSION} — HTML entity reference from generator JSON (DEC-027)

Usage: bun run scripts/entity-html/index.js [options]

Options:
  --module <dir>        module directory to render (default: ${DEFAULT_MODULE})
  --metadata <dir>      directory holding <Marker>.metadata.json
                        (default: <module>/.jcodebuddy/metadata/entity)
  --source-root <dir>   Java source root used to resolve link targets
                        (default: <module>/src/main/java)
  --link-base <dir>     directory that link paths are relative to; the page turns them back into
                        absolute paths from its own location (default: the module directory)
  --out <file>          output file (default: <metadata>/index.html)
  --packages <a.b,c.d>  only render views in these packages (default: every view in the metadata)
  --title <text>        page title (default: "<module> — entity reference")
  --bridge-port <n>     webview-jetbrains HTTP bridge port for the browser fallback, 0 to disable
                        (default: ${DEFAULT_BRIDGE_PORT})
  --soft                exit 0 even when the link check reports a divergence
  --quiet               print only the output path and the link-check summary
  --version             print the renderer identity and stop
  --help                print this text

The model comes from the JSON metadata. File and line are resolved from the committed source, and
every link is verified before it is written: a candidate whose line does not contain the member is
reported as a DEC-022 divergence and left out of the page.`;

/** The identity line: name, version, runtime and where this code came from. */
export function identity() {
  const runtime = typeof Bun !== 'undefined' ? `bun ${Bun.version}` : `node ${process.version}`;
  return `entity-html-index ${VERSION} (${runtime}) from ${relative(REPO_ROOT, fileURLToPath(import.meta.url)).split(sep).join('/')}`;
}

function parseArgs(argv) {
  const options = {
    module: DEFAULT_MODULE,
    metadata: null,
    sourceRoot: null,
    linkBase: null,
    out: null,
    title: null,
    packages: [],
    bridgePort: DEFAULT_BRIDGE_PORT,
    soft: false,
    quiet: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const value = () => {
      const next = argv[i + 1];
      if (next === undefined) {
        throw new Error(`${arg} needs a value`);
      }
      i += 1;
      return next;
    };
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--version') {
      options.version = true;
    } else if (arg === '--soft') {
      options.soft = true;
    } else if (arg === '--quiet') {
      options.quiet = true;
    } else if (arg === '--module') {
      options.module = value();
    } else if (arg.startsWith('--module=')) {
      options.module = arg.slice('--module='.length);
    } else if (arg === '--metadata') {
      options.metadata = value();
    } else if (arg.startsWith('--metadata=')) {
      options.metadata = arg.slice('--metadata='.length);
    } else if (arg === '--source-root') {
      options.sourceRoot = value();
    } else if (arg.startsWith('--source-root=')) {
      options.sourceRoot = arg.slice('--source-root='.length);
    } else if (arg === '--link-base') {
      options.linkBase = value();
    } else if (arg.startsWith('--link-base=')) {
      options.linkBase = arg.slice('--link-base='.length);
    } else if (arg === '--out') {
      options.out = value();
    } else if (arg.startsWith('--out=')) {
      options.out = arg.slice('--out='.length);
    } else if (arg === '--title') {
      options.title = value();
    } else if (arg.startsWith('--title=')) {
      options.title = arg.slice('--title='.length);
    } else if (arg === '--packages') {
      options.packages.push(...value().split(',').map((entry) => entry.trim()).filter(Boolean));
    } else if (arg.startsWith('--packages=')) {
      options.packages.push(...arg.slice('--packages='.length).split(',').map((entry) => entry.trim()).filter(Boolean));
    } else if (arg === '--bridge-port') {
      options.bridgePort = Number(value());
    } else if (arg.startsWith('--bridge-port=')) {
      options.bridgePort = Number(arg.slice('--bridge-port='.length));
    } else {
      throw new Error(`unknown argument: ${arg}`);
    }
  }
  return options;
}

/** Resolves the module-derived defaults, so the zero-configuration invocation stays exact. */
export function resolveOptions(partial = {}) {
  const options = { ...DEFAULTS, ...partial };
  const moduleDir = resolve(REPO_ROOT, options.module);
  const metadata = resolve(REPO_ROOT, options.metadata ?? join(moduleDir, '.jcodebuddy', 'metadata', 'entity'));
  const sourceRoot = resolve(REPO_ROOT, options.sourceRoot ?? join(moduleDir, 'src', 'main', 'java'));
  const linkBase = resolve(REPO_ROOT, options.linkBase ?? moduleDir);
  const out = resolve(REPO_ROOT, options.out ?? join(metadata, 'index.html'));
  if (!existsSync(metadata) || !statSync(metadata).isDirectory()) {
    throw new Error(`no metadata directory at ${metadata} — run a generator pass first (scripts/gen.cmd)`);
  }
  if (!existsSync(sourceRoot) || !statSync(sourceRoot).isDirectory()) {
    throw new Error(`no source root at ${sourceRoot} — pass --source-root or generate the module first`);
  }
  return {
    ...options,
    moduleDir,
    metadata,
    sourceRoot,
    linkBase,
    out,
    moduleName: moduleDir.split(sep).pop(),
  };
}

/**
 * The path from the output file's directory to the link base, as the page needs it.
 *
 * One value, not two: the page resolves exactly this against its own location, so there is no second
 * "depth" number that could disagree with it. When the two live on different Windows drives
 * `path.relative` returns an absolute path, which cannot be resolved relative to a `file:` URL — that
 * case is emitted as a `file:` URL instead, which `new URL` resolves as-is.
 */
function linkBaseFromOut(out, linkBase) {
  const from = dirname(out);
  const relativePath = relative(from, linkBase);
  if (isAbsolute(relativePath)) {
    return `file:///${linkBase.split(sep).join('/')}/`;
  }
  if (relativePath === '') {
    return './';
  }
  return `${relativePath.split(sep).join('/')}/`;
}

/**
 * Renders the page and returns everything a caller (the CLI, a test, a watcher) needs to report.
 * Writes the file as a side effect, so the CLI and the test exercise exactly the same path.
 */
export function generate(options) {
  const resolved = resolveOptions(options);
  // The module's class index (DEC-029) is resolved from the module root the CLI already knows, and
  // `loadMetadata` cross-checks it against the `classIndex` pointer each document carries: a wrong
  // pointer must not be able to break the page, and a document copied elsewhere must still render.
  const metadata = loadMetadata(resolved.metadata, {
    indexHint: join(resolved.moduleDir, '.jcodebuddy', 'index', 'classes.json'),
  });
  const scan = scanSources(resolved.sourceRoot, resolved.linkBase);
  const linkBase = linkBaseFromOut(resolved.out, resolved.linkBase);
  const { page, divergences, stats } = buildPage({
    metadata,
    scan,
    packages: resolved.packages,
    identity: identity(),
    moduleName: resolved.moduleName,
    linkBase,
    bridgePort: resolved.bridgePort,
    generationRecordPath: existsSync(join(resolved.metadata, 'generation.json'))
      ? relative(resolved.linkBase, join(resolved.metadata, 'generation.json')).split(sep).join('/')
      : null,
    title: resolved.title ?? `${resolved.moduleName} — entity reference`,
  });
  const html = renderPage(page, stats, divergences);
  mkdirSync(dirname(resolved.out), { recursive: true });
  writeFileSync(resolved.out, html, 'utf8');
  return { ...resolved, page, divergences, stats, html, lineCount: html.split('\n').length };
}

/** The CLI entry point. Returns the process exit code rather than calling `process.exit`. */
export function main(argv = process.argv.slice(2)) {
  let options;
  try {
    options = parseArgs(argv);
  } catch (error) {
    console.error(`[html] ${error.message}`);
    console.error(HELP);
    return 1;
  }
  if (options.help) {
    console.log(HELP);
    return 0;
  }
  if (options.version) {
    console.log(identity());
    return 0;
  }

  let result;
  try {
    result = generate(options);
  } catch (error) {
    console.error(`[html] ${error?.message ?? error}`);
    return 1;
  }

  const { page, divergences, stats, out, metadata, sourceRoot, linkBase } = result;
  const totalFields = page.markers.reduce((total, marker) => total + marker.fieldCount, 0);
  const totalViews = page.markers.reduce((total, marker) => total + marker.views.length, 0);

  if (!options.quiet) {
    console.log(identity());
    console.log(`Metadata: ${relative(REPO_ROOT, metadata).split(sep).join('/')} (${page.metadataFiles.length} files)`);
    console.log(`Source root: ${relative(REPO_ROOT, sourceRoot).split(sep).join('/')}   link base: ${relative(REPO_ROOT, linkBase).split(sep).join('/')}`);
    console.log(`Entities: ${page.markers.length}, views: ${totalViews}, fields: ${totalFields}`);
    for (const marker of page.markers) {
      console.log(`  ${marker.entityName} (${marker.packageName}): ${marker.views.length} views, ${marker.fieldCount} fields`);
    }
  }
  console.log(`HTML entity index written to: ${out}`);
  console.log(`Link check: ${stats.links} links verified, ${stats.checked - stats.links} candidate(s) rejected, ${stats.stale} stale`);
  const errors = divergences.filter((item) => item.severity !== 'warning');
  const warnings = divergences.filter((item) => item.severity === 'warning');
  console.log(`Divergences: ${errors.length} (${warnings.length} metadata note(s))`);
  if (!options.quiet) {
    for (const divergence of divergences) {
      console.log(`  [${divergence.severity}] ${divergence}`);
    }
    console.log('Open it in IntelliJ: right-click the file in the Project view and choose '
      + '"Open in WebView Explorer" (Ctrl+Alt+Shift+W), then click any name, cell or column header.');
  }
  if (errors.length > 0 && !options.soft) {
    console.error('[html] the link check failed; fix the reported divergence or pass --soft to continue.');
    return 1;
  }
  return 0;
}

if (import.meta.main) {
  process.exit(main());
}
