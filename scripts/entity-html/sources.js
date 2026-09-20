/**
 * Source locations for the entity HTML index.
 *
 * <h3>Why a source scan exists at all</h3>
 * The generator's metadata JSON is the model of a module's entities: which views exist, which fields
 * they expose, each field's type, kind, column, relation and expression, and the line of the
 * accessor that declares it. What it does *not* carry is a single file path, nor any location inside
 * a generated artifact, nor the ordinal layout of a view (its `allFields` array is a per-marker
 * union, not the per-view ledger). A page whose whole purpose is "click this and land on it" needs
 * those three things, so this module reads the committed Java and resolves them — and every location
 * it produces is validated before it reaches the page (see `links.js`).
 *
 * <h3>What it reads, and how it stays honest</h3>
 * The scan is line-oriented, not a parser, but it is not guessing either:
 *   - code is blanked first (comments and string/char literals replaced by spaces, line numbers
 *     preserved), so a brace or a declaration inside a comment or a string cannot move a line number;
 *   - the DEC-021 header (`// {@link <fqn>} <description>.`) is read from the *original* text,
 *     because it is a comment and blanking would erase it. That header is the generator's own
 *     navigable back-reference to the view a file was generated for, so it — not a name guess — is
 *     what pairs an artifact with its view;
 *   - nothing is emitted as a link unless the target line actually contains the member name, which
 *     is checked at build time (`links.js`) and reported as a divergence when it fails.
 *
 * This is the boundary recorded in DEC-027: the JSON is the model, the committed source is the
 * navigation target, and a renderer never invents a location.
 */
import { readFileSync, readdirSync, statSync } from 'fs';
import { join, relative, sep } from 'path';

