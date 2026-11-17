/**
 * The model behind the entity browser: every indexed type, the records among them, each record's
 * components, and the types those components and relations reach.
 *
 * <p>This module is deliberately free of `fs` and of the page. The build reads the class index files
 * (`.jcodebuddy/index/classes.json`, DEC-029) and their `imports.json` sidecars, hands them here, and
 * gets back one plain object that the page renders. There is no Java parsing in this file and no
 * second model: a fact the table does not carry is reported as a diagnostic, never guessed from the
 * source.</p>
 *
 * <h3>Records first, dependencies second</h3>
 * The first view is the module's records. A record has components (the header) and relations
 * (`implements`), and each component's type names other types. A name is resolved to a fully
 * qualified type when the class index or the declaring file's imports can say which one it is:
 *
 *   1. an exact FQN in the index;
 *   2. the declaring package, then the enclosing types of a nested declaration;
 *   3. an explicit import in the declaring file;
 *   4. a wildcard import's package;
 *   5. a unique simple name in the index (ambiguous names are left unlinked, and reported).
 *
 * <p>Everything else is an external type. It stays in the page as text — the page never claims to
 * open a file it does not know — and it is grouped with the other external dependencies rather than
 * silently dropped.</p>
 *
 * <h3>Enums are answers, not another click</h3>
 * A component whose type is an indexed enum carries that enum's constants on the component itself.
 * For an enum with {@link INLINE_ENUM_LIMIT} constants or fewer the page prints them after the name;
 * a larger enum prints the count and offers the drill-down. That is the point of the model flag: the
 * decision is made once, here, where the count is known, rather than by every renderer.
 */

/** Enums up to this many constants are shown in full beside the component. */
export const INLINE_ENUM_LIMIT = 12;

const PRIMITIVES = new Set([
  'boolean', 'byte', 'char', 'double', 'float', 'int', 'long', 'short', 'void',
]);

/** Words that are part of a type expression but name no type of their own. */
const TYPE_WORDS = new Set(['extends', 'super', 'final', 'var']);

/** JDK packages whose prefix is dropped from the type cell, so the column stays readable. */
const JDK_PACKAGES = ['java.lang.', 'java.util.', 'java.time.', 'java.math.', 'java.io.', 'java.util.function.'];

/** `java.util.List` -> `List`; a name outside those packages is returned unchanged. */
function displayTypeName(name) {
  for (const prefix of JDK_PACKAGES) {
    if (name.startsWith(prefix)) {
      return name.slice(prefix.length);
    }
  }
  return name;
}

