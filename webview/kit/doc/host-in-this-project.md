# Making webview available in your project

Every other document in this kit assumes the answer to one question is already **yes**: *is there a webview
host attached to this project?* This file is the answer to the question itself, written for a developer
setting the environment up, not for somebody building a host.

Read it when:

* clicks on a page you wrote do nothing, or fall through to the clipboard rung;
* `GET /open` answers `403` or `404`;
* a new machine, a new clone or a second IDE needs the environment again.

Read [`contract.md`](contract.md) instead when you need the *behaviour* of a call, and
[`page-authoring.md`](page-authoring.md) when you are building the page.

---

## 1. What "available" means, and the one step that creates it

A **host** is a process that (a) knows your project directory and (b) can move the editor's caret in it.
The page never starts one, and must not assume one is running: the environment has to exist before a
page's links mean anything. Three ways a host comes to exist:

| Host | How it starts | When it is the right answer |
| --- | --- | --- |
| **The IDE plugin**, `WebView Explorer` | installed in the IDE; its tool window serves pages itself (`Ctrl+Alt+Shift+W` in JetBrains). Its HTTP transport is **off until configured** | you read pages inside your IDE — the common case, and the one the injection path exists for |
| **A standalone host** | started by hand or by a script: it serves pages itself and drives an editor through an adapter | you want a page in an ordinary browser, or in a host that owns no webview of its own |
| **Another editor's extension** | same contract, different port | the editor is not JetBrains and not VS Code |

**The minimum a person must do** — this is the whole prerequisite list:

1. **Have the host installed** for the editor you use. For the JetBrains plugin that is one install from
   the plugins pane; nothing in a repository configures it.
2. **Point it at your project.** An IDE host takes the project you have open. A standalone host takes
   `--project <dir>`; if you leave it out it cannot resolve a single relative link.
3. **Open pages through the host, not from the filesystem**, when you want the *injected* function: the
   host injects `window.openFile` into the pages *it* loads. A page double-clicked from your file manager
   is an ordinary browser page and can only use the HTTP transport (§ 4).
4. **Configure the HTTP transport if a page has to work outside the IDE webview** — a port, and an
   authorization rule. It is off by default on purpose: a plugin must not open a listening socket nobody
   asked for.

Nothing in steps 1–4 belongs in a page, a generator or a committed config file that another contributor
would inherit by accident. The next two sections say where each fact goes.

---

## 2. Where the live facts are published

A page author's most common mistake is to write down a port. Do not: the port is a **deployment detail**,
and it is written down for you, per checkout, by the host that bound it.

```
<project>/.jcodebuddy/webview/host.json     the port this checkout is on, and the pin
<project>/.jcodebuddy/webview/token         the shared secret, when a token is configured
```

```json
{ "plugin": "hr.hrg.jetbrains.webview", "ide": "IntelliJ IDEA", "pid": 41232, "port": 18881,
  "sticky": false, "project": "D:/wrk/myproject", "tokenPath": "D:/wrk/myproject/.jcodebuddy/webview/token",
  "capabilities": ["open", "select"], "startedAt": "2026-09-26T10:31:02Z" }
```

Read it as **state, not configuration**, and three consequences follow:

* **It is ignored by git and per checkout.** Two worktrees of one project have different ports, and the
  file for the one you are not in is none of your business. It is never committed, and it never belongs in
  a `conf/` directory or a repository-wide config file.
* **It outlives the host that wrote it.** A stopped host leaves the record so the next start asks for the
  same port (and so a `sticky` pin survives), which means **a record is not proof that anything is
  listening**. "Is a host there?" is always answered by probing the port — `GET /health` — never by the
  file.
* **One host per project.** When the port a host asked for is held by a host already serving **this**
  project, the second host opens no endpoint at all rather than starting a second bridge for one project:
  two IDEs on one project is normal, and a page that finds two bridges has no rule for choosing. A host for
  *another* project, or anything else on that port, just means "take the next free port".

The port's **default** — where a fresh clone should start looking — is a choice, and it is the one thing
that *is* configuration: `.jcodebuddy/conf/webview.json` (`{ "port": 18882 }`), optional, committed. When a
checkout has had to move to the next free port it stays there, because the current port beats the default.

---

## 3. Asking a host what it can do

```console
# does anything answer, and what can it do? no credentials needed
curl http://127.0.0.1:18881/health
```

