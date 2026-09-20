/**
 * Builds the page model from the metadata JSON, resolving every link's file and line from the
 * committed source and validating each one before it can reach the page (DEC-027).
 *
 * <h3>The two inputs, and which one owns what</h3>
 * The JSON owns the *model*: which markers and views exist, a view's `gen` level and addons, the
 * fields it exposes, each field's type, `FieldKind`, column, relation, expression and the line the
 * generator recorded for it. The source scan owns *locations*: the file that declares a view, the
 * file each artifact lives in, and the line of a member inside an artifact.
 *
 * <h3>Why every link is checked</h3>
 * A line number is the one part of this page that no compiler checks, and a stale one is worse than
 * a missing one: it opens the wrong line and looks like it worked. So a candidate link is accepted
 * only when the target file exists *and* the target line contains the member (the DEC-021 header for
 * the `field source` role, whose line legitimately names no member). A rejected candidate becomes a
 * `html_link_stale` divergence in DEC-022's format and is left out of the page — the page may be
 * incomplete, but it never lies about where a member is.
 */
import { readFileSync, existsSync } from 'fs';
import { join } from 'path';
import { TypeInfo, containsIdentifier } from './sources.js';
import { packageOfPath, qualifiedTypeText, typeText } from './metadata.js';

/** The member roles the page can point at, in the order its columns are rendered. */
export const ROLE_ORDER = [
  'accessor',
  'annotation',
  'enum-constant',
  'record-component',
  'setter',
  'field',
  'ordinal-slot',
  'name-slot',
];

/** The label the page prints for each role. */
export const ROLE_LABELS = {
  accessor: 'accessor',
  annotation: 'field source',
  'enum-constant': 'enum constant',
  'record-component': 'record component',
  setter: 'setter',
  field: 'field',
  'ordinal-slot': 'ordinal slot',
  'name-slot': 'name lookup',
};

/**
 * One DEC-022-shaped diagnostic, kept structured so the page and the console print the same fact.
 *
 * `severity` is the renderer's own split, and it exists because the two kinds mean different things:
 * a **stale link** is a defect (the page would open the wrong line), while a **field the ledger does
 * not contain** is a fact about the metadata that the page can still render honestly. Only errors
 * make the run fail.
 */
export class Divergence {
  constructor(kind, location, cause, current, canonical, action, severity = 'error') {
    this.kind = kind;
    this.location = location;
    this.cause = cause;
    this.current = current;
    this.canonical = canonical;
    this.action = action;
    this.severity = severity;
  }

  toString() {
    return `kind=${this.kind}, location=${this.location}, cause=${this.cause}, current=${this.current}, `
      + `canonical=${this.canonical}, action=${this.action}`;
  }
}

/** Reads a file's lines once, so a page with a few hundred links reads each file once. */
class LineCache {
  constructor(linkBase) {
    this.linkBase = linkBase;
    this.cache = new Map();
  }

  lines(relativePath) {
    if (this.cache.has(relativePath)) {
      return this.cache.get(relativePath);
    }
    const file = join(this.linkBase, relativePath);
    const value = existsSync(file) ? readFileSync(file, 'utf8').split(/\r\n|\n|\r/) : null;
    this.cache.set(relativePath, value);
    return value;
  }

  line(relativePath, lineNumber) {
    const lines = this.lines(relativePath);
    if (!lines || lineNumber < 1 || lineNumber > lines.length) {
      return null;
    }
    return lines[lineNumber - 1];
  }
}

/**
 * Builds the page model.
 *
 * <h3>Where the model comes from</h3>
 * Since DEC-028 the generator records **every location of every field** and the artifact inventory of
 * every view, so this builder reads the answer instead of inferring it: a view's aspects come from
 * `views[].artifacts[]`, its columns from the roles its fields' `at` maps actually contain, and each
 * cell from `at` directly. The source scan is still performed, but only to **verify** a link — the file
 * exists and the line still contains the member (DEC-027 § 4.2, which does not change) — and as the
 * model for a document written before DEC-028, which carries no ids, no artifacts and no `at` maps.
 *
 * @param {object} options
 * @param {import('./metadata.js').MetadataBundle} options.metadata
 * @param {import('./sources.js').SourceScan} options.scan
 * @param {string[]} options.packages  only these packages (empty = every view in the metadata)
 * @param {string} options.identity    the renderer's identity line, shown in the page footer
 * @param {string} options.moduleName  the module directory name
 * @param {string} options.linkBase    the path from the output file's directory to the link base
 * @param {number} options.bridgePort  webview-jetbrains HTTP bridge port, or 0 to disable
 * @param {string|null} options.generationRecordPath  run record path relative to the link base
 */
