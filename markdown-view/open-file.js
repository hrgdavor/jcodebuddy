/**
 * The client half of the WebView link contract — the same ladder the generated entity page uses.
 *
 * Read `webview/kit/doc/contract.md` for the contract itself; this file is a compact, self-contained
 * implementation of it, and the reason the Markdown viewer works in three different hosts:
 *
 *   1. the IDE's own webview injects `window.openFile(path, line, column)`  -> call it;
 *   2. otherwise a loopback HTTP bridge may be listening                    -> hidden iframe to `/open`;
 *   3. otherwise there is nothing to open with                              -> copy `path:line` and say so.
 *
 * A page must never render a link that does nothing, which is why step 3 exists: a location the user can
 * paste into their IDE beats a dead link.
 *
 * **Ctrl+click on rendered text** opens *this document* at the line that produced that text (`data-line` on
 * the body says which file that is). It is the same ladder, entered one step earlier — the reader is
 * comparing the page with the source, and without this the only way back is to search for the sentence by
 * hand. A click on a link is left alone whatever the modifier, because a link already means a location and
 * two answers to one gesture is how a page becomes unpredictable.
 */

/** Where the page thinks the IDE bridge is; overridden by `data-bridge-port` on `<body>`. */
export const DEFAULT_BRIDGE_PORT = 18881;

/**
 * Escape a value for a double-quoted HTML attribute.
 *
 * Lives here with the client because both halves need it and there must be exactly one spelling: a
 * mismatch between how a path is escaped into `data-open` and how it is read back is a link that opens
 * the wrong file on exactly the paths that contain a quote.
 */
export function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * The `<script>` body that installs the click handler and the fallback ladder.
 *
 * Returned as text rather than shipped as a module because the page must be **one self-contained file**
 * with no `<script src>` and no network at view time (DEC-027 § 2). Plain ES5-style JavaScript, so the
 * JetBrains JCEF webview parses it with no build step.
 *
 * It is written with `String.raw` on purpose: the code contains regular expressions with backslashes, and
 * an ordinary template literal would silently turn `\\/` into `/` and change what the regex matches.
 *
 * @param {{bridgePort?: number}} [options]
 */
