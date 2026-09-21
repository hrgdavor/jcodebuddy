/**
 * Shared scanning core for the Phase 6 migration tooling.
 *
 * <p>Everything that needs to know "where is JavaParser still used, and how" goes
 * through {@link scanRepository}, so the four entry scripts cannot disagree with
 * each other about what they found. That matters more than it looks: the whole
 * point of the Phase 6 gate is that a status in `tracker.md` can be checked
 * against the disk, and two scanners with two definitions of "uses JavaParser"
 * would make that check meaningless.
 *
 * <p>No third-party dependencies, no network, no writes. Bun supplies the file
 * API and the glob.
 */

import { existsSync, readFileSync, statSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { dirname, join, relative, resolve, sep } from 'node:path';

/** Directory names never walked. */
const SKIP_DIRS = new Set([
  '.git',
  '.idea',
  '.kilo',
  '.mvn-local-repo',
  'node_modules',
  'target',
  'build',
  'out',
]);

/**
 * Prefixes excluded from the *migration queue* but still reported, because they
 * contain JavaParser on purpose. See the README section on the allowlist.
 */
const NON_QUEUE_PREFIXES = ['doc/', 'plans/', 'archive/', 'demo/'];

// The `(?![\w])` guard on both patterns is essential, for two different reasons:
// it keeps `com.github.javaparserfoo` from matching as a prefix, and — on the FQN
// pattern — it keeps `org.openrewrite.java.JavaParser` from being read as
// JavaParser's own class. OpenRewrite ships a class with that exact simple name,
// so without the guard the whole already-converted `hr.hrg.rewrite` package
// reports as unmigrated and the gate cannot be trusted.
const JAVA_PARSER_IMPORT = /^\s*import\s+(?:static\s+)?com\.github\.javaparser(?![\w])[^;]*;/;
const JAVA_PARSER_FQN = /(?<![\w.])com\.github\.javaparser(?![\w])/;
// A mention by bare name. Prose in this repo says "JavaParser" and "javaparser-core"
// rather than spelling the package out, and one such mention is load-bearing: the
// DependencyBoundaryTest asserts the dependency stays pinned in the root POM. A
// scanner that only looked for the package name could not see it, so the file
// would sit in the allowlist with no evidence it still needed to be there.
//
// A bare-name mention is only interesting when it is *not* OpenRewrite's own class.
// `org.openrewrite.java.JavaParser` and `JavaParser.fromJavaVersion()` are the
// migrated form, and counting them as JavaParser usage would report the finished
// `hr.hrg.rewrite` package as unmigrated. The exclusion below is by context on the
// same line, which is exact enough for a scanner whose findings are all
// hand-classified anyway.
const JAVA_PARSER_NAME = /\b(?:JavaParser|javaparser)\b/;
const OPENREWRITE_PARSER_CONTEXT =
  /org\.openrewrite|fromJavaVersion|parseInputs|JavaParser\.Builder|rewrite-java|runtimeClasspath/;

/**
 * Classify one Java file's relationship to JavaParser.
 *
 * <p>The distinction that matters is between a *use* and a *mention*: a
 * fully-qualified call site (`new com.github.javaparser.JavaParser()`) is a real
 * dependency with no import line, and a path inside a comment or a fixture
 * string is not. Files in this repo exist in both shapes, so the classifier
 * counts code lines and comment lines separately rather than treating any match
 * as a use.
 *
 * @param {string} text the file's full text
 * @param {string} repoPath the file's repository-relative, `/`-separated path
 * @returns {{
 *   hasImports: boolean,
 *   imports: string[],
 *   qualifiedRefs: string[],
 *   codeRefs: string[],
 *   commentRefs: string[],
 *   classified: 'none'|'imports'|'qualified-only'|'comment-only',
 *   scope: 'main'|'test'|'other',
 *   module: string,
 *   queue: boolean,
 *   nonQueueReason: string|null,
 *   lineCount: number
 * }}
 */
export function classifyJavaText(text, repoPath) {
  const imports = [];
  const qualifiedRefs = new Set();
  const codeRefs = [];
  const commentRefs = [];
  const proseRefs = [];

  const lines = text.split(/\r?\n/);
  for (const line of lines) {
    if (JAVA_PARSER_IMPORT.test(line)) {
      imports.push(line.trim());
      continue;
    }
    if (!JAVA_PARSER_FQN.test(line)) {
      // No package-qualified reference. It may still be a bare-name mention —
      // unless the line is about OpenRewrite's own class of the same name.
      if (JAVA_PARSER_NAME.test(line) && !OPENREWRITE_PARSER_CONTEXT.test(line)) {
        proseRefs.push(line.trim());
      }
      continue;
    }
    const trimmed = line.trim();
    const isComment =
      trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*');
    if (isComment) {
      commentRefs.push(trimmed);
    } else {
      codeRefs.push(trimmed);
    }
    for (const match of line.matchAll(/com\.github\.javaparser[\w.]*/g)) {
      qualifiedRefs.add(match[0]);
    }
  }

  const hasImports = imports.length > 0;
  const classified = hasImports
    ? 'imports'
    : codeRefs.length > 0
      ? 'qualified-only'
      : qualifiedRefs.size > 0 || proseRefs.length > 0
        ? 'comment-only'
        : 'none';

  const nonQueuePrefix = NON_QUEUE_PREFIXES.find((prefix) => repoPath.startsWith(prefix));
  const queue = !nonQueuePrefix && !repoPath.startsWith('scripts/');

  return {
    hasImports,
    imports,
    qualifiedRefs: [...qualifiedRefs].sort(),
    codeRefs,
    commentRefs,
    proseRefs,
    classified,
    scope: scopeOf(repoPath),
    module: moduleOf(repoPath),
    queue,
    nonQueueReason: nonQueuePrefix ? `lives under ${nonQueuePrefix}` : null,
    lineCount: lines.length,
  };
}

/** `main`, `test`, or `other`, from the repository path. */
export function scopeOf(repoPath) {
  if (repoPath.includes('/src/main/')) {
    return 'main';
  }
  if (repoPath.includes('/src/test/')) {
    return 'test';
  }
  return 'other';
}

/** The owning Maven module: the first path segment for a module-rooted path. */
export function moduleOf(repoPath) {
  const slash = repoPath.indexOf('/');
  return slash === -1 ? '(root)' : repoPath.slice(0, slash);
}

/**
 * Every `*.java` file under `dir`, repository-relative to `root`.
 *
 * @param {string} root absolute repository root
 * @returns {Promise<string[]>} sorted, `/`-separated repo paths
 */
export async function findJavaFiles(root) {
  const found = [];

  async function walk(dir) {
    let entries;
    try {
      entries = await readdir(dir, { withFileTypes: true });
    } catch {
      return; // Unreadable directory is not a migration finding.
    }
    for (const entry of entries) {
      if (entry.isDirectory()) {
        if (SKIP_DIRS.has(entry.name)) {
          continue;
        }
        await walk(join(dir, entry.name));
      } else if (entry.isFile() && entry.name.endsWith('.java')) {
        found.push(toRepoPath(root, join(dir, entry.name)));
      }
    }
  }

  await walk(root);
  return found.sort();
}

/** `root`-relative, `/`-separated path. */
export function toRepoPath(root, absolute) {
  return relative(root, absolute).split(sep).join('/');
}

/**
 * Walk up from `from` to the repository root: the directory that contains
 * `plans/rewrite-migration`. Not `cwd` — every entry script must work no matter
 * where it is invoked from, and the plan directory is the marker because it is
 * the thing this tooling serves.
 *
 * @param {string} [from]
 * @returns {string} absolute root
 */
export function findRepoRoot(from = import.meta.dir) {
  let dir = resolve(from);
  for (;;) {
    if (existsSync(join(dir, 'plans', 'rewrite-migration'))) {
      return dir;
    }
    const parent = dirname(dir);
    if (parent === dir) {
      throw new Error(
        `no repository root above ${from}: expected a directory containing plans/rewrite-migration`,
      );
    }
    dir = parent;
  }
}

/**
 * Scan the whole repository once.
 *
 * @param {string} root absolute repository root
 * @returns {Promise<{root: string, files: Array<object>, totals: object}>}
 */
export async function scanRepository(root) {
  const javaFiles = await findJavaFiles(root);
  const files = [];

  for (const repoPath of javaFiles) {
    const absolute = join(root, repoPath);
    let text;
    try {
      text = readFileSync(absolute, 'utf8');
    } catch {
      continue;
    }
    if (!JAVA_PARSER_FQN.test(text)) {
      const prose = text
        .split(/\r?\n/)
        .filter((line) => JAVA_PARSER_NAME.test(line) && !OPENREWRITE_PARSER_CONTEXT.test(line));
      if (prose.length === 0) {
        continue;
      }
    }
    const facts = classifyJavaText(text, repoPath);
    files.push({
      repoPath,
      absolute,
      size: statSync(absolute).size,
      ...facts,
    });
  }

  return { root, files, javaFilesTotal: javaFiles.length, totals: totalsOf(files) };
}

/** Aggregate counts over a scan result. */
export function totalsOf(files) {
  const queueFiles = files.filter((f) => f.queue);
  const withImports = files.filter((f) => f.hasImports);
  return {
    javaFilesWithAnyMention: files.length,
    imports: files.reduce((sum, f) => sum + f.imports.length, 0),
    queueFiles: queueFiles.length,
    queueWithImports: queueFiles.filter((f) => f.hasImports).length,
    queueImportLines: queueFiles.reduce((sum, f) => sum + f.imports.length, 0),
    nonQueueFiles: files.length - queueFiles.length,
    filesWithImports: withImports.length,
    modules: [...new Set(queueFiles.map((f) => f.module))].sort(),
  };
}

/** Group `files` by an accessor, preserving sorted key order. */
export function groupBy(files, keyFn) {
  const groups = new Map();
  for (const file of files) {
    const key = keyFn(file);
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(file);
  }
  return new Map([...groups.entries()].sort(([a], [b]) => String(a).localeCompare(String(b))));
}

/** Git-style `a/b/c.java` → `c.java`, for display. */
export function basename(repoPath) {
  const slash = repoPath.lastIndexOf('/');
  return slash === -1 ? repoPath : repoPath.slice(slash + 1);
}