/** Declaration kinds this scan recognises; the modifier prefix is consumed before the keyword. */
const DECLARATION = /^\s*(?:(?:public|protected|private|final|abstract|sealed|non-sealed|static|strictfp)\s+)*(@interface|interface|class|enum|record)\s+([A-Za-z_$][\w$]*)/;
const PACKAGE = /^\s*package\s+([\w.]+)\s*;/;
const HEADER = /^\s*\/\/\s*\{@link\s+([\w.$]+)\}\s*(.*)$/;
const ENUM_CONSTANT = /^\s*([A-Za-z_$][\w$]*)\s*\(/;
const METHOD = /^\s*(?:(?:public|protected|private)\s+)?(?:(?:static|final|default|abstract|synchronized|native|strictfp)\s+)*(?:<[^>]*>\s*)?([\w$.$<>,\[\]\s]+?)\s+([A-Za-z_$][\w$]*)\s*\(([^)]*)\)/;
const FIELD = /^\s*(?:(?:public|protected|private|static|final|transient|volatile)\s+)*(?!(?:return|if|for|while|switch|case|break|continue|throw|else|new|import|package|assert|try|catch|do)\b)([\w$.$<>,\[\]\s]+?)\s+([A-Za-z_$][\w$]*)\s*(?:=|;)/;
const CASE_ORDINAL = /^\s*case\s+(\d+)\s*(?:,|->|:)/;
const CASE_NAME = /^\s*case\s+"([^"]*)"\s*(?:,|->|:)/;
const FIELD_SOURCE = /^\s*@FieldSource\b/;
const ARM_TARGET = /->\s*\{?\s*(?:this\.)?([A-Za-z_$][\w$]*)/g;
const THIS_TARGET = /\bthis\.([A-Za-z_$][\w$]*)/g;

/**
 * One type declaration, with the member lines a field link can point at.
 *
 * Roles are the vocabulary the page's columns are built from: `accessor`, `annotation`,
 * `enum-constant`, `record-component`, `setter`, `field`, `ordinal-slot`, `name-slot`.
 */
export class TypeInfo {
  constructor(fqcn, simpleName, kind, packageName, file, line, relativePath = null) {
    this.fqcn = fqcn;
    this.simpleName = simpleName;
    this.kind = kind;
    this.packageName = packageName;
    this.file = file;
    this.line = line;
    /**
     * The file's path relative to the link base. Carried rather than recomputed so a type the metadata
     * names but the scan did not reach (a view outside the scanned source root) can still be
     * represented, with the path the generator recorded (DEC-027 section 4.1).
     */
    this.relativePath = relativePath;
    this.ownerFqcn = null;
    /** The brace depth of this type's own body: the depth a member declaration must sit at. */
    this.bodyDepth = -1;
    this.headerTargets = [];
    this.headerDescription = null;
    /** Simple names of the declared supertypes, for resolving where an inherited accessor lives. */
    this.extendsNames = [];
    /** Enum constants in declaration order: the DEC-023 ordinal ledger. */
    this.enumOrder = [];
    /** field name -> role -> the earliest line the role was seen on */
    this.members = new Map();
  }

  put(field, role, line) {
    if (!this.members.has(field)) {
      this.members.set(field, new Map());
    }
    const roles = this.members.get(field);
    if (!roles.has(role) || line < roles.get(role)) {
      roles.set(role, line);
    }
  }

  lineOf(field, role) {
    const roles = this.members.get(field);
    return roles ? roles.get(role) : undefined;
  }
}

/** Enclosing declarations by brace depth, so a nested type's qualified name is `Outer.Inner`. */
class TypeFrames {
  constructor() {
    this.byDepth = new Map();
  }

  /**
   * The innermost declaration enclosing `depth`.
   *
   * Not `at(depth - 1)`: a line inside a method body sits two or more levels below its type, and a
   * switch arm inside that body three — all of them still belong to the enclosing type, which is what
   * makes `case "id":` inside a generated `forName` a *name lookup for the enum*.
   */
  ownerAt(depth) {
    for (let d = depth - 1; d >= 0; d -= 1) {
      const frame = this.byDepth.get(d);
      if (frame) {
        return frame;
      }
    }
    return null;
  }

  /** The qualified name a declaration at `depth` gets, given the enclosing frames. */
  qualifyName(packageName, depth, simpleName) {
    const parts = [];
    for (let d = 0; d < depth; d += 1) {
      const frame = this.byDepth.get(d);
      if (frame) {
        parts.push(frame.simpleName);
      }
    }
    parts.push(simpleName);
    return packageName ? `${packageName}.${parts.join('.')}` : parts.join('.');
  }

  enter(depth, info) {
    this.byDepth.set(depth, info);
    for (const key of [...this.byDepth.keys()]) {
      if (key > depth) {
        this.byDepth.delete(key);
      }
    }
  }
}

/**
 * Replaces comments and string/char literals with spaces, preserving every line and column.
 *
 * Only structure is read from the blanked text (braces, declarations). Values that live *inside*
 * literals — a DEC-021 header, a `case "id"` label — are always read from the original.
 */
export function blankOut(code) {
  const out = code.split('');
  const n = code.length;
  let state = 'code';
  let i = 0;
  const blank = (index) => {
    if (out[index] !== '\n' && out[index] !== undefined) {
      out[index] = ' ';
    }
  };
  while (i < n) {
    const c = code[i];
    const c2 = code[i + 1];
    if (state === 'code') {
      if (c === '/' && c2 === '/') {
        blank(i);
        blank(i + 1);
        state = 'line';
        i += 2;
      } else if (c === '/' && c2 === '*') {
        blank(i);
        blank(i + 1);
        state = 'block';
        i += 2;
      } else if (c === '"' && code.startsWith('"""', i)) {
        blank(i);
        blank(i + 1);
        blank(i + 2);
        state = 'textBlock';
        i += 3;
      } else if (c === '"' || c === "'") {
        blank(i);
        state = c === '"' ? 'string' : 'char';
        i += 1;
      } else {
        i += 1;
      }
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        state = 'code';
      } else {
        blank(i);
      }
      i += 1;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && c2 === '/') {
        blank(i);
        blank(i + 1);
        state = 'code';
        i += 2;
      } else {
        blank(i);
        i += 1;
      }
      continue;
    }
    if (state === 'textBlock') {
      if (code.startsWith('"""', i)) {
        blank(i);
        blank(i + 1);
        blank(i + 2);
        state = 'code';
        i += 3;
      } else {
        blank(i);
        i += 1;
      }
      continue;
    }
    // string or char literal
    if (c === '\\') {
      blank(i);
      blank(i + 1);
      i += 2;
    } else if ((state === 'string' && c === '"') || (state === 'char' && c === "'")) {
      blank(i);
      state = 'code';
      i += 1;
    } else {
      blank(i);
      i += 1;
    }
  }
  return out.join('');
}

/** Every `.java` file under `root`, depth-first, sorted so two runs see the same order. */
export function javaFiles(root) {
  const found = [];
  const walk = (dir) => {
    for (const entry of readdirSync(dir).sort()) {
      const full = join(dir, entry);
      const stat = statSync(full);
      if (stat.isDirectory()) {
        if (entry === '.jcodebuddy' || entry === 'target' || entry === 'build' || entry === 'node_modules') {
          continue;
        }
        walk(full);
      } else if (entry.endsWith('.java')) {
        found.push(full);
      }
    }
  };
  walk(root);
  return found;
}

