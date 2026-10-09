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
/**
 * Split a link target into the file, the line, and whatever follows the '#' (plan step 9.7).
 *
 * Deliberately not a grammar. A page does not need to know what a fragment means: it puts the whole target on
 * `data-open` and hands it to the host, which is the only part that resolves anything. What the page DOES need is the
 * file, to check it exists, and a line, as the fallback a host that cannot resolve a location still understands.
 *
 * @returns {{target: string, line: number, fragment: string|null}}
 */
export function splitTarget(raw) {
  const text = String(raw ?? '').trim()
  const hash = text.indexOf('#')
  if (hash < 0) {
    const { target, line } = splitLineSuffix(text)
    return { target, line, fragment: null }
  }
  const target = text.slice(0, hash)
  const fragment = text.slice(hash + 1)
  // The one thing worth reading: a plain line fragment is a line the page can settle itself, so it does not have to
  // be passed on as a location at all.
  const line = /^L?(\d+)$/i.exec(fragment)
  return { target, line: line ? Number(line[1]) : 1, fragment: line ? null : fragment }
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
  const { target, line, fragment } = splitTarget(String(raw ?? ''));
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
      // A fragment travels with the path and means nothing to this renderer: the host reads it, in whatever
      // spelling the documentation used, inject prefixes and all. The page states the file and the fallback line.
      return { open: candidate, line, member: '', role: 'file', fragment, fromIndex: false };
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

/**
 * Render a block's source lines **separately** and join them, so each line keeps its own line number.
 *
 * Doing it here rather than with an offset map is the whole trick: a run's line is then a fact about the input
 * line it was handed, and nothing has to be measured. The first attempt mapped offsets into the joined text and
 * passed its tests for a paragraph with inline markup while failing the common case — a plain paragraph is one
 * run at offset zero, so every part of it reported the block's own line.
 *
 * @param {string[]} sourceLines the block's lines, in order
 * @param {number} firstLine the source line the first entry came from
 */
function renderLines(sourceLines, context, firstLine) {
  return sourceLines
    .map((text, index) => renderInline(text, { ...context, line: firstLine + index, blockLine: firstLine }))
    .join(' ');
}

/** `data-line` for a block element, so a click anywhere in it has a line even without a text span. */
function lineAttribute(line) {
  return line ? ` data-line="${line}"` : '';
}

/** Text with no inline markup left: escape it, keeping bare URLs visible. */
function inlineText(text, context) {
  const html = escapeHtml(text).replace(/\bhttps?:\/\/[^\s<)]+/g, (url) =>
    `<a href="${url}" title="${url}">${url}</a>`);
  return withLine(html, context.line, context.blockLine);
}

/**
 * Wrap a run of plain text in a `data-line` span, so **Ctrl+click on the text** can open this document at
 * the line that produced it.
 *
 * The line is not decoration: a rendered page is the third view of the same prose (source, IDE preview,
 * this page), and comparing them is how a writer checks a document. Without it the page is a dead end —
 * the only way back to the source is to search for the sentence by hand.
 *
 * Two deliberate choices:
 *
 * - **Only text is wrapped.** A link already owns the click (`data-open`), and the contract's rule is that a
 *   click on a link navigates to the link's target. Marking text keeps the two gestures from competing.
 * - **A run on the enclosing block's own line is left as bare text**, so the markup stays as small as the
 *   information it carries: a single-line paragraph gets the block's attribute rather than a span per word.
 *
 * This is why a block that spans source lines renders each line **separately** and joins the results: an
 * offset map was the first attempt, and it silently failed on the common case — a paragraph with no inline
 * markup is one run at offset zero, so the map reported the block's own line for all of it. Rendering each
 * source line on its own removes the arithmetic, and a test pins the two-line case.
 *
 * @param {string} html the already-escaped text
 * @param {number} line the source line this run came from
 * @param {number|undefined} blockLine the enclosing block's own line
 */
function withLine(html, line, blockLine) {
  if (!html.trim() || !line || line === blockLine) {
    return html;
  }
  return `<span data-line="${line}">${html}</span>`;
}