export function buildPage(options) {
  const { metadata, scan, packages = [], identity, moduleName, linkBase, bridgePort, generationRecordPath } = options;
  const divergences = [];
  const cache = new LineCache(scan.linkBase);
  const stats = { views: 0, fields: 0, links: 0, checked: 0, stale: 0 };
  const markers = [];

  // A document that names files by index id is unusable without the table, and there is no honest
  // fallback: an id rendered as if it were a path is a page of dead links that looks like it worked.
  // Reported and stopped, rather than rendered wrong.
  const indexMissing = metadata.needsIndex && !metadata.index.usable;
  if (indexMissing) {
    divergences.push(new Divergence('html_index_missing', metadata.directory,
      'the metadata names source files by index id, and the module index could not be read',
      metadata.index.problem ?? 'the index table is missing or unreadable',
      join(scan.linkBase, '.jcodebuddy', 'index', 'files.json'),
      'rerun a generator pass (scripts/gen.cmd) so the index and the documents are written together; '
      + 'the index is written before the documents precisely so this cannot happen'));
  }

  for (const marker of metadata.markers) {
    if (indexMissing) {
      break;
    }
    const views = [];
    for (const view of marker.views) {
      const fromMetadata = view.detailed && metadata.index.usable;
      // The package filter is applied to the view's own package, which is a fact of the view's file —
      // not of the marker's package, because a view may be declared outside it.
      const viewPackage = fromMetadata
        ? (packageOfPath(view.sourcePath) || marker.packageName)
        : null;
      const resolved = fromMetadata ? null : resolveViewType(scan, marker, view, divergences);
      if (packages.length > 0) {
        const packageName = fromMetadata
          ? viewPackage
          : (resolved ? resolved.packageName : marker.packageName);
        if (!packages.includes(packageName)) {
          continue;
        }
      }
      views.push(fromMetadata
        ? buildViewFromMetadata({ marker, view, cache, divergences, stats })
        : buildViewFromSource({ marker, view, scan, cache, divergences, stats, resolved }));
    }
    if (views.length === 0) {
      continue;
    }
    // The marker is a class like any other the metadata is about, so it gets a link too. The metadata
    // records the marker's file and declaration line (DEC-028), so the scan is only consulted to check
    // that the recorded line really does contain the marker — which is what makes a stale record
    // visible rather than a link that opens the wrong line. A document written before `markerLine`
    // existed has no recorded line, and the scan supplies it, exactly as it did before.
    let markerLine = -1;
    if (marker.markerSourcePath) {
      const candidates = [marker.markerLine];
      if (marker.markerLine <= 0) {
        const markerType = scan.typeAtPath(marker.markerSourcePath, marker.markerInterface)
          ?? syntheticType(marker.markerSourcePath, marker.markerInterface, marker.packageName);
        if (markerType) {
          candidates.push(markerType.line);
        }
      }
      markerLine = firstLineOf(cache, marker.markerSourcePath, candidates, marker.markerInterface);
    }
    markers.push({
      entityName: marker.entityName,
      markerInterface: marker.markerInterface,
      packageName: marker.packageName,
      idType: marker.idType,
      file: marker.file,
      markerPath: marker.markerSourcePath && markerLine > 0 ? marker.markerSourcePath : null,
      markerLine,
      views,
      fieldCount: views.reduce((total, item) => total + item.fields.length, 0),
    });
  }

  const page = {
    title: options.title ?? `${moduleName} — entity reference`,
    moduleName,
    identity,
    linkBase,
    bridgePort,
    generationRecordPath,
    generation: metadata.generation,
    metadataDirectory: metadata.directory,
    metadataFiles: metadata.markers.map((marker) => marker.file),
    packageFilter: packages,
    sourceRootDisplay: scan.sourceRootDisplay,
    markers,
  };
  return { page, divergences, stats };
}

/**
 * Builds one view from the metadata alone (DEC-028): the artifact inventory, the field location maps,
 * and the declared locations — reading the source **only** to verify a link.
 *
 * The scan is not consulted for a single fact here. That is the point of the change: the pass that
 * wrote the files knows where every field is, and a render-time scan could only infer it from naming
 * conventions and line patterns.
 */
