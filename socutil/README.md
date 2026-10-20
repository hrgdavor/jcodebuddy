# socutil

A tiny browser UI for **unix-domain sockets**: it lists the sockets in the directories you name, connects
to one, and relays it to the page like a terminal — output arrives over Server-Sent Events, input goes back
over `POST`. It is one binary with the page embedded (`@embedFile`), so there is nothing to install and no
build step for the UI.

It was written to answer the everyday question *"what is listening on a unix socket right now, and what
does it say?"* without `socat`, `nc`, and a second terminal. The socket side is byte-transparent; the page
shows the bytes as UTF-8 text.

The page is **deliberately vanilla** — one self-contained file, no framework, no bundler, no
`node_modules`, no CDN and no network at view time. That is the minimal-UI half of
[DEC-027](../doc-hipster-entity/architecture/decisions/DEC-027.md), and it is what lets the binary embed
the page and a fresh clone run the tool with no JavaScript build step. A page that grows navigation or
review workflows would be a different kind of page and would move to `jsx6`; this one is a list, a
terminal and a form.

## Build and run

The source is written against **Zig 0.17**, which moved the whole I/O surface (`std.Io`,
`std.process.Init`). Building with an older compiler produces a wall of mismatched symbols, so both the
build and a check script refuse it by name:

```sh
bun socutil/check-zig.mjs          # resolves --zig, $ZIG, the recorded checkout, then `zig` on PATH
cd socutil && zig build            # -> zig-out/bin/socutil[.exe]
zig build run -- --dir /run        # build and run, arguments after `--` passed through
```

`check-zig.mjs` looks for Zig 0.17 in this order: `--zig <path>`, `$ZIG`, the recorded checkout
`D:\wrk\zig\17\zig.exe`, then `zig` on `PATH`. A wrong version fails with the path to use.

```sh
socutil                        # scan the platform defaults, listen on 127.0.0.1:8765
socutil --port 0 /run /tmp     # any free port, two directories
socutil --listen 0.0.0.0 --port 9000 --dir /var/run
```

| Option            | Meaning                                                        |
| ----------------- | -------------------------------------------------------------- |
| `--listen <host>` | address to bind; also spelled `--host` (default `127.0.0.1`)   |
| `--port <port>`   | TCP port to bind; `0` picks a free one (default `8765`)        |
| `--dir <path>`    | a directory to scan; may be repeated, and bare arguments work  |
| `-h`, `--help`    | usage                                                          |

With no directory arguments the defaults are `$XDG_RUNTIME_DIR`, `/tmp`, `/var/run`, `/run` and `.` on
POSIX, and `%TEMP%`, `%TMP%` and `.` on Windows. The startup line prints the URL that was actually bound,
which is what the end-to-end test reads to find its port.

## How a socket is recognised

Discovery differs by platform, and the difference is not cosmetic:

- **POSIX** — the directory entry reports `File.Kind.unix_domain_socket`, or a stat of it does; that is
  authoritative.
- **Windows** — an `AF_UNIX` socket is a reparse point. A directory entry shows up as a symlink and
  `statFile` fails with `IO_REPARSE_TAG_NOT_HANDLED`, so there is no portable way to tell it from an
  ordinary symlink without connecting. Windows therefore uses the **name convention**: an entry ending in
  `.sock` or `.socket` (case-insensitive) is offered as a socket. Name your Windows sockets accordingly.

A directory that cannot be opened is logged and skipped — one bad default directory must not hide the
sockets in the rest.

## HTTP API

Every response uses `connection: close`; there is one request per connection.

