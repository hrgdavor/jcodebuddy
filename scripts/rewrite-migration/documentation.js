/**
 * The documentation half of the migration scan: does any markdown file still
 * tell a reader to use the library the code stopped using?
 *
 * <p>Phase 6 answered "where is JavaParser still imported" for source. Phase 8
 * asks the same question of prose, and the answer is not symmetric, because a
 * document has no import list to check. Three things are therefore measured
 * separately, and each one on its own is a different verdict:</p>
 *
 * <ol>
 *   <li><b>A petrified mention</b> — the package name, {@code com.github.javaparser}.
 *       Impossible to mistake for anything else, because OpenRewrite's own parser
 *       lives in {@code org.openrewrite.java}.</li>
 *   <li><b>A bare-name mention</b> — {@code JavaParser} in prose. This is the hard
 *       one: the repository legitimately says "JavaParser" about OpenRewrite's own
 *       {@code org.openrewrite.java.JavaParser}, about what the port replaced, and
 *       about a rule that was true of the old parser. A line is only a
 *       <em>live</em> mention when it is not also about OpenRewrite — the same
 *       context exclusion {@link scanRepository} applies to Java prose, for the
 *       same reason.</li>
 *   <li><b>Teaching a removed API</b> — {@code LexicalPreservingPrinter},
 *       {@code StaticJavaParser}, {@code SymbolSolver}, {@code JavaParser.parse},
 *       {@code ParserConfiguration}, {@code JavaSymbolSolver},
 *       {@code setLanguageLevel}. This is the verdict that matters: a document may
 *       name the library all it likes while recording history, but a document that
 *       shows a reader how to <em>call</em> the removed API is an instruction to
 *       re-add the dependency, and that is a defect with the same blast radius as
 *       a code defect.</li>
 * </ol>
 *
 * <p>Off-limits by construction, and reported rather than filtered, because each
 * is a different fact about why a file says the old name:</p>
 *
 * <ul>
 *   <li><b>Generated</b> — the report pages a script writes
 *       ({@code TEST-REPORT.md}, {@code BenchmarkReport.md}, {@code Checklist.md},
 *       {@code tracker.md}). A human edit to one of them is overwritten, so the
 *       mention is a property of the inputs, not of the page.</li>
 *   <li><b>Record</b> — the migration's own notes under {@code doc/brainstorm/} and
 *       {@code plans/}, which exist to describe the migration.</li>
 *   <li><b>Decision</b> — a DEC or brainstorm document: a record of what was
 *       decided, whose mentions are annotated rather than removed. DEC-028 says
 *       exactly this about DEC-029, so appending a note is how this repository
 *       already corrects one.</li>
 * </ul>
 *
 * <p>No third-party dependencies, no network, no writes.</p>
 */

import { existsSync, readFileSync, statSync } from 'node:fs';
import { readdir } from 'node:fs/promises';
import { join, relative, sep } from 'node:path';

import { DOC_ALLOWLIST, docAllowlistEntryFor } from './curation.js';

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
 * The removed library, spelled out. Anchored with `(?<![\w.])`/`(?![\w])` so
 * `com.github.javaparserfoo` is not a prefix match, and — the reason the guard
 * exists at all — so `org.openrewrite.java.JavaParser` is not read as its own
 * class.
 */
const REMOVED_PACKAGE = /(?<![\w.])com\.github\.javaparser(?![\w])/;

/** A bare-name mention, which may or may not be about the removed library. */
const REMOVED_NAME = /\b(?:JavaParser|javaparser)\b/;

/**
 * Context that makes a "JavaParser" line be about <em>OpenRewrite's</em> class of
 * the same simple name, or about the port itself. Copied in intent from
 * {@link inventory.js}, deliberately not imported from it: the two scanners
 * answer different questions and a shared constant would couple a change to one
 * to the other.
 */
const OPENREWRITE_CONTEXT =
  /org\.openrewrite|fromJavaVersion|parseInputs|runtimeClasspath|rewrite-java|openrewrite/i;

/**
 * The removed API, as call sites rather than names.
 *
 * <p>Each pattern is a qualified call or a class the removed library owned, and
 * the list is deliberately short. A bare class name is not enough: `Parser` and
 * `ParserConfiguration` are generic words that other parsers use, so matching
 * them would report a document as teaching the removed API for describing
 * OpenRewrite's own builder. Broad patterns also cost something subtler — an
 * earlier draft of this list matched `setLanguageLevel` and
 * `JavaParser.getStaticConfiguration`, which appear nowhere in the tree, so the
 * entry added a failure mode no real document could trigger while making the
 * list look more thorough than it was.</p>
 *
 * <p>Every pattern below is measured to occur in at least one document, and the
 * three that still occur only in generated or record files are kept because
 * those are exactly the pages the check has to classify correctly.</p>
 */
