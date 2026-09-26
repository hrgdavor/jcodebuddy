/**
 * highlight-runner.js — the few lines between microlighter and the page.
 *
 * It does three things, and each one is worth copying:
 *
 * 1. Calls `microlighter.highlightAll()` once the DOM is there, then again
 *    whenever anything dispatches the bubbling `syntax-highlight` event — the
 *    convention microlighter documents for a page whose content changes (an
 *    editable block, a filter that swaps a section, content injected later).
 *
 * 2. Reports what happened into `[data-syntax-status]`, so a reader can tell
 *    the difference between "no highlighting in this host" and "highlighting
 *    ran and found nothing". Both look identical otherwise, and that ambiguity
 *    costs an afternoon when the page is opened in an embedded browser.
 *
 * 3. Capability-detects the CSS Custom Highlight API. Every modern Chromium
 *    has it — JetBrains JCEF and the VS Code webview both do — but a page that
 *    is also read in Firefox, or in an older JCEF, should say so rather than
 *    appear silently broken. Code stays plain and readable either way, because
 *    microlighter never wraps tokens in markup: the text is always there.
 *
 * Configuration, all optional, on the script tag or on <body>:
 *   data-selector        the code-block selector (default "pre > code")
 *   data-syntax-status   attribute name is fixed; the element is looked up
 */
(function () {
  var script = document.currentScript;

  function setting(name, fallback) {
    var fromScript = script && script.getAttribute('data-' + name);
    var fromBody = document.body && document.body.getAttribute('data-' + name);
    return fromScript || fromBody || fallback;
  }

  function setStatus(text, state) {
    var elements = document.querySelectorAll('[data-syntax-status]');
    Array.prototype.forEach.call(elements, function (element) {
      element.textContent = element.getAttribute('data-syntax-status') === 'short'
        ? text.replace(/^Syntax:\s*/, '')
        : text;
      element.className = 'pill ' + state;
    });
  }

  function supported() {
    return typeof CSS !== 'undefined' && CSS.highlights && typeof Highlight === 'function';
  }

  function run() {
    if (!supported()) {
      setStatus('Syntax: unavailable (no CSS Custom Highlight API)', 'absent');
      document.body.setAttribute('data-syntax-ready', 'unsupported');
      return;
    }
    if (!window.microlighter) {
      setStatus('Syntax: library missing', 'absent');
      document.body.setAttribute('data-syntax-ready', 'missing');
      return;
    }

    var selector = setting('selector', 'pre > code');

    window.microlighter.highlightAll({ selector: selector })
      .then(function (blocks) {
        var categories = CSS.highlights.size;
        setStatus('Syntax: ' + blocks.length + ' block(s), ' + categories
          + ' token categor' + (categories === 1 ? 'y' : 'ies'), categories > 0 ? 'ready' : 'absent');
        // Also published as an attribute, so a smoke test or a human with
        // DevTools open can read one value instead of five.
        document.body.setAttribute('data-syntax-ready', blocks.length + '/' + categories);
        document.body.setAttribute('data-syntax-themes', setting('syntax-theme', 'default'));
      })
      .catch(function (error) {
        setStatus('Syntax: failed (' + (error && error.message ? error.message : error) + ')', 'absent');
        document.body.setAttribute('data-syntax-ready', 'error');
      });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', run);
  } else {
    run();
  }

  // The convention, exactly as microlighter documents it: bubble this event
  // from a changed block, or dispatch it on the document after you inject HTML.
  document.addEventListener('syntax-highlight', run);

  // A tiny theme switch, because the theme is only CSS variables and it is
  // worth being able to see that in the page. Optional: the attribute alone
  // does the work.
  document.addEventListener('click', function (event) {
    var button = event.target.closest ? event.target.closest('[data-theme-set]') : null;
    if (!button) { return; }
    document.body.setAttribute('data-syntax-theme', button.getAttribute('data-theme-set'));
  });
})();
