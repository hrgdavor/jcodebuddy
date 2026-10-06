#!/usr/bin/env bun
/**
 * Walking a repository's files the way git sees them — without asking git.
 *
 * Every script that reads "all the files in the repository" needs the same thing, and each one that improvises gets
 * it wrong in the same way: a hand-written skip list that misses a directory somebody added later. It is never obvious
 * either, because the symptom is not a wrong answer, it is a slow one — a link checker that walks a checked-out VS Code
 * into the thousands of files, or a formatter that reformats a dependency's Markdown.
 *
 * So the rule lives here, once, for every JavaScript utility in this ecosystem:
 *
 *   - **`.gitignore` is the definition of "not ours"**, read from each directory as the walk enters it, with nested
 *     files overriding their parents and later lines overriding earlier ones — git's own precedence.
 *   - **An ignored directory is not descended into.** That is not a shortcut: git cannot re-include a file whose
 *     parent directory is excluded, so not looking inside is precisely git's rule, and it is what makes this fast.
 *   - **No `git` process, no dependency.** A walker that shells out to `git ls-files` cannot help a script that runs in
 *     an exported tarball, and it makes the tool's behaviour depend on an environment rather than on a file.
 *
 * What is deliberately NOT here: `.git/info/exclude`, the global `core.excludesFile`, and `.gitattributes`. Those are
 * a user's or a machine's opinion about a checkout rather than a property of the sources, and a build that behaves
 * differently per machine is the failure this repository writes rules about.
 *
 * **The same functionality is wanted on the Java side, in the watcher, and is deliberately not built yet** — see
 * DEC-044, which records the intent and what would have to be true before it is worth doing.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

/** Directories no `.gitignore` ever needs to mention: git has no reason to track them and neither do we. */
const ALWAYS_SKIP = new Set(['.git']);

/**
 * One line of a `.gitignore`, compiled.
 *
 * @typedef {object} IgnoreRule
 * @property {string} base the directory the file lives in, relative to the walk root, POSIX, '' for the root
 * @property {RegExp} regex matched against a path relative to `base`
 * @property {boolean} negated `!pattern` — re-includes what an earlier rule excluded
 * @property {boolean} dirOnly a trailing slash: directories only
 */

/**
 * Turn one `.gitignore` line into a rule, or null when the line means nothing.
 *
 * The translation follows git's documented syntax: `*` and `?` do not cross a slash, `**` does, a pattern containing a
 * slash (other than a trailing one) is anchored to the file's directory, and anything else matches at any depth.
 *
 * @param {string} line
 * @param {string} base
 * @returns {IgnoreRule|null}
 */
export function compileRule(line, base = '') {
  let pattern = line;
  if (pattern.trim() === '' || pattern.trimStart().startsWith('#')) {
    return null;
  }
  // A trailing space is not part of the pattern unless it is escaped; a leading space never is.
  pattern = pattern.replace(/(?<!\\)\s+$/, '');
  if (pattern === '') {
    return null;
  }

  let negated = false;
  if (pattern.startsWith('!')) {
    negated = true;
    pattern = pattern.slice(1);
  }

  let dirOnly = false;
  if (pattern.endsWith('/')) {
    dirOnly = true;
    pattern = pattern.slice(0, -1);
  }

  // A slash anywhere but the end anchors the pattern to the `.gitignore`'s own directory.
  const anchored = pattern.slice(0, -1).includes('/') || pattern.startsWith('/');
  if (pattern.startsWith('/')) {
    pattern = pattern.slice(1);
  }
  if (pattern === '') {
    return null;
  }

  const body = globToRegexSource(pattern);
  const prefix = anchored ? '^' : '^(?:.*/)?';
  return { base, regex: new RegExp(`${prefix}${body}$`), negated, dirOnly };
}

/**
 * A `.gitignore` glob, as a regular expression source.
 *
 * Exported because it is the part most worth testing on its own: every mistake here is silent, and shows up as a file
 * that was or was not read.
 */