/** How wide something is indented, counting a tab as four — enough to compare levels, which is all this needs. */
function indentWidth(text) {
  let width = 0;
  for (const character of text) {
    width += character === '\t' ? 4 : 1;
  }
  return width;
}

/**
 * An item's text, with a task marker passed through the sentinel `renderInline` would otherwise escape.
 *
 * The checkbox is the only markup this renderer emits that the author did not type, so it cannot go through
 * `renderInline`: escaping it would print the markup instead of a checkbox.
 */
function taskTextOrText(text) {
  const task = /^\[([ xX])\]\s+(.*)$/.exec(text);
  return task ? `\u0000task:${task[1] === ' ' ? ' ' : 'x'}\u0000${task[2]}` : text;
}

/**
 * A list, nested by indentation.
 *
 * Items are grouped into a tree first, so a nested item is emitted **inside its parent `<li>`** — which is what makes
 * a document's structure survive into the HTML — and consecutive items whose marker type differs start a **sibling**
 * list rather than being merged, because `-` and `1.` are different lists (CommonMark does the same).
 */
function renderList(items, context) {
  const root = { indent: -1, children: [] };
  const stack = [root];
  for (const item of items) {
    // A dedent closes every deeper level it passes; an indent no open level has clamps to the level above it rather
    // than inventing a structure out of malformed input.
    while (stack.length > 1 && item.indent <= stack[stack.length - 1].indent) {
      stack.pop();
    }
    const node = { ...item, children: [] };
    stack[stack.length - 1].children.push(node);
    stack.push(node);
  }
  return renderListLevel(root, context);
}

