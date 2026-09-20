/**
 * Reads the generator's JSON metadata — the model the whole page is built from (DEC-027, DEC-028).
 *
 * `<Marker>.metadata.json` is written by `EntityMetadataGenerator` and is the only place that knows
 * what this module's entities *are*: which interfaces are views, which fields each view exposes in
 * which order, each field's `FieldKind`, and — since DEC-028 — **every location of every field**, as the
 * pass that wrote the files recorded it. Nothing in this file re-derives those facts from source.
 *
 * <h3>Two inputs, and the index between them</h3>
 * A document no longer contains a source path. It names each file by a short **id**, and the ids are
 * resolved by the module's central index, `.jcodebuddy/index/files.json`, which states every path once:
 *
 *     // <module>/.jcodebuddy/index/files.json
 *     { "format": 1, "module": "…", "sourceRoot": "src/main/java", "files": { "Person": "src/main/java/…" } }
 *
 * The index is located from the **module root the CLI already knows** and cross-checked against each
 * document's `fileIndex` pointer, so a wrong pointer cannot silently break the page and a moved index
 * still renders. When a document carries ids but the table cannot be read, this module does not guess:
 * `buildPage` reports `html_index_missing` and renders nothing, because an id rendered as if it were a
 * path is a page full of dead links that looks like it worked.
 *
 * The shape read here is the `toJson` contract from `EntityMetadataGenerator`:
 *
 *     { entityName, package, markerInterface, idType, markerFile, markerLine, fileIndex, views[], allFields[] }
 *     view:   { name, lineNumber, file, extends[], gen, discriminatorField, addons[], properties[],
 *               artifacts[], fields[] }
 *     artifact: { id, name, kind, file, line, generated, own, header? }
 *     field:  { name, ordinal, type, fieldKind, column?, relation?, expression?, at }
 *     at:     { <artifact id>: { <role>: line } }
 *
 * A document written before DEC-028 (plain `sourcePath` strings, no `artifacts`/`fields`) still reads:
 * every id key falls back to the path key it replaced.
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'fs';
import { join, resolve } from 'path';

/** The JDK packages whose prefix is dropped from a displayed type name. */
const JDK_PACKAGES = ['java.lang.', 'java.util.', 'java.time.', 'java.math.', 'java.io.', 'java.util.function.'];

/** The only index `format` this renderer understands. An unknown one is refused, never guessed. */
export const SUPPORTED_INDEX_FORMAT = 1;

/**
 * The module's central file index: id to module-relative path.
 *
 * `problem` is the reason the table is unusable, or null. It is carried rather than thrown so the
 * diagnostic can be reported in DEC-022's format by the page builder, which is where every other
 * divergence is decided.
 */
export class FileIndex {
  constructor(byId, file, problem) {
    this.byId = byId;
    this.file = file;
    this.problem = problem;
  }

  /** The module-relative path behind `id`, or null when the id is unknown or the table is unusable. */
  pathOf(id) {
    if (id === null || id === undefined || !this.byId) {
      return null;
    }
    return this.byId.get(id) ?? null;
  }

  get usable() {
    return this.byId !== null;
  }

  get size() {
    return this.byId ? this.byId.size : 0;
  }
}

/** One field of a marker's `allFields` array, with the views that expose it. */
class FieldFacts {
  constructor(raw, index) {
    this.name = raw.name;
    this.rawType = raw.type;
    this.typeByView = raw.typeByView ?? {};
    /** The declaring accessor's line, or -1 when the field is declared outside the source root. */
    this.lineNumber = raw.lineNumber;
    /**
     * The file that declares this field's accessor, relative to the module root, or null. This is the
     * location the generator resolved, and it is the *declaring* file — an inherited field's is a
     * different file than the view that exposes it (DEC-027 section 4.1: metadata first).
     */
    this.sourcePath = raw.file !== undefined && raw.file !== null
      ? index.pathOf(raw.file)
      : (raw.sourcePath ?? null);
    this.fieldKind = raw.fieldKind ?? null;
    this.column = raw.column ?? null;
    this.relation = raw.relation ?? null;
    this.expression = raw.expression ?? null;
    this.views = new Set(raw.views ?? []);
  }

  /** The Java type of this field as one view sees it. */
  typeFor(viewName) {
    const byView = this.typeByView ? this.typeByView[viewName] : undefined;
    return typeText(byView ?? this.rawType);
  }
}