```json
{ "plugin": "hr.hrg.jetbrains.webview", "port": 18881, "allowedOrigins": 1, "tokenRequired": false,
  "bridgeVersion": 1, "capabilities": ["open", "select"], "ide": "IntelliJ IDEA",
  "project": "D:/wrk/myproject" }
```

Three things to check, in this order:

1. **`project`** must be **your** project directory. A host serving somewhere else is the classic cause of
   "the link opens the wrong file" and of `404`s on every relative path.
2. **`capabilities`** must contain `open` for navigation. **An empty array is a real answer**, not a
   failure: it is what a host with no editor attached says, and it is how a page knows to use its last
   rung instead of calling a verb that cannot work.
3. **`allowedOrigins`** and **`tokenRequired`** decide whether *your* page may call it at all — see § 4.

The same document is what the kit's client reads when a page asks it to discover the host, so a page that
reports "no host: clipboard only" is reporting this response, not guessing.

A standalone host additionally publishes a **manifest** at `/.well-known/webview.json`, which names the
editor adapter and how precisely it can place a caret:

| `host.lineNavigation` | Meaning |
| --- | --- |
| `exact` | the caret lands on the requested line and column |
| `file-only` | the file opens; the line and column are **not** applied |
| `none` | no editor adapter is attached; navigation is refused |

`file-only` is not hypothetical — one measured editor refuses the `path:line:column` command-line form, so
its CLI adapter can open a file and nothing more. A page cannot fix that; it can only be honest about it,
which is what the status line and the clipboard rung are for.

---

## 4. Authorising a page

The transport opens an arbitrary file in your editor and, when a page is allowed to edit, changes files in
your project. So it is **denied unless the caller proves it may**, and the two proofs are different in an
important way:

| Route | What it needs |
| --- | --- |
| `GET /open`, `GET /file/…`, `GET /page/…` | an allowed **`Origin`** **or** the token — navigation only moves a caret |
| every state-changing route (`/api/v1/…`) | the **token alone**, never merely an allowed origin |

The reason for the asymmetry is not caution, it is precision: the origin rule is shared by *every page the
reader has open in that browser*, while the token is held only by a page this host served. A host with no
token configured therefore refuses writes outright rather than falling back to the weaker rule.

**A `file://` page sends no usable `Origin`.** That is the single fact that decides most local setups: a
page opened from disk cannot be allow-listed by origin in any meaningful way, so either

* **give it the token** — the simplest local setup, and what the kit's client supports out of the box
  (`?token=…` on the URL, or `data-bridge-token` on the client script tag); or
* **serve the page through the host** — `GET /page/<path>` injects the bridge as well, so an
  ordinary browser gets the injected function and no token is needed for *navigation*; or
* **allow its origin**, when the page is served by a local HTTP server you control and its origin is stable.

Two more facts that save an afternoon:

* **A hidden `<iframe>` needs no CORS grant**, which is why the kit's ladder uses one rather than `fetch`.
  `fetch` from a `file://` page is blocked by CORS regardless of what the host is willing to answer.
* **Navigation is rate limited** (20 per 20 s in this implementation, shared by every transport). A
  `mouseover` handler wired to navigation will exhaust it; a click will not.

---

## 5. When it does not work

| Symptom | Most likely cause | What to check |
| --- | --- | --- |
| A click falls to the clipboard rung, always | the page is not being loaded by the host, or no host is running | is `window.openFile` a function in the page's console? is `/health` answering? was the page opened *through* the host? |
| `403` from `/open` | the caller proved nothing: a `file://` page sends no usable origin | configure a token and pass `?token=…`, or serve the page through `/page/` |
| `403` naming a path as outside the project | the link escaped the project root, or the host is serving a different directory | compare `project` in `/health` with your page's link base |
| `404` from `/open` | the path did not resolve against the host's project | check the link's path and that the host's `project` is your project |
| `429` from `/open` | the rate limit | remove the handler that navigates without a click |
| Works in JetBrains, silently does nothing in VS Code | the page hard-coded a port | put the port in configuration; discover it from `/health` |
| The right file opens on line 1 | `data-line` is missing or not a number | 1-based `data-line`; the client falls back to `1` |
| The wrong file opens | the link base is wrong for the page's depth, or an absolute path was committed | re-check the link base and run the verifier |

The last two are page bugs rather than environment bugs, and they are the two this kit exists to prevent:
[`page-authoring.md`](page-authoring.md) § "Verifying before you ship" has the checks, and
`scripts/check-pages.mjs` implements them.