/** The scan's result: every type, its members, and the reverse indexes the link builder needs. */
export class SourceScan {
  constructor(root, linkBase) {
    this.root = root;
    this.linkBase = linkBase;
    this.sourceRootDisplay = relative(linkBase, root).split(sep).join('/');
    /** fqcn -> TypeInfo */
    this.types = new Map();
    /** simple name -> [TypeInfo] */
    this.bySimpleName = new Map();
    /** view fqcn -> [TypeInfo] whose DEC-021 header points back at that view */
    this.artifactsOf = new Map();
    /** relative path -> [TypeInfo] declared in that file */
    this.paths = new Map();
    this.fileErrors = [];
  }

  relativePath(file) {
    return relative(this.linkBase, file).split(sep).join('/');
  }

  typesNamed(simpleName) {
    return this.bySimpleName.get(simpleName) ?? [];
  }

  /** Adds one declaration to the type, simple-name and path indexes. */
  register(info, relativePath) {
    this.types.set(info.fqcn, info);
    if (!this.bySimpleName.has(info.simpleName)) {
      this.bySimpleName.set(info.simpleName, []);
    }
    this.bySimpleName.get(info.simpleName).push(info);
    if (!this.paths.has(relativePath)) {
      this.paths.set(relativePath, []);
    }
    this.paths.get(relativePath).push(info);
    for (const target of info.headerTargets) {
      if (!this.artifactsOf.has(target)) {
        this.artifactsOf.set(target, []);
      }
      this.artifactsOf.get(target).push(info);
    }
  }

  type(fqcn) {
    return this.types.get(fqcn);
  }

  pathOf(type) {
    return type.relativePath ?? this.relativePath(type.file);
  }

  /**
   * The type a metadata `sourcePath` points at.
   *
   * The generator records the declaring file, so the path is exact where a name is not: a view may live
   * in another package than its marker (the example's `PaymentMethodAuditable` belongs to the
   * `example.Auditable` marker), and no name-based search can tell two same-named types apart.
   *
   * @param relativePath the path the metadata carries
   * @param simpleName   the type's simple name when known, else the file's first top-level type
   */
  /** Every type declared in one file, top-level first, in declaration order. */
  typesAtPath(relativePath) {
    return this.paths.get(relativePath) ?? [];
  }

  /**
   * The type a metadata `sourcePath` points at.
   *
   * The generator records the declaring file, so the path is exact where a name is not: a view may live
   * in another package than its marker (the example's `PaymentMethodAuditable` belongs to the
   * `example.Auditable` marker), and no name-based search can tell two same-named types apart.
   *
   * @param relativePath the path the metadata carries
   * @param simpleName   the type's simple name when known, else the file's first top-level type
   */
  typeAtPath(relativePath, simpleName = null) {
    const candidates = this.typesAtPath(relativePath);
    if (candidates.length === 0) {
      return null;
    }
    const topLevel = candidates.filter((type) => type.ownerFqcn === null);
    const pool = topLevel.length > 0 ? topLevel : candidates;
    if (simpleName) {
      const named = pool.find((type) => type.simpleName === simpleName);
      if (named) {
        return named;
      }
    }
    return pool[0];
  }

  /** The generated (and nested) siblings of a view: header back-references, then name conventions. */
  artifactsFor(viewFqcn) {
    const found = new Map();
    for (const type of this.artifactsOf.get(viewFqcn) ?? []) {
      found.set(type.fqcn, type);
    }
    const view = this.types.get(viewFqcn);
    if (view) {
      for (const sibling of namingConventionSiblings(view.simpleName)) {
        const candidate = this.types.get(qualify(view.packageName, sibling));
        if (candidate) {
          found.set(candidate.fqcn, candidate);
        }
      }
      for (const type of this.paths.get(this.pathOf(view)) ?? []) {
        if (type.fqcn.startsWith(`${viewFqcn}.`)) {
          found.set(type.fqcn, type);
        }
      }
    }
    return [...found.values()].sort((a, b) => a.fqcn.localeCompare(b.fqcn));
  }
}

/** The generator's artifact naming conventions, as a contract (see the tooling README). */
export function namingConventionSiblings(viewName) {
  return [
    `${viewName}_`,
    `${viewName}Record`,
    `${viewName}Builder`,
    `${viewName}BuilderTracking`,
    `${viewName}Validator`,
    `${viewName}RowAdapter`,
    `${viewName}Binder`,
    `${viewName}Mapper`,
  ];
}