/**
 * One type declaration that belongs to a view (DEC-028).
 *
 * `own` distinguishes an artifact the view owns — its own file, the nested types it declares, the
 * siblings the generator emitted for it — from a **foreign declaring interface** the view's fields
 * reference, which is listed so a field's location map can key on it. Only owned artifacts become
 * aspects and columns on the page, which is what keeps an inherited field's accessor in the view's own
 * accessor column (pointing at the interface that declares it) rather than adding a column per
 * interface in the hierarchy.
 */
export class ArtifactFacts {
  constructor(raw, index) {
    this.id = raw.id;
    this.name = raw.name;
    this.kind = raw.kind;
    this.fileId = raw.file ?? null;
    this.path = raw.file !== undefined && raw.file !== null ? index.pathOf(raw.file) : null;
    this.line = typeof raw.line === 'number' ? raw.line : -1;
    this.generated = raw.generated === true;
    this.own = raw.own !== false;
    this.header = raw.header ?? null;
  }

  /**
   * The artifact's qualified name, for ordering and for the page's "where" labels.
   *
   * Derived from the file's path rather than from the document's marker package, because a view — and
   * therefore its generated siblings — may be declared in a different package than the marker that
   * claims it (the example's `paymentMethod.entity.PaymentMethodAuditable` belongs to the
   * `example.Auditable` marker).
   */
  get fqcn() {
    const pkg = packageOfPath(this.path);
    return pkg ? `${pkg}.${this.name}` : this.name;
  }

  /** The last segment of the display name: `PersonSummary.Record` is shown as `Record`. */
  get shortName() {
    const dot = this.name.lastIndexOf('.');
    return dot < 0 ? this.name : this.name.slice(dot + 1);
  }

  /** Whether the display name is a nested type of the view's own file. */
  get nested() {
    return this.name.includes('.');
  }
}