/** One level of the tree: its items, with an open list flushed whenever the marker type changes. */
function renderListLevel(node, context) {
  let html = '';
  let open = null;
  const flush = () => {
    if (!open) {
      return;
    }
    const cls = open.tasks ? ' class="contains-task-list"' : '';
    html += `<${open.tag}${cls}>${open.items.join('')}</${open.tag}>`;
    open = null;
  };
  for (const child of node.children) {
    if (!open || open.ordered !== child.ordered) {
      flush();
      open = { ordered: child.ordered, tag: child.ordered ? 'ol' : 'ul', tasks: false, items: [] };
    }
    const task = child.text.startsWith('\u0000task:');
    open.tasks = open.tasks || task;
    // A wrapped item is rendered as ONE run rather than per source line: a continuation line is appended to the
    // item's text, so rendering the lines separately printed it twice. Per-line precision is kept where the
    // reader actually compares prose with source — a paragraph (see `renderLines`) — and a wrapped bullet opens
    // at its own first line, which is what the item is.
    const body = task ? child.text.slice(child.text.indexOf('\u0000', 1) + 1) : child.text;
    const itemLine = child.line ?? context.line;
    const itemContext = { ...context, line: itemLine, blockLine: itemLine };
    const inner = task
      ? renderTaskItem(child.text, body, itemContext)
      : renderInline(body, itemContext);
    const children = child.children.length > 0 ? renderListLevel(child, context) : '';
    open.items.push(`<li${lineAttribute(itemLine)}>${inner}${children}</li>`);
  }
  flush();
  return html;
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
 * **Every block carries its source line** as `data-line`, and a run of text that came from a *different*
 * line than its block carries its own. That is what makes Ctrl+click on a rendered sentence open this
 * document at the line that produced it — see `withLine` for why links are excluded and why a same-line run
 * is left bare.
 *
 * @param {string} markdown
 * @param {{directory?: string, index?: ClassIndex, isOpenable?: Function, makeLink: Function, lineOffset?: number}} context
 *        `directory` is the document's directory **relative to the project root**, used to resolve
 *        relative links. `lineOffset` is added to every reported line, and exists for the one caller that
 *        renders a *slice* of a document: a blockquote strips its `>` markers and renders the body, so the
 *        body's line 1 is the document's line N.
 * @returns {{html: string, headings: Array<{level: number, text: string, id: string}>}}
 */
export function renderMarkdown(markdown, context) {
  const lines = String(markdown ?? '').replace(/\r\n?/g, '\n').split('\n');
  const headings = [];
  const usedIds = new Set();
  const html = [];
  const shift = Number(context.lineOffset) || 0;
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
    // The document line this block starts on, 1-based, plus any offset a caller imposed.
    const start = i + 1 + shift;

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
      html.push(`<pre${lineAttribute(start)}><code${language}>${escapeHtml(body.join('\n'))}</code></pre>`);
      continue;
    }

    if (/^\s*$/.test(line)) {
      i++;
      continue;
    }

    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      html.push(`<hr${lineAttribute(start)}>`);
      i++;
      continue;
    }

    const headingMatch = /^(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const text = headingMatch[2];
      const id = heading(text);
      headings.push({ level, text, id });
      html.push(`<h${level} id="${id}"${lineAttribute(start)}>`
        + `${renderInline(text, { ...context, line: start, blockLine: start })}</h${level}>`);
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
      // Each row and each cell reports its own line: a wide table read as one block would send every
      // Ctrl+click to the header row.
      const row = (text, tag, rowLine) => {
        const cells = splitRow(text);
        return `<tr${lineAttribute(rowLine)}>${cells.map((cell, index) => {
          const style = align[index] ? ` style="text-align:${align[index]}"` : '';
          return `<${tag}${style}>${renderInline(cell, { ...context, line: rowLine, blockLine: rowLine })}</${tag}>`;
        }).join('')}</tr>`;
      };
      html.push(`<table${lineAttribute(start)}><thead>${row(line, 'th', start)}</thead><tbody>`);
      const headerLine = start;
      i += 2;
      let bodyLine = headerLine + 2;
      while (i < lines.length && lines[i].includes('|') && !/^\s*$/.test(lines[i])) {
        html.push(row(lines[i], 'td', bodyLine));
        bodyLine++;
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
      // The body was stripped of its `>` markers, so its own line 1 is this block's line: the offset is what
      // keeps a Ctrl+click inside a quote pointing at the quoted line rather than at the top of the document.
      const inner = renderMarkdown(body.join('\n'), { ...context, lineOffset: start - 1 });
      html.push(`<blockquote${lineAttribute(start)}>${inner.html}</blockquote>`);
      continue;
    }

    // Lists: consecutive items, **nested by indentation**, with task markers. The indent used to be captured and
    // then ignored, so every nested item became a sibling and a document's structure was thrown away; a change of
    // marker type (a `1.` inside a `-` list) broke the list into two blocks instead of nesting one inside the other.
    const listItem = (text) => /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(text);
    const firstItem = listItem(line);
    if (firstItem) {
      const items = [];
      while (i < lines.length) {
        const item = listItem(lines[i]);
        if (!item) {
          // A continuation line belongs to the previous item when it is indented past that item's own marker.
          if (items.length > 0 && /^\s+\S/.test(lines[i])
              && indentWidth(lines[i]) > items[items.length - 1].indent) {
            items[items.length - 1].text += ' ' + lines[i].trim();
            i++;
            continue;
          }
          break;
        }
        items.push({
          indent: indentWidth(item[1]),
          ordered: /\d/.test(item[2]),
          text: taskTextOrText(item[3]),
          // The item's own line, which is what a Ctrl+click on the bullet opens.
          line: i + 1 + shift,
        });
        i++;
      }
      html.push(renderList(items, context));
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
    // A paragraph that wrapped across source lines is rendered line by line, so each one keeps its own line
    // number and a Ctrl+click lands on the sentence the reader is looking at rather than on the first line.
    html.push(`<p${lineAttribute(start)}>${renderLines(paragraph, context, start)}</p>`);
  }

  return { html: html.join('\n'), headings };
}

/**
 * A task-list item: a disabled checkbox, then the item's inline Markdown.
 *
 * The checkbox is generated markup rather than author text, which is why the item carried a sentinel through
 * `renderInline`'s escaping instead of being passed through as-is. The sentinel is `\0task:<space|x>\0`, and
 * the state is read from the character before the closing sentinel — comparing the whole sentinel to `' '` was
 * true for every item, because the prefix is part of it, so every unchecked box rendered as done.
 */
function renderTaskItem(marker, body, context) {
  const end = marker.indexOf('\u0000', 1);
  const checked = marker.charAt(end - 1) === 'x';
  return `<input type="checkbox" disabled${checked ? ' checked' : ''}> `
    + renderInline(body, context);
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