export function globToRegexSource(pattern) {
  let out = '';
  for (let i = 0; i < pattern.length; i++) {
    const char = pattern[i];
    if (char === '\\' && i + 1 < pattern.length) {
      // An escaped character is literal, including the ones that are syntax to us.
      out += pattern[i + 1].replace(/[.+^${}()|[\]\\]/g, '\\$&');
      i++;
      continue;
    }
    if (char === '*') {
      if (pattern[i + 1] === '*') {
        // `**/` is "any number of directories"; a trailing `/**` is "everything below".
        if (pattern[i + 2] === '/') {
          out += '(?:.*/)?';
          i += 2;
          continue;
        }
        out += '.*';
        i++;
        continue;
      }
      out += '[^/]*';
      continue;
    }
    if (char === '?') {
      out += '[^/]';
      continue;
    }
    if (char === '[') {
      const end = pattern.indexOf(']', i + 1);
      if (end > i) {
        let body = pattern.slice(i + 1, end);
        // A `!` right after `[` negates the class in glob syntax; in a regex it is `^`.
        if (body.startsWith('!')) {
          body = `^${body.slice(1)}`;
        }
        out += `[${body}]`;
        i = end;
        continue;
      }
    }
    out += char.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return out;
}

/** A stack of ignore rules that grows as the walk descends. */
class IgnoreStack {
  constructor(root) {
    this.root = root;
    this.levels = [];
  }

  /** Read `dir`'s own `.gitignore`, if it has one, and push its rules. */
  enter(dir, relativeDir) {
    let rules = [];
    try {
      const text = readFileSync(join(dir, '.gitignore'), 'utf8');
      rules = text
        .split(/\r?\n/)
        .map((line) => compileRule(line, relativeDir))
        .filter(Boolean);
    } catch (error) {
      if (error.code !== 'ENOENT' && error.code !== 'EISDIR') {
        throw error;
      }
    }
    this.levels.push(rules);
  }

  leave() {
    this.levels.pop();
  }

  /**
   * Whether a path is ignored, by git's precedence: the deepest file that mentions it wins, and within a file the last
   * matching line wins.
   *
   * @param {string} relativePath POSIX, relative to the root
   * @param {boolean} isDirectory
   */
  ignores(relativePath, isDirectory) {
    let decision = false;
    for (let level = 0; level < this.levels.length; level++) {
      const rules = this.levels[level];
      for (let i = 0; i < rules.length; i++) {
        const rule = rules[i];
        if (rule.dirOnly && !isDirectory) {
          continue;
        }
        const candidate = rule.base === '' ? relativePath : relativeTo(relativePath, rule.base);
        if (candidate === null) {
          continue;
        }
        if (rule.regex.test(candidate)) {
          decision = !rule.negated;
        }
      }
    }
    return decision;
  }
}

/** `path` relative to `base`, or null when it is not under it. */
function relativeTo(path, base) {
  if (base === '') {
    return path;
  }
  return path === base ? '' : path.startsWith(`${base}/`) ? path.slice(base.length + 1) : null;
}

function toPosix(path) {
  return path.split(sep).join('/');
}

/**
 * Every file under `root` that git would call the repository's own.
 *
 * @param {string} root
 * @param {object} [options]
 * @param {string[]} [options.extensions] only these extensions (`.md`, case-insensitive). Everything when omitted —
 *        which is almost never what a caller wants: filtering here is what keeps a walk cheap.
 * @param {string[]} [options.ignore] extra directory names to skip, on top of `.gitignore`. For a caller's own output
 *        directory that is not ignored yet, not for "the usual suspects" — those belong in `.gitignore`.
 * @param {(path: string, stats: import('node:fs').Stats) => void} [options.visit] called per file instead of collecting
 * @returns {string[]} POSIX paths relative to `root`, when no `visit` is given
 */
export function listFiles(root, options = {}) {
  const found = [];
  const extensions = (options.extensions ?? []).map((extension) => extension.toLowerCase());
  const extraSkip = new Set(options.ignore ?? []);
  const stack = new IgnoreStack(root);
  const want = (name) => extensions.length === 0
    || extensions.some((extension) => name.toLowerCase().endsWith(extension));

  const descend = (dir, relativeDir) => {
    stack.enter(dir, relativeDir);
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch (error) {
      // An unreadable directory is skipped rather than fatal: a walk is a scan, not a proof.
      stack.leave();
      return;
    }
    for (const entry of entries) {
      const name = entry.name;
      const childRelative = relativeDir === '' ? name : `${relativeDir}/${name}`;
      const isDirectory = entry.isDirectory();
      if (isDirectory && (ALWAYS_SKIP.has(name) || extraSkip.has(name))) {
        continue;
      }
      if (stack.ignores(childRelative, isDirectory)) {
        // Not descended into when it is a directory - see the note at the top: git cannot re-include a file below an
        // excluded directory either.
        continue;
      }
      const child = join(dir, name);
      if (entry.isSymbolicLink()) {
        // Not followed: a link out of the tree is how a walk escapes it, and the risk is not worth the convenience.
        continue;
      }
      if (isDirectory) {
        descend(child, childRelative);
        continue;
      }
      if (!entry.isFile() || !want(name)) {
        continue;
      }
      let stats;
      try {
        stats = statSync(child);
      } catch (error) {
        continue;
      }
      if (options.visit) {
        options.visit(childRelative, stats);
      } else {
        found.push(childRelative);
      }
    }
    stack.leave();
  };

  descend(root, '');
  return found;
}

/**
 * Whether one path is ignored, for a caller that has a path already and wants git's opinion of it.
 *
 * The walk has to read the ignore files on the way down; this does the same for the path's ancestors only.
 */
export function isIgnored(root, path) {
  const target = toPosix(relative(root, path));
  if (target === '' || target.startsWith('..')) {
    return false;
  }
  const stack = new IgnoreStack(root);
  stack.enter(root, '');

  // Judged one component at a time, because a directory is decided by its PARENT's rules while its own rules decide
  // its contents. A file inside an excluded directory is excluded - git cannot re-include it - so an ignored
  // ancestor is the answer without looking further.
  const parts = target.split('/');
  let walked = '';
  let entered = 0;
  try {
    for (let i = 0; i < parts.length; i++) {
      walked = walked === '' ? parts[i] : `${walked}/${parts[i]}`;
      const isDirectory = i < parts.length - 1;
      if (stack.ignores(walked, isDirectory)) {
        return true;
      }
      if (isDirectory) {
        stack.enter(join(root, walked), walked);
        entered++;
      }
    }
    return false;
  } finally {
    for (let i = 0; i < entered; i++) {
      stack.leave();
    }
  }
}