/** `src/main/java/a/b/C.java` -> `a.b`; `a/b/C.java` -> `a.b` (a module root with no source prefix). */
export function packageOfPath(path) {
  if (!path) {
    return '';
  }
  const withoutExtension = path.replace(/\.java$/, '');
  const withoutSourcePrefix = withoutExtension.replace(/^(?:src\/(?:main|test)\/java|src)\//, '');
  const slash = withoutSourcePrefix.lastIndexOf('/');
  return slash < 0 ? '' : withoutSourcePrefix.slice(0, slash).replace(/\//g, '.');
}

/**
 * One field of one view, with **every location the pass recorded** (DEC-028).
 *
 * `at` is the passed-through artifact id to role to line map. Its keys are artifact ids of this view's
 * artifact list, its roles are the fixed vocabulary the page's columns are built from, and a role that
 * does not exist for the field is absent rather than present with a made-up line.
 */
export class ViewFieldFacts {
  constructor(raw) {
    this.name = raw.name;
    /** The DEC-023 ledger position, or null when the ledger does not carry the field. */
    this.ordinal = typeof raw.ordinal === 'number' && raw.ordinal > 0 ? raw.ordinal : null;
    this.rawType = raw.type;
    this.fieldKind = raw.fieldKind ?? null;
    this.column = raw.column ?? null;
    this.relation = raw.relation ?? null;
    this.expression = raw.expression ?? null;
    this.at = raw.at && typeof raw.at === 'object' ? raw.at : {};
  }

  /** Every `(artifactId, role, line)` triple, in artifact order then role order — the JSON's own order. */
  locations() {
    const found = [];
    for (const [artifactId, roles] of Object.entries(this.at)) {
      for (const [role, line] of Object.entries(roles ?? {})) {
        found.push({ artifactId: Number(artifactId), role, line });
      }
    }
    return found;
  }
}

/** One view of a marker, with its own properties and — since DEC-028 — its artifacts and field map. */
class ViewFacts {
  constructor(raw, index) {
    this.name = raw.name;
    this.lineNumber = raw.lineNumber;
    /** The view's own file id, or null in a document written before DEC-028. */
    this.fileId = raw.file ?? null;
    /** The view's own file, relative to the module root, or null (a pre-`sourcePath` JSON). */
    this.sourcePath = raw.file !== undefined && raw.file !== null
      ? index.pathOf(raw.file)
      : (raw.sourcePath ?? null);
    this.gen = raw.gen ?? 'DEFAULT';
    this.addons = raw.addons ?? [];
    this.extendsTypes = raw.extends ?? [];
    this.discriminatorField = raw.discriminatorField ?? '';
    /**
     * The view's own declared properties, with each `file` id resolved to a path.
     *
     * The id is resolved once here rather than at every read site: a property's `file` is what says
     * which interface *declared* the field, and a caller that resolved it differently at two call sites
     * would attribute one field to two interfaces.
     */
    this.properties = (raw.properties ?? []).map((property) => ({
      ...property,
      sourcePath: property.file !== undefined && property.file !== null
        ? index.pathOf(property.file)
        : (property.sourcePath ?? null),
    }));
    this.artifacts = (raw.artifacts ?? []).map((artifact) => new ArtifactFacts(artifact, index));
    this.fields = (raw.fields ?? []).map((field) => new ViewFieldFacts(field));
    /**
     * Whether this view carries the DEC-028 detail. A view without it — an older document — is rendered
     * by the source scan, which is the one case the scan is still the model.
     *
     * The test is the **presence** of the keys, not their content: a view the pass skipped (a
     * `--packages` filter excludes its package) legitimately has empty lists, and that is an answer —
     * "this pass produced nothing for this view" — which the page must render as a declaration with no
     * columns. Treating an empty list as "no detail" would send the renderer back to the scan and make
     * it show fields the pass explicitly did not produce.
     */
    this.detailed = Array.isArray(raw.artifacts) && Array.isArray(raw.fields);
  }
}

/** One `<Marker>.metadata.json`: the entity, its views, and the fields they expose together. */
export class MarkerMetadata {
  constructor(file, raw, index) {
    this.file = file;
    this.entityName = raw.entityName;
    this.packageName = raw.package ?? '';
    this.markerInterface = raw.markerInterface;
    this.idType = raw.idType ?? '';
    /** The pointer the document carries to the index, or null in a document written before DEC-028. */
    this.fileIndexPointer = raw.fileIndex ?? null;
    this.markerLine = typeof raw.markerLine === 'number' ? raw.markerLine : -1;
    /** The marker interface's own file, relative to the module root, or null. */
    this.markerSourcePath = raw.markerFile !== undefined && raw.markerFile !== null
      ? index.pathOf(raw.markerFile)
      : (raw.markerSourcePath ?? null);
    this.views = (raw.views ?? []).map((view) => new ViewFacts(view, index));
    this.allFields = (raw.allFields ?? []).map((field) => new FieldFacts(field, index));
    this.byName = new Map(this.allFields.map((field) => [field.name, field]));
  }

  /** The fields `viewName` exposes, in JSON order, each with the type that view sees. */
  fieldsFor(viewName) {
    return this.allFields.filter((field) => field.views.has(viewName));
  }

  /** The `properties` entry `viewName` declares itself, or undefined. */
  ownProperty(viewName, fieldName) {
    const view = this.views.find((candidate) => candidate.name === viewName);
    if (!view) {
      return undefined;
    }
    return view.properties.find((property) => property.name === fieldName);
  }
}

/** Everything read from one metadata directory. */
export class MetadataBundle {
  constructor(directory, markers, generation, index) {
    this.directory = directory;
    this.markers = markers;
    this.generation = generation;
    this.index = index;
    /**
     * Whether any document names its files by id. A bundle that does needs the index; a bundle that
     * does not (every document written before DEC-028) renders from the source scan exactly as it did.
     */
    this.needsIndex = markers.some((marker) => marker.fileIndexPointer !== null
      || marker.views.some((view) => view.fileId !== null || view.detailed));
  }

  get viewCount() {
    return this.markers.reduce((total, marker) => total + marker.views.length, 0);
  }

  get fieldCount() {
    return this.markers.reduce((total, marker) => total + marker.allFields.length, 0);
  }
}

/**
 * Locates and reads the module's index.
 *
 * Two routes, both tried: the module root the CLI knows (`<module>/.jcodebuddy/index/files.json`) and
 * the `fileIndex` pointer a document carries (relative to the document). The module-root route wins
 * when it works, because that is a fact about the module rather than about one document; the pointer is
 * what makes a document that was copied elsewhere still readable, and it is what the DEC-028 contract
 * promises a consumer that has only the document. Either way the table is the same one.
 */
function loadIndex(directory, rawDocuments, indexHint) {
  const pointer = rawDocuments.map((raw) => raw.fileIndex).find(Boolean) ?? null;
  const candidates = [];
  if (indexHint) {
    candidates.push({ file: indexHint, route: 'module root' });
  }
  if (pointer) {
    candidates.push({ file: resolve(directory, pointer), route: 'document pointer' });
  }
  if (candidates.length === 0) {
    return new FileIndex(null, null, 'no document carries a fileIndex pointer');
  }

  // Every route is tried and every failure is kept: reporting only the last one told a reader about the
  // pointer when the module's table had been found and refused, which is the opposite of the diagnosis
  // they need.
  const problems = [];
  for (const candidate of candidates) {
    if (!existsSync(candidate.file) || !statSync(candidate.file).isFile()) {
      problems.push(`no index table at ${candidate.file} (via the ${candidate.route})`);
      continue;
    }
    let raw;
    try {
      raw = JSON.parse(readFileSync(candidate.file, 'utf8'));
    } catch (error) {
      problems.push(`the index table at ${candidate.file} is not valid JSON (${error?.message ?? error})`);
      continue;
    }
    if (raw.format !== SUPPORTED_INDEX_FORMAT) {
      // An unknown format means "do not trust this table": the ids may mean something else entirely,
      // and guessing would be worse than refusing.
      problems.push(`the index table at ${candidate.file} has format ${JSON.stringify(raw.format)}, and `
        + `this renderer understands only ${SUPPORTED_INDEX_FORMAT}`);
      continue;
    }
    return new FileIndex(new Map(Object.entries(raw.files ?? {})), candidate.file, null);
  }
  return new FileIndex(null, null, problems.join('; '));
}

/**
 * Reads every `<Marker>.metadata.json` in `directory`, the module's index, and `generation.json`.
 *
 * @param {string} directory the metadata directory
 * @param {{indexHint?: string}} [options] `indexHint` is the index file's path resolved from the module
 *        root; when it is absent only each document's own pointer is used
 */
export function loadMetadata(directory, options = {}) {
  if (!existsSync(directory) || !statSync(directory).isDirectory()) {
    throw new Error(`no metadata directory at ${directory} — run a generator pass first (scripts/gen.cmd)`);
  }
  const rawDocuments = [];
  const files = [];
  for (const entry of readdirSync(directory).sort()) {
    if (!entry.endsWith('.metadata.json')) {
      continue;
    }
    files.push(entry);
    rawDocuments.push(JSON.parse(readFileSync(join(directory, entry), 'utf8')));
  }
  if (rawDocuments.length === 0) {
    throw new Error(`no *.metadata.json in ${directory} — run a generator pass first (scripts/gen.cmd)`);
  }

  const index = loadIndex(directory, rawDocuments, options.indexHint ?? null);
  const markers = files.map((entry, position) => new MarkerMetadata(entry, rawDocuments[position], index));
  markers.sort((a, b) => a.entityName.localeCompare(b.entityName));

  const generationFile = join(directory, 'generation.json');
  const generation = existsSync(generationFile)
    ? JSON.parse(readFileSync(generationFile, 'utf8'))
    : null;
  return new MetadataBundle(directory, markers, generation, index);
}

/** A Java type as the metadata writes it: a string, or an object with generic arguments. */
export function typeText(type) {
  if (typeof type === 'string') {
    return shortType(type);
  }
  if (type && typeof type === 'object') {
    const base = shortType(type.type ?? '?');
    const generics = type.genericArguments;
    if (Array.isArray(generics) && generics.length > 0) {
      return `${base}<${generics.map(typeText).join(', ')}>`;
    }
    return base;
  }
  return '?';
}

/** The full type name behind a displayed name, for the page's tooltip. */
export function qualifiedTypeText(type) {
  if (typeof type === 'string') {
    return type;
  }
  if (type && typeof type === 'object') {
    const base = type.type ?? '?';
    const generics = type.genericArguments;
    if (Array.isArray(generics) && generics.length > 0) {
      return `${base}<${generics.map(qualifiedTypeText).join(', ')}>`;
    }
    return base;
  }
  return '?';
}

/** `java.util.Map` -> `Map`, so a cell stays readable; the full name stays in the tooltip. */
export function shortType(name) {
  let short = name;
  for (const prefix of JDK_PACKAGES) {
    if (short.startsWith(prefix)) {
      short = short.slice(prefix.length);
      break;
    }
  }
  const nested = short.lastIndexOf('.');
  return nested < 0 ? short : short.slice(nested + 1);
}
