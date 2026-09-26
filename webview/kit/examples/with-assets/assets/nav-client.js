/**
 * The navigation client — the page half of doc/contract.md.
 *
 * Drop this file anywhere and reference it from any page:
 *
 *     <script id="nav-client"
 *             src="../assets/nav-client.js"
 *             data-link-base="../../../../.."      <!-- the project root, relative to THIS script -->
 *             data-bridge-port="18881"></script>
 *
 * Three things to know about it:
 *
 * 1. **The config lives on the script tag, not on the page.** `data-link-base`
 *    is resolved against the script's own `src`, so one copy of this file serves
 *    a page at `index.html` and a page at `pages/deep/report.html` with no edits
 *    and no per-page bookkeeping: the value describes the distance from
 *    `assets/` to the project root, which is the same for every page. A page
 *    that would rather configure itself can put `data-link-base` /
 *    `data-bridge-port` on `<body>` instead, in which case they are relative to
 *    the page; the script tag wins when both are present.
 *
 * 2. **It never hard-codes the bridge port.** A conventional port differs per
 *    host, so it is a deployment detail and belongs in configuration: the port a
 *    host is really on is published in the project's
 *    `.jcodebuddy/webview/host.json`, and `GET /health` says whether anything is
 *    listening on it. See doc/host-in-this-project.md.
 *
 * 3. **The ladder never dead-ends.** Injected function, then the loopback HTTP
 *    transport, then the clipboard. A click always does something the user can
 *    see, which is the rule that makes a page usable in a browser and in a CI
 *    artifact as well as inside the host's own webview.
 *
 * Plain ES5-style JavaScript on purpose: an embedded webview parses it with no
 * build step, and there is no dependency to install.
 */
(function () {
  var script = document.currentScript;

  function config(name, fallback) {
    var fromScript = script && script.getAttribute('data-' + name);
    var fromBody = document.body && document.body.getAttribute('data-' + name);
    return fromScript || fromBody || fallback;
  }

  /**
   * The absolute URL of the project root.
   *
   * `data-link-base` is relative to the script's own location, so it stays
   * correct no matter how deep the page is or how it was reached.
   */
  function projectRoot() {
    var base = config('link-base', '');
    // Configuring the script with a directory is the common case; resolve it
    // against the script URL, not against the page.
    if (script && script.src && base) {
      try {
        return new URL(base.replace(/\/+$/, '') + '/', script.src).href;
      } catch (error) { /* fall through to the page-relative spelling */ }
    }
    return base;
  }

  var ROOT = projectRoot();
  var PORT = parseInt(config('bridge-port', '0'), 10) || 0;
  var TOKEN = config('bridge-token', '');
  var toast = null;
  var onStatus = [];

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

  /** A page-relative path -> the absolute path the IDE wants. Never a baked-in constant. */
  function absoluteTarget(relative) {
    if (!relative) { return ''; }
    if (isAbsolute(relative)) { return relative; }
    try {
      var url = new URL(relative, ROOT || document.baseURI);
      var path = decodeURIComponent(url.pathname);
      return path.replace(/^\/([A-Za-z]:)/, '$1');   // strip the leading slash off /C:/...
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

  function open(link) {
    var relative = link.getAttribute('data-open');
    var line = parseInt(link.getAttribute('data-line') || '1', 10);
    var member = link.getAttribute('data-member') || '';
    var target = absoluteTarget(relative) || relative;
    return navigate(target, line, member || relative);
  }

  /** The ladder itself, taking an already resolved target. */
  function navigate(target, line, label) {
    // 1. the IDE webview injected this after the page loaded
    if (typeof window.openFile === 'function') {
      window.openFile(target, line, 1);
      say('Opened ' + (label || target) + ' at line ' + line + ' in the IDE.');
      return 'injected';
    }
    // 2. the loopback HTTP bridge, through a hidden iframe so no CORS grant is needed
    if (PORT > 0) {
      var url = 'http://127.0.0.1:' + PORT + '/open?filePath=' + encodeURIComponent(target)
        + '&line=' + line + '&column=1' + (TOKEN ? '&token=' + encodeURIComponent(TOKEN) : '');
      var previous = document.getElementById('bridge-frame');
      if (previous) { previous.remove(); }
      var frame = document.createElement('iframe');
      frame.id = 'bridge-frame';
      frame.style.display = 'none';
      frame.setAttribute('aria-hidden', 'true');
      frame.src = url;
      document.body.appendChild(frame);
      say('Sent line ' + line + ' to the IDE bridge on port ' + PORT + '.');
      return 'bridge';
    }
    // 3. nothing to open with — say so, and put the location where it is usable
    copy(target + ':' + line);
    say('No IDE bridge here. Location copied: ' + target + ':' + line);
    return 'clipboard';
  }

  function mode() {
    if (typeof window.openFile === 'function') { return 'injected'; }
    return PORT > 0 ? 'bridge' : 'clipboard';
  }

  function describe() {
    var current = mode();
    if (current === 'injected') {
      return 'IDE bridge: injected (v' + (window.__jcbWebViewBridge || '?') + ')';
    }
    if (current === 'bridge') {
      return 'IDE bridge: absent (HTTP fallback :' + PORT + ')';
    }
    return 'IDE bridge: absent (clipboard only)';
  }

  /** Paint every element a page may have nominated to show the current mode. */
  function report() {
    var state = mode() === 'injected' ? 'ready' : 'absent';
    var elements = document.querySelectorAll('[data-bridge-status]');
    Array.prototype.forEach.call(elements, function (element) {
      element.textContent = element.getAttribute('data-bridge-status') === 'short'
        ? mode()
        : describe();
      element.className = 'pill ' + state;
    });
    onStatus.forEach(function (fn) { fn(mode(), describe()); });
  }

  // ONE delegated handler for the whole document: a generated block that lands
  // in the page later needs no wiring of its own.
  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('[data-open]') : null;
    if (!link) { return; }
    event.preventDefault();
    open(link);
  });

  // Say up front which mode this page is in, so a user never has to guess why a
  // click did nothing visible. Marking an element is enough: `data-bridge-status`
  // with no value (or any value) both work — the attribute says *where*, not what.
  report();

  window.addEventListener('load', function () { setTimeout(report, 0); });

  /**
   * Public surface, small on purpose:
   *   window.jcbNav.open(path, line)      navigate programmatically
   *   window.jcbNav.mode()                'injected' | 'bridge' | 'clipboard'
   *   window.jcbNav.absoluteTarget(p)     what the page will actually ask for
   *   window.jcbNav.links()               every data-open link, resolved (for tests)
   */
  window.jcbNav = {
    open: function (path, line) {
      var target = absoluteTarget(path) || path;
      return navigate(target, parseInt(line, 10) || 1, path);
    },
    mode: mode,
    describe: describe,
    absoluteTarget: absoluteTarget,
    projectRoot: function () { return ROOT; },
    onStatus: function (fn) { onStatus.push(fn); return function () { onStatus.splice(onStatus.indexOf(fn), 1); }; },
    links: function () {
      return Array.prototype.map.call(document.querySelectorAll('[data-open]'), function (element) {
        return {
          open: element.getAttribute('data-open'),
          line: parseInt(element.getAttribute('data-line') || '1', 10),
          member: element.getAttribute('data-member') || '',
          resolved: absoluteTarget(element.getAttribute('data-open'))
        };
      });
    }
  };

  // Keep the old probe name working: some pages and tests look for it.
  window.__pageDebug = {
    linkBase: ROOT,
    bridgePort: PORT,
    absoluteTarget: absoluteTarget,
    links: window.jcbNav.links
  };
})();