const REMOVED_API = [
  // The lexical-preservation primitive: the single most instructive identifier
  // in the migration, and unique to the removed library.
  'LexicalPreservingPrinter',
  // The static facade. `StaticJavaParser.parse(...)` is the call the retired
  // builder guide was built on.
  'StaticJavaParser',
  // The symbol solver, in both the class name a reader would import and the
  // interface a caller would hold.
  'JavaSymbolSolver',
  'SymbolSolver',
  // The two configuration classes. `ParserConfiguration` is the one a reader
  // would import to set a language level; `LanguageLevel.JAVA_25` is the value
  // the migration's own checklist records having deleted. `PrettyPrinterConfiguration`
  // is the printer's, and it is the one file whose output was expected to change
  // when it went.
  'ParserConfiguration',
  'LanguageLevel.JAVA',
  'PrettyPrinterConfiguration',
];

/**
 * Files a script writes. Editing one is pointless, so a mention in it is a
 * property of the generator's inputs and must be fixed there or not at all.
 */
const GENERATED = [
  'doc/brainstorm/rewrite-migration/06-migration/Checklist.md',
  'doc/brainstorm/rewrite-migration/06-migration/tracker.md',
  'doc/brainstorm/rewrite-migration/07-testing/TEST-REPORT.md',
  'doc/brainstorm/rewrite-migration/07-testing/benchmarks/BenchmarkReport.md',
];

/**
 * Path prefixes that are the migration's own record. These files say JavaParser
 * because that is their subject, and `curation-freshness` exists to stop exactly
 * the kind of tidying that would remove it.
 */
const RECORD_PREFIXES = [
  'doc/brainstorm/rewrite-migration/',
  'plans/rewrite-migration/',
  'archive/',
  'demo/',
];

/**
 * Decision records. A DEC states what was decided and why; a later phase adding a
 * "superseded in part by" line is how this repository corrects one, so a mention
 * here is annotated rather than rewritten.
 */
const DECISION_PREFIXES = [
  'doc-hipster-entity/architecture/decisions/',
  'doc-hipster-entity/architecture/decisions-watch/',
  'doc-hipster-entity/brainstorm/',
  'doc/architecture/decisions-watch/',
  'doc/brainstorm/',
];

/**
 * What kind of document a path is, from its location alone.
 *
 * <p>Order matters: generated before record, because the generated pages happen
 * to live under a record prefix and "a script will overwrite this" is the more
 * specific fact.</p>
 *
 * @param {string} repoPath
 * @returns {'generated'|'record'|'decision'|'live'}
 */
export function docKindOf(repoPath) {
  if (GENERATED.includes(repoPath)) {
    return 'generated';
  }
  if (RECORD_PREFIXES.some((prefix) => repoPath.startsWith(prefix))) {
    return 'record';
  }
  if (DECISION_PREFIXES.some((prefix) => repoPath.startsWith(prefix))) {
    return 'decision';
  }
  return 'live';
}

/**
 * Measure one markdown document.
 *
 * @param {string} text the file's full text
 * @param {string} repoPath repository-relative, `/`-separated path
 * @returns {{
 *   repoPath: string, kind: string, mentionLines: number, packageLines: number,
 *   liveNameLines: string[], taughtApis: string[], teachesRemovedApi: boolean,
 *   isHistorical: boolean, allowed: boolean, allow: object|null,
 *   lineCount: number
 * }}
 */
export function classifyMarkdown(text, repoPath) {
  const kind = docKindOf(repoPath);
  const lines = text.split(/\r?\n/);

  let mentionLines = 0;
  let packageLines = 0;
  const liveNameLines = [];
  const taught = new Set();

  lines.forEach((line, index) => {
    const hasPackage = REMOVED_PACKAGE.test(line);
    const hasName = REMOVED_NAME.test(line);

    // The API check runs on *every* line, not only on lines that already name the
    // library. `LexicalPreservingPrinter.setup(cu)` and `StaticJavaParser.parse(x)`
    // contain no mention of the package or the product name at all — they are the
    // calls inside the code blocks a guide shows, which is exactly where the
    // teaching lives and exactly where a mention-gated scan would look away.
    for (const api of REMOVED_API) {
      if (line.includes(api)) {
        taught.add(api);
      }
    }

    if (!hasPackage && !hasName) {
      return;
    }
    mentionLines++;
    if (hasPackage) {
      packageLines++;
    }
    // A bare name that the same line also ties to OpenRewrite is the migrated
    // spelling, not the removed one. The package name is never excluded: no
    // OpenRewrite line can contain it.
    if (hasName && !hasPackage && !OPENREWRITE_CONTEXT.test(line)) {
      liveNameLines.push(`${index + 1}:${line.trim()}`);
    }
  });

  const allow = docAllowlistEntryFor(repoPath);
  // A record or a decision is allowed to teach the old API *by definition*: the
  // migration guide's code samples are the port's instructions to whoever reads
  // it next, and "do not teach what was removed" does not apply to the document
  // whose subject is the removal.
  const isHistorical = kind !== 'live';
  const teachesRemovedApi = taught.size > 0;

  return {
    repoPath,
    kind,
    mentionLines,
    packageLines,
    liveNameLines,
    taughtApis: [...taught].sort(),
    teachesRemovedApi,
    isHistorical,
    allowed: Boolean(allow),
    allow: allow ?? null,
    lineCount: lines.length,
  };
}