function buildViewFromMetadata(context) {
  const { marker, view, cache, divergences, stats } = context;
  const viewPath = view.sourcePath;
  const viewArtifact = view.artifacts.find((artifact) => artifact.own && artifact.name === view.name) ?? null;
  const viewFqcn = viewArtifact
    ? viewArtifact.fqcn
    : (viewPath ? `${packageOfPath(viewPath)}.${view.name}` : view.name);
  const viewLine = viewArtifact
    ? firstLineOf(cache, viewPath, [viewArtifact.line, view.lineNumber], view.name)
    : firstLineOf(cache, viewPath, [view.lineNumber], view.name);

  // The aspects, in the order the page has always used: the view itself, then its other own artifacts
  // sorted by qualified name. A foreign declaring interface is NOT an aspect — it is where an inherited
  // field's accessor lives, and it appears through the view's own accessor column, which is what keeps
  // the column set of a view with a deep hierarchy small.
  const aspects = [];
  if (viewArtifact) {
    aspects.push({
      fqcn: viewFqcn,
      name: view.name,
      kind: viewArtifact.kind,
      path: viewPath,
      line: viewLine,
      generated: false,
      description: '@View declaration',
      artifact: viewArtifact,
      isView: true,
    });
  }
  for (const artifact of view.artifacts
    .filter((candidate) => candidate.own && candidate !== viewArtifact)
    .sort((a, b) => a.fqcn.localeCompare(b.fqcn))) {
    aspects.push({
      fqcn: artifact.fqcn,
      name: artifact.shortName,
      kind: artifact.kind,
      path: artifact.path,
      line: firstLineOf(cache, artifact.path, [artifact.line], artifact.shortName),
      generated: artifact.generated,
      description: artifact.header ?? (artifact.nested ? 'nested type' : null),
      artifact,
      isView: artifact === viewArtifact,
    });
  }

  const artifactById = new Map(view.artifacts.map((artifact) => [artifact.id, artifact]));
  const hasEnum = view.artifacts.some((artifact) => artifact.own && artifact.name === `${view.name}_`);
  const enumArtifact = view.artifacts.find((artifact) => artifact.own && artifact.name === `${view.name}_`) ?? null;

  /**
   * The interface-side facts of one field: **which declaration the generator treats as the field's
   * owner**, and the `@FieldSource` line that defines it.
   *
   * The owner is read from the metadata's own declarations — the view's `properties[]` entry when the
   * view declares the field, the marker's `allFields[]` row otherwise — because that is the answer the
   * generator commits to (`allFields[].file` is documented as "the declaring accessor's file"). It is
   * deliberately not inferred from the location map: a field the concrete view *overrides* as a
   * `default` accessor has a location in the view's own file **and** in the root that declares it
   * abstract, and the page's "declared in" label has always named the root. The accessor's LINE inside
   * that file does come from the location map, because only the location map distinguishes the accessor
   * from the declaration start (an annotated accessor declares on a different line than it is written
   * on: 16 and 17 for `PersonSummary.age`).
   */
  const derive = (field) => {
    const locations = field.locations()
      .map((location) => ({ ...location, artifact: artifactById.get(location.artifactId) }))
      .filter((location) => location.artifact);
    const own = marker.ownProperty(view.name, field.name);
    const facts = marker.byName.get(field.name);
    const ownerPath = own?.sourcePath ?? facts?.sourcePath ?? null;
    const inOwner = ownerPath
      ? locations.find((location) => location.role === 'accessor' && location.artifact.path === ownerPath)
      : null;
    const fallbackLine = Math.max(own?.lineNumber ?? -1, facts?.lineNumber ?? -1);
    const line = inOwner ? inOwner.line : fallbackLine;
    const declared = ownerPath && line > 0 ? { path: ownerPath, line } : null;

    // The `@FieldSource` line of the declaring interface, which is where a DERIVED/JOINED field is
    // actually defined. It is a peer of the accessor rather than part of it, so it gets its own cell.
    const annotations = locations.filter((location) => location.role === 'annotation'
      && location.artifact.kind === 'interface');
    const annotation = (ownerPath
      ? annotations.find((location) => location.artifact.path === ownerPath)
      : null)
      ?? annotations.find((location) => location.artifact.own)
      ?? annotations[0]
      ?? null;

    return {
      declared,
      declaredName: ownerPath ? baseNameOf(ownerPath) : null,
      annotation: annotation ? { path: annotation.artifact.path, line: annotation.line } : null,
    };
  };

  const prepared = view.fields.map((field) => ({ field, derived: derive(field) }));

  /**
   * Where one field lives inside one aspect under one role, or null.
   *
   * The declaring-interface fallback applies to the **view's own aspect only**: a generated builder or
   * record does not inherit the interface's accessor, so its other cells are legitimately empty. Only
   * the view aspect — whose contract *is* the interface — points at the interface that declares an
   * inherited field, which is what keeps `PersonDetails.firstName` clickable through to `Person`.
   */
  const cellFor = (entry, aspect, role) => {
    const artifactId = aspect.artifact ? aspect.artifact.id : null;
    if (artifactId !== null) {
      const roles = entry.field.at[String(artifactId)];
      if (roles && roles[role] !== undefined) {
        return { path: aspect.artifact.path, line: roles[role] };
      }
    }
    if (!aspect.isView) {
      return null;
    }
    if (role === 'accessor' && entry.derived.declared) {
      return entry.derived.declared;
    }
    if (role === 'annotation' && entry.derived.annotation) {
      return entry.derived.annotation;
    }
    return null;
  };

  const fields = prepared.map(({ field, derived }) => ({
    ordinal: field.ordinal,
    inLedger: hasEnum ? field.ordinal !== null : null,
    name: field.name,
    typeText: typeText(field.rawType),
    typeTitle: qualifiedTypeText(field.rawType),
    fieldKind: field.fieldKind,
    column: field.column,
    relation: field.relation,
    expression: field.expression,
    declaredIn: derived.declaredName,
    declaredPath: derived.declared ? derived.declared.path : null,
    declaredLine: derived.declared ? derived.declared.line : -1,
    annotationPath: derived.annotation ? derived.annotation.path : null,
    annotationLine: derived.annotation ? derived.annotation.line : -1,
    declaring: derived.declared
      ? { type: { simpleName: derived.declaredName }, line: derived.declared.line }
      : null,
    links: new Map(),
  }));

  // A field the ledger does not carry is a real fact about the two sources disagreeing, so it is
  // rendered without an ordinal *and* reported — as a warning, because the page can still say where
  // those fields live. This is DEC-028's kept behaviour for the case (§ 10).
  const extra = prepared.filter((entry) => entry.field.ordinal === null).map((entry) => entry.field.name);
  if (extra.length > 0) {
    divergences.push(new Divergence('html_field_not_in_ledger',
      enumArtifact ? `${enumArtifact.path}:${enumArtifact.line}` : view.name,
      'the metadata attributes these fields to the view, but its field enum has no constant for them',
      extra.join(', '),
      `a constant per attributed field in ${view.name}_`,
      'the page lists them without an ordinal, because the ledger — not the metadata union — defines '
      + 'the positional contract; regenerate and, if the constants are still absent, treat it as a '
      + 'generator question rather than a renderer one',
      'warning'));
  }

  // Columns are the (aspect, role) pairs a field actually occupies — read from the field maps, never
  // probed against the source.
  const columns = [];
  let columnId = 0;
  for (const aspect of aspects) {
    for (const role of ROLE_ORDER) {
      const present = prepared.some((entry) => cellFor(entry, aspect, role) !== null);
      if (!present) {
        continue;
      }
      columns.push({
        id: columnId++,
        artifact: aspect.name,
        artifactFqcn: aspect.fqcn,
        role,
        roleLabel: ROLE_LABELS[role] ?? role,
        path: aspect.path,
        line: aspect.line,
        generated: aspect.generated,
      });
    }
  }

  for (let i = 0; i < fields.length; i += 1) {
    const field = fields[i];
    const entry = prepared[i];
    for (const column of columns) {
      const aspect = aspects.find((candidate) => candidate.fqcn === column.artifactFqcn);
      const cell = cellFor(entry, aspect, column.role);
      if (!cell) {
        continue;
      }
      stats.checked += 1;
      const reason = validate(cache, cell, field, column.role);
      if (reason) {
        stats.stale += 1;
        divergences.push(new Divergence('html_link_stale',
          `${cell.path}:${cell.line}`,
          reason,
          `link for ${field.name} (${column.role}) in ${column.artifact}`,
          'a line that contains the member',
          'rerun a generator pass so the metadata and the links agree, then re-render the index'));
        continue;
      }
      field.links.set(column.id, {
        path: cell.path,
        line: cell.line,
        member: field.name,
        role: column.role,
        where: column.artifact,
      });
      stats.links += 1;
    }
  }

  /**
   * The field's own **name cell** is the one link that is not part of a column: it points at the
   * declaring accessor (`declared`) or at the `@FieldSource` line, and the pre-DEC-028 renderer checked
   * it separately (`validDeclaring`) because the column probe could not.
   *
   * Checked here, after the column loop, for one reason: the same location is very often *also* a cell
   * in the view's accessor or annotation column, and reporting one stale record twice — once for the
   * name and once for the cell — is noise. A location already reported is dropped without a second
   * divergence; one that only the name cell uses is reported, because rendering it would be a link that
   * opens the wrong line. A failed location is then removed from the field, so nothing renders from it:
   * "declared outside the source root" is represented by the location being absent, which is a
   * legitimate and common answer, while a recorded line that is not there is a stale record.
   */
  const alreadyReported = new Set(divergences
    .filter((item) => item.kind === 'html_link_stale')
    .map((item) => item.location));
  for (const entry of prepared) {
    for (const role of ['accessor', 'annotation']) {
      const cell = role === 'accessor' ? entry.derived.declared : entry.derived.annotation;
      if (!cell) {
        continue;
      }
      const reason = validate(cache, cell, entry.field, role);
      if (!reason) {
        continue;
      }
      const where = `${cell.path}:${cell.line}`;
      if (!alreadyReported.has(where)) {
        alreadyReported.add(where);
        stats.stale += 1;
        divergences.push(new Divergence('html_link_stale', where, reason,
          role === 'accessor'
            ? `declaring accessor for ${entry.field.name}`
            : `field source line for ${entry.field.name}`,
          'a line that contains the member',
          'rerun a generator pass so the metadata and the links agree, then re-render the index'));
      }
      entry.derived = role === 'accessor'
        ? { ...entry.derived, declared: null, declaredName: null }
        : { ...entry.derived, annotation: null };
    }
  }
  // The field objects were built before these removals, so the removals are applied to them as well.
  for (let i = 0; i < fields.length; i += 1) {
    fields[i].declaredPath = prepared[i].derived.declared ? prepared[i].derived.declared.path : null;
    fields[i].declaredLine = prepared[i].derived.declared ? prepared[i].derived.declared.line : -1;
    fields[i].declaredIn = prepared[i].derived.declaredName;
    fields[i].annotationPath = prepared[i].derived.annotation ? prepared[i].derived.annotation.path : null;
    fields[i].annotationLine = prepared[i].derived.annotation ? prepared[i].derived.annotation.line : -1;
  }

  stats.views += 1;
  stats.fields += fields.length;
  return {
    name: view.name,
    fqcn: viewFqcn,
    path: viewPath,
    line: viewLine,
    gen: view.gen,
    addons: view.addons,
    extendsTypes: view.extendsTypes,
    discriminatorField: view.discriminatorField,
    aspects: aspects.map(({ artifact, isView, ...rest }) => rest),
    columns,
    fields,
  };
}

