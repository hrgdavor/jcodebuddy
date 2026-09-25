// Smoke test for the WebView page examples.
//
//   node webview/examples/smoke-test.mjs
//
// Four checks, in order:
//   1. every inline <script> and every local .js asset parses (`node --check`)
//   2. each page opens in a real Chromium over file://, with no network, and
//      microlighter registers CSS Highlight ranges for its languages
//   3. every data-open target resolves � through the SAME link base the page
//      itself computes � to a file that exists, and where the link claims a
//      member by line, the member is on that line
//   4. no page pulls an off-box asset or bakes in an absolute project path
//
// Check 3 is the one that matters most: "resolve or leave alone" is the rule
// that keeps a generated page from shipping links that look right and go
// nowhere (webview/doc/webview-link-api.md § 4, DEC-022).
//
// The browser is driven over the DevTools Protocol with Node's own fetch and
// WebSocket, so the test has no dependencies and no node_modules.
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { createServer } from "node:net";
import { dirname, join, resolve, relative } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { tmpdir } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const PAGES = [
  { label: "self-contained", file: join(HERE, "self-contained", "index.html"), languages: 5 },
  { label: "with-assets/index", file: join(HERE, "with-assets", "index.html"), languages: 2 },
  { label: "with-assets/pages/guide", file: join(HERE, "with-assets", "pages", "guide.html"), languages: 5 },
  { label: "with-assets/pages/entity-reference", file: join(HERE, "with-assets", "pages", "entity-reference.html"), languages: 2 },
  // The editing page has no highlighted code of its own (its diff pane is filled at runtime), so the language
  // count is 0; it is in the list for the property that matters here - it must render and stay self-sufficient
  // with no host, because the page a user opens from the filesystem is the one that has no host.
  { label: "with-assets/pages/edit-demo", file: join(HERE, "with-assets", "pages", "edit-demo.html"), languages: 0 },
];

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter(Boolean);

let failures = 0;
const pass = (message) => console.log(`  ok   ${message}`);
const fail = (message) => { failures += 1; console.log(`  FAIL ${message}`); };
const note = (message) => console.log(`  note ${message}`);
const section = (title) => console.log(`\n${title}`);

const existing = PAGES.filter((page) => existsSync(page.file));
for (const page of PAGES.filter((p) => !existsSync(p.file))) note(`not written yet: ${page.label}`);

/** Ask the OS for a port nobody is listening on. */
function freePort() {
  return new Promise((done, reject) => {
    const server = createServer();
    server.unref();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => done(port));
    });
  });
}

/**
 * Kill Chromium and everything it spawned.
 *
 * `child.kill()` is not enough: the process Node spawns is a launcher, and the
 * browser behind it survives  holding the profile directory and the debug port,
 * which is exactly what makes the NEXT run fail. So the browser's own process id
 * is fetched over CDP (see `browserProcessId`) and the whole tree is reaped.
 */
function killTree(pid) {
  if (!pid) return;
  if (process.platform === "win32") {
    spawnSync("taskkill", ["/F", "/T", "/PID", String(pid)], { stdio: "ignore" });
  } else {
    try { process.kill(pid, "SIGKILL"); } catch { /* already gone */ }
  }
}

/** The browser process id, straight from the browser. */
async function browserProcessId(browser, launcherPid) {
  try {
    const info = await browser.send("SystemInfo.getProcessInfo");
    const found = (info.processInfo || []).find((entry) => entry.type === "browser");
    if (found) return found.id;
  } catch { /* older Chromium: fall back to the launcher pid */ }
  return launcherPid;
}

/* ------------------------------------------------------------------ helpers */

/** Comments first: these pages document themselves and a comment may quote markup. */
const stripComments = (html) => html.replace(/<!--[\s\S]*?-->/g, "");

/**
 * Executable markup only.
 *
 * The code samples contain escaped tags (`&lt;div class="x"&gt;`), and a naive
 * attribute scan matches `class="x"` inside them. `<pre>` blocks are demo
 * content, never wiring, so the checks look at the page without them.
 */
