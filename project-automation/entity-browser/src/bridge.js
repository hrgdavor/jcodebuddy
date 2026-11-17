/**
 * The navigation half of the entity browser: take a source location the model recorded and hand it
 * to whichever host is present, in the order the frozen navigation contract specifies
 * (`webview/kit/doc/contract.md`):
 *
 *   1. `window.openFile(path, line, column)` — the JetBrains WebView Explorer injects it;
 *   2. the host's loopback HTTP bridge, through a hidden iframe (a `file://` page cannot `fetch`);
 *   3. clipboard, with the `path:line` text a reader can paste into the IDE's search.
 *
 * <p>Feature-detect, never call an undefined `window.openFile`: the contract is explicit that the
 * `TypeError` would silently kill every later click on the page.</p>
 */

/** The target text a clipboard fallback carries. */
export function formatTarget(target) {
  const line = target?.line > 0 ? target.line : 1;
  return `${target?.path ?? ''}:${line}`;
}

/** The directory part of the page's own URL, with its trailing slash. */
function pageDirectory(href) {
  const cut = String(href ?? '').lastIndexOf('/');
  return cut < 0 ? '' : String(href).slice(0, cut + 1);
}

/**
 * The link base as an absolute file path, derived from where the page was loaded from.
 *
 * `model.linkBase` is one relative path from the output's directory to the project root (or a
 * `file:` URL when the two are on different drives). Resolving it against the page's own directory is
 * what lets the same file work whether the IDE project is the module or the repository above it; the
 * contract accepts an absolute path, and an absolute path is the only spelling that does not depend on
 * guessing which directory the host calls "the project".
 */
export function absoluteBase(linkBase, href) {
  try {
    const directory = pageDirectory(href);
    const resolved = new URL(linkBase || './', directory).href;
    if (!resolved.startsWith('file:')) {
      return null;
    }
    let path = decodeURIComponent(resolved.slice('file:'.length));
    // `file:///C:/…` decodes to `///C:/…`; the host wants `C:/…`. A POSIX `file:///home/…`
    // decodes to `///home/…` and wants `/home/…`, so only a drive letter loses all its slashes.
    if (/^\/+[A-Za-z]:/.test(path)) {
      path = path.replace(/^\/+/, '');
    } else if (path.startsWith('///')) {
      path = path.slice(2);
    }
    return path.replace(/\/+$/, '');
  } catch {
    return null;
  }
}

/** A page-relative target as an absolute file path, or the relative text when the page is not a file. */
export function absoluteTarget(relative, linkBase, href) {
  const base = absoluteBase(linkBase, href);
  if (!base) {
    return relative;
  }
  if (!relative) {
    return base;
  }
  return `${base}/${String(relative).replace(/^\/+/, '')}`;
}

/** The URL the HTTP transport uses. Pure, so the test does not need a DOM. */
export function bridgeUrl(target, port, token = '') {
  const path = target?.path ?? '';
  const line = target?.line > 0 ? target.line : 1;
  const column = target?.column > 0 ? target.column : 1;
  const auth = token ? `&token=${encodeURIComponent(token)}` : '';
  return `http://127.0.0.1:${port}/open?filePath=${encodeURIComponent(path)}`
    + `&line=${line}&column=${column}${auth}`;
}

/** Which transport this document can use, for the header pill. */
export function bridgeKind(port) {
  if (typeof window !== 'undefined' && typeof window.openFile === 'function') {
    return 'ide';
  }
  if (port > 0) {
    return 'http';
  }
  return 'clipboard';
}

/** A reader-facing label for {@link bridgeKind}. */
export function bridgeLabel(port) {
  const kind = bridgeKind(port);
  if (kind === 'ide') {
    return 'IDE navigation live';
  }
  if (kind === 'http') {
    return `IDE bridge on :${port}`;
  }
  return 'standalone — links copy path:line';
}

/** Copy text without throwing when the page is a `file://` document with no clipboard grant. */
export async function copyText(text) {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to the textarea path
  }
  if (typeof document === 'undefined') {
    return false;
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand('copy');
    area.remove();
    return copied;
  } catch {
    return false;
  }
}

/**
 * Opens a source location by the best available transport.
 *
 * @param {{path: string, line: number, column?: number, member?: string}} target
 * @param {number} port the HTTP bridge port, or 0
 * @param {{linkBase?: string, href?: string, pageToken?: string}} [context] the page's `model.linkBase`,
 *        its own URL, and the one-time token in a plugin-served page URL; when given, the relative path
 *        is resolved to an absolute one and the token authenticates the host's `/open` route
 * @returns {{transport: 'ide'|'http'|'clipboard'|'none', message: string}}
 */
export function openLocation(target, port, context = {}) {
  if (!target?.path) {
    return { transport: 'none', message: 'No source path was recorded for this location.' };
  }
  const line = target.line > 0 ? target.line : 1;
  const column = target.column > 0 ? target.column : 1;
  const path = context.linkBase
    ? absoluteTarget(target.path, context.linkBase, context.href ?? (typeof location === 'undefined' ? '' : location.href))
    : target.path;

  if (typeof window !== 'undefined' && typeof window.openFile === 'function') {
    try {
      window.openFile(path, line, column);
      return { transport: 'ide', message: `Opened ${path}:${line} in the IDE.` };
    } catch {
      // A host that throws is treated as absent; the contract says it must not, but a page cannot
      // make that true and must not lose the fallback over it.
    }
  }

  if (port > 0 && typeof document !== 'undefined') {
    const frame = document.createElement('iframe');
    frame.setAttribute('aria-hidden', 'true');
    frame.style.display = 'none';
    frame.src = bridgeUrl({ path, line, column }, port, context.pageToken ?? '');
    document.body.appendChild(frame);
    setTimeout(() => frame.remove(), 1500);
    return { transport: 'http', message: `Sent ${path}:${line} to the IDE bridge on port ${port}.` };
  }

  const text = formatTarget({ path, line });
  void copyText(text);
  return { transport: 'clipboard', message: `Copied ${text}.` };
}