/** Builds one view the pre-DEC-028 way: the JSON is the model, the source scan supplies locations. */
function buildViewFromSource(context) {
  const { marker, view, scan, cache, divergences, stats, resolved } = context;
  const viewFqcn = resolved ? resolved.fqcn : `${marker.packageName}.${view.name}`;
  const viewPath = resolved ? scan.pathOf(resolved) : null;
  const viewLine = resolved
    ? firstLineOf(cache, viewPath, [view.lineNumber, resolved.line], view.name)
    : -1;

  const aspectTypes = resolved ? scan.artifactsFor(viewFqcn) : [];
  const aspects = [];
  if (resolved) {
    aspects.push({
      fqcn: viewFqcn,
      name: view.name,
      kind: resolved.kind,
      path: viewPath,
      line: viewLine,
      generated: false,
      description: '@View declaration',
      type: resolved,
    });
  }
  for (const type of aspectTypes) {
    aspects.push({
      fqcn: type.fqcn,
      name: type.ownerFqcn ? type.fqcn.slice(type.ownerFqcn.length + 1) : type.simpleName,
      kind: type.kind,
      path: scan.pathOf(type),
      line: firstLineOf(cache, scan.pathOf(type), [type.line], type.simpleName),
      generated: type.headerTargets.includes(viewFqcn),
      description: type.headerDescription ?? (type.ownerFqcn ? 'nested type' : null),
      type,
    });
  }

  const enumAspect = aspects.find((aspect) => aspect.fqcn === `${viewFqcn}_`) ?? null;
  const ledgerOrder = enumAspect && enumAspect.type.enumOrder.length > 0
    ? [...enumAspect.type.enumOrder]
    : fallbackOrder(marker, view, resolved);

  // The page shows the union: the ledger's fields (with their ordinals, DEC-023) plus any field the
  // metadata attributes to this view that the ledger has no constant for. The second group is a real
  // fact about the two sources disagreeing, so it is rendered *and* reported — but as a warning, since
  // the page can still say where those fields live.
  const attributed = marker.fieldsFor(view.name).map((field) => field.name);
  const extra = attributed.filter((name) => !ledgerOrder.includes(name));
  const order = [...ledgerOrder, ...extra];
  if (extra.length > 0) {
    divergences.push(new Divergence('html_field_not_in_ledger',
      enumAspect ? `${enumAspect.path}:${enumAspect.line}` : view.name,
      'the metadata attributes these fields to the view, but its field enum has no constant for them',
      extra.join(', '),
      `a constant per attributed field in ${view.name}_`,
      'the page lists them without an ordinal, because the ledger — not the metadata union — defines '
      + 'the positional contract; regenerate and, if the constants are still absent, treat it as a '
      + 'generator question rather than a renderer one',
      'warning'));
  }

  const fields = order.map((fieldName) => {
    const facts = marker.byName.get(fieldName);
    const own = marker.ownProperty(view.name, fieldName);
    const declaring = validDeclaring(cache, scan,
      declaringAccessor(scan, marker, view, resolved, fieldName, facts, own), fieldName);
    const annotation = declaring
      ? annotationCell(cache, scan, declaring, fieldName, own, facts)
      : null;
    const typeValue = own?.type ?? facts?.rawType;
    return {
      ordinal: enumAspect && ledgerOrder.includes(fieldName) ? ledgerOrder.indexOf(fieldName) + 1 : null,
      inLedger: enumAspect === null ? null : ledgerOrder.includes(fieldName),
      name: fieldName,
      typeText: typeValue === undefined ? '?' : typeTextOf(typeValue, facts, view.name),
      typeTitle: typeValue === undefined ? '' : qualifiedTypeText(typeValue),
      fieldKind: own?.fieldKind ?? facts?.fieldKind ?? null,
      column: own?.column ?? facts?.column ?? null,
      relation: own?.relation ?? facts?.relation ?? null,
      expression: own?.expression ?? facts?.expression ?? null,
      declaredIn: declaring ? declaring.type.simpleName : null,
      declaredPath: declaring ? scan.pathOf(declaring.type) : null,
      declaredLine: declaring ? declaring.line : -1,
      annotationPath: annotation ? annotation.path : null,
      annotationLine: annotation ? annotation.line : -1,
      declaring,
      links: new Map(),
    };
  });

  // Columns are the (aspect, role) pairs a field actually occupies — probed, never assumed.
  const columns = [];
  let columnId = 0;
  for (const aspect of aspects) {
    for (const role of ROLE_ORDER) {
      const present = fields.some((field) => cellForSource(scan, aspect, role, field, viewFqcn) !== null);
      if (!present) {
        continue;
      }
      columns.push({
        id: columnId++,
        artifact: aspect.name,
        artifactFqcn: aspect.fqcn,
        role,
        roleLabel: ROLE_LABELS[role] ?? role,
        path: aspect.path,
        line: aspect.line,
        generated: aspect.generated,
      });
    }
  }

  for (const field of fields) {
    for (const column of columns) {
      const aspect = aspects.find((candidate) => candidate.fqcn === column.artifactFqcn);
      const cell = cellForSource(scan, aspect, column.role, field, viewFqcn);
      if (!cell) {
        continue;
      }
      stats.checked += 1;
      const reason = validate(cache, cell, field, column.role);
      if (reason) {
        stats.stale += 1;
        divergences.push(new Divergence('html_link_stale',
          `${cell.path}:${cell.line}`,
          reason,
          `link for ${field.name} (${column.role}) in ${column.artifact}`,
          'a line that contains the member',
          'rerun a generator pass so the metadata and the links agree, then re-render the index'));
        continue;
      }
      field.links.set(column.id, {
        path: cell.path,
        line: cell.line,
        member: field.name,
        role: column.role,
        where: column.artifact,
      });
      stats.links += 1;
    }
  }

  stats.views += 1;
  stats.fields += fields.length;
  return {
    name: view.name,
    fqcn: viewFqcn,
    path: viewPath,
    line: viewLine,
    gen: view.gen,
    addons: view.addons,
    extendsTypes: view.extendsTypes,
    discriminatorField: view.discriminatorField,
    aspects: aspects.map(({ type, ...rest }) => rest),
    columns,
    fields,
  };
}