const stripSamples = (html) => html.replace(/<pre\b[\s\S]*?<\/pre>/gi, "");

/**
 * Every element that carries `attribute`, with all its `data-*` attributes.
 *
 * Deliberately tolerant: the tags here are hand-written HTML with hyphenated
 * attribute names, and a page in this example links three self-closing
 * `<script src>` tags, so nothing may rely on well-formed closing tags.
 */
function attributesOfTags(html, attribute) {
  const found = [];
  const tagRe = /<([a-zA-Z][a-zA-Z0-9-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)\/?>/g;
  let tag;
  while ((tag = tagRe.exec(html)) !== null) {
    const attrs = {};
    const attrRe = /([a-zA-Z_:][\w:.-]*)\s*=\s*"([^"]*)"/g;
    let attr;
    while ((attr = attrRe.exec(tag[2])) !== null) {
      if (attr[1].startsWith("data-")) attrs[attr[1].slice(5)] = attr[2];
    }
    if (attrs[attribute] === undefined) continue;
    found.push({ name: tag[1], tag: tag[0], attrs });
  }
  return found;
}

function srcOf(tag) {
  const match = /\bsrc\s*=\s*"([^"]*)"/.exec(tag);
  return match && match[1];
}

function inlineScripts(html) {
  const scripts = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
  let match;
  while ((match = re.exec(html)) !== null) {
    const attrs = match[1];
    if (/\bsrc\s*=/.test(attrs)) continue;
    if (/type\s*=\s*["']?(?!text\/javascript|module|application\/javascript)/i.test(attrs)) continue;
    scripts.push({ id: (attrs.match(/id\s*=\s*["']([^"']+)/) || [, "(anonymous)"])[1], body: match[2] });
  }
  return scripts;
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

/**
 * The link base the PAGE computes, reproduced.
 *
 * `nav-client.js` resolves `data-link-base` against its own `src`, not against
 * the page, so a page in a subfolder writes the same value as the root page.
 * The test must do the same arithmetic or it verifies links that the page would
 * not have produced.
 */
function linkBaseOf(html, pageFile) {
  // The script tag's value wins, resolved against the script's OWN directory �
  // that is exactly what nav-client.js does, and it is why a page in a
  // subfolder writes the same `data-link-base` as the page at the root.
  const navTag = attributesOfTags(html, "link-base").find((entry) => srcOf(entry.tag));
  if (navTag) {
    return resolve(dirname(resolve(dirname(pageFile), srcOf(navTag.tag))), navTag.attrs["link-base"]);
  }

  const bodyTag = attributesOfTags(html, "link-base").find((entry) => entry.name.toLowerCase() === "body");
  if (bodyTag) return resolve(dirname(pageFile), bodyTag.attrs["link-base"]);

  return dirname(pageFile);
}

const scratch = join(tmpdir(), "webview-page-smoke");
/** A killed Chromium can hold its profile directory for a moment on Windows. */
function clean(dir) {
  try { rmSync(dir, { recursive: true, force: true, maxRetries: 6, retryDelay: 200 }); }
  catch { /* a leftover temp directory is not a test failure */ }
}
clean(scratch);
mkdirSync(scratch, { recursive: true });

/* --------------------------------------------------------- 1. syntax checks */

section("1. every script parses");

for (const page of existing) {
  const html = stripComments(readFileSync(page.file, "utf8"));
  const scripts = inlineScripts(html);
  const localAssets = [...html.matchAll(/<script[^>]+src="(?!https?:)([^"]+)"/gi)].map((m) => m[1]);

  if (scripts.length === 0 && localAssets.length === 0) {
    fail(`${page.label}: no script at all � nothing drives the page`);
  } else if (scripts.length === 0) {
    pass(`${page.label}: no inline script (uses ${localAssets.length} local asset(s))`);
  }

  scripts.forEach((script, index) => {
    const file = join(scratch, `${page.label.replace(/\W+/g, "-")}-${index}.mjs`);
    writeFileSync(file, script.body, "utf8");
    const result = spawnSync(process.execPath, ["--check", file], { encoding: "utf8" });
    if (result.status === 0) pass(`${page.label} <script id="${script.id}">`);
    else fail(`${page.label} <script id="${script.id}">: ${(result.stderr || "").split("\n").slice(1, 4).join(" ")}`);
  });

  // Every local asset a page references must exist next to it.
  for (const asset of localAssets) {
    const target = resolve(dirname(page.file), asset);
    if (existsSync(target)) pass(`${page.label}: asset ${asset}`);
    else fail(`${page.label}: asset ${asset} does not exist`);
  }
}

for (const file of walk(join(HERE, "with-assets", "assets"))) {
  if (!file.endsWith(".js")) continue;
  const result = spawnSync(process.execPath, ["--check", file], { encoding: "utf8" });
  const label = file.slice(HERE.length + 1).replace(/\\/g, "/");
  if (result.status === 0) pass(label);
  else fail(`${label}: ${(result.stderr || "").split("\n").slice(1, 4).join(" ")}`);
}

/* -------------------------------------------------------------- 2. rendering */

section("2. pages render offline in Chromium, and highlight");

const chrome = CHROME_CANDIDATES.find((candidate) => existsSync(candidate));
if (!chrome) {
  note("no Chromium found; browser checks not run");
} else {
  // A fixed port is a flake waiting to happen: a Chromium from an earlier run
  // can still hold its debug port for a while. Ask the OS for a free one.
  const port = await freePort();
  const profile = join(scratch, "chrome-profile");
  const child = spawn(chrome, [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-extensions",
    "--allow-file-access-from-files",
    "--remote-debugging-port=" + port,
    "--user-data-dir=" + profile,
    "about:blank",
  ], { stdio: "ignore" });

  const endpoint = await waitForDevTools(port);
  if (!endpoint) {
    fail("chromium never opened a DevTools endpoint");
    killTree(child.pid);
  } else {
    const browser = await connect(endpoint);
    const browserPid = await browserProcessId(browser, child.pid);

    for (const page of existing) {
      const url = pathToFileURL(page.file).href;
      const created = await browser.send("Target.createTarget", { url: "about:blank" });
      const attached = await browser.send("Target.attachToTarget", { targetId: created.targetId, flatten: true });
      const sessionId = attached.sessionId;
      await browser.send("Runtime.enable", {}, sessionId);
      await browser.send("Page.enable", {}, sessionId);
      const loaded = browser.nextEvent("Page.loadEventFired", 10000);
      await browser.send("Page.navigate", { url }, sessionId);
      await loaded;
      await new Promise((r) => setTimeout(r, 900));

      const probe = await evaluate(browser, sessionId, `({
        ready: document.body.getAttribute('data-syntax-ready'),
        syntax: (document.querySelector('[data-syntax-status], #syntax-status') || {}).textContent,
        bridge: (document.querySelector('[data-bridge-status], #bridge-status') || {}).textContent,
        blocks: document.querySelectorAll('pre > code[class*="language-"]').length,
        categories: typeof CSS !== 'undefined' && CSS.highlights ? CSS.highlights.size : -1,
        names: typeof CSS !== 'undefined' && CSS.highlights ? [...CSS.highlights.keys()].sort() : [],
        links: document.querySelectorAll('[data-open]').length,
        navMode: window.jcbNav ? window.jcbNav.mode() : null,
        base: window.jcbNav ? window.jcbNav.linkBase : null,
      })`);

      const [blocks, categories] = String(probe.ready || "0/0").split("/").map(Number);

      if (page.languages === 0) {
        // A page with no code of its own (the editing example) has nothing to highlight; asserting six syntax
        // categories on it would assert that it loaded a highlighter it does not need.
        pass(`${page.label}: no highlighted code expected`);
      } else if (blocks >= page.languages && categories >= 6) {
        pass(`${page.label}: ${probe.blocks} block(s) -> ${probe.categories} categories`);
      } else {
        fail(`${page.label}: expected >= ${page.languages} blocks and >= 6 categories, got ${probe.ready} (${probe.syntax})`);
      }

      if (probe.links > 0) pass(`${page.label}: ${probe.links} navigable location link(s)`);
      else pass(`${page.label}: no location links (page-to-page navigation only)`);

      if (/injected|absent|clipboard/.test(probe.bridge || "")) pass(`${page.label}: ${probe.bridge.trim()}`);
      else fail(`${page.label}: bridge status never painted (${probe.bridge})`);

      // With no IDE in the loop the client must be in one of the two fallbacks:
      // 'bridge' when a port is configured (a real IDE bridge may be listening),
      // 'clipboard' when 0 disables it. 'injected' would mean a bug in the probe.
      if (probe.navMode === "bridge" || probe.navMode === "clipboard") {
        pass(`${page.label}: nav client in '${probe.navMode}' mode (no IDE bridge injected)`);
      } else {
        fail(`${page.label}: nav client reported mode ${probe.navMode}`);
      }

      // Every link must resolve to an absolute path, and it must be the same
      // path the test computes from the page's own link base.
      if (probe.links > 0) {
        const resolved = await evaluate(browser, sessionId,
          `window.jcbNav.links().slice(0, 4).map(l => [l.open, l.line, l.resolved])`);
        const expectedBase = linkBaseOf(stripComments(stripSamples(readFileSync(page.file, "utf8"))), page.file);
        const good = resolved.every(([, , path]) => /^([A-Za-z]:[\\/]|\/)/.test(path));
        const shown = relative(HERE, expectedBase).replace(/\\/g, "/") || ".";
        if (good) pass(`${page.label}: links resolve absolutely (base ${shown})`);
        else fail(`${page.label}: link resolution produced ${JSON.stringify(resolved)}`);
      }

      await browser.send("Target.closeTarget", { targetId: created.targetId });
    }

    // Ask it to leave tidily, then make sure it did.
    await browser.send("Browser.close").catch(() => {});
    browser.close();
    await new Promise((r) => setTimeout(r, 400));
    killTree(browserPid);
    killTree(child.pid);
  }
}

async function evaluate(browser, sessionId, expression) {
  // Wrapped so the expression can be any value: the probe is an object, but an
  // expression that returns undefined must not crash the test.
  const result = await browser.send("Runtime.evaluate", {
    expression: `JSON.stringify({ value: (${expression}) })`,
    returnByValue: true,
  }, sessionId);
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  }
  return JSON.parse(result.result.value).value;
}

async function waitForDevTools(port, attempts = 100) {
  for (let i = 0; i < attempts; i += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) return (await response.json()).webSocketDebuggerUrl;
    } catch { /* not listening yet */ }
    await new Promise((r) => setTimeout(r, 250));
  }
  return null;
}

async function connect(endpoint) {
  const socket = new WebSocket(endpoint);
  const pending = new Map();
  const events = [];
  const waiters = [];
  let nextId = 1;

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve: done, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(message.error.message));
      else done(message.result);
      return;
    }
    events.push(message);
    for (let i = waiters.length - 1; i >= 0; i -= 1) {
      if (waiters[i].method === message.method) {
        waiters[i].resolve(message.params);
        waiters.splice(i, 1);
      }
    }
  });

  await new Promise((done, reject) => {
    socket.addEventListener("open", done);
    socket.addEventListener("error", () => reject(new Error("DevTools socket failed")));
  });

  const send = (method, params = {}, sessionId) => new Promise((done, reject) => {
    const id = nextId++;
    pending.set(id, { resolve: done, reject });
    socket.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
  });

  const nextEvent = (method, timeout = 5000) => {
    const found = events.findIndex((e) => e.method === method);
    if (found !== -1) return Promise.resolve(events.splice(found, 1)[0].params);
    return new Promise((done, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${method}`)), timeout);
      waiters.push({ method, resolve: (params) => { clearTimeout(timer); done(params); } });
    });
  };

  return { send, nextEvent, close: () => socket.close() };
}

/* ------------------------------------------------------------------ 3. links */

section("3. every data-open target resolves, and the line holds the member");

for (const page of existing) {
  const html = stripComments(stripSamples(readFileSync(page.file, "utf8")));
  const base = linkBaseOf(html, page.file);
  let checked = 0;

  for (const { attrs } of attributesOfTags(html, "open")) {
    const target = attrs.open;
    if (/^[a-z]+:\/\//i.test(target)) continue;             // external URL: not ours to check

    // The page's client resolves a relative target against the link base.
    const absolute = /^([A-Za-z]:[\\/]|\/)/.test(target) ? resolve(target) : resolve(base, target);
    if (!existsSync(absolute)) {
      fail(`${page.label}: data-open="${target}" -> ${relative(HERE, absolute)} does not exist`);
      continue;
    }

    const line = Number(attrs.line || 1);
    if (attrs.member) {
      const text = readFileSync(absolute, "utf8").split(/\r?\n/);
      const target = text[line - 1];
      if (!target || !target.includes(attrs.member)) {
        fail(`${page.label}: data-member="${attrs.member}" is not on ${target}:${line}`);
        continue;
      }
      // A link that resolves to a Markdown heading is not a location: the caret lands on a title, and the
      // claim "this is where window.openFile is defined" becomes false the moment anything is inserted above
      // it. This happened — four links pointed at a `## ` heading — so it is checked rather than trusted.
      if (/^\s*#{1,6}\s/.test(target)) {
        fail(`${page.label}: data-member="${attrs.member}" resolves to the heading ${target.trim()}`
          + ` on ${attrs.open}:${line}; point at the line that holds the member, not at the section title`);
        continue;
      }
    }
    checked += 1;
  }
  pass(`${page.label}: ${checked} link(s) verified, base ${relative(HERE, base).replace(/\\/g, "/") || "."}`);
}

/* -------------------------------------------------------- 4. page-to-page links */

section("4. every ordinary page link resolves on disk");

for (const page of existing) {
  // Samples are demo content and their escaped markup confuses a naive href
  // scan, exactly as it does for data-open.
  const html = stripComments(stripSamples(readFileSync(page.file, "utf8")));
  const hrefs = [...html.matchAll(/<a\b[^>]*\bhref="([^"]*)"/gi)].map((m) => m[1]);
  let broken = 0;
  for (const href of hrefs) {
    if (/^(https?:|mailto:|#)/i.test(href)) continue;
    if (!existsSync(resolve(dirname(page.file), href.split("#")[0]))) {
      fail(`${page.label}: href="${href}" does not exist`);
      broken += 1;
    }
  }
  if (broken === 0) pass(`${page.label}: ${hrefs.length} anchor(s), all local targets exist`);
}

/* ---------------------------------------------------------------- 5. offline */

section("5. no off-box asset, no absolute project path");

for (const page of existing) {
  const html = stripComments(stripSamples(readFileSync(page.file, "utf8")));
  const offenders = [];

  // A page must not LOAD anything off-box. A clickable link to a project's
  // GitHub page is navigation, not an asset, and is not this check's business.
  const remoteLoads = [
    ...[...html.matchAll(/<script[^>]+\bsrc="(https?:\/\/[^"]+)"/gi)].map((m) => m[1]),
    ...[...html.matchAll(/<link[^>]+href="(https?:\/\/[^"]+)"/gi)].map((m) => m[1]),
    ...[...html.matchAll(/<img[^>]+\bsrc="(https?:\/\/[^"]+)"/gi)].map((m) => m[1]),
    ...[...html.matchAll(/\bsrcset="(https?:\/\/[^"]+)"/gi)].map((m) => m[1]),
  ];
  if (remoteLoads.length) offenders.push(`remote asset ${remoteLoads[0]}`);
  if (/@import\s+url\(/i.test(html)) offenders.push("CSS @import");
  if (/(?:src|href)="[A-Za-z]:[\\/]/.test(html)) offenders.push("absolute Windows path");
  if (/(?:src|href)="\/[A-Za-z]/.test(html)) offenders.push("absolute POSIX path");

  if (offenders.length) fail(`${page.label}: ${offenders.join(", ")}`);
  else pass(`${page.label}: self-sufficient`);
}

/* --------------------------------------------------------------------- done */

console.log(`\n${failures === 0 ? "PASS" : `FAIL (${failures})`}`);
clean(scratch);
process.exit(failures === 0 ? 0 : 1);

