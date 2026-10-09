/**
 * How the page delivers its decisions: **POST when it is served, download when it is a file** (plan step
 * 4.13, deliverable 3).
 *
 * <p>The standalone document stays the primary artifact: opened from `file://` there is no server to talk to
 * and no way to write, so the page hands the payload to the browser as a download — the one write it can
 * perform. When the same page is served by `src_build/serve.js`, it POSTs the <em>identical</em> payload to
 * one endpoint instead, which is the enhancement and never the requirement (AGENTS.md § 2: a UI must be usable
 * standalone, and the webview — or here, the server — is never what makes the tool work).</p>
 *
 * <p>Kept out of the component because it is a rule about transport rather than about rendering, and a rule
 * about transport is testable without a DOM.</p>
 */

/** The one endpoint a served page posts to; `serve.js` owns the other side of it. */
export const DECISIONS_ENDPOINT = '/api/decisions'

/**
 * Whether this document is being served rather than opened from a file.
 *
 * <p>Asked of the protocol rather than of a flag the build would have to bake in: the same `review.js` is used
 * both ways, and a build-time constant would mean two builds and a way for them to differ.</p>
 */
export function isServed(location = globalThis.location) {
  return location?.protocol === 'http:' || location?.protocol === 'https:'
}

/** Hand the payload to the browser as a download. The one write a `file://` page can perform. */
export function downloadDecisions(payload, { document: doc, fileName, location } = {}) {
  const view = doc ?? globalThis.document
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' }))
  const link = view.createElement('a')
  link.href = url
  // The caller's name wins, and the payload's is the fallback: the page knows the branch it is reviewing while
  // the payload is just data, and both are offered because either can be the one that is set.
  link.download = fileName ?? payload?.fileName ?? 'decisions.json'
  link.click()
  URL.revokeObjectURL(url)
}

/**
 * Deliver the payload by whichever transport this document has.
 *
 * <p>Returns a result rather than throwing: a served page whose POST failed must tell the reviewer, and the
 * reviewer must still be able to fall back to the download. Swallowing the failure would leave somebody
 * believing their decisions were recorded when nothing received them.</p>
 *
 * @returns {Promise<{transport: 'post'|'download', ok: boolean, detail: string}>}
 */
export async function saveDecisions(payload, {
  location = globalThis.location,
  fetchImpl = globalThis.fetch,
  document: doc,
  fileName,
} = {}) {
  if (!isServed(location)) {
    downloadDecisions({ ...payload, fileName }, { location, document: doc })
    return { transport: 'download', ok: true, detail: 'downloaded: the page is a file, so nothing could receive it' }
  }
  try {
    const response = await fetchImpl(DECISIONS_ENDPOINT, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    })
    const text = await response.text()
    return {
      transport: 'post',
      ok: response.ok,
      detail: response.ok ? text.trim() : `the server refused it (HTTP ${response.status}): ${text.trim()}`,
    }
  } catch (failure) {
    return {
      transport: 'post',
      ok: false,
      detail: `could not reach the server (${failure.message}); the download still works`,
    }
  }
}
