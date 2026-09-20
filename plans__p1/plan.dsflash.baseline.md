# hipster-entity — Phase 0 baseline and build notes

Recorded by the executor of `plans__p1/plan.dsflash.md`, task **0.4** (regression baseline)
plus the environment facts tasks **0.1**, **0.1a** and **0.3** require.

## Recorded launcher and JDK (0.1 / 0.1a)

| Item | Value |
|---|---|
| JDK for compiler **and** test fork | `C:\Program Files\Java\jdk-25` |
| Maven launcher used for the recorded command | `D:\programs\mvn\bin\mvn.cmd` — **Apache Maven 3.9.0** |
| Wrapper on `PATH` | `D:\programs\cmd\mvn.bat` → `mvnd --raw-streams %*` (mvnd 1.0.0-m4 / Maven 4.0.0-alpha-4) |
| mvnd daemon JDK | pinned to jdk-25 by `~/.m2/mvnd.properties` (`java.home=C:/Program Files/Java/jdk-25`) |
| Machine default `JAVA_HOME` before Phase 0 | `C:\Program Files\Java\jdk-21` (the failure mode 0.1 describes) |

**Why the scripts do not use mvnd (documented deviation, see `plan.dsflash.notes.md` N-1).**
The mvnd client could not be exercised in this execution environment at all: it fails before
building with

```
org.mvndaemon.mvnd.common.DaemonException: java.nio.file.AccessDeniedException:
C:\Users\hrg\.m2\mvnd\registry\1.0.0-m4\registry.bin
```

because it writes its daemon registry outside the workspace. `scripts/mvn-jdk25.cmd` therefore
defaults to Apache Maven 3.9.0 and takes the launcher from `JCODEBUDDY_MVN`, so a developer whose
mvnd is not sandboxed can switch back with one environment variable.

## The recorded command (0.3 / 0.4)

A Maven profile **cannot** narrow a reactor (0.3), so the scoping mechanism is the explicit
`-pl` list, wrapped by `scripts/mvn-jdk25.cmd`:

```
scripts\mvn-jdk25.cmd
# expands to:
mvn -o -pl hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example -am test
```

`scripts\mvn-jdk25.cmd <goal>` runs the same module list with another goal, and any other
argument list is passed through to Maven unchanged.

## Baseline result — BUILD SUCCESS

```
Reactor Summary for JCodeBuddy Parent 1.0-SNAPSHOT:
  JCodeBuddy Parent .................................. SUCCESS [  0.000 s]
  hipster-entity-api ................................. SUCCESS [  0.380 s]   0 tests
  hipster-entity-core ................................ SUCCESS [  0.704 s]  42 tests
  hipster-entity-tooling ............................. SUCCESS [  2.488 s]  22 tests
  hipster-entity-example ............................. SUCCESS [  0.028 s]   0 tests
  hipster-entity-jackson ............................. SUCCESS [  0.025 s]   0 tests
  hipster-entity-test ................................ SUCCESS [  1.014 s]   4 tests
  BUILD SUCCESS
  Total time: 4.747 s
```

Per-module surefire counts: **api 0, core 42, tooling 22, jackson 0, test 4, example 0 — 68 total.**
This matches the plan's recorded baseline exactly. The `test` module count of 4 is the number
surefire *runs*; the fifth `@Test` in `PersonSummaryFileBenchmarkRunner` is not matched by
surefire's default includes, exactly as the plan warns.

Note on exit codes: `scripts/mvn-jdk25.cmd` invokes `mvn.cmd` directly, so `$LASTEXITCODE`
**is** meaningful under it (`1` here is the DSH harness reporting the piped `Select-String`
pipeline, not the build). The plan's "gate on the build outcome, not on the exit code" warning
applies to the mvnd wrapper, which is not used by these scripts.

## Phase 0.2 — reactor POM validation

Reproduced before the fix, on Apache Maven 3.9.0 + JDK 25:

```
[ERROR] 'dependencies.dependency.version' for hr.hrg.jcodebuddy:metadata-server:jar is missing. @ line 45, column 21
[ERROR] 'dependencies.dependency.version' for hr.hrg.jcodebuddy:metadata-mcp-server:jar is missing. @ line 49, column 21
[ERROR] 'dependencies.dependency.version' for hr.hrg.jcodebuddy:metadata-server:jar is missing. @ line 21, column 21
[ERROR] The build could not read 2 projects
```

Fixed by adding `<dependencyManagement>` entries for `metadata-server` and
`metadata-mcp-server` at `${project.version}` in the root `pom.xml`. Both were referenced
versionless by a sibling; the fix leaves `modules` and the plugin management untouched.