function qualify(packageName, simpleName) {
  return packageName ? `${packageName}.${simpleName}` : simpleName;
}

/** Whether `text` contains `word` as a whole Java identifier (so `id` does not match `idType`). */
export function containsIdentifier(text, word) {
  return new RegExp(`(?<![\\w$])${word.replace(/\$/g, '\\$')}(?![\\w$])`).test(text);
}

function countBraces(line) {
  let depth = 0;
  for (const char of line) {
    if (char === '{') {
      depth += 1;
    } else if (char === '}') {
      depth -= 1;
    }
  }
  return depth;
}

/**
 * Scans one file and adds its types to `scan`.
 *
 * A function rather than a method so the per-file state (frames, pending record header, pending
 * annotation) cannot leak from one file into the next.
 */
function scanFile(scan, file) {
  const code = readFileSync(file, 'utf8');
  const structural = blankOut(code).split(/\r\n|\n|\r/);
  const original = code.split(/\r\n|\n|\r/);
  const relativePath = scan.relativePath(file);

  const packageName = (structural.map((line) => line.match(PACKAGE)).find(Boolean) ?? [, ''])[1];

  const header = { targets: [], description: null };
  for (let i = 0; i < Math.min(original.length, 6); i += 1) {
    const trimmed = original[i].trim();
    if (!trimmed.startsWith('//')) {
      break;
    }
    const match = trimmed.match(HEADER);
    if (match) {
      header.targets.push(match[1]);
      const tail = match[2].trim();
      if (tail && header.description === null) {
        header.description = tail;
      }
    }
  }

  const frames = new TypeFrames();
  let depth = 0;
  let pendingAnnotation = null;
  let recordHeader = null;

  for (let i = 0; i < structural.length; i += 1) {
    const line = structural[i];
    const raw = original[i];

    if (recordHeader) {
      recordHeader.lines.push(line);
      recordHeader.buffer += ` ${line}`;
      if (/\{|\}\s*$|;\s*$/.test(line)) {
        finishRecordComponents(recordHeader);
        recordHeader = null;
      }
      depth += countBraces(line);
      continue;
    }

    const declaration = line.match(DECLARATION);
    if (declaration) {
      const kind = declaration[1] === '@interface' ? 'annotation' : declaration[1];
      const simpleName = declaration[2];
      const fqcn = frames.qualifyName(packageName, depth, simpleName);
      const info = new TypeInfo(fqcn, simpleName, kind, packageName, file, i + 1, relativePath);
      info.extendsNames = extendsNamesOf(line);
      info.bodyDepth = depth + 1;
      if (depth > 0) {
        const owner = frames.ownerAt(depth);
        info.ownerFqcn = owner ? owner.fqcn : null;
      } else {
        info.headerTargets = header.targets;
        info.headerDescription = header.description;
      }
      scan.register(info, relativePath);
      frames.enter(depth, info);
      if (kind === 'record') {
        recordHeader = { info, buffer: line.slice(line.indexOf('(')), startLine: i + 1, lines: [line] };
        if (/\{|\}\s*$|;\s*$/.test(line)) {
          finishRecordComponents(recordHeader);
          recordHeader = null;
        }
      }
      depth += countBraces(line);
      continue;
    }

    const owner = frames.ownerAt(depth);
    if (owner) {
      // Switch arms are legal anywhere inside the type, method bodies included: an ordinal slot lives
      // in a builder's get(int)/set(int, Object), and a name lookup lives in an enum's forName.
      const nameSlot = raw.match(CASE_NAME);
      if (nameSlot) {
        owner.put(nameSlot[1], 'name-slot', i + 1);
      } else if (CASE_ORDINAL.test(line)) {
        for (const field of armTargets(structural, i)) {
          owner.put(field, 'ordinal-slot', i + 1);
        }
      }

      // Declarations are only read at the type's own body depth, so a call or an assignment inside a
      // method body cannot be mistaken for the member it mentions.
      if (depth === owner.bodyDepth) {
        if (pendingAnnotation) {
          const annotated = line.match(METHOD);
          if (annotated) {
            owner.put(annotated[2], 'annotation', pendingAnnotation);
            pendingAnnotation = null;
          } else if (line.trim() !== '' && !line.trim().startsWith('@')) {
            pendingAnnotation = null;
          }
        }

        if (FIELD_SOURCE.test(line)) {
          pendingAnnotation = i + 1;
        }

        if (owner.kind === 'enum') {
          const constant = line.match(ENUM_CONSTANT);
          if (constant) {
            owner.put(constant[1], 'enum-constant', i + 1);
            if (!owner.enumOrder.includes(constant[1])) {
              owner.enumOrder.push(constant[1]);
            }
          }
        } else {
          const method = line.match(METHOD);
          if (method) {
            const parameters = method[3].trim();
            const arity = parameters === '' ? 0 : parameters.split(',').length;
            owner.put(method[2], arity === 0 ? 'accessor' : arity === 1 ? 'setter' : 'method', i + 1);
          } else {
            const field = line.match(FIELD);
            if (field) {
              owner.put(field[2], 'field', i + 1);
            }
          }
        }
      }
    }

    depth += countBraces(line);
  }
  if (recordHeader) {
    finishRecordComponents(recordHeader);
  }
}

