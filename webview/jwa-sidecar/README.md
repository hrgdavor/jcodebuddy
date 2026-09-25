# JWA Sidecar: the LSP transport of the webview product

This guide explains how to create and configure addon modules (like `jwa-builder`) for the Java Watch Agent (JWA) Sidecar.

The sidecar lives under `webview/` because it is the same product as the two IDE plugins: it shows a page's
request to the editor, over LSP instead of over a webview. It is also the transport that reaches an editor with
no plugin at all — Zed implements `window/showDocument`, so a browser window beside Zed can move Zed's caret
without anything installed on Zed's side. See [`../PLAN-webview-suite.md`](../PLAN-webview-suite.md) § 5.

## Recommended Project Structure

To avoid "dependency leaks" and keep user projects lightweight, we strictly recommend a **Multi-Module Maven Project** structure for all addons.

### 1. The API Module (`-api`)
This module contains only the markers (annotations, interfaces) that the user needs to apply in their code.

- **Dependencies**: Zero or extremely minimal.
- **Target**: The user project's `compile` classpath.
- **Example**: `jwa-builder-api` contains the `@GenerateBuilder` annotation.

### 2. The Worker Module (Implementation)
This module contains the heavy logic, source parsing, and transformation engines.

- **Dependencies**: Heavy libraries (e.g., `rewrite-java` + its version-specific parser, `Jackson`).
- **Target**: Only the **Sidecar's runtime**.
- **Example**: `jwa-builder` contains the `BuilderTransformationEngine`.

---

## What the sidecar is today

The sidecar itself is **six classes** — it is an LSP shell, not a library — plus the one test class that guards
its HTTP surface:

| Class | Role |
|---|---|
| `SidecarApp` | the entry point (LSP on stdin/stdout, Jump HTTP on 127.0.0.1:7979) |
| `JwaLanguageServer` | LSP server wiring; delegates navigation to `webview-core`'s `Navigator` |
| `JwaLanguageClient` | client callbacks |
| `JwaTextDocumentService` | text-document lifecycle and the builder code actions |
| `JwaWorkspaceService` | workspace-level requests |
| `JumpParams` | the `mytool/jump` Remote Jump parameters |
| `SidecarAppJumpServiceTest` | the jump endpoint's authorization, over a real socket |

The behaviour it drives still lives in the worker modules (`jwa-builder`'s `RecordBuilderProcessorTest`,
`RecordBuilderFormattingTest` and `ClassMemberProcessorTest`); what the sidecar's own test covers is the part
that had no coverage and was wrong — that the HTTP surface refuses an uninvited caller.

## The jump endpoint

```text
GET http://127.0.0.1:7979/jump?uri=<file url>&line=<n>&column=<n>
GET http://127.0.0.1:7979/health
```

Configure with VM options:

```text
-Djwa.sidecar.jumpPort=7979
-Djwa.sidecar.allowedOrigins=http://localhost:3000
-Djwa.sidecar.token=<optional shared secret>
```

It binds the loopback address only, is rate limited to 20 jumps per 20 seconds, sends
`Access-Control-Allow-Origin` only to an origin the allow-list names, and **denies every caller until a token or
an allowed origin is configured**. Before 2026-09-25 it answered `Access-Control-Allow-Origin: *` with no
authentication and no address argument (binding every interface), so any page in the user's browser could move
their editor; the current behaviour is the shared rule from `webview-core`, the same one the JetBrains plugin's
HTTP bridge applies.

## Why Two Modules?

Maven's dependency management handles sub-modules as distinct units. By separating them:
1.  **Hygiene**: User projects depending on the `-api` module will **never** accidentally pull in heavy implementation dependencies.
2.  **No "Provided" Hacks**: You don't need to manually exclude dependencies or mark them as `optional`.
3.  **Encapsulation**: The user only sees the public API, while the implementation details are hidden within the sidecar's runtime.

---

## Configuration: `jwa-sidecar.txt`

The sidecar dynamically loads addons based on a project-level configuration file named `jwa-sidecar.txt`.

### Format
One artifact per line (standard Maven GAV format) or a local path.

```text
# Remote Maven artifacts (fetched from .m2)
hr.hrg.watch2:jwa-builder:1.0-SNAPSHOT

# Local development paths
./libs/my-custom-addon-impl.jar
./my-addon/target/classes
```

---

## Reference Implementation

The `jwa-builder` project in this repository serves as the reference implementation for this pattern.
Its modules stayed at the repository root when the sidecar moved under `webview/`, so these links go up
two levels:
- [jwa-builder-api](../../jwa-builder-api/pom.xml)
- [jwa-builder](../../jwa-builder/pom.xml)

## Development Workflow

1.  **Define Annotations**: Create your markers in the `-api` module.
2.  **Implement Logic**: Create your transformation logic in the worker module, depending on the `-api`.
3.  **Install**: Run `mvn install` to place the artifacts in your local `.m2`.
4.  **Register**: Add your worker's GAV to `jwa-sidecar.txt` in the target project.
5.  **Run**: Launch the sidecar. It will resolve the worker and its dependencies from `.m2`.