export function openFileClientScript(options = {}) {
  const bridgePort = Number.isFinite(options.bridgePort) ? options.bridgePort : DEFAULT_BRIDGE_PORT;
  return String.raw`
(function () {
  var BRIDGE_PORT = parseInt(document.body.getAttribute('data-bridge-port') || '__PORT__', 10);
  var LINK_BASE = document.body.getAttribute('data-link-base') || '';
  var toast = null;

  /**
   * The document this page renders, read AT CLICK TIME rather than once at load.
   *
   * It has to be read here: in the host mode (markdown-view/page.js) the page is built with no document and the
   * view data arrives afterwards, so page-client.js sets data-source during its first render - after this script
   * has run. Caching the attribute at load made Ctrl+click silently do nothing in exactly the case this feature
   * was requested for (a host loading a Markdown file, the JetBrains plugin among them), and a browser check is
   * what caught it. Note the absence of backticks in this comment: it lives inside a String.raw template, where
   * one would end the literal.
   */
  function sourcePath() {
    return document.body.getAttribute('data-source') || '';
  }

  function say(text) {
    if (!toast) {
      toast = document.createElement('div');
      toast.id = 'toast';
      document.body.appendChild(toast);
    }
    toast.textContent = text;
    toast.classList.add('show');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { toast.classList.remove('show'); }, 2600);
  }

  function isAbsolute(target) {
    return /^[A-Za-z]:[\\/]/.test(target) || target.charAt(0) === '/' || target.indexOf('://') > 0;
  }

  function absoluteTarget(relative) {
    if (!relative) { return ''; }
    if (isAbsolute(relative)) { return relative; }
    try {
      var base = LINK_BASE ? LINK_BASE.replace(/\/+$/, '') + '/' : '';
      var url = new URL(base + relative, document.baseURI);
      var path = decodeURIComponent(url.pathname);
      // The fragment is PART OF THE TARGET: it is how a link names a method or a region (plan 9.7), and
      // url.pathname does not carry it. Dropping it here is what made a click open the right file at line 1.
      var hash = url.hash || '';
      return path.replace(/^\/([A-Za-z]:)/, '$1') + hash;
    } catch (error) {
      return relative;
    }
  }

  function copy(text) {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text);
        return true;
      }
    } catch (error) { /* clipboard blocked: the toast still shows the path */ }
    return false;
  }

  /**
   * Hand one location to the host, by whichever of the three transports this page has.
   *
   * Split out of the link handler so a Ctrl+click on text takes the SAME ladder: a second copy of these three
   * steps is how a fallback drifts, and the copy that drifts is the one nobody tested (a browser, where
   * window.openFile is absent).
   */
  function openAt(relative, line, member, what) {
    var target = absoluteTarget(relative) || relative;

    if (typeof window.openFile === 'function') {
      window.openFile(target, line, 1);
      // "Asked", not "Opened": the page hands the location over and cannot know what the IDE did with it. The
      // old wording said "at line 1" for a method link, which reads like the IDE ignored the method when the
      // line was only ever the fallback.
      say(member
        ? 'Asked the IDE to open ' + member + ' in ' + String(relative).split('#')[0] + '.'
        : 'Asked the IDE to open ' + target + (line > 1 ? ' at line ' + line : '') + '.');
      return;
    }
    if (BRIDGE_PORT > 0) {
      var url = 'http://127.0.0.1:' + BRIDGE_PORT + '/open?filePath=' + encodeURIComponent(target)
        + '&line=' + line + '&column=1';
      var previous = document.getElementById('bridge-frame');
      if (previous) { previous.remove(); }
      var frame = document.createElement('iframe');
      frame.id = 'bridge-frame';
      frame.style.display = 'none';
      frame.setAttribute('aria-hidden', 'true');
      frame.src = url;
      document.body.appendChild(frame);
      say('Sent line ' + line + ' to the IDE bridge on port ' + BRIDGE_PORT + '.');
      return;
    }
    copy(target + ':' + line);
    say('No IDE bridge here. Location copied: ' + target + ':' + line);
  }

  function open(link) {
    var relative = link.getAttribute('data-open');
    var line = parseInt(link.getAttribute('data-line') || '1', 10);
    var member = link.getAttribute('data-member') || '';
    openAt(relative, line, member, 'link');
  }

  document.addEventListener('click', function (event) {
    // Ctrl (or Cmd on a Mac) is the gesture for "go to where this came from". A link is left untouched
    // whatever the modifier: a link already names a location, and the browser's own Ctrl+click on one should
    // keep working.
    if (event.ctrlKey || event.metaKey) {
      var onLink = event.target.closest ? event.target.closest('[data-open]') : null;
      if (onLink) { return; }
      var line = event.target.closest ? event.target.closest('[data-line]') : null;
      var own = sourcePath();
      if (!line || !own) { return; }
      event.preventDefault();
      openAt(own, parseInt(line.getAttribute('data-line') || '1', 10), '',
        'this document');
      return;
    }
    var link = event.target.closest ? event.target.closest('[data-open]') : null;
    if (!link) { return; }
    event.preventDefault();
    open(link);
  });

  // Say up front which of the three modes this page is in, so a user never has to guess why a click did
  // nothing visible.
  var status = document.getElementById('bridge-status');
  if (status) {
    if (typeof window.openFile === 'function') {
      status.textContent = 'IDE bridge: ready';
      status.className = 'pill ready';
    } else {
      status.textContent = BRIDGE_PORT > 0 ? 'IDE bridge: absent (HTTP fallback)' : 'IDE bridge: absent';
      status.className = 'pill absent';
    }
  }
})();
`.replace('__PORT__', String(bridgePort)).trim();
}

/**
 * The stylesheet, turquoise like the generated entity page, so the two views of this repository look like
 * one product. Kept here rather than in the renderer so a user copying the tool gets a usable page in one
 * import.
 */
