/**
 * Verifies the source locations the class index recorded, before the page writes a single link.
 *
 * <p>DEC-027's rule is that a link to the wrong line is worse than no link. The class index's lines
 * come from javac through `JavacPositions`, so they are normally exact; this pass is what makes a
 * **stale** index visible instead of a page full of quiet mis-links. It checks the file exists and
 * that the recorded line contains the member's name, then either keeps the line for the page's
 * `data-line` attribute or clears it (the page still shows the path as text).</p>
 *
 * <p>A missing file is an error (the index names a path that is not there). A line whose text does
 * not contain the name is a warning: the link is dropped, the page renders, and the build reports
 * which location it refused. The pass reads source at build time only; the page carries paths and
 * lines, never file content.</p>
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * @param {object} model the model returned by `buildBrowserModel`
 * @param {Array} indexes the same index entries the model was built from; each carries `root` and
 *        `classes[].row.path` (module-relative)
 * @returns {{checked: number, verified: number, stale: number, missing: number}}
 */
export function verifySourceLinks(model, indexes) {
  /** fqn -> `{absolute, moduleRelative}` */
  const sourceOf = new Map();
  for (const index of indexes) {
    for (const entry of index.classes) {
      if (!sourceOf.has(entry.fqn)) {
        sourceOf.set(entry.fqn, {
          absolute: join(index.root, entry.row.path),
          moduleRelative: entry.row.path,
        });
      }
    }
  }

  /** @type {Map<string, string[]|null>} */
  const lineCache = new Map();
  function linesOf(file) {
    if (lineCache.has(file)) {
      return lineCache.get(file);
    }
    let lines = null;
    try {
      lines = readFileSync(file, 'utf8').split(/\r?\n/);
    } catch {
      lines = null;
    }
    lineCache.set(file, lines);
    return lines;
  }

  function problem(kind, severity, location, cause, current, canonical, action) {
    model.diagnostics.push({ kind, severity, location, cause, current, canonical, action });
  }

  function verifyLine(lines, line, name, location, what) {
    if (!line || line < 1) {
      return 0;
    }
    const text = lines[line - 1];
    if (text !== undefined && text.includes(name)) {
      return line;
    }
    problem('browser_line_stale', 'warning', location,
      `${what} is not on the line the class index records`,
      `line ${line}: ${text === undefined ? '<past end of file>' : text.trim().slice(0, 90)}`,
      `a line containing ${JSON.stringify(name)}`, 'regenerate the class index, or fix the source reference');
    return 0;
  }

  let checked = 0;
  let verified = 0;
  let stale = 0;
  let missing = 0;

  for (const type of Object.values(model.types)) {
    const source = sourceOf.get(type.fqn);
    if (!source) {
      type.sourceAvailable = false;
      type.sourceLine = 0;
      missing += 1;
      problem('browser_source_missing', 'error', type.fqn,
        'the model knows the type but not the file that declares it', type.fqn,
        'a class-index row with a path', 'rebuild the model from the same indexes');
      continue;
    }
    const lines = linesOf(source.absolute);
    if (!lines) {
      type.sourceAvailable = false;
      type.sourceLine = 0;
      missing += 1;
      problem('browser_source_missing', 'error', source.moduleRelative,
        'the class index names a file that does not exist', source.moduleRelative,
        'an existing file under the indexed module', 'regenerate the class index');
      continue;
    }
    checked += 1;
    type.sourceAvailable = true;
    type.sourceLine = verifyLine(lines, type.line, type.simpleName,
      `${source.moduleRelative}:${type.line}`, `the declaration of ${type.simpleName}`);
    if (type.sourceLine) {
      verified += 1;
    } else if (type.line > 0) {
      stale += 1;
    }
    for (const member of [
      ...type.components, ...type.enumConstants, ...type.fields,
      ...type.methods, ...type.constructors, ...type.nested,
    ]) {
      member.sourceLine = verifyLine(lines, member.line, member.name,
        `${source.moduleRelative}:${member.line}`, `member ${member.name}`);
    }
  }

  return { checked, verified, stale, missing };
}
