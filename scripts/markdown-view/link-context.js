/**
 * How a page turns a link target into something a host can act on — the part that can be wrong, kept pure.
 *
 * <p>This exists because of a live report: a click in the rendered page asked the IDE for a file "at line 1" and no
 * editor tab appeared. Two faults were behind it, and neither was visible by looking at the generated HTML, because a
 * page that renders in the browser has no `data-open` in its bytes at all — the links are built at view time. The
 * links are therefore built by *this* function, which a test can call, instead of by code buried in a page script.
 *
 * <p>What it does, and why each part matters:
 *
 * <ul>
 *   <li><b>Absolute paths stay exactly as written.</b> A page whose document directory is known resolves every target
 *       against it, so the host receives `D:/proj/src/A.java#add` — a path, as the frozen contract says `data-open`
 *       is.</li>
 *   <li><b>The fragment rides along.</b> `#add`, `#L42-L58`, `#region:name` are the location (plan step 9.7); the
 *       host is the side that resolves one, so losing it here means every click lands on line 1 no matter how right
 *       the rest is.</li>
 *   <li><b>Nothing is claimed about the file.</b> A browser has no filesystem, so the test is a source extension and
 *       a shape — and a target the host cannot reach is the host's to refuse, with a reason (the frozen contract's
 *       `404`), rather than the page's to guess about.</li>
 * </ul>
 */
import { classIndexFrom, isSourcePath, renderMarkdown } from './render.js';
import { escapeHtml } from './open-file.js';

/**
 * The context `renderMarkdown` needs, from a host's view data.
 *
 * @param {object} options
 * @param {string} [options.docDir] the document's directory, absolute and POSIX-style: relative links resolve here
 * @param {object} [options.indexJson] a module's class index, when the host has one (DEC-029)
 * @param {string} [options.moduleName]
 * @param {(target: object, text: string, kind: string) => string} [options.makeLink] how to spell the link; the
 *        default is the one a page uses, and a caller that wants to count or collect passes its own
 * @returns {{directory: string, index: object, isOpenable: (p: string) => boolean, makeLink: Function}}
 */
export function linkContext(options = {}) {
  const directory = String(options.docDir ?? '').replace(/\\/g, '/').replace(/\/+$/, '');
  return {
    directory,
    // classIndexFrom parses JSON text - that is how the CLI hands it a file it read. A host has already parsed
    // it, and passing the object through unchanged silently produced an empty index and no links at all.
    index: classIndexFrom(
      typeof options.indexJson === 'string' ? options.indexJson : JSON.stringify(options.indexJson ?? {}),
      String(options.moduleName ?? ''),
    ),
    // A known source extension, no whitespace, and absolute - which it is, because `directory` is.
    isOpenable: (candidate) => Boolean(candidate)
      && !/\s/.test(candidate)
      && (candidate.startsWith('/') || /^[A-Za-z]:\//.test(candidate))
      && isSourcePath(candidate),
    makeLink: options.makeLink ?? ((target, text, kind) => anchorFor(target, text, kind)),
  };
}

/**
 * The anchor a page writes: `data-open` carries the whole target, including the fragment.
 *
 * `data-member` and `data-role` are display metadata — the frozen contract is explicit that `data-member` is never a
 * routing key — while the fragment inside `data-open` is what the host resolves.
 */
export function anchorFor(target, text, kind, escape = escapeHtml) {
  const open = target.fragment ? `${target.open}#${target.fragment}` : target.open;
  const attributes = [
    `data-open="${escape(open)}"`,
    `data-line="${target.line}"`,
    target.member ? `data-member="${escape(target.member)}"` : '',
    target.role ? `data-role="${escape(target.role)}"` : '',
  ].filter(Boolean).join(' ');
  const title = target.member ? ` title="${escape(target.member)}"` : '';
  return `<a ${attributes}${title}>${text}</a>`;
}

/**
 * Every link a document would produce, as a host would see them — for a test, and for any tool that wants to check a
 * page's links without a browser.
 *
 * @returns {Array<{open: string, line: number, fragment: string|null, member: string, role: string}>}
 */
export function linksFor(markdown, options = {}) {
  const found = [];
  const context = linkContext({
    ...options,
    makeLink: (target, text, kind) => {
      const open = target.fragment ? `${target.open}#${target.fragment}` : target.open;
      found.push({
        open,
        path: target.open,
        line: target.line,
        fragment: target.fragment ?? null,
        member: target.member ?? '',
        role: target.role ?? '',
        text: String(text),
      });
      return anchorFor(target, text, kind);
    },
  });
  // Rendered for its side effects: the renderer is what decides which targets become links at all.
  renderMarkdown(String(markdown ?? ''), context);
  return found;
}

