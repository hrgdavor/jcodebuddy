import { insert } from '@jsx6/jsx6'
import { signal } from '@jsx6/signal'

/**
 * The compiler console: the log page of the incremental-compiler plugin, over HTTP.
 *
 * <p>It is the same page the plugin's tool window shows, reached the other way round: the plugin publishes its
 * state and its buttons on the Unix socket DEC-053 defines
 * (`<project>/.jcodebuddy/ipc/jcodebuddy-incremental-build.sock`), and the host this page is served from
 * proxies that socket at `/api/tools/<tool>/call`. Nothing here knows about IntelliJ; {@code build.status},
 * {@code build.log} and {@code build.rebuild} are the same names the tool window's buttons use.</p>
 *
 * <p>One rule shapes the page: <b>every call carries the host token</b>. The list of tools is a read, but a
 * call can rebuild, stop watching or refresh roots, so the host refuses it without the token and this page
 * refuses to pretend otherwise. A standalone first visit may carry the token in the URL
 * ({@code /app/incremental-build.html?token=...}); it is stored in {@code localStorage} and removed from the
 * address bar, so later links without it keep working. The IDE host injects the same key before this script
 * runs, so its pages need no token in the URL at all. A page opened with no token anywhere explains where to
 * get it instead of rendering an empty log.</p>
 *
 * <p>The log is pushed, not polled: the tool streams {@code tool.event} notifications on the socket and the
 * host forwards them as SSE, so a line appears the moment it is written. {@code build.log} is used once, for
 * the retained tail; after that the stream carries the new lines, and the SSE {@code id} lets a reconnect
 * resume where the page stopped (the host prefers EventSource's {@code Last-Event-ID} over the URL's
 * {@code since}). A lower sequence than the page has means the tool restarted, and the page reloads rather
 * than appending a log from another process.</p>
 */

const TOOL = 'jcodebuddy-incremental-build'
const MAX_LINES = 2_000
const LOG_LIMIT = 200
const TOKEN_KEY = 'jcodebuddy.token'

/**
 * The host token. A standalone first visit may carry {@code ?token=}; it is stored and removed from the
 * address bar, so later links (and a reload) keep working from localStorage. The IDE host injects the same
 * key before this script runs, which is why its pages need no token in the URL at all.
 */
function resolveToken() {
  const fromUrl = new URLSearchParams(location.search).get('token') || ''
  if (fromUrl) {
    try {
      localStorage.setItem(TOKEN_KEY, fromUrl)
      const clean = new URL(location.href)
      clean.searchParams.delete('token')
      history.replaceState(null, '', clean)
    } catch {
      // Storage or history may be unavailable; the in-memory token is still used.
    }
    return fromUrl
  }
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

const TOKEN = resolveToken()

const $chips = signal('connecting…')
const $headline = signal('')

insert(document.getElementById('page'), (
  <div class="wrap">
    <header class="head">
      <div>
        <h1>JCodeBuddy Incremental Build</h1>
        <p class="sub">live tool endpoint <code>{TOOL}</code></p>
      </div>
      <p class="chips">{$chips}</p>
    </header>
    <p class="status">{$headline}</p>
    <div class="bar">
      <button onclick={() => command('build.rebuild')}>Rebuild</button>
      <button onclick={() => command('build.watch.stop')}>Stop Watch</button>
      <button onclick={() => command('build.watch.start')}>Start Watch</button>
      <button onclick={() => command('build.refreshErrors')}>Refresh Errors</button>
      <button onclick={() => command('build.refreshRoots')}>Refresh Roots</button>
      <button onclick={() => command('build.listClasspath')}>Detailed Classpath</button>
    </div>
    <div class="bar">
      <button onclick={() => show('build.roots')}>List Roots</button>
      <button onclick={() => show('build.diagnostics')}>Diagnostics</button>
      <button onclick={clearView}>Clear View</button>
    </div>
    <pre class="info" id="info"></pre>
    <pre class="log" id="log"></pre>
    <footer class="foot" id="foot"></footer>
  </div>
))

const logElement = document.getElementById('log')
const infoElement = document.getElementById('info')
const footElement = document.getElementById('foot')

let cursor = 0
let polling = false
let statusTimer
let stream

if (!TOKEN) {
  $chips('token required')
  $headline(
    'Open this page once with ?token=<host token> (or open it from the IDE, which injects it). ' +
      'The token is remembered in localStorage, so later links without it keep working. ' +
      'Without a token the host refuses every tool call, because a call can change the build.',
  )
  for (const button of document.querySelectorAll('button')) {
    button.disabled = true
  }
} else {
  start()
}

function start() {
  void refreshStatus()
  void catchUp().then(openStream)
  statusTimer = setInterval(refreshStatus, 3_000)
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) {
      void refreshStatus()
    }
  })
  window.addEventListener('beforeunload', () => {
    clearInterval(statusTimer)
    if (stream) {
      stream.close()
    }
  })
}