/**
 * The view's own declaration.
 *
 * **Metadata first** (DEC-027 § 4.1): the generator records each view's `sourcePath` relative to the
 * module root, so the file is a fact rather than a search. That matters because a view need not live in
 * its marker's package — the example's `PaymentMethodAuditable` belongs to the `example.Auditable`
 * marker — and a name-based search cannot tell two same-named types apart. Only a JSON produced before
 * `sourcePath` existed falls back to resolving the name: the marker's package first, then a unique
 * simple-name match, and an ambiguity is reported.
 */
function resolveViewType(scan, marker, view, divergences) {
  if (view.sourcePath) {
    const byPath = scan.typeAtPath(view.sourcePath, view.name);
    if (byPath) {
      return byPath;
    }
    // The metadata is authoritative even when the scan did not reach the file: the path is what the
    // page links with, and a synthetic entry keeps the artifact lookup (which is header- or
    // convention-driven) working from that path.
    return syntheticType(view.sourcePath, view.name, marker.packageName, view.lineNumber);
  }
  const candidates = scan.typesNamed(view.name);
  if (candidates.length === 0) {
    divergences.push(new Divergence('html_view_file_missing', view.name,
      'no type with this simple name exists under the source root',
      `view ${view.name} of marker ${marker.markerInterface}`, 'the view\'s own .java file',
      'check that the metadata is fresh (rerun a generator pass) and that the view compiles'));
    return null;
  }
  const samePackage = candidates.find((candidate) => candidate.packageName === marker.packageName);
  if (samePackage) {
    return samePackage;
  }
  if (candidates.length > 1) {
    divergences.push(new Divergence('html_view_file_ambiguous', view.name,
      'several types share this simple name and none is in the marker\'s package',
      candidates.map((candidate) => candidate.fqcn).join(', '), `${marker.packageName}.${view.name}`,
      'regenerate the metadata so it carries sourcePath, or run the renderer with --packages'));
  }
  return candidates[0];
}