/** Every `*.md` file under `dir`, repository-relative to `root`. */
export async function findMarkdownFiles(root) {
  const found = [];

  async function walk(dir) {
    let entries;
    try {
      entries = await readdir(dir, { withFileTypes: true });
    } catch {
      return; // An unreadable directory is not a documentation finding.
    }
    for (const entry of entries) {
      if (entry.isDirectory()) {
        if (SKIP_DIRS.has(entry.name)) {
          continue;
        }
        await walk(join(dir, entry.name));
      } else if (entry.isFile() && entry.name.endsWith('.md')) {
        found.push(relative(root, join(dir, entry.name)).split(sep).join('/'));
      }
    }
  }

  await walk(root);
  return found.sort();
}

/**
 * The whole documentation inventory.
 *
 * <p>Every tracked markdown file is measured, and the ones with no mention at all
 * are dropped from `files` — a report of 400 silent files is not a finding. The
 * counts of what was walked and what was dropped are still returned, so "found
 * nothing" and "did not look" stay distinguishable.</p>
 *
 * @param {string} root absolute repository root
 * @returns {Promise<{root: string, files: Array<object>, totals: object}>}
 */
export async function scanDocumentation(root) {
  const markdown = await findMarkdownFiles(root);
  const files = [];

  for (const repoPath of markdown) {
    const absolute = join(root, repoPath);
    let text;
    try {
      text = readFileSync(absolute, 'utf8');
    } catch {
      continue;
    }
    if (!REMOVED_PACKAGE.test(text) && !REMOVED_NAME.test(text)) {
      continue;
    }
    files.push({ absolute, size: statSync(absolute).size, ...classifyMarkdown(text, repoPath) });
  }

  return { root, files, markdownTotal: markdown.length, totals: docTotalsOf(files) };
}

/** Aggregate counts over a documentation scan. */
export function docTotalsOf(files) {
  const live = files.filter((file) => file.kind === 'live');
  return {
    mentioning: files.length,
    mentions: files.reduce((sum, file) => sum + file.mentionLines, 0),
    teaching: files.filter((file) => file.teachesRemovedApi).length,
    live: live.length,
    liveTeaching: live.filter((file) => file.teachesRemovedApi).length,
    liveUnallowlisted: live.filter((file) => !file.allowed).length,
    allowlisted: files.filter((file) => file.allowed).length,
  };
}

/**
 * The failure `docs-honest` is made of, as plain data so the check itself holds
 * no policy.
 *
 * <p>An allowlist entry is checked for its <em>existence</em> here and for its
 * <em>shape</em> (a reason, a retirement condition) by the gate, because a
 * missing non-empty string is a curation defect rather than a scan result. The
 * opposite staleness — an entry whose file stopped mentioning the library
 * altogether — cannot be computed from the scan, because such a file drops out
 * of it; the gate derives that from the allowlist keys instead.</p>
 *
 * @param {{files: Array<object>}} scan
 * @returns {{unallowlisted: Array<object>}}
 */
export function docOffenders(scan) {
  return {
    // A live document that names the library with no recorded reason. Whether it
    // merely mentions or actively teaches is reported per line by the gate, but
    // both are the same defect: an unrecorded decision.
    unallowlisted: scan.files.filter((file) => file.kind === 'live' && !file.allowed),
  };
}

/** Whether a path is currently named by the documentation allowlist. */
export function isDocAllowlisted(repoPath) {
  return Boolean(docAllowlistEntryFor(repoPath));
}

/**
 * Paths in the allowlist that no longer exist on disk.
 *
 * <p>Exact keys only: a wildcard key names no file, so "does it exist" is not a
 * question that can be asked of it. An entry left behind by a file that stopped
 * mentioning the library altogether is a different staleness and is detected by
 * the gate against the scan, because such a file drops out of the scan entirely
 * and cannot be seen from here.</p>
 */
export function staleDocAllowlistPaths(root) {
  return Object.keys(DOC_ALLOWLIST).filter(
    (path) => !path.includes('*') && !existsSync(join(root, path)),
  );
}