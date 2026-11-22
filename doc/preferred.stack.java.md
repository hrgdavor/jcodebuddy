# Preferred Java stack

This is the direct, third-party Java stack this checkout chooses and uses. It is a **direct-dependency**
list, not a dependency tree: a library that arrives only through another library is not listed here.
Plugins, Maven itself and internal `hr.hrg.jcodebuddy` modules are also not external libraries.

Versions are the ones declared or managed in the root [`pom.xml`](../pom.xml) and in the module POMs at
the time this page was written. A direct declaration often omits `<version>` because the root POM manages
it. Test- and provided-scope entries are marked because they are direct declarations but are not shipped
as runtime dependencies.

The list includes the frozen `watch/java-watch-*` modules for completeness; that group is frozen and is
not the place to add new work.

## Platform and source model

| Library                                                 | Version  | Scope / where                                          | Why it is here                                                                          |
| ------------------------------------------------------- | -------- | ------------------------------------------------------ | --------------------------------------------------------------------------------------- |
| **OpenRewrite `rewrite-core`** (`org.openrewrite`)      | 8.90.4   | compile; core, builder, entity/IoC tooling, merge-java | The Lossless Semantic Tree model: reads, queries and positions for Java source. It is the parser and model for every Java-producing tool. |
| **OpenRewrite `rewrite-java`**                          | 8.90.4   | compile; same modules                                  | Java-specific LST, visitors and type model used by the class/index and generator code.  |
| **OpenRewrite `rewrite-java-25`**                       | 8.90.4   | compile; same modules                                  | The Java 25 parser variant for the repository's release-25 sources.                     |
| **OpenRewrite `rewrite-maven`**                         | 8.90.4   | compile; merge-java                                    | Maven model support for the merge/review tooling.                                       |
| **Eclipse ECJ** (`org.eclipse.jdt:ecj`)                 | 3.46.100 | compile; jcodebuddy-compile, frozen java-watch-run     | Batch/incremental Java compiler used by the compile helper and the frozen watch runner. |
| **Apache Fory** (`org.apache.fory:fory-core`)           | 1.3.0    | compile; jcodebuddy-meta                               | Fast binary serialization for metadata/cache payloads.                                  |
| **Jackson 3 `jackson-databind`** (`tools.jackson.core`) | 3.2.1    | compile; most metadata, IPC, agent and tooling modules | The JSON/JSON5 data binding for metadata, IPC messages, tool descriptors, config and audit manifests. |
| **Jackson 3 `jackson-core`** (`tools.jackson.core`)     | 3.2.1    | managed/compile where used                             | Streaming JSON primitives behind databind.                                              |
| **Gson** (`com.google.code.gson`)                       | 2.10.1   | compile; webview-core and webview-eclipse              | Small JSON codec in the webview host/Eclipse integration, kept separate from the Jackson-based product paths. |

### Explicitly not JavaParser

There is no `com.github.javaparser` direct dependency. Java parsing goes through OpenRewrite; positions
come from javac; writes are text splices through the repository's `SourceSplicer` (DEC-030).
`bun scripts/check-no-javaparser.js` enforces the production boundary.

## Watching, paths and hashing

| Library                                                | Version                          | Scope / where                                                     | Why it is here |
| ------------------------------------------------------ | -------------------------------- | ----------------------------------------------------------------- | -------------- |
| **fs-meta** (`hr.hrg:fs-meta`)                         | 0.3.6 / 0.3.7 (module-dependent) | compile; project-automation, hipster-entity-tooling, watch client | The shared Zig daemon and its Java client: one watcher/stream for the project instead of a private `WatchService` per tool. |
| **gitignore** (`hr.hrg:gitignore`)                     | 0.3.3 / 0.3.5                    | compile; jcodebuddy-watch, project-automation                     | Git's wildmatch/`.gitignore` semantics, the only wildcard syntax accepted by this repository (DEC-051). No glob dialect is used. |
| **WyHash** (`hr.hrg:wyhash`)                           | 1.0.0                            | compile; agent and frozen watch core                              | Fast content hashing for change detection and cache identities; it is the hash used by the legacy metadata cache. |
| **Directory Watcher** (`io.methvin:directory-watcher`) | 0.18.0                           | compile; frozen `watch/java-watch-*`                              | The old watch group's Java watcher. It remains only because that group is frozen; new watching work uses fs-meta. |