| Request                          | Response                                                                                         |
| -------------------------------- | ------------------------------------------------------------------------------------------------ |
| `GET /`                          | the embedded page (`/index.html` is the same)                                                    |
| `GET /app.js`, `GET /style.css`  | the embedded assets                                                                              |
| `GET /api/sockets`               | `{"dirs":[…],"sockets":[{"name","dir","path"}]}` — a fresh scan on every request, sorted by path |
| `GET /api/events?id=&path=`      | an SSE stream for one scanned socket: `ready`, then `out` events, then `exit`                    |
| `POST /api/input?id=`            | writes the request body to the socket; `{"ok":true,"written":<bytes>}`                           |
| `POST /api/close?id=`            | shuts the socket down and ends the session                                                       |
| `GET /favicon.ico`               | `204`                                                                                            |

`path` must equal a socket found by the scan on that same request: the client cannot ask the server to
connect anywhere else. Session ids are 1–64 alphanumeric, `-` or `_` characters, and identify the live
connection on later `POST`s.

The SSE stream carries three event types:

```
event: ready
data: {"id":"…","path":"…"}

event: out
data: {"b":"<base64 of the socket bytes>"}

event: exit
data: {"reason":"eof"}
```

`out` data is base64 so arbitrary bytes survive the text protocol; the page decodes it with one streaming
`TextDecoder` per session, so a multi-byte character split across chunks is still decoded once. A
`: keepalive` comment is sent after about 15 seconds of silence. The reason on `exit` is one of `eof`,
`reset`, `closed`, `timeout`, `canceled`, `io error`, or `buffer overflow`. A failed keepalive write is how
a vanished browser is noticed, which then shuts the socket down.

Errors are JSON `{"error":"…"}` with these statuses: `400` missing/invalid id or path, `404` unknown
session or socket, `405` wrong method (with `Allow`), `409` session id already in use, `413` a body of
64 KiB or more, `502` the socket could not be connected to or written.

## What it is not

- **Not authenticated.** It binds `127.0.0.1` by default; anyone who can reach the port can read and write
  every socket under the scanned directories. Do not expose it to a network you do not trust.
- **Not binary-safe on input.** The page sends text lines; output is decoded as UTF-8. A protocol that
  needs raw bytes in both directions is a different tool.
- **Bounded.** Up to 1 MiB of unread socket output is buffered per session; beyond that the oldest half is
  dropped so a stalled browser cannot make the server grow without bound.

## Tests

```sh
cd socutil && zig build test   # unit tests: session-id validation, query decoding, JSON escaping, suffixes
bun socutil/e2e.mjs            # end-to-end: fixture socket, real server, raw HTTP + SSE
bun socutil/ui-smoke.mjs       # the page in a real browser: list, connect, send, receive, disconnect
```

The end-to-end test builds nothing: build first (it says so if the binary is missing). It starts the
server on port 0, reads the port from the server's own startup line, and drives a real unix socket with a
greeting, an echo round trip, a clean `EOF` exit, and the error statuses above. Its fixture lives under the
repository's `.tmp/socutil-e2e/`, and it always tears the process and socket down, including on failure.
Use `--exe <path>` to test another build.

The browser test is the half the end-to-end test cannot see — the page's own `EventSource`, base64
decoding, form and status line — driven over the Chrome DevTools Protocol. It looks for Chrome or a
Chromium on the usual paths (`CHROME`, or `--chrome <path>`, overrides that) and prints `SKIP` and exits 0
when none is installed; the server-side test is the mandatory one, and this one is an enhancement. Its
profile and fixture live under `.tmp/socutil-ui-smoke/`.

## Layout

| Path                    | What it is                                                        |
| ----------------------- | ----------------------------------------------------------------- |
| `src/main.zig`          | argument parsing, defaults, listener, one thread per connection   |
| `src/server.zig`        | HTTP routing, the SSE relay, session lifecycle and refcounting    |
| `src/sockets.zig`       | socket discovery and the platform rules above                     |
| `src/ui/`               | the page, script and stylesheet, embedded at build time           |
| `check-zig.mjs`         | toolchain check: says which Zig to use instead of failing vaguely |
| `e2e.mjs`               | the end-to-end test                                               |
| `ui-smoke.mjs`          | the page exercised in a real browser                              |
