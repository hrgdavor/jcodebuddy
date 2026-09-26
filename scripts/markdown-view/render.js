/**
 * Markdown → one self-contained clickable page.
 *
 * This is a deliberately small renderer, and it is a **worked example** as much as a tool: it shows the
 * three pieces a custom document needs, and nothing else.
 *
 *   1. **Resolve a link target to a file and a line.** `classIndex` (`.jcodebuddy/index/classes.json`,
 *      DEC-029) turns a bare type name into its declaring file and declaration line; plain paths are
 *      resolved relative to the project root or to the Markdown file itself; `#L14` / `:14` set the line.
 *   2. **Put the location on the element** as `data-open` / `data-line` / `data-member`, which is the only
 *      contract the IDE host reads (`webview/kit/doc/contract.md` § 1.1).
 *   3. **Emit one HTML file** with the click handler inlined, so it needs no server, no bundler and no
 *      network at view time.
 *
 * The Markdown subset supported is what this repository's prose actually uses: headings, paragraphs,
 * fenced code, inline code, emphasis, links, images (rendered as text), ordered and unordered lists
 * (including `- [ ]` task items), blockquotes, horizontal rules, and tables with alignment.
 *
 * @module markdown-view/render
 */
import { escapeHtml } from './open-file.js';

/** A class index row and the maps a document needs to resolve names to locations. */
export class ClassIndex {
  constructor(rows, moduleName) {
    /** @type {Map<string, {path: string, line: number, kind: string}>} by fully qualified name */
    this.byFqn = rows;
    /** @type {Map<string, string>} simple name -> FQN, for a bare `` `PersonSummary` `` in prose */
    this.bySimpleName = new Map();
    for (const fqn of rows.keys()) {
      const simple = fqn.slice(fqn.lastIndexOf('.') + 1);
      // First wins, and rows come in key order, so the answer is deterministic rather than dependent on
      // which document happened to be parsed first. An ambiguous bare name resolves to the first FQN in
      // key order; a document that cares writes the qualified name.
      if (!this.bySimpleName.has(simple)) {
        this.bySimpleName.set(simple, fqn);
      }
    }
    this.moduleName = moduleName;
  }

  /**
   * This index with `other`'s rows added **behind** it: a name both tables know keeps this table's answer.
   *
   * Used to consult a module's own index and the repository's without an ordering rule at every call
   * site — a module document is about the module's types, so the module's row must win.
   */
  withFallback(other) {
    if (!other || other.size === 0) {
      return this;
    }
    const merged = new Map(other.byFqn);
    for (const [fqn, row] of this.byFqn) {
      merged.set(fqn, row);   // this table wins
    }
    return new ClassIndex(merged, this.moduleName);
  }

  /** The row for a fully qualified name, a bare simple name, or a nested `Outer.Inner` suffix. */
  lookup(name) {
    if (!name) {
      return null;
    }
    const direct = this.byFqn.get(name);
    if (direct) {
      return { ...direct, fqn: name };
    }
    const simple = this.bySimpleName.get(name);
    if (simple) {
      return { ...this.byFqn.get(simple), fqn: simple };
    }
    // A nested type written as `PersonSummary.Record` or just `Record`.
    for (const [fqn, row] of this.byFqn) {
      if (fqn.endsWith('.' + name)) {
        return { ...row, fqn };
      }
    }
    return null;
  }

  get size() {
    return this.byFqn.size;
  }
}

/**
 * Parse `.jcodebuddy/index/classes.json` into a {@link ClassIndex}.
 *
 * A missing or unusable index is **not** an error: the tool then resolves only explicit paths, which is
 * still useful, and says so in its report. A document generator that hard-failed on a missing index would
 * be useless in a project that has not run a pass yet.
 */
export function classIndexFrom(json, moduleName = '') {
  const rows = new Map();
  let classes = {};
  try {
    classes = JSON.parse(json)?.classes ?? {};
  } catch (error) {
    return new ClassIndex(rows, moduleName);
  }
  for (const [fqn, row] of Object.entries(classes)) {
    if (row && typeof row.path === 'string') {
      rows.set(fqn, {
        path: row.path,
        line: typeof row.line === 'number' && row.line > 0 ? row.line : 1,
        kind: row.kind ?? '',
      });
    }
  }
  return new ClassIndex(rows, moduleName);
}