/** The simple names in a declaration's `extends` / `implements` clause, generics stripped. */
function extendsNamesOf(line) {
  const names = [];
  const clause = line.match(/\b(?:extends|implements)\s+([^{;]*)/);
  if (!clause) {
    return names;
  }
  for (const part of splitTopLevel(clause[1])) {
    const cleaned = part.replace(/<[^>]*>/g, '').trim();
    const last = cleaned.split(/[\s,]+/).filter(Boolean).pop();
    if (last) {
      names.push(last.split('.').pop());
    }
  }
  return names;
}

/** Splits a comma-separated list, ignoring commas nested inside generics. */
function splitTopLevel(text) {
  const parts = [];
  let current = '';
  let depth = 0;
  for (const char of text) {
    if (char === '<') {
      depth += 1;
    } else if (char === '>') {
      depth -= 1;
    }
    if (char === ',' && depth === 0) {
      parts.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  parts.push(current);
  return parts;
}

/** The field names an ordinal switch arm writes: `case 3 -> age;`, `case 0 -> { this.id = x; }`. */
function armTargets(structural, start) {
  const found = new Set();
  const text = [];
  for (let i = start; i < structural.length; i += 1) {
    if (i > start && /^\s*(case\b|default\b)/.test(structural[i])) {
      break;
    }
    text.push(structural[i]);
    if (i > start && /;\s*$/.test(structural[i])) {
      break;
    }
  }
  const joined = text.join('\n');
  for (const match of joined.matchAll(ARM_TARGET)) {
    found.add(match[1]);
  }
  for (const match of joined.matchAll(THIS_TARGET)) {
    found.add(match[1]);
  }
  return found;
}

/** Reads a record's component names out of its header and records the line each is written on. */
function finishRecordComponents(recordHeader) {
  const { info, buffer, lines, startLine } = recordHeader;
  const open = buffer.indexOf('(');
  if (open < 0) {
    return;
  }
  let nesting = 0;
  let close = -1;
  for (let i = open; i < buffer.length; i += 1) {
    if (buffer[i] === '(') {
      nesting += 1;
    } else if (buffer[i] === ')') {
      nesting -= 1;
      if (nesting === 0) {
        close = i;
        break;
      }
    }
  }
  if (close < 0) {
    return;
  }
  const header = buffer.slice(open + 1, close);
  const parts = [];
  let current = '';
  let generic = 0;
  for (const char of header) {
    if (char === '<') {
      generic += 1;
    } else if (char === '>') {
      generic -= 1;
    }
    if (char === ',' && generic === 0) {
      parts.push(current);
      current = '';
    } else {
      current += char;
    }
  }
  parts.push(current);
  for (const part of parts) {
    const name = part.trim().split(/[\s<>,]+/).filter(Boolean).pop();
    if (!name || !/^[A-Za-z_$][\w$]*$/.test(name)) {
      continue;
    }
    let line = info.line;
    for (let i = 0; i < lines.length; i += 1) {
      if (containsIdentifier(lines[i], name)) {
        line = startLine + i;
        break;
      }
    }
    info.put(name, 'record-component', line);
  }
}

/** Scans every Java file under `root`, with paths made relative to `linkBase`. */
export function scanSources(root, linkBase) {
  const scan = new SourceScan(root, linkBase);
  for (const file of javaFiles(root)) {
    try {
      scanFile(scan, file);
    } catch (error) {
      scan.fileErrors.push({ file: scan.relativePath(file), message: String(error?.message ?? error) });
    }
  }
  return scan;
}
