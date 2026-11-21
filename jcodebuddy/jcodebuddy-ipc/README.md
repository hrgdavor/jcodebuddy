# JCodeBuddy IPC — the live tool endpoint

A tool that runs inside an open project publishes **one Unix domain socket** under the project's derived
`.jcodebuddy/ipc/` subtree, a **descriptor** next to it, and answers **newline-delimited JSON-RPC 2.0** over
it. A host — the webview server, a standalone server, or any process allowed to read the project — discovers
the descriptor and proxies HTTP to it. Nothing binds a TCP port: there is no number to choose, claim or
collide with, and the operating system scopes the socket to its user.

The decision is [DEC-053](../../doc-hipster-entity/architecture/decisions/DEC-053.md). The compiler plugin is
the first endpoint; `project-automation/compiler-console/` is the first page.

## Files

```text
<project>/.jcodebuddy/ipc/
├── <tool>.sock    the socket          (or a shorter path in the temp dir, see below)
└── <tool>.json    the descriptor      (always here)
```

`<tool>` is a lowercase slug: `[a-z0-9][a-z0-9-]*`, e.g. `jcodebuddy-incremental-build`.

`.jcodebuddy/ipc/` is **derived** (DEC-026): machine-written, git-ignored, never configuration, and safe to
delete while nothing is running. A tool recreates it on its next start.

### The descriptor

```json
{
  "protocol": "jcodebuddy-tool/1",
  "tool": "jcodebuddy-incremental-build",
  "title": "JCodeBuddy Incremental Build",
  "ide": "IntelliJ IDEA 2026.2",
  "ideVersion": "262.10968.63",
  "pid": 12345,
  "socket": "D:/wrk/proj/.jcodebuddy/ipc/jcodebuddy-incremental-build.sock",
  "project": "D:/wrk/proj",
  "startedAt": "2026-11-19T18:40:00.123Z",
  "capabilities": ["tool.info", "tool.subscribe", "build.status", "build.log", "build.rebuild"],
  "topics": ["log"]
}
```

- Absolute paths, forward-slashed, so a Windows path is not rewritten by the JSON layer.
- **No secret.** The socket is a filesystem object; there is nothing to leak.
- **No liveness claim.** A descriptor outlives the process that wrote it, exactly like a host's `host.json`.
  "Is a tool there?" is answered by connecting, never by the file's existence.
- `socket` is the **authority**. The conventional path is used when it fits the platform's `sockaddr_un`
  limit (108 bytes on Linux and Windows, 104 on macOS); otherwise the socket moves to the system temp
  directory under a name derived from the project path, and this field is the only way to learn where it is.

## Protocol

JSON-RPC 2.0, one JSON object per line, UTF-8. A connection may carry several requests, one at a time.

```text
→ {"jsonrpc":"2.0","id":1,"method":"tool.info"}
← {"jsonrpc":"2.0","id":1,"result":{ ...the descriptor... }}
```

- **`tool.info` is answered by the transport**, not by the tool: every endpoint has it, it returns the
  descriptor, and it is how a consumer proves the socket is live before trusting it.
- Other methods are the tool's; `capabilities` lists them. A refusal carries a numeric code
  (`-32601` unknown method, `-32602` bad params, `-32603` internal error, `-32700` parse error,
  `-32600` invalid request) and never only a message: a consumer branches on the number.
- A **notification** (no `id`) is executed and never answered, per JSON-RPC.
- A method's `params` must be a JSON object when present; a result is any JSON value.
- `jcodebuddy-ipc` caps one request line at 1 MiB and one endpoint at 16 concurrent connections. A local
  client that exceeds either is refused, not buffered without bound.

### Push: `tool.subscribe` and `tool.event`

A tool that produces events declares **topics** in its descriptor and answers `tool.subscribe`:

```text
→ {"jsonrpc":"2.0","id":2,"method":"tool.subscribe","params":{"topic":"log","since":7}}
← {"jsonrpc":"2.0","id":2,"result":{"topic":"log","since":7}}
← {"jsonrpc":"2.0","method":"tool.event","params":{"topic":"log","data":{"sequence":8,"text":"..."}}}
← {"jsonrpc":"2.0","method":"tool.event","params":{"topic":"log","data":{"sequence":9,"text":"..."}}}
```

- The confirming response is the **last request/response message on that connection**: after it, the
  connection carries notifications only. A consumer that wants calls too opens a second connection — which is
  exactly what a host does per SSE client.
- `since` is the last sequence the consumer already has; `null`/absent means "from now". The tool replays its
  retained backlog after `since` and then pushes live events. The compiler's log replays under the same lock
  its writes take, so no line can fall between the backlog and the live stream.