async function call(method, params = {}) {
  const response = await fetch(`/api/tools/${TOOL}/call`, {
    method: 'POST',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json', 'X-WebView-Token': TOKEN },
    body: JSON.stringify({ method, params }),
  })
  let body = {}
  try {
    body = await response.json()
  } catch {
    // A host or proxy that answered with text: the status code is all that is left.
  }
  if (!response.ok) {
    throw new Error(body.message || body.error || `HTTP ${response.status}`)
  }
  return body.result
}

async function command(method) {
  try {
    await call(method, {})
    $headline(`${method}: accepted`)
    void refreshStatus()
  } catch (error) {
    noteFailure(error)
  }
}

async function show(method) {
  try {
    const result = await call(method, {})
    infoElement.textContent = format(method, result)
  } catch (error) {
    noteFailure(error)
  }
}

function format(method, value) {
  if (method === 'build.roots') {
    const roots = value ?? []
    if (roots.length === 0) {
      return 'no Java source roots'
    }
    return roots
      .map((root) => `${root.test ? 'TEST' : 'MAIN'} [${root.module}] ${root.root} -> ${root.output}`)
      .join('\n')
  }
  if (method === 'build.diagnostics') {
    const diagnostics = value ?? []
    if (diagnostics.length === 0) {
      return 'no diagnostics'
    }
    return diagnostics.map((entry) => `${entry.file}:${entry.line}:${entry.column}  ${entry.message}`).join('\n')
  }
  return JSON.stringify(value, null, 2)
}

async function refreshStatus() {
  if (polling || document.hidden) {
    return
  }
  polling = true
  try {
    const status = await call('build.status', {})
    $chips(
      `${status.enabled ? 'enabled' : 'disabled'} · ${status.running ? 'running' : 'stopped'}` +
        ` · watch: ${status.watch} · batch ${status.batchMarker}`,
    )
    const diagnostics = status.diagnostics ?? {}
    const daemon = status.daemon ?? {}
    $headline(
      `${status.failedFiles ?? 0} file(s) waiting for retry · ${diagnostics.files ?? 0} file(s) with` +
        ` diagnostics · daemon ${daemon.root || 'not attached'}`,
    )
    footElement.textContent =
      `build log: ${status.log?.file ?? ''} — ${status.log?.size ?? 0} line(s) retained,` +
      ` newest sequence ${status.log?.last ?? 0}`
  } catch (error) {
    noteFailure(error)
  } finally {
    polling = false
  }
}

/** Fills the view with the log's retained tail and sets the cursor the live stream resumes from. */
async function catchUp() {
  try {
    const page = await call('build.log', { limit: LOG_LIMIT })
    appendLines(page.lines ?? [])
    cursor = Number(page.next ?? cursor)
  } catch (error) {
    noteFailure(error)
  }
}

/**
 * The live half: an EventSource on the host's SSE route, which carries the tool's JSON-RPC
 * `tool.event` notifications. The browser reconnects by itself and sends `Last-Event-ID`, which the host
 * prefers over the URL's `since` — so a reconnect resumes at the last line this page rendered, and the tool's
 * retained backlog fills any gap.
 */
function openStream() {
  const url = `/api/tools/${TOOL}/events?topic=log&since=${cursor}` +
    `&token=${encodeURIComponent(TOKEN)}`
  stream = new EventSource(url)
  stream.addEventListener('tool-event', (message) => {
    let payload
    try {
      payload = JSON.parse(message.data)
    } catch {
      return
    }
    if (payload.topic !== 'log' || !payload.data) {
      return
    }
    const line = payload.data
    const sequence = Number(line.sequence)
    if (!Number.isFinite(sequence)) {
      return
    }
    if (sequence < cursor) {
      // A lower sequence can only be a restarted tool: its log starts over.
      logElement.textContent = ''
      cursor = 0
      $headline('the tool restarted; the log view was reloaded')
    }
    if (sequence <= cursor) {
      return
    }
    appendLines([line])
    cursor = sequence
  })
  stream.onopen = () => {
    void refreshStatus()
  }
  stream.onerror = () => {
    $chips('stream disconnected — reconnecting…')
  }
}

function appendLines(entries) {
  if (entries.length === 0) {
    return
  }
  const atBottom = logElement.scrollHeight - logElement.scrollTop - logElement.clientHeight < 16
  for (const entry of entries) {
    logElement.append(document.createTextNode(`${entry.text}\n`))
  }
  while (logElement.childNodes.length > MAX_LINES) {
    logElement.firstChild.remove()
  }
  if (atBottom) {
    logElement.scrollTop = logElement.scrollHeight
  }
}

function clearView() {
  logElement.textContent = ''
  infoElement.textContent = ''
  $headline('view cleared; the host keeps its log buffer')
}

function noteFailure(error) {
  const message = error instanceof Error ? error.message : String(error)
  $chips('not connected')
  $headline(
    `${message} — is the incremental build enabled in the IDE, and is this page served by the host that` +
      ' proxies .jcodebuddy/ipc?',
  )
}