/** A type the metadata names but the scan did not reach, carrying just its path and declaration line. */
function syntheticType(sourcePath, simpleName = null, packageName = '', line = -1) {
  const base = sourcePath.slice(sourcePath.lastIndexOf('/') + 1).replace(/\.java$/, '');
  const name = simpleName ?? base;
  return new TypeInfo(packageName ? `${packageName}.${name}` : name, name, 'type', packageName,
    join(sourcePath), line, sourcePath);
}

/** Builds one view: its aspects, its fields (in ledger order), its columns and its cells. */
function buildView(context) {
  const { marker, view, scan, cache, divergences, stats, resolved } = context;
  const viewFqcn = resolved ? resolved.fqcn : `${marker.packageName}.${view.name}`;
  const viewPath = resolved ? scan.pathOf(resolved) : null;
  const viewLine = resolved
    ? firstLineOf(cache, viewPath, [view.lineNumber, resolved.line], view.name)
    : -1;

  const aspectTypes = resolved ? scan.artifactsFor(viewFqcn) : [];
  const aspects = [];
  if (resolved) {
    aspects.push({
      fqcn: viewFqcn,
      name: view.name,
      kind: resolved.kind,
      path: viewPath,
      line: viewLine,
      generated: false,
      description: '@View declaration',
      type: resolved,
    });
  }
  for (const type of aspectTypes) {
    aspects.push({
      fqcn: type.fqcn,
      name: type.ownerFqcn ? type.fqcn.slice(type.ownerFqcn.length + 1) : type.simpleName,
      kind: type.kind,
      path: scan.pathOf(type),
      line: firstLineOf(cache, scan.pathOf(type), [type.line], type.simpleName),
      generated: type.headerTargets.includes(viewFqcn),
      description: type.headerDescription ?? (type.ownerFqcn ? 'nested type' : null),
      type,
    });
  }

  const enumAspect = aspects.find((aspect) => aspect.fqcn === `${viewFqcn}_`) ?? null;
  const ledgerOrder = enumAspect && enumAspect.type.enumOrder.length > 0
    ? [...enumAspect.type.enumOrder]
    : fallbackOrder(marker, view, resolved);

  // The page shows the union: the ledger's fields (with their ordinals, DEC-023) plus any field the
  // metadata attributes to this view that the ledger has no constant for. The second group is a real
  // fact about the two sources disagreeing, so it is rendered *and* reported — but as a warning, since
  // the page can still say where those fields live.
  const attributed = marker.fieldsFor(view.name).map((field) => field.name);
  const extra = attributed.filter((name) => !ledgerOrder.includes(name));
  const order = [...ledgerOrder, ...extra];
  if (extra.length > 0) {
    divergences.push(new Divergence('html_field_not_in_ledger',
      enumAspect ? `${enumAspect.path}:${enumAspect.line}` : view.name,
      'the metadata attributes these fields to the view, but its field enum has no constant for them',
      extra.join(', '),
      `a constant per attributed field in ${view.name}_`,
      'the page lists them without an ordinal, because the ledger — not the metadata union — defines '
      + 'the positional contract; regenerate and, if the constants are still absent, treat it as a '
      + 'generator question rather than a renderer one',
      'warning'));
  }

  const fields = order.map((fieldName) => {
    const facts = marker.byName.get(fieldName);
    const own = marker.ownProperty(view.name, fieldName);
    const declaring = validDeclaring(cache, scan,
      declaringAccessor(scan, marker, view, resolved, fieldName, facts, own), fieldName);
    const annotation = declaring
      ? annotationCell(cache, scan, declaring, fieldName, own, facts)
      : null;
    const typeValue = own?.type ?? facts?.rawType;
    return {
      ordinal: enumAspect && ledgerOrder.includes(fieldName) ? ledgerOrder.indexOf(fieldName) + 1 : null,
      inLedger: enumAspect === null ? null : ledgerOrder.includes(fieldName),
      name: fieldName,
      typeText: typeValue === undefined ? '?' : typeTextOf(typeValue, facts, view.name),
      typeTitle: typeValue === undefined ? '' : qualifiedTypeText(typeValue),
      fieldKind: own?.fieldKind ?? facts?.fieldKind ?? null,
      column: own?.column ?? facts?.column ?? null,
      relation: own?.relation ?? facts?.relation ?? null,
      expression: own?.expression ?? facts?.expression ?? null,
      declaredIn: declaring ? declaring.type.simpleName : null,
      declaredPath: declaring ? scan.pathOf(declaring.type) : null,
      declaredLine: declaring ? declaring.line : -1,
      annotationPath: annotation ? annotation.path : null,
      annotationLine: annotation ? annotation.line : -1,
      declaring,
      links: new Map(),
    };
  });

  // Columns are the (aspect, role) pairs a field actually occupies — probed, never assumed.
  const columns = [];
  let columnId = 0;
  for (const aspect of aspects) {
    for (const role of ROLE_ORDER) {
      const present = fields.some((field) => cellFor(scan, aspect, role, field, viewFqcn) !== null);
      if (!present) {
        continue;
      }
      columns.push({
        id: columnId++,
        artifact: aspect.name,
        artifactFqcn: aspect.fqcn,
        role,
        roleLabel: ROLE_LABELS[role] ?? role,
        path: aspect.path,
        line: aspect.line,
        generated: aspect.generated,
      });
    }
  }

  for (const field of fields) {
    for (const column of columns) {
      const aspect = aspects.find((candidate) => candidate.fqcn === column.artifactFqcn);
      const cell = cellFor(scan, aspect, column.role, field, viewFqcn);
      if (!cell) {
        continue;
      }
      stats.checked += 1;
      const reason = validate(cache, cell, field, column.role);
      if (reason) {
        stats.stale += 1;
        divergences.push(new Divergence('html_link_stale',
          `${cell.path}:${cell.line}`,
          reason,
          `link for ${field.name} (${column.role}) in ${column.artifact}`,
          'a line that contains the member',
          'rerun a generator pass so the metadata and the links agree, then re-render the index'));
        continue;
      }
      field.links.set(column.id, {
        path: cell.path,
        line: cell.line,
        member: field.name,
        role: column.role,
        where: column.artifact,
      });
      stats.links += 1;
    }
  }

  stats.views += 1;
  stats.fields += fields.length;
  return {
    name: view.name,
    fqcn: viewFqcn,
    path: viewPath,
    line: viewLine,
    gen: view.gen,
    addons: view.addons,
    extendsTypes: view.extendsTypes,
    discriminatorField: view.discriminatorField,
    aspects: aspects.map(({ type, ...rest }) => rest),
    columns,
    fields,
  };
}