- Events are capped per subscriber (256). A consumer that falls behind is **ended, not silently skipped**: the
  tool sends `tool.event-error` with `reason: overflow` and closes; the consumer re-reads the missing lines
  from the tool's own method by sequence.
- `topics` is in the descriptor, so a proxy can offer a stream only to a tool that has one.

## Pushing it to a browser: SSE

`webviewd` turns one subscription into one SSE response — `GET /api/tools/<tool>/events?topic=&since=&token=`:

- token-only, because EventSource cannot set a header, so the token rides in the query;
- `text/event-stream`, one `event: tool-event` frame per notification, `data: {"topic":…,"data":{…}}`, and the
  data's `sequence` as the SSE `id`;
- a `: keep-alive` comment every 15 s, which also notices a closed tab (the failed write closes the
  subscription);
- `Last-Event-ID` wins over the query's `since`, so EventSource's automatic reconnect resumes where the page
  stopped;
- a tool refusal is `400`, an unreachable tool `502`, an unknown tool `404`.

`project-automation/compiler-console` uses this: the retained tail once with `build.log`, then the stream.

## Lifecycle and ownership

- **One endpoint per tool per project.** A second start does not bind: `ToolEndpoint.start()` throws
  `BusyException` naming the live socket. Two editor windows on one project cannot give a consumer two
  answers. The test is a real `tool.info` call; a socket file left by a crashed process is removed and
  replaced.
- `stop()` closes the server, every connection and the worker pool, and deletes **the socket and descriptor
  it wrote**. It never deletes another process's endpoint.
- On POSIX the socket is created with `rw-------`; on Windows it is a file only that user can reach.
- No Windows named pipe fallback: the JDK's `AF_UNIX` works on Windows 10 1803+, and a second transport would
  be a second contract. A platform without Unix sockets exposes no tool endpoints.

## Using it from Java

Server:

```java
ToolEndpoint endpoint = new ToolEndpoint(projectRoot, "jcodebuddy-incremental-build",
        "JCodeBuddy Incremental Build", "IntelliJ IDEA", "262.1", methods);
ToolDescriptor descriptor = endpoint.start();      // BusyException when another instance owns it
...
endpoint.stop();
```

`ToolMethods` is one method: `Object call(String method, Map<String, Object> params) throws ToolException`,
plus `capabilities()`. A tool that streams also implements `topics()` and `subscribe(topic, since, sink)`,
where `sink` receives each event's data and the returned handle stops the stream. The result is any
JSON-serializable value, so a tool never depends on the library's JSON types.

Client (a host, a CLI, a test):

```java
for (ToolDescriptor descriptor : ToolDescriptor.list(projectRoot)) {
    try (ToolClient client = ToolClient.connect(descriptor)) {
        Object status = client.call("build.status", Map.of());
        Object log = client.call("build.log", Map.of("since", 0, "limit", 200));
    }
}
```

An event stream (the client is consumed by it; use another client for calls):

```java
try (ToolClient client = ToolClient.connect(descriptor);
     ToolSubscription stream = client.subscribe("log", 7L)) {
    Map<String, Object> event = stream.nextEvent();   // {topic: "log", data: {sequence, text}}
}
```

`ToolDescriptor.list(project)` skips a malformed descriptor instead of hiding the others; read one directly
to learn why it is unreadable.

## Running it by hand

Any JSON-lines client works. With `socat`:

```sh
echo '{"jsonrpc":"2.0","id":1,"method":"tool.info"}' \
  | socat - UNIX-CONNECT:.jcodebuddy/ipc/jcodebuddy-incremental-build.sock
```

With Java, the library's own client is the portable option (above). The compiler's methods are documented in
the plugin's README.

## What a host must do

The socket has no token, so the HTTP boundary is where authentication belongs — and it is the host's, not the
tool's:

- forward calls under the host's **own** token and allow-list (`webviewd`: `/api/tools` origin-or-token,
  `/api/tools/<tool>/call` token-only);
- map a tool refusal to `400` with the JSON-RPC code, an unknown tool to `404`, an unreachable socket to
  `502`, so a page can say "not running" instead of showing an empty log;
- never invent a second framing, a second descriptor or a second discovery rule.

`webviewd` implements exactly that (`WebviewServer.handleTools`), and the compiler page talks to it.

## Tests

`ToolEndpointTest` binds real sockets: descriptor publication, `tool.info`, method dispatch, refusal codes,
the second instance, a stale socket file, the temp fallback, `stop()` cleanup, descriptor listing and raw
JSON-RPC frames (notification, bad params, parse error). The module is in the recorded gate.
