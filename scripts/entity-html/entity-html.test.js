/**
 * Tests for the entity HTML index (DEC-027).
 *
 * These run with Bun's own runner, from `scripts/`:
 *
 *     bun test
 *
 * They assert the properties that make the page trustworthy rather than its exact bytes: that every
 * link points at a file and a line that exists and contains what it claims, that two runs produce the
 * same page, that the page is a single self-contained file with framework-free vanilla JavaScript, and
 * that a path in it is never absolute (the page resolves its own location instead).
 */
import { describe, expect, test, beforeAll, afterAll } from 'bun:test';
import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'fs';
import { dirname, join, relative, resolve, sep } from 'path';
import { fileURLToPath } from 'url';
import { generate, resolveOptions } from './index.js';
import { containsIdentifier } from './sources.js';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..', '..');
const MODULE_DIR = join(REPO_ROOT, 'hipster-entity-example');
const SCRATCH = join(MODULE_DIR, '.jcodebuddy', 'agent-state', 'entity-html-test');

let first;
let second;

beforeAll(() => {
  mkdirSync(SCRATCH, { recursive: true });
  // The output must sit inside the module: the page's links are relative to the link base, and a
  // temp directory on another Windows drive cannot be expressed relative to it.
  first = generate({ out: join(SCRATCH, 'run-1', 'index.html'), quiet: true });
  second = generate({ out: join(SCRATCH, 'run-2', 'index.html'), quiet: true });
});

afterAll(() => {
  rmSync(SCRATCH, { recursive: true, force: true });
});

/** Every `data-open` / `data-line`(/ `data-member`, `data-role`) group in the page. */
function linksOf(html) {
  const pattern = /data-open="([^"]+)"\s+data-line="(\d+)"(?:[^>]*?)data-member="([^"]*)"\s+data-role="([^"]*)"/g;
  const found = [];
  for (const match of html.matchAll(pattern)) {
    found.push({ path: match[1], line: Number(match[2]), member: match[3], role: match[4] });
  }
  return found;
}