/** The view's declaration line: the metadata's when that line really is the declaration. */
function firstLineOf(cache, path, candidates, name) {
  for (const candidate of candidates) {
    if (candidate > 0 && namesMember(cache, path, candidate, name)) {
      return candidate;
    }
  }
  return candidates.find((candidate) => candidate > 0) ?? -1;
}

function namesMember(cache, path, line, name) {
  const text = cache.line(path, line);
  return text !== null && containsIdentifier(text, name);
}

/** The field order when there is no field enum: own properties first, then the rest, from the JSON. */
function fallbackOrder(marker, view, resolved) {
  const order = [];
  for (const property of view.properties) {
    if (!order.includes(property.name)) {
      order.push(property.name);
    }
  }
  for (const field of marker.fieldsFor(view.name)) {
    if (!order.includes(field.name)) {
      order.push(field.name);
    }
  }
  return order;
}

function typeTextOf(typeValue, facts, viewName) {
  if (typeValue === undefined) {
    return '?';
  }
  return facts ? facts.typeFor(viewName) : typeText(typeValue);
}

/**
 * The type that declares a field's accessor.
 *
 * **Metadata first** (DEC-027 § 4.1), and the metadata's answer is the *declaring* file, not the view's:
 * the generator records a `sourcePath` on each view, on each property it parses and on each merged
 * field, so an inherited accessor resolves to the interface that declares it (`PersonDetails.firstName`
 * → `Person.java`) and an addon field to its addon source (`createdAt` → `Auditable.java`) without a
 * search. Within that file the type that actually holds the accessor is chosen, which is what makes the
 * tooltip say `Person` rather than the file's first type.
 *
 * Only a JSON produced before `sourcePath` existed falls back to the older route: the `extends` chain
 * the metadata names, then the source scan, then the line number the generator recorded.
 */