/** The type-name spelling the index writes: identifiers with optional dots. */
const TYPE_NAME = /[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*/g;

/** @typedef {{kind: string, severity: string, location: string, cause: string, current: string, canonical: string, action: string}} Diagnostic */

/**
 * A DEC-022 diagnostic. The build prints these; the page footer counts them.
 *
 * @param {string} kind
 * @param {'error'|'warning'} severity
 * @param {string} location
 * @param {string} cause
 * @param {string} current
 * @param {string} canonical
 * @param {string} action
 * @returns {Diagnostic}
 */
export function diagnostic(kind, severity, location, cause, current, canonical, action) {
  return { kind, severity, location, cause, current, canonical, action };
}

/** A diagnostic as the one-line DEC-022 format the other reports use. */
export function formatDiagnostic(item) {
  return `[${item.severity}] ${item.kind} @ ${item.location}: ${item.cause} (current: ${item.current}; `
    + `canonical: ${item.canonical}; action: ${item.action})`;
}

/** The package a fully qualified name sits in, for a top-level type. */
function packageOfFqn(fqn) {
  const dot = fqn.lastIndexOf('.');
  return dot < 0 ? '' : fqn.slice(0, dot);
}

/** The last segment of a dotted name. */
function lastSegment(name) {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? name : name.slice(dot + 1);
}

/**
 * Reads a file's import lines (the `imports.json` sidecar) into the two lookups name resolution
 * needs: explicit simple-name imports, and wildcard packages. A static import is ignored: it names a
 * member, not a type, and resolving one as a type would be a wrong link.
 *
 * @param {string[]|undefined} lines
 */
export function parseImports(lines) {
  const explicit = new Map();
  const wildcards = [];
  for (const line of lines ?? []) {
    const text = String(line).trim().replace(/;$/, '');
    if (!text.startsWith('import ')) {
      continue;
    }
    const body = text.slice('import '.length).trim();
    if (body.startsWith('static ')) {
      continue;
    }
    if (body.endsWith('.*')) {
      wildcards.push(body.slice(0, -2));
      continue;
    }
    const simple = lastSegment(body);
    if (simple) {
      explicit.set(simple, body);
    }
  }
  return { explicit, wildcards };
}

/**
 * Builds the page model from the class-index entries the build prepared.
 *
 * @param {{
 *   indexes: Array<{id: string, module: string, sourceRoot: string, classes: Array<{fqn: string, row: object, path: string, imports?: string[]}>}>,
 *   title?: string,
 *   bridgePort?: number,
 *   generation?: object,
 * }} input
 */
export function buildBrowserModel({ indexes, title = 'Entity browser', bridgePort = 18881, generation = null }) {
  /** @type {Diagnostic[]} */
  const diagnostics = [];
  const byFqn = new Map();
  const bySimple = new Map();
  const byPackage = new Map();
  /** fqn -> the index entry that declared it, for source verification and module labels. */
  const sourceOf = new Map();

  // Pass 1: register every row. Resolution is a whole-index question, so it cannot happen while one
  // file is being read.
  for (const index of indexes) {
    if (!index.data || index.data.format !== 1) {
      diagnostics.push(diagnostic('browser_index_format', 'error', index.id,
        'the class index has a format this browser does not understand',
        JSON.stringify(index.data?.format), '1', 'regenerate the module, or update the browser'));
      continue;
    }
    for (const entry of index.classes) {
      const { fqn, row } = entry;
      if (byFqn.has(fqn)) {
        diagnostics.push(diagnostic('browser_duplicate_type', 'error', `${index.id}:${fqn}`,
          'two indexes declare the same fully qualified type',
          `also declared by ${sourceOf.get(fqn)?.index.id ?? 'another index'}`,
          'one row per FQN', 'index one module per browser, or fix the duplicate source roots'));
        continue;
      }
      const packageName = row.enclosing ? packageOfFqn(row.enclosing) : packageOfFqn(fqn);
      const displayName = fqn.slice(packageName.length + 1);
      const simpleName = lastSegment(displayName);
      const registered = {
        entry,
        fqn,
        row,
        packageName,
        displayName,
        simpleName,
        module: index.module,
        sourceRoot: index.sourceRoot ?? '',
        imports: parseImports(entry.imports),
      };
      byFqn.set(fqn, registered);
      sourceOf.set(fqn, { index, entry });
      for (const name of new Set([simpleName, displayName])) {
        if (!bySimple.has(name)) {
          bySimple.set(name, []);
        }
        bySimple.get(name).push(fqn);
      }
      if (!byPackage.has(packageName)) {
        byPackage.set(packageName, new Map());
      }
      const inPackage = byPackage.get(packageName);
      for (const name of new Set([simpleName, displayName])) {
        if (!inPackage.has(name)) {
          inPackage.set(name, []);
        }
        inPackage.get(name).push(fqn);
      }
    }
  }

  /**
   * Resolves one written type name in the context of the declaration that wrote it.
   *
   * @returns {{fqn: string|null, name: string, local: boolean, ambiguous?: boolean}}
   */
  function resolveName(raw, context) {
    const text = String(raw ?? '').trim();
    if (!text) {
      return { fqn: null, name: text, local: false };
    }
    if (byFqn.has(text)) {
      return { fqn: text, name: text, local: true };
    }
    // A nested type written with its outer name, a member of the declaring type, or a top-level type in
    // the same package. The declaring type comes first: a nested member named `Record` belongs to the
    // type that declares it, not to the first other type that happens to share the simple name.
    const candidates = [];
    candidates.push(`${context.fqn}.${text}`);
    candidates.push(context.packageName ? `${context.packageName}.${text}` : text);
    let enclosing = context.fqn;
    while (enclosing && byFqn.get(enclosing)?.row.enclosing) {
      enclosing = byFqn.get(enclosing).row.enclosing;
      candidates.push(`${enclosing}.${text}`);
    }
    for (const candidate of candidates) {
      if (byFqn.has(candidate)) {
        return { fqn: candidate, name: text, local: true };
      }
    }
    // `import a.b.Outer;` followed by `Outer.Inner`.
    const first = text.split('.')[0];
    const explicitOuter = context.imports.explicit.get(first);
    if (explicitOuter) {
      const viaOuter = `${explicitOuter}${text.slice(first.length)}`;
      if (byFqn.has(viaOuter)) {
        return { fqn: viaOuter, name: text, local: true };
      }
      if (text === first) {
        return { fqn: null, name: explicitOuter, local: false };
      }
    }
    const explicit = context.imports.explicit.get(text);
    if (explicit) {
      return byFqn.has(explicit)
        ? { fqn: explicit, name: text, local: true }
        : { fqn: null, name: explicit, local: false };
    }
    for (const wildcard of context.imports.wildcards) {
      const candidate = `${wildcard}.${text}`;
      if (byFqn.has(candidate)) {
        return { fqn: candidate, name: text, local: true };
      }
    }
    const sameSimple = bySimple.get(text) ?? [];
    if (sameSimple.length === 1) {
      return { fqn: sameSimple[0], name: text, local: true };
    }
    if (sameSimple.length > 1) {
      return { fqn: null, name: text, local: false, ambiguous: true };
    }
    return { fqn: null, name: text, local: false };
  }

  /** A type expression as display parts, with every type name that resolved locally marked. */
  function typeModel(text, context, location) {
    const source = String(text ?? '');
    const parts = [];
    const refs = [];
    const external = [];
    let last = 0;
    for (const match of source.matchAll(TYPE_NAME)) {
      if (match.index > last) {
        parts.push({ text: source.slice(last, match.index) });
      }
      const raw = match[0];
      if (PRIMITIVES.has(raw) || TYPE_WORDS.has(raw)) {
        parts.push({ text: raw });
      } else {
        const resolved = resolveName(raw, context);
        if (resolved.local) {
          refs.push(resolved.fqn);
          parts.push({ text: raw, target: resolved.fqn, display: displayTypeName(raw) });
        } else {
          external.push(resolved.name);
          parts.push({ text: raw, ambiguous: resolved.ambiguous === true, display: displayTypeName(raw) });
          if (resolved.ambiguous) {
            diagnostics.push(diagnostic('browser_ambiguous_type', 'warning', location,
              'a simple type name matches more than one indexed type, so it is not linked',
              raw, 'a qualified name or an import', 'qualify the type in the source, or index one module'));
          }
        }
      }
      last = match.index + raw.length;
    }
    if (last < source.length) {
      parts.push({ text: source.slice(last) });
    }
    return {
      text: source,
      parts,
      refs: [...new Set(refs)].sort(),
      primary: refs.length > 0 ? refs[0] : null,
      external: [...new Set(external)].sort(),
    };
  }

  /** One raw member row, in the shape the page needs and no wider. */
  function memberModel(member, context) {
    const type = typeModel(member.type ?? '', context,
      `${context.fqn}#${member.name}:${member.kind}`);
    const parameters = (member.parameters ?? []).map((parameter) => ({
      name: parameter.name ?? '',
      type: typeModel(parameter.type ?? '', context, `${context.fqn}#${member.name}(${parameter.name})`),
    }));
    return {
      name: member.name,
      kind: member.kind,
      modifiers: member.modifiers ?? [],
      annotations: (member.annotations ?? []).map((annotation) => ({
        name: annotation.name,
        args: annotation.args ?? [],
        text: `@${annotation.name}${annotation.args?.length ? `(${annotation.args.join(', ')})` : ''}`,
      })),
      line: typeof member.line === 'number' ? member.line : -1,
      sourceLine: typeof member.line === 'number' ? member.line : 0,
      type,
      parameters,
      signature: parameters.length > 0
        ? `${member.name}(${parameters.map((parameter) => parameter.type.text).join(', ')})`
        : member.name,
      hasBody: member.hasBody === true,
      throws: member.throws ?? [],
    };
  }

  // Pass 2: resolve every declaration now that the whole index is known.
  const types = {};
  for (const fqn of [...byFqn.keys()].sort()) {
    const registered = byFqn.get(fqn);
    const { entry, row, packageName, displayName, simpleName } = registered;
    const context = { fqn, packageName, imports: registered.imports };
    const location = `${entry.module}:${row.path}`;
    const members = (row.members ?? []).map((member) => memberModel(member, context));
    const components = members.filter((member) => member.kind === 'record-component');
    const enumConstants = members.filter((member) => member.kind === 'enum-constant');
    const relations = (row.relations ?? []).map((relation) => {
      const resolved = resolveName(relation.name, context);
      return {
        kind: relation.kind,
        name: relation.name,
        target: resolved.local ? resolved.fqn : null,
      };
    });
    const dependencies = new Set();
    const externalDependencies = new Set();
    for (const member of members) {
      for (const ref of member.type.refs) {
        dependencies.add(ref);
      }
      for (const name of member.type.external) {
        externalDependencies.add(name);
      }
      for (const parameter of member.parameters) {
        for (const ref of parameter.type.refs) {
          dependencies.add(ref);
        }
        for (const name of parameter.type.external) {
          externalDependencies.add(name);
        }
      }
    }
    for (const relation of relations) {
      if (relation.target) {
        dependencies.add(relation.target);
      }
    }
    for (const component of components) {
      if (component.type.primary) {
        const target = byFqn.get(component.type.primary);
        if (target && target.row.kind === 'enum') {
          const constants = (target.row.members ?? [])
            .filter((member) => member.kind === 'enum-constant')
            .map((member) => member.name);
          component.enum = {
            fqn: target.fqn,
            name: target.displayName,
            constants,
            inline: constants.length <= INLINE_ENUM_LIMIT,
            more: Math.max(0, constants.length - INLINE_ENUM_LIMIT),
          };
        }
      }
    }
    dependencies.delete(fqn);
    const displayDependencies = [...dependencies].sort().map((target) => {
      const targetRow = byFqn.get(target);
      const targetKind = targetRow?.row.kind ?? 'type';
      const constants = targetKind === 'enum'
        ? (targetRow.row.members ?? []).filter((member) => member.kind === 'enum-constant').map((member) => member.name)
        : [];
      return {
        fqn: target,
        name: targetRow?.displayName ?? target,
        kind: targetKind,
        package: targetRow?.packageName ?? packageOfFqn(target),
        componentCount: targetRow
          ? (targetRow.row.members ?? []).filter((member) => member.kind === 'record-component').length
          : 0,
        enum: targetKind === 'enum'
          ? {
            constants,
            inline: constants.length <= INLINE_ENUM_LIMIT,
            more: Math.max(0, constants.length - INLINE_ENUM_LIMIT),
          }
          : null,
      };
    });
    const searchText = [
      displayName,
      fqn,
      packageName,
      ...components.map((component) => `${component.name} ${component.type.text}`),
      ...components.flatMap((component) => component.enum?.constants ?? []),
      ...enumConstants.map((constant) => constant.name),
      ...relations.map((relation) => relation.name),
      ...displayDependencies.map((dependency) => dependency.name),
      ...displayDependencies.flatMap((dependency) => dependency.enum?.constants ?? []),
    ].join(' ').toLowerCase();
    types[fqn] = {
      fqn,
      name: displayName,
      simpleName,
      package: packageName,
      kind: row.kind,
      modifiers: row.modifiers ?? [],
      generated: row.generated === 1 || row.generated === true,
      path: entry.path,
      line: typeof row.line === 'number' ? row.line : -1,
      sourceLine: typeof row.line === 'number' ? row.line : 0,
      module: registered.module,
      sourceRoot: registered.sourceRoot,
      annotations: (row.annotations ?? []).map((annotation) => ({
        name: annotation.name,
        args: annotation.args ?? [],
        text: `@${annotation.name}${annotation.args?.length ? `(${annotation.args.join(', ')})` : ''}`,
      })),
      relations,
      components,
      enumConstants,
      fields: members.filter((member) => member.kind === 'field'),
      methods: members.filter((member) => member.kind === 'method'),
      constructors: members.filter((member) => member.kind === 'constructor'),
      nested: members.filter((member) => member.kind === 'nested'),
      dependencies: displayDependencies,
      externalDependencies: [...externalDependencies].sort(),
      searchText,
    };
    // A source location is written only when the build verified it; the verification pass runs after
    // the model is built because it reads files, which this module deliberately does not do.
    types[fqn].sourceAvailable = true;
  }

  const records = Object.values(types)
    .filter((type) => type.kind === 'record')
    .sort((a, b) => a.name.localeCompare(b.name) || a.fqn.localeCompare(b.fqn))
    .map((type) => type.fqn);

  const enumCount = Object.values(types).filter((type) => type.kind === 'enum').length;
  const modules = indexes.map((index) => ({
    id: index.id,
    module: index.module,
    sourceRoot: index.sourceRoot ?? '',
    directory: index.directory ?? '',
    classes: index.classes.length,
  })).sort((a, b) => a.module.localeCompare(b.module) || a.id.localeCompare(b.id));

  const model = {
    schemaVersion: 1,
    title,
    bridgePort,
    modules,
    types,
    records,
    stats: {
      types: Object.keys(types).length,
      records: records.length,
      enums: enumCount,
      modules: modules.length,
      components: Object.values(types).reduce((total, type) => total + type.components.length, 0),
      inlineEnums: Object.values(types).reduce(
        (total, type) => total + type.components.filter((component) => component.enum?.inline).length, 0),
    },
    generation,
    diagnostics,
  };
  return model;
}

/**
 * The record a deep link names, or the first record. Used by the page and by the tests.
 *
 * @param {object} model
 * @param {string} [fqn]
 */
export function selectedInitial(model, fqn) {
  if (fqn && model.types[fqn]) {
    return fqn;
  }
  return model.records[0] ?? Object.keys(model.types)[0] ?? null;
}

/**
 * Filters the sidebar. Records are the default scope; `all` widens it to every indexed type. The
 * query matches the search text the model prepared (name, package, components, enum constants and
 * local dependencies), so nothing is re-derived per keystroke.
 *
 * @param {object} model
 * @param {string} query
 * @param {'records'|'all'} scope
 */
export function filterTypes(model, query, scope = 'records') {
  const pool = scope === 'all' ? Object.keys(model.types) : model.records;
  const needle = String(query ?? '').trim().toLowerCase();
  const rows = pool.map((fqn) => model.types[fqn]).filter(Boolean);
  if (!needle) {
    return rows;
  }
  const terms = needle.split(/\s+/).filter(Boolean);
  return rows.filter((row) => terms.every((term) => row.searchText.includes(term)));
}