/** `#L14`, `#l14`, `#14` or a `:14` suffix. Returns `{target, line}` with `line` 1 when absent. */
export function splitLineSuffix(raw) {
  const text = String(raw ?? '');
  const hash = /#L?(\d+)$/i.exec(text);
  if (hash) {
    return { target: text.slice(0, hash.index), line: Number(hash[1]) };
  }
  const colon = /:(\d+)$/.exec(text);
  if (colon && !/^[A-Za-z]:$/.test(text.slice(0, colon.index))) {
    return { target: text.slice(0, colon.index), line: Number(colon[1]) };
  }
  return { target: text, line: 1 };
}

/** Whether a resolved target is a source file this tool should make clickable. */
export function isSourcePath(target) {
  return /\.(java|kt|xml|json|cmd|sh|md|js|ts|css|html|yml|yaml|properties|sql)$/i.test(target);
}

/**
 * Resolve a Markdown link or code span into a navigation target.
 *
 * The rule is **resolve or leave alone**: a target is returned only when it names something that
 * actually exists on disk (a file or directory) or a type the class index knows. Anything else stays
 * plain text — a link to a path that is not there is worse than no link, and prose is full of
 * path-shaped text that is not a path (`—`, `…`, `.java`, `src/main/java` as a phrase).
 *
 * @param {string} raw the link text, e.g. `src/main/java/a/B.java#L42` or `PersonSummary`
 * @param {{directory: string, index: ClassIndex, isOpenable: (p: string) => boolean}} context
 * @returns {{open: string, line: number, member: string, role: string, fromIndex: boolean}|null}
 */
export function resolveTarget(raw, context) {
  const { target, line } = splitLineSuffix(String(raw ?? '').trim());
  // Cheapest and most important guard first: a sentence, an elided path (`src/main/java/…`) or an
  // over-long blob is prose, not a location — and this holds whatever the caller's `isOpenable` says, so
  // no caller can emit a link to one by handing the resolver a permissive predicate.
  if (!target || /\s/.test(target) || target.includes('…') || target.length > 300) {
    return null;
  }
  const openable = context.isOpenable ?? (() => true);

  // 1. A file or directory in the project, written from the project root or relative to this document.
  //    Both spellings occur in real prose, so both are tried before giving up.
  for (const candidate of [joinPosix(context.directory, target), target]) {
    if (candidate && openable(candidate)) {
      return { open: candidate, line, member: '', role: 'file', fromIndex: false };
    }
  }

  // 2. A bare type name the class index knows. This is the interesting case, and it is why the index
  //    exists: `PersonSummary` in a sentence becomes a link to its declaration without a path being
  //    written anywhere. Restricted to names that look like types, because a lowercase word that happens
  //    to match a file name is far more likely to be prose.
  if (/^[A-Z]/.test(target) && !target.endsWith('.java')) {
    const row = context.index.lookup(target);
    if (row) {
      return {
        open: row.path,
        line: line > 1 ? line : row.line,
        // The link opens the type's own declaration line, where the *simple* name is what appears, so that
        // is what a verifier should look for. The tooltip still shows the fully qualified name.
        member: row.fqn.slice(row.fqn.lastIndexOf('.') + 1),
        role: 'type',
        fromIndex: line <= 1,
      };
    }
  }
  return null;
}

/** Join two POSIX-style paths and normalise `..`/`.` without touching the filesystem. */
export function joinPosix(base, relative) {
  const parts = `${base ? base + '/' : ''}${relative}`.split('/');
  const out = [];
  for (const part of parts) {
    if (part === '' || part === '.') {
      continue;
    }
    if (part === '..') {
      out.pop();
      continue;
    }
    out.push(part);
  }
  return out.join('/');
}

// ── inline markdown ─────────────────────────────────────────────────────────────────────────────────

/**
 * Render inline Markdown, turning anything that resolves into a navigation link.
 *
 * `context.makeLink` is supplied by the caller so this function stays pure: it takes the resolved target
 * and returns the anchor markup, which is where the `data-open` attributes are written.
 */