function declaringAccessor(scan, marker, view, resolved, fieldName, facts, own) {
  const metaPath = own?.sourcePath ?? facts?.sourcePath ?? null;
  if (metaPath) {
    const inFile = scan.typesAtPath(metaPath);
    const type = inFile.find((candidate) => candidate.lineOf(fieldName, 'accessor') !== undefined)
      ?? inFile.find((candidate) => candidate.ownerFqcn === null)
      ?? syntheticType(metaPath);
    const scanned = type.lineOf(fieldName, 'accessor');
    const line = scanned !== undefined
      ? scanned
      : Math.max(own?.lineNumber ?? -1, facts?.lineNumber ?? -1);
    if (line > 0) {
      return { type, line };
    }
  }

  const queue = [];
  if (resolved) {
    queue.push(resolved);
  }
  for (const raw of view.extendsTypes) {
    queue.push(...scan.typesNamed(simpleNameOf(raw)));
  }
  const visited = new Set();
  while (queue.length > 0) {
    const type = queue.shift();
    if (!type || visited.has(type.fqcn)) {
      continue;
    }
    visited.add(type.fqcn);
    const line = type.lineOf(fieldName, 'accessor');
    if (line !== undefined) {
      return { type, line };
    }
    for (const superName of type.extendsNames ?? []) {
      queue.push(...scan.typesNamed(simpleNameOf(superName)));
    }
  }
  // Last resort: the line the generator recorded for the field, matched against the scan's accessors.
  if (facts && facts.lineNumber > 0) {
    for (const type of scan.types.values()) {
      const line = type.lineOf(fieldName, 'accessor');
      if (line === facts.lineNumber) {
        return { type, line };
      }
    }
  }
  return null;
}

/**
 * The `@FieldSource` line for a field, or null.
 *
 * The annotation is a peer of the accessor rather than part of it — it is where a DERIVED/JOINED field
 * is actually defined — so it gets its own cell. The scan finds it by looking ahead from
 * `@FieldSource` to the annotated method; a file the scan did not reach falls back to the line the
 * metadata recorded for the view's own property, which for an annotated accessor *is* the annotation's
 * line (verified by reading it).
 */
function annotationCell(cache, scan, declaring, fieldName, own, facts) {
  const scanned = declaring.type.lineOf(fieldName, 'annotation');
  if (scanned !== undefined) {
    return { path: scan.pathOf(declaring.type), line: scanned };
  }
  const metadataLine = own?.lineNumber ?? facts?.lineNumber ?? -1;
  const path = scan.pathOf(declaring.type);
  if (metadataLine > 0 && (cache.line(path, metadataLine) ?? '').includes('@FieldSource')) {
    return { path, line: metadataLine };
  }
  return null;
}

/**
 * Where one field lives inside one aspect under one role, or null.
 *
 * The declaring-interface fallback applies to the **view's own aspect only**. A generated builder or
 * record does not inherit the interface's accessor: what it has is its own getter, its own setter, its
 * own field declaration, and the page says so by leaving the other cells empty. Only the view aspect
 * — whose contract *is* the interface — points at the interface that declares an inherited field,
 * which is what keeps `PersonDetails.firstName` clickable through to `Person`.
 */
function cellForSource(scan, aspect, role, field, viewFqcn) {
  if (!aspect) {
    return null;
  }
  const own = aspect.type ? aspect.type.lineOf(field.name, role) : undefined;
  if (own !== undefined) {
    return { path: scan.pathOf(aspect.type), line: own };
  }
  if (aspect.fqcn !== viewFqcn) {
    return null;
  }
  if (role === 'accessor' && field.declaredPath) {
    return { path: field.declaredPath, line: field.declaredLine };
  }
  if (role === 'annotation' && field.annotationPath) {
    return { path: field.annotationPath, line: field.annotationLine };
  }
  return null;
}

/**
 * Keeps a declaring-accessor candidate only when its line really is that accessor.
 *
 * The cell that carries it is the field's own name in the table's first column, and it is the one
 * link the column probe cannot double-check — so it is checked here, and a candidate that fails is
 * dropped rather than rendered. A dropped one is not a divergence: "declared outside this source
 * root" is a legitimate, common answer (an inherited `id`), and the page says exactly that.
 */
function validDeclaring(cache, scan, declaring, fieldName) {
  if (!declaring) {
    return null;
  }
  const path = scan.pathOf(declaring.type);
  const text = cache.line(path, declaring.line);
  if (text === null || !containsIdentifier(text, fieldName)) {
    return null;
  }
  return declaring;
}

/** Why a candidate cell is not a usable link, or null when it is one. */
function validate(cache, cell, field, role) {
  if (!cell.path) {
    return 'the artifact has no path in the source scan';
  }
  const text = cache.line(cell.path, cell.line);
  if (text === null) {
    return 'the file is missing or the line is outside it';
  }
  if (role === 'annotation') {
    return text.includes('@FieldSource') ? null : 'the line does not carry a @FieldSource annotation';
  }
  if (!containsIdentifier(text, field.name)) {
    return 'the line does not contain the member name';
  }
  return null;
}

/** `com/acme/Person.java` -> `Person`: the name the page prints for a declaring interface. */
function baseNameOf(path) {
  const base = path.slice(path.lastIndexOf('/') + 1);
  return base.replace(/\.java$/, '');
}

/** `com.acme.Person` / `com.acme.Person<Long>` -> `Person`. */
function simpleNameOf(raw) {
  const withoutGenerics = raw.split('<')[0].trim();
  const last = withoutGenerics.lastIndexOf('.');
  return last < 0 ? withoutGenerics : withoutGenerics.slice(last + 1);
}
