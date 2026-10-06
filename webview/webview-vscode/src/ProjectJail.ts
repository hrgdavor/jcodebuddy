/**
 * The project jail for the VS Code host, with symbolic links resolved.
 *
 * `BridgePolicy` decides *policy* from the request — is there an allowed origin, is the path decoded safely —
 * and it is deliberately pure so it can be tested against shared vectors without a filesystem. That is exactly
 * why it cannot answer this question: whether a path is inside the project is a fact about the disk, and a
 * string check is not enough. `root/../../etc/passwd` is caught by normalising, but `root/link-to-etc/passwd`
 * is not — and on Linux, where a symlink is an ordinary thing for a repository to contain, that is the escape
 * that matters.
 *
 * The Java core has the same rule in `PathResolver` (`toRealPath`, and the resolved parent for a file that
 * does not exist yet, because a write of a new file hits that case). This is that rule for the host that
 * reaches files without the core. Keep the two in step: the maintainer's rule is that no webview plugin may
 * reach any file outside the project root, and it is not a rule that can be half true.
 *
 * The `realPath` function is a parameter so the rule can be tested on a machine that will not create symlinks
 * without privileges — which is every Windows machine in this repository's history.
 */
import { realpathSync } from 'node:fs';
import { isAbsolute, resolve, sep } from 'node:path';

/** Resolve every symlink in a path that exists. Throws when it does not. */
export type RealPath = (path: string) => string;

/** The platform's resolver. `native` is the one that resolves symlinks rather than trusting the string. */
export const nativeRealPath: RealPath = (path) => realpathSync.native(path);

/** Forward slashes, no trailing separator, `.`/`..` folded away for the text as written. */
export function normalizeForCompare(path: string): string {
  const slashed = String(path ?? '').replace(/\\/g, '/');
  const isWindows = /^[a-zA-Z]:\//.test(slashed) || slashed.startsWith('//');
  const parts: string[] = [];
  for (const segment of slashed.split('/')) {
    if (segment === '' || segment === '.') {
      continue;
    }
    if (segment === '..' && parts.length > 0 && parts[parts.length - 1] !== '..') {
      parts.pop();
      continue;
    }
    parts.push(segment);
  }
  const joined = parts.join('/');
  if (isWindows) {
    return joined;
  }
  return slashed.startsWith('/') ? `/${joined}` : joined;
}

function segments(path: string): string[] {
  return normalizeForCompare(path).split('/').filter((part) => part !== '');
}

function sameSegment(left: string, right: string): boolean {
  if (left === right) {
    return true;
  }
  // Windows and macOS fold case; Linux does not, and must not, or a jail would accept a path that the disk
  // would treat as a different file.
  const foldCase = process.platform === 'win32' || process.platform === 'darwin';
  return foldCase && left.toLowerCase() === right.toLowerCase();
}

/** True when `candidate` names a location inside `root`. Both are expected to be absolute. */
export function isInside(root: string, candidate: string): boolean {
  const rootParts = segments(root);
  const candidateParts = segments(candidate);
  if (rootParts.length === 0 || candidateParts.length < rootParts.length) {
    return false;
  }
  for (let i = 0; i < rootParts.length; i++) {
    if (!sameSegment(rootParts[i], candidateParts[i])) {
      return false;
    }
  }
  return true;
}

/**
 * Resolve every symlink the way the Java core does: the real path when the file exists, and the real path of
 * its parent plus the file name when it does not.
 *
 * @returns the resolved path, or null when it cannot be resolved at all — and null is the closed answer, not
 *          "probably fine"
 */
export function resolveRealPath(path: string, realPath: RealPath = nativeRealPath): string | null {
  const absolute = isAbsolute(path) ? path : resolve(path);
  try {
    return normalizeForCompare(realPath(absolute));
  } catch {
    // Unreadable, or not there: try the parent, which is the case a write of a new file hits.
  }
  const cut = Math.max(absolute.lastIndexOf('/'), absolute.lastIndexOf(sep));
  if (cut <= 0) {
    return null;
  }
  const parent = absolute.slice(0, cut);
  const name = absolute.slice(cut + 1);
  try {
    return normalizeForCompare(`${realPath(parent)}/${name}`);
  } catch {
    // Unreadable means unverifiable, and unverifiable means no.
    return null;
  }
}

/**
 * The path to open, or null when it is not inside the project.
 *
 * @param projectRoot the project directory; blank or missing means this host has no boundary to enforce
 * @param filePath absolute or project-relative, either slash style
 * @param realPath the resolver, injected so the rule is testable without creating symlinks
 */
export function resolveInsideProject(
  projectRoot: string | null | undefined,
  filePath: string | null | undefined,
  realPath: RealPath = nativeRealPath,
): string | null {
  if (!filePath || !String(filePath).trim()) {
    return null;
  }
  const root = projectRoot && String(projectRoot).trim() ? resolveRealPath(String(projectRoot), realPath) : null;
  if (!root) {
    // No project, no boundary: nothing to enforce, and nothing claimed. A host that serves a single file.
    return null;
  }
  const absolute = isAbsolute(filePath) ? filePath : resolve(root, filePath);
  const real = resolveRealPath(absolute, realPath);
  if (!real || !isInside(root, real)) {
    return null;
  }
  return real;
}