export function renderInline(text, context) {
  const source = String(text ?? '');
  let out = '';
  let i = 0;
  // One pass, so an escaped character or a code span cannot be re-interpreted by a later rule.
  const pattern = /(`[^`]+`)|(!?\[[^\]]*\]\([^)\s]+(?:\s+"[^"]*")?\))|(\*\*[^*]+\*\*)|(\*[^*]+\*)|(~~[^~]+~~)/g;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    out += inlineText(source.slice(i, match.index), context);
    const token = match[0];
    if (token.startsWith('`')) {
      out += codeSpan(token.slice(1, -1), context);
    } else if (token.startsWith('!')) {
      // An image: rendered as its alt text. This tool never loads a remote resource (DEC-027 § 2).
      out += `<em>${escapeHtml(/^!\[([^\]]*)\]/.exec(token)[1] || 'image')}</em>`;
    } else if (token.startsWith('[')) {
      out += markdownLink(token, context);
    } else if (token.startsWith('**')) {
      out += `<strong>${renderInline(token.slice(2, -2), context)}</strong>`;
    } else if (token.startsWith('~~')) {
      out += `<del>${renderInline(token.slice(2, -2), context)}</del>`;
    } else {
      out += `<em>${renderInline(token.slice(1, -1), context)}</em>`;
    }
    i = pattern.lastIndex;
  }
  out += inlineText(source.slice(i), context);
  return out;
}

/** Text with no inline markup left: escape it, keeping bare URLs visible. */
function inlineText(text, context) {
  return escapeHtml(text).replace(/\bhttps?:\/\/[^\s<)]+/g, (url) =>
    `<a href="${url}" title="${url}">${url}</a>`);
}

/** A code span: a link when it names something openable, a `<code>` otherwise. */
function codeSpan(content, context) {
  const target = resolveTarget(content, context);
  if (target) {
    return context.makeLink(target, content, 'code');
  }
  return `<code>${escapeHtml(content)}</code>`;
}

/** `[text](target "title")` — an internal target becomes a navigation link, an external URL stays a link. */
function markdownLink(token, context) {
  const parsed = /^\[([^\]]*)\]\(([^)\s]+)(?:\s+"([^"]*)")?\)$/.exec(token);
  if (!parsed) {
    return escapeHtml(token);
  }
  const [, text, href, title] = parsed;
  if (/^(https?:|mailto:)/i.test(href)) {
    return `<a href="${escapeHtml(href)}"${title ? ` title="${escapeHtml(title)}"` : ''}>`
      + `${renderInline(text, context)}</a>`;
  }
  const target = resolveTarget(href, context);
  if (target) {
    return context.makeLink(target, text, 'markdown', title);
  }
  // An unresolved internal link: keep the text, show where it pointed. A link that goes nowhere is worse
  // than a visible path (webview/kit/doc/contract.md § 4).
  return `${renderInline(text, context)} <code>${escapeHtml(href)}</code>`;
}

// ── block markdown ──────────────────────────────────────────────────────────────────────────────────