## Tool surfaces and integration

| Library                                                        | Version               | Scope / where                  | Why it is here                                                                        |
| -------------------------------------------------------------- | --------------------- | ------------------------------ | ------------------------------------------------------------------------------------- |
| **MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp`)           | 2.0.0                 | compile; jcodebuddy-meta-mcp   | The Model Context Protocol transport/server used to expose metadata as MCP tools.     |
| **LSP4J** (`org.eclipse.lsp4j:org.eclipse.lsp4j`)              | 0.21.1                | compile; jwa-sidecar           | Language Server Protocol types and JSON-RPC plumbing for the webview sidecar.         |
| **JSch** (`com.jcraft:jsch`)                                   | 0.1.55                | compile; frozen java-watch-scp | SSH transport for the frozen SCP tool.                                                |
| **JSch agentproxy** (`com.jcraft:jsch.agentproxy.*`)           | 0.0.9                 | compile; frozen java-watch-scp | SSH agent integrations (connector factory and platform connectors) for the same tool. |
| **GraalVM Polyglot** (`org.graalvm.polyglot:polyglot`, `java`) | 25.0.2                | compile; frozen java-watch-run | Embedded guest-language runtime for the old watch runner's scripting support.         |
| **JGit** (`org.eclipse.jgit:org.eclipse.jgit`)                 | 6.10.0.202406032230-r | compile; merge-java            | Branch, commit and history access for the merge/review workflow.                      |
| **inject-examples** (`hr.hrg.inject:inject-examples`)          | 0.1.0                 | compile; webview-core          | The reference parser for section markers/references, so the Java and JS halves cannot spell a reference differently. |

## Webview host and Eclipse integration

These are direct dependencies of the webview/Eclipse modules. The Eclipse bundles are `provided` because
the Eclipse platform supplies them at runtime; they are not redistributed by a normal application build.

| Library                             | Version  | Scope             | Why it is here                                               |
| ----------------------------------- | -------- | ----------------- | ------------------------------------------------------------ |
| **Eclipse Core Commands**           | 3.13.0   | provided          | Eclipse command/handler integration.                         |
| **Eclipse Core Jobs**               | 3.15.900 | provided          | Eclipse job scheduling.                                      |
| **Eclipse Core Resources**          | 3.24.100 | provided          | Workspace resources and file access.                         |
| **Eclipse Core Runtime**            | 3.35.0   | provided          | Eclipse runtime/plugin lifecycle.                            |
| **Equinox Common**                  | 3.21.0   | provided          | Equinox platform utilities.                                  |
| **Eclipse JFace**                   | 3.40.0   | provided          | Eclipse UI dialogs/viewers.                                  |
| **Eclipse JFace Text**              | 3.31.100 | provided          | Text editor support.                                         |
| **Eclipse OSGi**                    | 3.24.300 | provided          | OSGi bundle runtime.                                         |
| **Eclipse SWT Win32**               | 3.135.0  | provided, Windows | SWT native widget binding used by the Windows Eclipse build. |
| **Eclipse Text**                    | 3.14.800 | provided          | Text/buffer utilities.                                       |
| **Eclipse UI IDE**                  | 3.23.200 | provided          | IDE-level UI contributions.                                  |
| **Eclipse UI Workbench**            | 3.140.0  | provided          | Workbench integration.                                       |
| **Eclipse UI Workbench TextEditor** | 3.20.200 | provided          | Editor/viewer integration.                                   |
| **Gson**                            | 2.10.1   | compile           | JSON bridge in the Eclipse/host integration.                 |
| **JUnit 4** (`junit:junit`)         | 4.13.2   | test              | Legacy tests in the webview host; new tests prefer JUnit 5.  |

## Entity, SQL and IoC-adjacent APIs

| Library                                                 | Version | Scope / where                     | Why it is here                                                                                     |
| ------------------------------------------------------- | ------- | --------------------------------- | -------------------------------------------------------------------------------------------------- |
| **Jakarta Validation API** (`jakarta.validation`)       | 3.0.2   | provided; entity tooling/examples | Validation annotations the entity metadata reads; the implementation is supplied by the consuming application. |
| **Jakarta Inject API** (`jakarta.inject`)               | 2.0.0   | test; hipster-ioc-test            | The standard injection annotation API exercised by generated IoC contexts.                         |
| **JPA API** (`javax.persistence:javax.persistence-api`) | 2.2     | test; hipster-sql-tooling         | JPA annotations used by SQL/entity test fixtures.                                                  |
| **H2** (`com.h2database:h2`)                            | 1.4.196 | test; hipster-sql-tooling         | In-memory SQL database for adapter/generator tests and demos.                                      |
| **MongoDB BSON** (`org.mongodb:bson`)                   | 4.11.1  | test; hipster-sql-tooling         | BSON types used by the MongoDB-facing SQL fixture.                                                 |
| **hipster-sql** (`hr.hrg:hipster-sql`)                  | 1.3.4   | provided; hipster-sql-tooling     | The SQL runtime API the SQL tooling compiles against; the application supplies its implementation. |

## Testing and benchmarks

| Library                                                                | Version | Scope                       | Why it is here                                                 |
| ---------------------------------------------------------------------- | ------- | --------------------------- | -------------------------------------------------------------- |
| **JUnit Jupiter** (`org.junit.jupiter:junit-jupiter`)                  | 5.10.2  | test                        | The default test framework.                                    |
| **JUnit Jupiter API** (`junit-jupiter-api`)                            | 5.10.2  | test                        | Tests and extensions that only need the API.                   |
| **JUnit Jupiter Engine** (`junit-jupiter-engine`)                      | 5.10.2  | test (runtime)              | Surefire's JUnit 5 engine.                                     |
| **JUnit Jupiter Params** (`junit-jupiter-params`)                      | 5.10.2  | test                        | Parameterized tests.                                           |
| **JUnit Platform Engine** (`org.junit.platform:junit-platform-engine`) | 1.10.2  | test/compile in examples    | Platform engine API for the custom test launchers.             |
| **JUnit Platform Launcher** (`junit-platform-launcher`)                | 1.10.2  | compile in examples         | Programmatic launcher for the fs-meta reload example.          |
| **JUnit Vintage Engine** (`org.junit.vintage:junit-vintage-engine`)    | 5.10.2  | test                        | Runs the remaining JUnit 4 tests through the JUnit 5 platform. |
| **Mockito Core** (`org.mockito:mockito-core`)                          | 5.13.0  | test; merge-java            | Mocking in merge/review tests.                                 |
| **Mockito JUnit Jupiter** (`mockito-junit-jupiter`)                    | 5.13.0  | test; merge-java            | Mockito extension for JUnit 5.                                 |
| **JMH Core** (`org.openjdk.jmh:jmh-core`)                              | 1.37    | test; metadata/entity/arena | Harness for the measured hot paths.                            |
| **JMH Annotation Processor** (`jmh-generator-annprocess`)              | 1.37    | test/annotation processor   | Generates the JMH benchmark classes.                           |

## Logging and diagnostics

| Library                                     | Version | Scope                                  | Why it is here                                                     |
| ------------------------------------------- | ------- | -------------------------------------- | ------------------------------------------------------------------ |
| **SLF4J API** (`org.slf4j:slf4j-api`)       | 2.0.9   | compile                                | Logging facade used by product and tooling modules.                |
| **SLF4J Simple** (`org.slf4j:slf4j-simple`) | 2.0.9   | runtime; agent, sidecar, watch modules | Minimal binding for standalone tools; applications may replace it. |

## Version ownership

The root POM's `<dependencyManagement>` is the single version source for most of the stack
(OpenRewrite, Jackson, JUnit, JMH, SLF4J, ECJ, Fory, MCP, LSP4J, JSch, Graal, Gson, directory-watcher,
gitignore, wyhash and JGit). `fs-meta` is intentionally not managed there because it has its own
coordinates and version. Eclipse platform bundles carry explicit provided versions in the Eclipse POM.

The transitive graph is deliberately not repeated here. If a library is not in this table, the code
should not be depending on it directly; introducing one means adding it here and to a POM.
