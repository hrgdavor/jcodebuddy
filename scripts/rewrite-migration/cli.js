/**
 * Command-line plumbing shared by the four Phase 6 entry scripts.
 */

import { findRepoRoot } from './inventory.js';

/**
 * Minimal flag parser. Supports `--flag`, `--key value`, and positionals.
 *
 * @param {string[]} argv `process.argv.slice(2)`
 * @param {Record<string, 'boolean'|'string'>} spec
 * @returns {{flags: Record<string, string|boolean>, positional: string[]}}
 */
export function parseArgs(argv, spec) {
  const flags = {};
  const positional = [];
  for (let index = 0; index < argv.length; index++) {
    const token = argv[index];
    if (!token.startsWith('--')) {
      positional.push(token);
      continue;
    }
    const name = token.slice(2);
    const kind = spec[name];
    if (kind === undefined) {
      throw new UsageError(`unknown option --${name}`);
    }
    if (kind === 'boolean') {
      flags[name] = true;
    } else {
      const value = argv[++index];
      if (value === undefined) {
        throw new UsageError(`--${name} needs a value`);
      }
      flags[name] = value;
    }
  }
  return { flags, positional };
}

/** Thrown for a bad invocation; the entry scripts print it without a stack. */
export class UsageError extends Error {}

/**
 * Resolve the repository root, honouring `--root`.
 *
 * @param {Record<string, string|boolean>} flags
 * @returns {string}
 */
export function resolveRoot(flags) {
  const explicit = flags.root;
  if (typeof explicit === 'string') {
    return findRepoRoot(explicit);
  }
  return findRepoRoot();
}

/** Pad `text` to `width` (already-stringified values only). */
export function pad(text, width) {
  const value = String(text);
  return value.length >= width ? value : value + ' '.repeat(width - value.length);
}

/** Left-pad a number for a right-aligned column. */
export function padLeft(text, width) {
  const value = String(text);
  return value.length >= width ? value : ' '.repeat(width - value.length) + value;
}

/** A section banner, matching the repo's plain-text tooling style. */
export function heading(title) {
  return `\n${title}\n${'='.repeat(title.length)}`;
}

/** Write a line to stdout. */
export function out(line = '') {
  process.stdout.write(`${line}\n`);
}

/** Write a line to stderr. */
export function warn(line) {
  process.stderr.write(`${line}\n`);
}

/** Join a path list for display, with a count when it is long. */
export function listOrCount(items, limit = 8) {
  if (items.length <= limit) {
    return items.join(', ');
  }
  return `${items.slice(0, limit).join(', ')} … (+${items.length - limit} more)`;
}
