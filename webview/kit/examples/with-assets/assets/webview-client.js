/**
 * webview-client.js — the page's whole side of the host contract, in one dependency-free file.
 *
 * It is the successor to `nav-client.js` for pages that want more than navigation. `nav-client.js` walks
 * "injected → bridge → clipboard" from a configured port; this walks the same ladder from **what the host says
 * it can do** (`GET /health` and `/.well-known/webview.json`), and adds the rungs the write contract needs:
 * read a file, propose an edit, show the diff, apply it — into the editor's buffer when the host can, otherwise
 * to disk — and undo it.
 *
 * Three properties are deliberate:
 *
 * 1. **No DOM.** Everything above the clipboard rung works with a `fetch` and a token; only the browser
 *    conveniences (`auto()`, the click handler) touch `document`. That is what lets a node test drive the same
 *    code against a live `webviewd` instead of a mock, which is how this file is verified.
 * 2. **It never guesses a capability.** The mode is `injected`, `bridge` or `clipboard` as before, and the edit
 *    rung additionally requires `edit` in the host's capability list. A page that asks for a buffer no host can
 *    provide gets the host's `409 no-buffer-edit` rather than a silent disk write.
 * 3. **The digest comes from the host.** `/file/` and `/page/` answer with `X-WebView-Digest` (and an `ETag`),
 *    so the page proposes its edit with the host's own idea of the file. When a host does not send it (the IDE
 *    plugins do not yet), the client falls back to hashing the text it received, which is the same thing
 *    computed twice.
 *
 * Usage in a page:
 *
 *     <script src="../assets/webview-client.js"
 *             data-bridge-port="18899" data-bridge-token="…"></script>
 *     <script>
 *       const client = await window.jcbClient.auto();     // discovers the host, paints nothing
 *       await client.open('src/A.java', 12, 1);
 *       const file = await client.read('src/A.java');
 *       const proposal = await client.proposeEdit(file.path, file.digest,
 *           [{startLine: 12, startColumn: 5, endLine: 12, endColumn: 9, newText: 'renamed'}]);
 *       // show proposal.unifiedDiff, then:
 *       await client.applyEdit(file.path, file.digest, edits);
 *     </script>
 *
 * Plain ES2017 JavaScript on purpose: the JetBrains JCEF webview parses it with no build step, and there is
 * nothing to install.
 */
