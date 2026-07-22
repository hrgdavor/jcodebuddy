/* global Bun */
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'fs';
import { dirname, resolve, relative, join, sep } from 'path';
import { fileURLToPath } from 'url';
import { Glob } from 'bun';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
const _cache = { fileIndex: null };

const args = process.argv.slice(2);
if (args.length === 0) {
  console.log(`Usage: bun ${process.argv[1]} <glob-or-file> [more...]

Scans markdown files for <!-- INCLUDE:path/to/source#REGION --> directives
and updates the immediately following fenced code block with the current
source content.

Arguments can be file paths or glob patterns:
  doc/architecture/materialization-levels.md   Single file
  "doc/**/*.md"                                 All .md files under doc/
  "**/*.md"                                     All .md files in the repo

Path resolution for INCLUDE directives:
  ~record/Person.java   Short path – searches the repo for a file whose
                         path ends with the given suffix. Prefix with ~.
  ../relative/path.java  Resolved relative to the markdown file, then
                         the repo root, then with leading ../ stripped.

If the source path contains a #REGION suffix, only the lines between
the matching region markers are included:
  //#region REGION ... //#endregion   (or //endregion)
  /* #region REGION ... #endregion */
  <!-- #region REGION ... #endregion -->
`);
  process.exit(1);
}

// ── expand globs ──────────────────────────────────────────────

function expandArgs(args) {
  const files = new Set();
  for (const arg of args) {
    if (arg.includes('*') || arg.includes('?') || arg.includes('{')) {
      const glob = new Glob(arg);
      for (const match of glob.scanSync({ cwd: repoRoot, absolute: true })) {
        files.add(match);
      }
    } else {
      files.add(resolve(arg));
    }
  }
  return [...files];
}

const mdFiles = expandArgs(args);
if (mdFiles.length === 0) {
  console.log('No files matched the given pattern(s).');
  process.exit(0);
}

let totalUpdated = 0;
let totalSkipped = 0;

for (const filePath of mdFiles) {
  const result = processMarkdownFile(filePath);
  totalUpdated += result.updated;
  totalSkipped += result.skipped;
}

if (totalUpdated > 0) {
  console.log(`Done. ${totalUpdated} block(s) updated.`);
} else {
  console.log('All blocks are up-to-date.');
}

// ── core ──────────────────────────────────────────────────────

function processMarkdownFile(fileArg) {
  const mdPath = resolve(fileArg);
  if (!existsSync(mdPath)) {
    console.error(`Error: markdown file not found: ${mdPath}`);
    process.exit(1);
  }

  const mdText = readFileSync(mdPath, 'utf8');
  const eol = mdText.includes('\r\n') ? '\r\n' : '\n';
  const lines = mdText.split(/\r?\n/);
  const mdDir = dirname(mdPath);

  let updated = 0;
  let skipped = 0;
  const out = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const m = line.match(/<!--\s*INCLUDE:(.+?)\s*-->/);
    if (!m) {
      out.push(line);
      continue;
    }

    out.push(line); // keep the INCLUDE directive itself

    const { filePath: srcRelPath, region } = parseIncludeRef(m[1].trim());
    const srcPath = resolveSource(mdDir, srcRelPath);

    // advance past any blank lines between the directive and the fence
    let j = i + 1;
    while (j < lines.length && lines[j].trim() === '') {
      out.push(lines[j]);
      j++;
    }

    // expect an opening code fence
    if (j >= lines.length || !lines[j].match(/^\s*(`{3,}|~{3,})/)) {
      console.warn(`  WARN: no code fence after INCLUDE at ${fileArg}:${i + 1} – skipping`);
      skipped++;
      continue;
    }

    const fenceLine = lines[j];
    const fenceMatch = fenceLine.match(/^(\s*)(`{3,}|~{3,})/);
    const closeTicks = fenceMatch[2];

    // find the closing fence
    const closeIdx = findClosingFence(lines, j + 1, closeTicks);
    if (closeIdx < 0) {
      console.warn(`  WARN: unterminated code fence at ${fileArg}:${j + 1} – skipping`);
      skipped++;
      continue;
    }

    // read source
    let snippet;
    if (!srcPath) {
      console.warn(`  WARN: source not found: ${srcRelPath} (referenced at ${fileArg}:${i + 1})`);
      skipped++;
      // keep existing block unchanged
      for (let k = j; k <= closeIdx; k++) out.push(lines[k]);
      i = closeIdx;
      continue;
    }

    try {
      snippet = readSourceSnippet(srcPath, region);
    } catch (err) {
      console.warn(`  WARN: ${err.message} (referenced at ${fileArg}:${i + 1})`);
      skipped++;
      for (let k = j; k <= closeIdx; k++) out.push(lines[k]);
      i = closeIdx;
      continue;
    }

    const oldBlock = lines.slice(j + 1, closeIdx).join('\n');
    const newBlock = snippet;

    if (oldBlock !== newBlock) {
      updated++;
      const relSrc = srcRelPath + (region ? '#' + region : '');
      console.log(`  Updated block from ${relSrc} at ${fileArg}:${j + 1}`);
    }

    out.push(fenceLine);
    out.push(...newBlock.split('\n'));
    out.push(lines[closeIdx]);
    i = closeIdx;
  }

  const result = out.join(eol);
  if (result !== mdText) {
    writeFileSync(mdPath, result, 'utf8');
  }

  return { updated, skipped };
}