export const PAGE_STYLE = String.raw`
/* GitHub light, which is what a reader of documentation expects to see - and what the host's own Markdown
   preview looks like, so a page rendered here does not stand out as a different program. */
:root {
  --bg: #ffffff; --surface: #f6f8fa; --surface2: #f6f8fa; --border: #d0d7de;
  --text: #1f2328; --dim: #59636e; --accent: #0969da; --accent-dim: #0969da;
  --green: #1a7f37; --yellow: #9a6700; --orange: #bc4c00; --red: #cf222e;
  --head-cell: #f6f8fa; --hover-accent: #eaeef2; --filled: #f6f8fa; --empty-mark: #d0d7de;
  --border-green: #aceebb; --border-orange: #f5d9a8; --border-red: #ffcecb;

  /* microlighter's theme contract: a theme only sets these custom properties, and the ::highlight() rules
     at the bottom of this style consume them. These are GitHub light's token colours. */
  --syntax-background: #f6f8fa; --syntax-foreground: #1f2328;
  --syntax-comment: #6e7781; --syntax-keyword: #cf222e; --syntax-operator: #0550ae;
  --syntax-string: #0a3069; --syntax-constant: #0550ae; --syntax-function: #8250df;
  --syntax-type: #953800; --syntax-variable: #953800; --syntax-property: #0550ae;
  --syntax-tag: #116329; --syntax-selector: #6639ba; --syntax-inserted: #1a7f37;
  --syntax-deleted: #cf222e;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text);
  line-height: 1.55; }
a { color: var(--accent); text-decoration: none; border-bottom: 1px solid var(--accent-dim); }
a:hover { border-bottom-color: var(--accent); }
code { font-family: 'Cascadia Code', 'JetBrains Mono', Consolas, monospace; font-size: .85em;
  color: var(--yellow); background: var(--surface2); padding: .05rem .3rem; border-radius: 4px; }
pre { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: .7rem .8rem;
  overflow-x: auto; margin: .8rem 0; }
pre code { background: none; color: var(--text); padding: 0; font-size: .8rem; }
header.top { position: sticky; top: 0; z-index: 20; background: var(--bg); border-bottom: 1px solid var(--border);
  padding: .7rem 1.2rem .6rem; display: flex; flex-wrap: wrap; gap: .8rem; align-items: baseline; }
header.top h1 { font-size: 1.05rem; font-weight: 700; }
header.top h1 span { color: var(--accent); }
.top-sub { color: var(--dim); font-size: .8rem; display: flex; flex-wrap: wrap; gap: .8rem; }
.pill { font-size: .74rem; padding: .2rem .5rem; border-radius: 999px; border: 1px solid var(--border);
  background: var(--surface2); color: var(--dim); }
.pill.ready { color: var(--green); border-color: var(--border-green); }
.pill.absent { color: var(--orange); border-color: var(--border-orange); }
#layout { display: grid; grid-template-columns: 290px minmax(0, 1fr); align-items: start; }
#nav { position: sticky; top: 92px; max-height: calc(100vh - 104px); overflow: auto;
  padding: .9rem .6rem 2rem 1.2rem; border-right: 1px solid var(--border); font-size: .84rem; }
#nav .nav-title { text-transform: uppercase; letter-spacing: .1em; font-size: .68rem; color: var(--dim);
  margin-bottom: .5rem; }
#nav a { display: block; padding: .22rem .4rem; border-radius: 5px; border-bottom: none; color: var(--dim); }
#nav a:hover { color: var(--text); background: var(--surface2); }
#nav a.lvl-2 { padding-left: 1.1rem; }
#nav a.lvl-3 { padding-left: 1.9rem; font-size: .95em; }
main { padding: 1rem 1.4rem 4rem; min-width: 0; max-width: 60rem; }
h1, h2, h3 { line-height: 1.25; }
h1 { font-size: 1.5rem; margin: .2rem 0 .8rem; }
h2 { font-size: 1.18rem; margin: 1.6rem 0 .6rem; padding-bottom: .25rem; border-bottom: 1px solid var(--border); }
h3 { font-size: 1rem; margin: 1.2rem 0 .4rem; color: var(--accent); }
p { margin: .6rem 0; }
ul, ol { margin: .6rem 0 .6rem 1.3rem; }
li { margin: .18rem 0; }
ul.contains-task-list { list-style: none; margin-left: .2rem; }
blockquote { border-left: 3px solid var(--accent-dim); padding: .1rem .9rem; margin: .8rem 0; color: var(--dim); }
hr { border: none; border-top: 1px solid var(--border); margin: 1.4rem 0; }
table { border-collapse: separate; border-spacing: 0; margin: .9rem 0; font-size: .84rem; width: 100%;
  border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
th, td { border-bottom: 1px solid var(--border); border-right: 1px solid var(--border);
  padding: .35rem .55rem; text-align: left; vertical-align: top; }
th { background: var(--head-cell); font-weight: 600; }
tr:last-child td { border-bottom: none; }
th:last-child, td:last-child { border-right: none; }
tbody tr:hover td { background: var(--filled); }
.source-note { margin-top: 2.4rem; padding-top: .8rem; border-top: 1px solid var(--border); color: var(--dim);
  font-size: .78rem; }
footer.bottom { border-top: 1px solid var(--border); padding: 1rem 1.4rem 3rem; color: var(--dim); font-size: .78rem; }
#toast { position: fixed; right: 1rem; bottom: 1rem; max-width: 70vw; background: var(--surface2);
  color: var(--text); border: 1px solid var(--accent-dim); border-left: 3px solid var(--accent);
  border-radius: 8px; padding: .55rem .7rem; font-size: .8rem; opacity: 0; transform: translateY(6px);
  transition: opacity .18s ease, transform .18s ease; pointer-events: none; z-index: 50; }
#toast.show { opacity: 1; transform: translateY(0); }
@media (max-width: 980px) {
  #layout { grid-template-columns: 1fr; }
  #nav { position: static; max-height: none; border-right: none; }
}

/* ---- the document ----
   The page renders a Markdown file, so the reading experience is the feature: these are GitHub's own
   proportions, which is what "resembles GitHub light mode" means in practice. */
#doc { max-width: 920px; padding: 8px 32px 72px; font-size: 16px; }
#doc > :first-child { margin-top: 0; }
#doc h1, #doc h2 { padding-bottom: .3em; border-bottom: 1px solid var(--border); }
#doc h1 { font-size: 2em; font-weight: 600; margin: 24px 0 16px; }
#doc h2 { font-size: 1.5em; font-weight: 600; margin: 24px 0 16px; }
#doc h3 { font-size: 1.25em; font-weight: 600; margin: 24px 0 16px; }
#doc h4 { font-size: 1em; font-weight: 600; margin: 24px 0 16px; }
#doc h5, #doc h6 { font-size: .875em; font-weight: 600; color: var(--dim); margin: 24px 0 16px; }
#doc p, #doc ul, #doc ol, #doc blockquote, #doc table, #doc pre { margin: 0 0 16px; }
#doc ul, #doc ol { padding-left: 2em; }
#doc li + li { margin-top: .25em; }
#doc li > ul, #doc li > ol { margin: .25em 0 0; }
#doc blockquote { padding: 0 1em; color: var(--dim); border-left: .25em solid var(--border); }
#doc hr { height: .25em; background: var(--border); border: 0; margin: 24px 0; }
#doc img { max-width: 100%; }
#doc table { border-collapse: collapse; border-spacing: 0; display: block; width: max-content;
  max-width: 100%; overflow: auto; }
#doc th, #doc td { border: 1px solid var(--border); padding: 6px 13px; }
#doc th { background: var(--head-cell); font-weight: 600; text-align: left; }
#doc tr:nth-child(2n) td { background: var(--surface); }
#doc pre { padding: 16px; overflow: auto; font-size: 85%; line-height: 1.45; }
#doc pre code { background: none; padding: 0; font-size: 100%; }
#doc code { font-size: 85%; }

/* ---- the code colours ----
   microlighter never wraps a token in a <span>: it registers ranges on CSS.highlights and lets these rules
   colour them with ::highlight(category). Without them the ranges exist and nothing shows, which is what a
   page with no theme at all looks like - and it is why the highlighter looked broken rather than plain. */
::highlight(comment) { color: var(--syntax-comment); }
::highlight(keyword) { color: var(--syntax-keyword); }
::highlight(operator) { color: var(--syntax-operator); }
::highlight(string) { color: var(--syntax-string); }
::highlight(constant) { color: var(--syntax-constant); }
::highlight(numeric) { color: var(--syntax-constant); }
::highlight(function) { color: var(--syntax-function); }
::highlight(type) { color: var(--syntax-type); }
::highlight(variable) { color: var(--syntax-variable); }
::highlight(property) { color: var(--syntax-property); }
::highlight(attribute-value) { color: var(--syntax-string); }
::highlight(tag) { color: var(--syntax-tag); }
::highlight(selector) { color: var(--syntax-selector); }
::highlight(character-entity) { color: var(--syntax-constant); }
::highlight(link) { color: var(--syntax-function); }
::highlight(inserted) { color: var(--syntax-inserted); }
::highlight(deleted) { color: var(--syntax-deleted); }
`;