(function (global) {
  'use strict';

  var DEFAULT_PORT = 0;

  function isAbsolute(target) {
    return /^[A-Za-z]:[\\/]/.test(target) || target.charAt(0) === '/' || target.indexOf('://') > 0;
  }

  /**
   * Builds a client over one host.
   *
   * @param options.port      the host's port; 0 means "there is no host, use the clipboard"
   * @param options.token     the shared secret, when the host requires one for writes
   * @param options.fetchImpl injectable for tests
   * @param options.opener    how to reach an injected bridge; defaults to window.openFile
   */
  function create(options) {
    var settings = options || {};
    var port = parseInt(settings.port, 10) || DEFAULT_PORT;
    var token = settings.token || '';
    var fetchImpl = settings.fetchImpl || (typeof fetch === 'function' ? fetch.bind(global) : null);
    var base = port > 0 ? 'http://127.0.0.1:' + port : '';
    var state = {
      mode: typeof settings.opener === 'function' ? 'injected' : (port > 0 ? 'bridge' : 'clipboard'),
      plugin: '',
      bridgeVersion: 0,
      capabilities: [],
      tokenRequired: false,
      host: null,
      reachable: false
    };
    var listeners = [];

    function headers(extra) {
      var all = extra || {};
      if (token) { all['X-WebView-Token'] = token; }
      return all;
    }

    function url(path, query) {
      var full = base + path;
      if (query) {
        var pairs = [];
        Object.keys(query).forEach(function (key) {
          pairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(query[key]));
        });
        if (pairs.length) { full += (full.indexOf('?') < 0 ? '?' : '&') + pairs.join('&'); }
      }
      return full;
    }

    async function json(response) {
      var text = await response.text();
      try {
        return text ? JSON.parse(text) : {};
      } catch (error) {
        return { raw: text };
      }
    }

    /** Reads what the host can do. Never throws: an unreachable host is the clipboard rung, not an error. */
    async function discover() {
      if (!base || !fetchImpl) {
        if (state.mode !== 'injected') { state.mode = 'clipboard'; }
        return state;
      }
      try {
        var response = await fetchImpl(url('/health'), { headers: headers() });
        if (!response.ok) {
          // A port is configured but nothing answers on it: the honest rung is the clipboard, not "bridge".
          // Leaving the mode at 'bridge' is how a page ends up claiming a host it does not have.
          state.reachable = false;
          if (state.mode !== 'injected') { state.mode = 'clipboard'; }
          return state;
        }
        var body = await json(response);
        state.reachable = true;
        state.plugin = body.plugin || '';
        state.bridgeVersion = body.bridgeVersion || 0;
        state.capabilities = body.capabilities || [];
        state.tokenRequired = !!body.tokenRequired;
        if (state.mode !== 'injected') { state.mode = 'bridge'; }
        try {
          var manifest = await fetchImpl(url('/.well-known/webview.json'), { headers: headers() });
          if (manifest.ok) {
            var described = await json(manifest);
            state.host = described.host || null;
            if (described.capabilities) { state.capabilities = described.capabilities; }
          }
        } catch (error) { /* the manifest is a bonus: /health already answered */ }
      } catch (error) {
        state.reachable = false;
        if (state.mode !== 'injected') { state.mode = 'clipboard'; }
      }
      return state;
    }

    function can(capability) {
      return state.capabilities.indexOf(capability) >= 0;
    }

    /** Feature-detect, then discover, then decide. One await, and the caller knows its rung. */
    async function ready() {
      if (typeof settings.opener === 'function') {
        state.mode = 'injected';
        state.capabilities = ['open'];
      }
      await discover();
      return state;
    }

    /** The absolute path a page-relative link means, when the client knows its own project root. */
    function absolute(path, linkBase) {
      if (!path || isAbsolute(path)) { return path || ''; }
      try {
        var resolved = new global.URL(path, linkBase || (global.document && global.document.baseURI) || '');
        var decoded = decodeURIComponent(resolved.pathname);
        return decoded.replace(/^\/([A-Za-z]:)/, '$1');
      } catch (error) {
        return path;
      }
    }

    /** Navigation, in the same order nav-client.js uses: injected, then the host, then the clipboard. */
    async function open(path, line, column) {
      var target = path;
      var atLine = parseInt(line, 10) || 1;
      var atColumn = parseInt(column, 10) || 1;
      if (typeof settings.opener === 'function') {
        settings.opener(target, atLine, atColumn);
        state.mode = 'injected';
        return 'injected';
      }
      if (base && fetchImpl) {
        try {
          var response = await fetchImpl(url('/open', {
            filePath: target, line: atLine, column: atColumn, token: token
          }), { headers: headers() });
          if (response.ok) {
            state.mode = 'bridge';
            return 'bridge';
          }
        } catch (error) { /* fall through to the clipboard */ }
      }
      copy(target + ':' + atLine);
      state.mode = 'clipboard';
      return 'clipboard';
    }

    function copy(text) {
      try {
        if (global.navigator && global.navigator.clipboard && global.navigator.clipboard.writeText) {
          global.navigator.clipboard.writeText(text);
          return true;
        }
      } catch (error) { /* blocked clipboard: the caller still gets the string back */ }
      return false;
    }

    /**
     * Reads a project file through the host, with the digest the host says it has.
     *
     * @return {path, text, digest, contentType}
     */
    async function read(path, options2) {
      var absolutePath = (options2 && options2.absolute) || absolute(path);
      var response = await fetchImpl(url('/file/' + absolutePath + (token ? '' : '')), {
        headers: headers()
      });
      if (!response.ok) {
        throw new Error('the host refused to serve ' + absolutePath + ': HTTP ' + response.status);
      }
      var text = await response.text();
      var digest = response.headers.get('X-WebView-Digest');
      if (!digest) {
        digest = await hash(text);
      }
      return {
        path: absolutePath,
        text: text,
        digest: digest,
        contentType: response.headers.get('Content-Type') || ''
      };
    }

    /** sha256:<hex> over UTF-8, for hosts that do not announce a digest. */
    async function hash(text) {
      var bytes = new global.TextEncoder().encode(text);
      var buffer = await global.crypto.subtle.digest('SHA-256', bytes);
      var hex = Array.prototype.map.call(new Uint8Array(buffer), function (byte) {
        return ('0' + byte.toString(16)).slice(-2);
      }).join('');
      return 'sha256:' + hex;
    }

    /** One write-contract call. Every verb goes through here so the headers and the errors are in one place. */
    async function write(route, body) {
      if (!base || !fetchImpl) {
        throw new Error('no host is configured: this page is on the clipboard rung');
      }
      var response = await fetchImpl(url(route), {
        method: 'POST',
        headers: headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body)
      });
      var parsed = await json(response);
      parsed.status = response.status;
      return parsed;
    }

    /** The proposal: a diff and the digest the file would have, with nothing written. */
    function proposeEdit(filePath, expectedDigest, edits, target) {
      return write('/api/v1/diff', {
        filePath: filePath, expectedDigest: expectedDigest, edits: edits,
        target: target || 'auto', dryRun: true
      });
    }

    /**
     * The accepted edit. `target` is passed through: 'auto' lets the host choose the editor's buffer when it can,
     * 'buffer' insists on it (and the host answers 409 no-buffer-edit rather than writing to disk), 'disk' insists
     * on this host's own write.
     */
    function applyEdit(filePath, expectedDigest, edits, target) {
      return write('/api/v1/applyEdit', {
        filePath: filePath, expectedDigest: expectedDigest, edits: edits,
        target: target || 'auto', dryRun: false
      });
    }

    function undo(filePath) {
      return write('/api/v1/undo', { filePath: filePath });
    }

    function redo(filePath) {
      return write('/api/v1/redo', { filePath: filePath });
    }

    /**
     * File-change events. Uses `EventSource` where it exists; elsewhere (node, older webviews) it returns a
     * no-op unsubscribe and says so, rather than pretending to watch.
     */
    function watch(onChange) {
      if (typeof global.EventSource !== 'function' || !base) {
        return { watching: false, close: function () {} };
      }
      var source = new global.EventSource(url('/api/v1/events', { token: token }));
      source.addEventListener('change', function (event) {
        try {
          onChange(JSON.parse(event.data));
        } catch (error) { /* a malformed frame must not kill the stream */ }
      });
      return {
        watching: true,
        close: function () { source.close(); }
      };
    }

    function onStatus(fn) {
      listeners.push(fn);
      fn(state.mode, describe());
      return function () {
        var index = listeners.indexOf(fn);
        if (index >= 0) { listeners.splice(index, 1); }
      };
    }

    function describe() {
      if (state.mode === 'injected') {
        return 'IDE bridge: injected (v' + (state.bridgeVersion || (global.__jcbWebViewBridge || '?')) + ')';
      }
      if (state.mode === 'bridge') {
        var what = state.capabilities.length ? state.capabilities.join(', ') : 'no editor verbs';
        return 'Host ' + (state.plugin || '?') + ' on :' + port + ' — ' + what;
      }
      return 'No host: clipboard only';
    }

    return {
      state: state,
      ready: ready,
      discover: discover,
      can: can,
      describe: describe,
      absolute: absolute,
      open: open,
      read: read,
      proposeEdit: proposeEdit,
      applyEdit: applyEdit,
      undo: undo,
      redo: redo,
      watch: watch,
      copy: copy,
      onStatus: onStatus
    };
  }

  /**
   * The browser convenience: read the port and token off the script tag (or `<body>`) unless the caller passes
   * them, build a client, discover, wire the `data-open` click delegation, and publish the `jcbNav` probe that
   * `nav-client.js` publishes — so a page (or a test) written against the older client keeps working.
   *
   * @param overrides.port  explicit port, e.g. taken from the page's own URL when a host served it
   * @param overrides.token explicit token, for the same reason
   */
  async function auto(overrides) {
    var given = overrides || {};
    var script = global.document && global.document.currentScript;
    function config(name, fallback) {
      var fromScript = script && script.getAttribute('data-' + name);
      var fromBody = global.document && global.document.body && global.document.body.getAttribute('data-' + name);
      return fromScript || fromBody || fallback;
    }
    var linkBase = config('link-base', '');
    if (script && script.src && linkBase) {
      try {
        linkBase = new global.URL(linkBase.replace(/\/+$/, '') + '/', script.src).href;
      } catch (error) { /* keep the page-relative spelling */ }
    }
    var client = create({
      port: given.port !== undefined ? given.port : config('bridge-port', '0'),
      token: given.token !== undefined ? given.token : config('bridge-token', ''),
      opener: given.opener !== undefined ? given.opener
        : (typeof global.openFile === 'function' ? global.openFile : undefined)
    });
    await client.ready();
    if (global.document) {
      global.document.addEventListener('click', function (event) {
        var link = event.target && event.target.closest ? event.target.closest('[data-open]') : null;
        if (!link) { return; }
        event.preventDefault();
        client.open(client.absolute(link.getAttribute('data-open'), linkBase),
          parseInt(link.getAttribute('data-line') || '1', 10), 1);
      });
    }
    function links() {
      if (!global.document) { return []; }
      return Array.prototype.map.call(global.document.querySelectorAll('[data-open]'), function (element) {
        return {
          open: element.getAttribute('data-open'),
          line: parseInt(element.getAttribute('data-line') || '1', 10),
          member: element.getAttribute('data-member') || '',
          resolved: client.absolute(element.getAttribute('data-open'), linkBase)
        };
      });
    }
    // The compatibility surface: the same names nav-client.js exposes, so a page can switch clients without
    // rewriting anything that reads them, and the examples' smoke test keeps its probe.
    if (!global.jcbNav) {
      global.jcbNav = {
        open: function (path, line) { return client.open(client.absolute(path, linkBase), line, 1); },
        mode: function () { return client.state.mode; },
        describe: function () { return client.describe(); },
        absoluteTarget: function (path) { return client.absolute(path, linkBase); },
        projectRoot: function () { return linkBase; },
        linkBase: linkBase,
        links: links,
        onStatus: client.onStatus
      };
      global.__pageDebug = {
        linkBase: linkBase,
        bridgePort: client.state.reachable ? Number(client.state.port || 0) : 0,
        absoluteTarget: global.jcbNav.absoluteTarget,
        links: links
      };
    }
    return client;
  }

  global.jcbClient = { create: create, auto: auto };
})(typeof window !== 'undefined' ? window : globalThis);