/** Slug for a heading anchor, so the table of contents can link to it. */
export function slugify(text) {
  return String(text ?? '')
    .toLowerCase()
    .replace(/`([^`]*)`/g, '$1')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'section';
}

/**
 * Convert Markdown to HTML and collect the headings for a table of contents.
 *
 * @param {string} markdown
 * @param {{directory?: string, index?: ClassIndex, isOpenable?: Function, makeLink: Function}} context
 *        `directory` is the document's directory **relative to the project root**, used to resolve
 *        relative links.
 * @returns {{html: string, headings: Array<{level: number, text: string, id: string}>}}
 */
export function renderMarkdown(markdown, context) {
  const lines = String(markdown ?? '').replace(/\r\n?/g, '\n').split('\n');
  const headings = [];
  const usedIds = new Set();
  const html = [];
  let i = 0;

  const heading = (text) => {
    let id = slugify(text);
    while (usedIds.has(id)) {
      id += '-1';
    }
    usedIds.add(id);
    return id;
  };

  while (i < lines.length) {
    const line = lines[i];

    // Fenced code: emitted verbatim (escaped), never inline-processed.
    const fence = /^(\s*)(`{3,}|~{3,})\s*([\w+-]*)\s*$/.exec(line);
    if (fence) {
      const marker = fence[2][0].repeat(3);
      const body = [];
      i++;
      while (i < lines.length && !new RegExp(`^\\s*${marker}`).test(lines[i])) {
        body.push(lines[i]);
        i++;
      }
      i++; // the closing fence
      const language = fence[3] ? ` class="language-${escapeHtml(fence[3])}"` : '';
      html.push(`<pre><code${language}>${escapeHtml(body.join('\n'))}</code></pre>`);
      continue;
    }

    if (/^\s*$/.test(line)) {
      i++;
      continue;
    }

    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      html.push('<hr>');
      i++;
      continue;
    }

    const headingMatch = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const text = headingMatch[2];
      const id = heading(text);
      headings.push({ level, text, id });
      html.push(`<h${level} id="${id}">${renderInline(text, context)}</h${level}>`);
      i++;
      continue;
    }

    // A table: a header row, then the `---|---` separator.
    if (line.includes('|') && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(lines[i + 1])) {
      const align = lines[i + 1].split('|').slice(1, -1).map((cell) => {
        const trimmed = cell.trim();
        if (trimmed.startsWith(':') && trimmed.endsWith(':')) return 'center';
        if (trimmed.endsWith(':')) return 'right';
        return '';
      });
      const row = (text, tag) => {
        const cells = splitRow(text);
        return `<tr>${cells.map((cell, index) => {
          const style = align[index] ? ` style="text-align:${align[index]}"` : '';
          return `<${tag}${style}>${renderInline(cell, context)}</${tag}>`;
        }).join('')}</tr>`;
      };
      html.push(`<table><thead>${row(line, 'th')}</thead><tbody>`);
      i += 2;
      while (i < lines.length && lines[i].includes('|') && !/^\s*$/.test(lines[i])) {
        html.push(row(lines[i], 'td'));
        i++;
      }
      html.push('</tbody></table>');
      continue;
    }

    // Blockquote: one level, which is all this repository's prose uses.
    if (/^\s*>\s?/.test(line)) {
      const body = [];
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        body.push(lines[i].replace(/^\s*>\s?/, ''));
        i++;
      }
      html.push(`<blockquote>${renderMarkdown(body.join('\n'), context).html}</blockquote>`);
      continue;
    }

    // Lists: consecutive `-`/`*`/`+` or `1.` items, with task markers.
    const listMatch = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(line);
    if (listMatch) {
      const ordered = /\d/.test(listMatch[2]);
      const items = [];
      while (i < lines.length) {
        const item = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(lines[i]);
        if (!item) {
          // A continuation line belongs to the previous item when it is indented.
          if (items.length > 0 && /^\s+\S/.test(lines[i])) {
            items[items.length - 1] += ' ' + lines[i].trim();
            i++;
            continue;
          }
          break;
        }
        if (/\d/.test(item[2]) !== ordered) {
          break;
        }
        let text = item[3];
        const task = /^\[([ xX])\]\s+(.*)$/.exec(text);
        if (task) {
          // The checkbox is the only place this renderer emits markup the author did not type, so it goes
          // through a sentinel: `renderInline` escapes everything else, and escaping this too would print
          // the markup instead of a checkbox.
          items.push(`\u0000task:${task[1] === ' ' ? ' ' : 'x'}\u0000${task[2]}`);
        } else {
          items.push(text);
        }
        i++;
      }
      const tasks = items.some((item) => item.startsWith('\u0000task:'));
      const tag = ordered ? 'ol' : 'ul';
      const cls = tasks ? ' class="contains-task-list"' : '';
      html.push(`<${tag}${cls}>${items.map((item) => `<li>${item.startsWith('\u0000task:')
        ? renderTaskItem(item, context)
        : renderInline(item, context)}</li>`).join('')}</${tag}>`);
      continue;
    }

    // Paragraph: consume until a blank line or the start of another block.
    const paragraph = [line];
    i++;
    while (i < lines.length && !/^\s*$/.test(lines[i])
        && !/^(\s*)(#{1,6}\s|>|[-*+]\s|\d+[.)]\s|`{3,}|~{3,})/.test(lines[i])
        && !/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(lines[i])) {
      paragraph.push(lines[i]);
      i++;
    }
    html.push(`<p>${renderInline(paragraph.join(' '), context)}</p>`);
  }

  return { html: html.join('\n'), headings };
}

/**
 * A task-list item: a disabled checkbox, then the item's inline Markdown.
 *
 * The checkbox is generated markup rather than author text, which is why the item carried a sentinel
 * through `renderInline`'s escaping instead of being passed through as-is.
 */
function renderTaskItem(item, context) {
  const end = item.indexOf('\u0000', 1);
  const checked = item.slice(1, end) !== ' ';
  return `<input type="checkbox" disabled${checked ? ' checked' : ''}> `
    + renderInline(item.slice(end + 1), context);
}

/** Split a table row on unescaped `|`, dropping the leading and trailing empty cells. */
function splitRow(text) {  const cells = String(text).replace(/\\\|/g, '\u0001').split('|').map((cell) => cell.trim().replace(/\u0001/g, '|'));
  if (cells.length > 0 && cells[0] === '') {
    cells.shift();
  }
  if (cells.length > 0 && cells[cells.length - 1] === '') {
    cells.pop();
  }
  return cells;
}