describe('the page and its links', () => {
  test('is one self-contained HTML file', () => {
    expect(first.html.startsWith('<!DOCTYPE html>')).toBe(true);
    expect(first.html.trimEnd().endsWith('</html>')).toBe(true);
    expect(first.html).toContain('<meta charset="utf-8">');
    expect(first.html).not.toContain('<script src=');
    expect(first.html).not.toContain('<link rel="stylesheet"');
    expect(first.html).not.toContain('<img ');
    expect(first.html).not.toContain('https://');
  });

  test('uses vanilla JavaScript and no framework', () => {
    const script = first.html.slice(first.html.indexOf('<script>') + '<script>'.length,
      first.html.indexOf('</script>'));
    // No module system at all, so the inline script cannot pull a framework in.
    expect(script).not.toMatch(/\bimport\s/);
    expect(script).not.toMatch(/\brequire\(/);
    expect(script).not.toMatch(/\bexport\s/);
    // And no framework global is referenced by name.
    for (const global of ['React', 'createApp', 'createSignal', 'createRoot', 'Vue', 'Svelte', 'Solid',
      'Preact', 'angular', 'jQuery', 'LitElement']) {
      expect(script).not.toContain(global);
    }
    // It must be valid JavaScript on its own: the Function constructor parses without executing.
    expect(() => new Function(script)).not.toThrow();
  });

  test('every link resolves to a line that contains what it claims', () => {
    const links = linksOf(first.html);
    expect(links.length).toBeGreaterThan(50);
    const bad = [];
    for (const link of links) {
      const file = join(first.linkBase, link.path);
      if (!existsSync(file)) {
        bad.push(`${link.path}: missing file`);
        continue;
      }
      const line = readFileSync(file, 'utf8').split(/\r\n|\n|\r/)[link.line - 1];
      if (line === undefined) {
        bad.push(`${link.path}:${link.line}: outside the file`);
      } else if (link.role === 'annotation') {
        // The @FieldSource line legitimately names no member: the renderer's rule for that role.
        if (!line.includes('@FieldSource')) {
          bad.push(`${link.path}:${link.line}: not a @FieldSource line`);
        }
      } else if (link.member && !containsIdentifier(line, link.member)) {
        bad.push(`${link.path}:${link.line}: does not contain ${link.member}`);
      }
    }
    expect(bad).toEqual([]);
  });

  test('never writes an absolute path into the page', () => {
    expect(first.html).not.toContain(REPO_ROOT);
    expect(first.html).not.toContain(REPO_ROOT.split(sep).join('/'));
    // A link target never starts at a drive letter or a root, and no path uses a backslash.
    expect(first.html).not.toMatch(/data-open="[A-Za-z]:/);
    expect(first.html).not.toMatch(/data-open="\//);
    expect(first.html).not.toContain('\\');
    // The only absolute URL is the IDE bridge's own loopback endpoint.
    const urls = [...first.html.matchAll(/https?:\/\/[^\s"'<>)]+/g)].map((match) => match[0]);
    expect(urls.every((url) => url.startsWith('http://127.0.0.1:'))).toBe(true);
  });

  test('the page can turn its own location into the link base', () => {
    const linkBase = first.html.match(/data-link-base="([^"]+)"/)[1];
    const pageHref = `file:///${dirname(first.out).split(sep).join('/')}/index.html`;
    const resolved = decodeURIComponent(new URL(linkBase, pageHref).pathname);
    expect(resolved.replace(/^\//, '').replace(/\/$/, '')).toBe(first.linkBase.split(sep).join('/'));
  });

  test('carries paths to source files, never their content', () => {
    // The page is a set of links: it names a file and a line. It must not quote Java — a second, stale
    // copy of the tree in a report is exactly what the metadata's `sourcePath` exists to avoid.
    const sourceText = [['public interface '], ['public enum '], ['public final class '], ['public record '],
      ['@Override'], ['{@link'], ['\\n']];
    for (const value of sourceText.flat()) {
      expect(first.html).not.toContain(value);
    }
    // The one <pre> the page has is the renderer's own diagnostics block, not a code sample.
    const blocks = [...first.html.matchAll(/<pre>([\s\S]*?)<\/pre>/g)].map((match) => match[1]);
    expect(blocks.length).toBeLessThanOrEqual(2);
    for (const block of blocks) {
      expect(block).toMatch(/kind=|Link check|every link was verified/);
    }
  });

  test('is deterministic', () => {
    expect(second.html).toBe(first.html);
  });
});

describe('the model behind the page', () => {
  test('groups views under their marker, with aspects resolved', () => {
    const names = first.page.markers.map((marker) => marker.entityName);
    expect(names).toEqual(['Auditable', 'PaymentMethod', 'Person']);
    const summary = first.page.markers
      .find((marker) => marker.entityName === 'Person')
      .views.find((view) => view.name === 'PersonSummary');
    expect(summary.aspects.map((aspect) => aspect.name)).toContain('PersonSummaryBuilder');
    expect(summary.aspects.map((aspect) => aspect.name)).toContain('PersonSummaryBuilderTracking');
    expect(summary.columns.map((column) => `${column.artifact}.${column.role}`))
      .toContain('PersonSummaryBuilder.setter');
    expect(summary.fields.map((field) => field.name)).toEqual(
      ['id', 'firstName', 'lastName', 'age', 'departmentName', 'metadata']);
  });

  test('resolves an inherited field to the interface that declares it', () => {
    const person = first.page.markers.find((marker) => marker.entityName === 'Person');
    const details = person.views.find((view) => view.name === 'PersonDetails');
    const firstName = details.fields.find((field) => field.name === 'firstName');
    expect(firstName.declaredIn).toBe('Person');
    expect(firstName.declaredPath).toContain('person/entity/Person.java');
  });

  test('takes every view path from the metadata, not from a name search', () => {
    // The generator records `sourcePath` relative to the module root; the renderer must use it. The
    // case that proves it is a view declared outside its marker's package: name resolution would have
    // looked under the marker's package and found nothing.
    const auditable = first.page.markers.find((marker) => marker.entityName === 'Auditable');
    const paymentAuditable = auditable.views.find((view) => view.name === 'PaymentMethodAuditable');
    expect(paymentAuditable.path).toBe(
      'src/main/java/hr/hrg/hipster/entityexample/paymentMethod/entity/PaymentMethodAuditable.java');

    for (const marker of first.page.markers) {
      for (const view of marker.views) {
        expect(view.path.startsWith('src/main/java/')).toBe(true);
      }
    }
    expect(first.page.markers.every((marker) => marker.views.every((view) => view.path !== null))).toBe(true);
  });

  test('keeps the accessor and the field-source line distinct', () => {
    // `age` is DERIVED: its @FieldSource sits on line 16 and the accessor on line 17. Both come from the
    // per-field location map the pass recorded — which is the point: neither can be recovered from the
    // metadata's `lineNumber`, which is the declaration start (the annotation's line).
    const person = first.page.markers.find((marker) => marker.entityName === 'Person');
    const summary = person.views.find((view) => view.name === 'PersonSummary');
    const age = summary.fields.find((field) => field.name === 'age');
    expect(age.declaredLine).toBe(17);
    expect(age.annotationLine).toBe(16);
    const accessorColumn = summary.columns.find((column) => column.role === 'accessor');
    const annotationColumn = summary.columns.find((column) => column.role === 'annotation');
    expect(age.links.get(accessorColumn.id).line).toBe(17);
    expect(age.links.get(annotationColumn.id).line).toBe(16);
  });

  /**
   * A view the pass produced nothing for is an answer, not a hole.
   *
   * A `--packages` filter makes a pass skip a view entirely, and the metadata then says so with empty
   * `artifacts`/`fields` lists. The renderer must render exactly that — the declaration and no columns —
   * rather than treating the empty lists as "no detail here" and going back to the source scan, which
   * would show fields the pass explicitly did not produce.
   */
  test('renders a view the pass skipped as a declaration with no columns', () => {
    const filtered = join(SCRATCH, 'filtered-metadata');
    mkdirSync(filtered, { recursive: true });
    for (const name of first.page.metadataFiles) {
      const raw = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      for (const view of raw.views ?? []) {
        if (view.name !== 'PersonSummary') {
          continue;
        }
        view.artifacts = [];
        view.fields = [];
      }
      writeFileSync(join(filtered, name), JSON.stringify(raw, null, 2));
    }

    const result = generate({ metadata: filtered, out: join(SCRATCH, 'filtered', 'index.html') });
    const summary = result.page.markers
      .find((marker) => marker.entityName === 'Person')
      .views.find((view) => view.name === 'PersonSummary');
    expect(summary.fields).toEqual([]);
    expect(summary.columns).toEqual([]);
    expect(summary.aspects).toEqual([]);
    // The declaration is still a link: the view's own file id is in the document either way.
    expect(summary.path).toContain('PersonSummary.java');
    expect(summary.line).toBeGreaterThan(0);
  });

  /**
   * A field the ledger does not carry is still rendered, without an ordinal, and reported.
   *
   * The committed example has no such field — every field the metadata attributes to a view is a
   * constant in that view's enum — so the case is built by hand from a real document. It is the
   * behaviour DEC-028 § 10 keeps: the field list comes from the ledger, and a field the ledger lacks
   * would otherwise be invisible rather than shown as a disagreement.
   */
  test('marks a field the ledger does not carry, and says so', () => {
    const ledgerless = join(SCRATCH, 'ledgerless-metadata');
    mkdirSync(ledgerless, { recursive: true });
    for (const name of first.page.metadataFiles) {
      const raw = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      const view = (raw.views ?? []).find((candidate) => candidate.name === 'PersonSummary');
      if (view) {
        view.fields.push({
          name: 'ghost', ordinal: -1, type: 'java.lang.String', fieldKind: 'COLUMN', at: {},
        });
      }
      writeFileSync(join(ledgerless, name), JSON.stringify(raw, null, 2));
    }

    const result = generate({ metadata: ledgerless, out: join(SCRATCH, 'ledgerless', 'index.html') });
    const summary = result.page.markers
      .find((marker) => marker.entityName === 'Person')
      .views.find((view) => view.name === 'PersonSummary');
    const ghost = summary.fields.find((field) => field.name === 'ghost');
    expect(ghost.ordinal).toBe(null);
    expect(ghost.inLedger).toBe(false);
    expect(ghost.links.size).toBe(0);
    const warning = result.divergences.find((item) => item.kind === 'html_field_not_in_ledger');
    expect(warning).toBeDefined();
    expect(warning.severity).toBe('warning');
    expect(warning.current).toBe('ghost');
  });

  test('reports no unverified link for the committed example', () => {
    expect(first.stats.stale).toBe(0);
    expect(first.divergences.filter((item) => item.severity !== 'warning')).toEqual([]);
  });

  /**
   * Every link on the page is a location the **pass recorded** — nothing is inferred from source.
   *
   * This is the test that proves discovery no longer scans. The recorded locations are read here from
   * the raw documents and the module index, independently of the page model, and every rendered link
   * must be one of them: a link at a line no document mentions could only have come from a scan
   * guessing, which is what DEC-028 removes.
   */
  test('renders only locations the metadata records', () => {
    const files = new Map(Object.entries(JSON.parse(
      readFileSync(join(first.linkBase, '.jcodebuddy', 'index', 'classes.json'), 'utf8')).classes)
      .map(([fqn, row]) => [fqn, row.path]));

    // marker -> view -> `field|role` -> set of `path:line`
    const recorded = new Map();
    for (const name of first.page.metadataFiles) {
      const document = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      const byView = new Map();
      recorded.set(document.entityName, byView);
      for (const view of document.views ?? []) {
        const artifactPath = new Map((view.artifacts ?? [])
          .map((artifact) => [String(artifact.id), files.get(artifact.file)]));
        const byField = new Map();
        byView.set(view.name, byField);
        for (const field of view.fields ?? []) {
          for (const [artifactId, roles] of Object.entries(field.at ?? {})) {
            const path = artifactPath.get(artifactId);
            for (const [role, line] of Object.entries(roles)) {
              const key = `${field.name}|${role}`;
              if (!byField.has(key)) {
                byField.set(key, new Set());
              }
              byField.get(key).add(`${path}:${line}`);
            }
          }
        }
      }
    }

    const unrecorded = [];
    let rendered = 0;
    for (const marker of first.page.markers) {
      for (const view of marker.views) {
        for (const field of view.fields) {
          for (const link of field.links.values()) {
            rendered += 1;
            const allowed = recorded.get(marker.entityName)?.get(view.name)?.get(`${link.member}|${link.role}`);
            if (!allowed || !allowed.has(`${link.path}:${link.line}`)) {
              unrecorded.push(`${view.name}.${link.member} (${link.role}) -> ${link.path}:${link.line}`);
            }
          }
        }
      }
    }
    expect(unrecorded).toEqual([]);
    expect(rendered).toBe(first.stats.links);
    expect(rendered).toBeGreaterThan(50);
  });
});

describe('a metadata file written before sourcePath existed', () => {
  /**
   * The renderer must keep working against a JSON from an older pass. It does: the model facts are the
   * same, and only the *locations* fall back to the source scan (DEC-027 section 4.1's "prefer the
   * metadata, never require it").
   */
  test('still renders, resolving the missing locations from the source', () => {
    const stripped = join(SCRATCH, 'stripped-metadata');
    mkdirSync(stripped, { recursive: true });
    for (const marker of first.page.metadataFiles) {
      const raw = JSON.parse(readFileSync(join(first.metadata, marker), 'utf8'));
      delete raw.markerSourcePath;
      for (const view of raw.views ?? []) {
        delete view.sourcePath;
        for (const property of view.properties ?? []) {
          delete property.sourcePath;
        }
      }
      for (const field of raw.allFields ?? []) {
        delete field.sourcePath;
      }
      writeFileSync(join(stripped, marker), JSON.stringify(raw, null, 2));
    }

    const legacy = generate({ metadata: stripped, out: join(SCRATCH, 'legacy', 'index.html') });
    expect(legacy.page.markers.length).toBe(first.page.markers.length);
    expect(legacy.page.markers.every((marker) => marker.views.every((view) => view.path !== null))).toBe(true);
    expect(legacy.divergences.filter((item) => item.severity !== 'warning')).toEqual([]);
    expect(legacy.stats.links).toBeGreaterThan(50);
  });

  /**
   * A document written before the class index — plain `sourcePath` strings, no FQN references, no
   * artifacts, no field maps — renders exactly as it did, from the scan.
   */
  test('renders a pre-class-index document, references and all absent', () => {
    const legacy = join(SCRATCH, 'legacy-metadata');
    mkdirSync(legacy, { recursive: true });
    const files = new Map(Object.entries(JSON.parse(
      readFileSync(join(first.linkBase, '.jcodebuddy', 'index', 'classes.json'), 'utf8')).classes)
      .map(([fqn, row]) => [fqn, row.path]));
    const pathOf = (fqn) => (fqn === null || fqn === undefined ? null : files.get(fqn) ?? null);

    for (const name of first.page.metadataFiles) {
      const raw = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      delete raw.classIndex;
      raw.markerSourcePath = pathOf(raw.markerFile);
      delete raw.markerFile;
      for (const view of raw.views ?? []) {
        view.sourcePath = pathOf(view.file);
        delete view.file;
        for (const property of view.properties ?? []) {
          property.sourcePath = pathOf(property.file);
          delete property.file;
        }
        delete view.artifacts;
        delete view.fields;
      }
      for (const field of raw.allFields ?? []) {
        field.sourcePath = pathOf(field.file);
        delete field.file;
      }
      writeFileSync(join(legacy, name), JSON.stringify(raw, null, 2));
    }

    const result = generate({ metadata: legacy, out: join(SCRATCH, 'legacy-dec028', 'index.html') });
    expect(result.page.markers.length).toBe(first.page.markers.length);
    expect(result.page.markers.every((marker) => marker.views.every((view) => view.path !== null))).toBe(true);
    expect(result.divergences.filter((item) => item.severity !== 'warning')).toEqual([]);
    // The scan is the older, weaker model: it recognises a member by a line pattern, so it can miss a
    // field the pass recorded. What it must never do is invent one — every field it shows has to be a
    // field the metadata knows about for that view.
    const recorded = new Map();
    for (const name of first.page.metadataFiles) {
      const document = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      for (const view of document.views ?? []) {
        recorded.set(view.name, new Set((view.fields ?? []).map((field) => field.name)));
      }
    }
    for (const marker of result.page.markers) {
      for (const view of marker.views) {
        const known = recorded.get(view.name) ?? new Set();
        for (const field of view.fields) {
          expect(known.has(field.name)).toBe(true);
        }
      }
    }
  });
});

/**
 * The module index: where it is found, that both routes agree, and what a missing one does.
 *
 * DEC-028 puts the file ids in the documents and the paths in one table, so the renderer has two ways to
 * find that table — the module root it knows and the `fileIndex` pointer each document carries. Both must
 * give the same answer, and when neither does the renderer must fail loudly: an id rendered as if it were
 * a path is a page of dead links that looks like it worked.
 */
describe('the module index', () => {
  const ROUTE = join(SCRATCH, 'index-route');
  const scratchModule = join(ROUTE, 'example');
  const scratchMetadata = join(ROUTE, 'metadata');

  /** A scratch module holding a copy of the index, and copies of the documents with a chosen pointer. */
  function stage(pointer) {
    rmSync(ROUTE, { recursive: true, force: true });
    mkdirSync(join(scratchModule, '.jcodebuddy', 'index'), { recursive: true });
    mkdirSync(scratchMetadata, { recursive: true });
    copyFileSync(join(first.linkBase, '.jcodebuddy', 'index', 'classes.json'),
      join(scratchModule, '.jcodebuddy', 'index', 'classes.json'));
    for (const name of first.page.metadataFiles) {
      const raw = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      raw.classIndex = pointer;
      writeFileSync(join(scratchMetadata, name), JSON.stringify(raw, null, 2));
    }
    return {
      module: scratchModule,
      metadata: scratchMetadata,
      sourceRoot: join(first.linkBase, 'src', 'main', 'java'),
      linkBase: first.linkBase,
      out: join(ROUTE, 'index.html'),
      quiet: true,
    };
  }

  test('is found from the module root even when a document pointer is wrong', () => {
    const result = generate(stage('does/not/exist/classes.json'));
    expect(result.page.markers.length).toBe(first.page.markers.length);
    expect(result.divergences.filter((item) => item.kind === 'html_index_missing')).toEqual([]);
    expect(result.stats.links).toBe(first.stats.links);
    rmSync(ROUTE, { recursive: true, force: true });
  });

  test('is found from the document pointer when the module has none', () => {
    const pointer = relative(scratchMetadata, join(first.linkBase, '.jcodebuddy', 'index', 'classes.json'))
      .split(sep).join('/');
    // The staged module carries no index at all: only the pointer can resolve the references.
    const options = stage(pointer);
    rmSync(join(scratchModule, '.jcodebuddy', 'index', 'classes.json'));
    const result = generate(options);
    expect(result.page.markers.length).toBe(first.page.markers.length);
    expect(result.divergences.filter((item) => item.kind === 'html_index_missing')).toEqual([]);
    expect(result.stats.links).toBe(first.stats.links);
    rmSync(ROUTE, { recursive: true, force: true });
  });

  test('a missing index is a loud diagnostic, not a page of dangling references', () => {
    const options = stage('does/not/exist/classes.json');
    const present = generate(options);
    rmSync(join(scratchModule, '.jcodebuddy', 'index', 'classes.json'));
    const missing = generate(options);
    rmSync(ROUTE, { recursive: true, force: true });

    expect(present.page.markers.length).toBe(first.page.markers.length);
    const problem = missing.divergences.find((item) => item.kind === 'html_index_missing');
    expect(problem).toBeDefined();
    expect(problem.severity).not.toBe('warning');
    expect(problem.current).toContain('classes.json');
    // Nothing is rendered from an index that could not be read.
    expect(missing.page.markers).toEqual([]);
    expect(missing.stats.links).toBe(0);
  });

  test('an unrecognised index format is refused rather than guessed', () => {
    const options = stage('does/not/exist/classes.json');
    writeFileSync(join(scratchModule, '.jcodebuddy', 'index', 'classes.json'),
      JSON.stringify({ format: 99, classes: { Person: { path: 'wrong/Person.java' } } }));
    const result = generate(options);
    rmSync(ROUTE, { recursive: true, force: true });
    const problem = result.divergences.find((item) => item.kind === 'html_index_missing');
    expect(problem).toBeDefined();
    expect(problem.current).toContain('99');
    expect(result.page.markers).toEqual([]);
  });
});

describe('the size budget', () => {
  /**
   * The documents stay inside their budget, and the class index stays small.
   *
   * The budget is a *shape* guard rather than byte policing (DEC-028 § 5.2, DEC-029 § "honest
   * accounting"): DEC-028 adds information — every field's every location — and DEC-029 adds the class
   * manifest, and the counterfactual is what the numbers are for. Writing the path inline per location
   * instead of grouping locations under an artifact id and stating each path once in the index would cost
   * roughly 35 KB of path text across the example's ~420 locations, on top of what is here. So the
   * interesting property is not the exact count; it is that a fact has not started being repeated once per
   * view, per field or per location again.
   *
   * DEC-029 makes the documents *larger* than the DEC-028 ids did: a fully qualified name is longer than
   * the short id it replaced (measured: 50 993 → 56 097 bytes). That is the price of a reference an IDE's
   * rename refactor can maintain, and it is stated rather than hidden.
   */
  test('keeps the documents and the class index small enough to stay diffable', () => {
    const documents = first.page.metadataFiles
      .reduce((total, name) => total + statSync(join(first.metadata, name)).size, 0);
    const index = statSync(join(first.linkBase, '.jcodebuddy', 'index', 'classes.json')).size;
    expect(index).toBeLessThan(20 * 1024);
    expect(documents).toBeLessThan(60 * 1024);
    expect(documents + index).toBeLessThan(80 * 1024);
  });
});

describe('a recorded location that no longer verifies', () => {
  /**
   * A stale line is dropped and reported, and never rendered as if it worked.
   *
   * A line number is the one part of this metadata no compiler checks, so the renderer's rule is that a
   * link is written only when the target line really contains the member (DEC-027 § 4.2). This pins the
   * rule where it is easiest to lose: the location map now comes from the metadata rather than from a
   * scan, so a document that has gone stale relative to the tree must still be caught.
   */
  test('is dropped from the page and reported as an error', () => {
    const stale = join(SCRATCH, 'stale-metadata');
    mkdirSync(stale, { recursive: true });
    for (const name of first.page.metadataFiles) {
      const raw = JSON.parse(readFileSync(join(first.metadata, name), 'utf8'));
      for (const view of raw.views ?? []) {
        if (view.name !== 'PersonSummary') {
          continue;
        }
        const own = (view.artifacts ?? []).find((artifact) => artifact.name === 'PersonSummary');
        const age = (view.fields ?? []).find((field) => field.name === 'age');
        // Point the accessor at a line far outside the file: the recorded line is now a lie.
        age.at[String(own.id)].accessor = 9999;
      }
      writeFileSync(join(stale, name), JSON.stringify(raw, null, 2));
    }

    const result = generate({ metadata: stale, out: join(SCRATCH, 'stale', 'index.html') });
    const problems = result.divergences.filter((item) => item.kind === 'html_link_stale');
    expect(problems.length).toBeGreaterThan(0);
    expect(problems.every((item) => item.severity !== 'warning')).toBe(true);
    expect(problems[0].location).toContain(':9999');
    expect(result.stats.stale).toBe(problems.length);

    const summary = result.page.markers
      .find((marker) => marker.entityName === 'Person')
      .views.find((view) => view.name === 'PersonSummary');
    const age = summary.fields.find((field) => field.name === 'age');
    expect(age.declaredLine).toBe(-1);
    expect(age.declaredPath).toBe(null);
    for (const link of age.links.values()) {
      expect(link.line).not.toBe(9999);
    }
    // Everything else still renders: one stale record must not blank the page, and the stale location
    // is reported once rather than once per link that happened to point at it.
    expect(result.stats.links).toBe(first.stats.links - 1);
    expect(problems.length).toBe(1);
  });
});

describe('the default invocation', () => {
  test('finds the module, its metadata and its source root without arguments', () => {
    const resolved = resolveOptions({});
    expect(relative(REPO_ROOT, resolved.metadata).split(sep).join('/'))
      .toBe('hipster-entity-example/.jcodebuddy/metadata/entity');
    expect(relative(REPO_ROOT, resolved.sourceRoot).split(sep).join('/'))
      .toBe('hipster-entity-example/src/main/java');
    expect(relative(REPO_ROOT, resolved.linkBase).split(sep).join('/')).toBe('hipster-entity-example');
    expect(relative(REPO_ROOT, resolved.out).split(sep).join('/'))
      .toBe('hipster-entity-example/.jcodebuddy/metadata/entity/index.html');
  });
});