// ── helpers ───────────────────────────────────────────────────

/** Parse "path/to/file.java#REGION" into { filePath, region }. */
function parseIncludeRef(raw) {
  const hash = raw.lastIndexOf('#');
  if (hash < 0) return { filePath: raw, region: null };
  return {
    filePath: raw.slice(0, hash),
    region: raw.slice(hash + 1) || null,
  };
}

/** Try mdDir-relative first, then repo-root-relative, then strip leading ../ and try root. */
function resolveSource(mdDir, relPath) {
  // ~suffix means short-path lookup
  if (relPath.startsWith('~')) {
    return resolveShortPath(relPath.slice(1));
  }

  const fromMd = resolve(mdDir, relPath);
  if (existsSync(fromMd)) return fromMd;

  const fromRoot = resolve(repoRoot, relPath);
  if (existsSync(fromRoot)) return fromRoot;

  // strip leading ../ segments and try from root (handles doc/ referencing sibling modules)
  const stripped = relPath.replace(/^(?:\.\.[\\/])+/, '');
  if (stripped !== relPath) {
    const fromStripped = resolve(repoRoot, stripped);
    if (existsSync(fromStripped)) return fromStripped;
  }

  return null;
}

// ── short-path file index (cached, gitignore-aware) ──────────

function resolveShortPath(suffix) {
  if (!_cache.fileIndex) _cache.fileIndex = buildFileIndex();

  const normalised = suffix.replace(/\\/g, '/');
  const matches = [];
  for (const [relPath, absPath] of _cache.fileIndex) {
    if (relPath === normalised || relPath.endsWith('/' + normalised)) {
      matches.push(absPath);
    }
  }

  if (matches.length === 1) return matches[0];
  if (matches.length > 1) {
    console.warn(`  WARN: ambiguous short path ~${suffix} matches ${matches.length} files:`);
    for (const m of matches) console.warn(`    ${relative(repoRoot, m)}`);
    return null;
  }
  return null;
}

function buildFileIndex() {
  const ignorePatterns = loadGitignorePatterns();
  const index = new Map();

  function walk(dir) {
    let entries;
    try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      const full = join(dir, entry.name);
      const rel = relative(repoRoot, full).replace(/\\/g, '/');

      if (isIgnored(rel, entry.isDirectory(), ignorePatterns)) continue;

      if (entry.isDirectory()) {
        walk(full);
      } else {
        index.set(rel, full);
      }
    }
  }

  walk(repoRoot);
  return index;
}

function loadGitignorePatterns() {
  const patterns = [];
  const gitignorePath = join(repoRoot, '.gitignore');
  if (!existsSync(gitignorePath)) return patterns;

  const lines = readFileSync(gitignorePath, 'utf8').split(/\r?\n/);
  for (const raw of lines) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    patterns.push(line.replace(/\/$/, '')); // strip trailing slash
  }
  return patterns;
}

function isIgnored(relPath, isDir, patterns) {
  // always skip .git
  if (relPath === '.git' || relPath.startsWith('.git/')) return true;

  for (const pattern of patterns) {
    // simple prefix/segment match – covers the common gitignore entries
    const segments = relPath.split('/');
    if (segments.some(seg => seg === pattern)) return true;
    if (relPath === pattern || relPath.startsWith(pattern + '/')) return true;
  }
  return false;
}

/** Read a source file, optionally extracting only a named region. */
function readSourceSnippet(srcPath, region) {
  const text = readFileSync(srcPath, 'utf8');
  if (!region) {
    return text.replace(/\r?\n$/, '');
  }

  const lines = text.split(/\r?\n/);
  const start = findRegionStart(lines, region);
  if (start < 0) {
    throw new Error(`region '${region}' not found in ${srcPath}`);
  }
  const end = findRegionEnd(lines, start + 1);
  if (end < 0) {
    throw new Error(`no matching endregion for '${region}' in ${srcPath}`);
  }

  return lines.slice(start + 1, end).join('\n');
}

/**
 * Find region start marker. Matches:
 *   //#region NAME   //region NAME
 *   /* #region NAME  <!-- #region NAME -->
 */
function findRegionStart(lines, name) {
  const re = new RegExp(
    '^\\s*(?:\\/\\/|/\\*+|<!--)\\s*#?region\\s+' + escRe(name) + '\\b'
  );
  for (let i = 0; i < lines.length; i++) {
    if (re.test(lines[i])) return i;
  }
  return -1;
}

/**
 * Find endregion marker after startIdx. Matches:
 *   //#endregion   //endregion
 *   /* #endregion  <!-- #endregion -->
 */
function findRegionEnd(lines, startIdx) {
  const re = /^\s*(?:\/\/|\/\*+|<!--)\s*#?endregion\b/;
  for (let i = startIdx; i < lines.length; i++) {
    if (re.test(lines[i])) return i;
  }
  return -1;
}

function findClosingFence(lines, startIdx, fence) {
  for (let i = startIdx; i < lines.length; i++) {
    if (lines[i].trim() === fence) return i;
  }
  return -1;
}

function escRe(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

