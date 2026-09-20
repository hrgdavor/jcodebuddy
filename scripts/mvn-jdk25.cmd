@echo off
rem ---------------------------------------------------------------------------
rem mvn-jdk25.cmd - run Maven with JDK 25 on both the Maven JVM and the surefire
rem test fork (plan.dsflash.md 0.1 / 0.3).
rem
rem The root POM requires maven.compiler.release=25, so the compiler and the
rem forked test JVM must both be JDK 25. `.mvn/jvm.config` cannot select a JDK
rem (it only passes JVM options to the Maven process), so the JDK is selected
rem here through JAVA_HOME.
rem
rem Usage:
rem   scripts\mvn-jdk25.cmd                       -> hipster-entity regen+test set
rem   scripts\mvn-jdk25.cmd hipster-entity test   -> explicit goal
rem   scripts\mvn-jdk25.cmd -o -pl <mods> -am test
rem   scripts\mvn-jdk25.cmd hipster-entity install
rem
rem A Maven property argument must be QUOTED when the shortcut is used, because
rem cmd.exe splits an unquoted batch argument at `=` and `.` before the script
rem ever sees it:
rem
rem   cmd /c "scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=SomeTest""
rem
rem An unquoted `-Dtest=SomeTest` arrives as two arguments (`-Dtest`, `SomeTest`)
rem and Maven reports `Unknown lifecycle phase "SomeTest"`. This file stays pure
rem ASCII and CRLF: cmd.exe mis-parses a batch file with LF-only line endings,
rem which shows up as a stray `'m' is not recognized` from inside a `goto` loop.
rem
rem Environment overrides:
rem   JCODEBUDDY_JDK25   - JDK 25 home (default: C:\Program Files\Java\jdk-25)
rem   JCODEBUDDY_MVN     - Maven launcher (default: D:\programs\mvn\bin\mvn.cmd,
rem                        i.e. Apache Maven 3.9 - mvnd is not usable under a
rem                        file sandbox because it writes its daemon registry
rem                        outside the workspace)
rem   JCODEBUDDY_HE_MODULES - comma separated module list for the
rem                        "hipster-entity" shortcut
rem
rem Both the free-form and the shortcut invocation pass
rem -Dmaven.compiler.useIncrementalCompilation=false, and the DEFAULT invocation adds `clean test`
rem (see :he_default). The POSIX sibling scripts/mvn-jdk25.sh is kept in step with this file by
rem GateParityTest, because D-21's `clean` half was applied to this file only and the .sh silently
rem kept running a bare `mvn`.
rem ---------------------------------------------------------------------------
setlocal

if "%JCODEBUDDY_JDK25%"=="" set "JCODEBUDDY_JDK25=C:\Program Files\Java\jdk-25"
if "%JCODEBUDDY_MVN%"=="" set "JCODEBUDDY_MVN=D:\programs\mvn\bin\mvn.cmd"
if "%JCODEBUDDY_HE_MODULES%"=="" set "JCODEBUDDY_HE_MODULES=hipster-entity-api,hipster-entity-core,hipster-entity-tooling,hipster-entity-jackson,hipster-entity-test,hipster-entity-example"

if not exist "%JCODEBUDDY_JDK25%\bin\java.exe" (
    echo [mvn-jdk25] ERROR: no JDK 25 at "%JCODEBUDDY_JDK25%". Set JCODEBUDDY_JDK25. 1>&2
    exit /b 1
)
if not exist "%JCODEBUDDY_MVN%" (
    echo [mvn-jdk25] ERROR: no Maven launcher at "%JCODEBUDDY_MVN%". Set JCODEBUDDY_MVN. 1>&2
    exit /b 1
)

set "JAVA_HOME=%JCODEBUDDY_JDK25%"

rem The `hipster-entity` shortcut expands to the recorded -pl module list
rem (0.3: a Maven profile cannot narrow a reactor, so -pl is the mechanism).
set "HE_MODULES=%JCODEBUDDY_HE_MODULES%"
rem No arguments at all -> the recorded hipster-entity test command (0.3/0.4).
if "%~1"=="" goto :he
if "%~1"=="hipster-entity" goto :he

call "%JCODEBUDDY_MVN%" -Dmaven.compiler.useIncrementalCompilation=false %*
exit /b %ERRORLEVEL%

:he
if "%~1"=="hipster-entity" shift
rem Collect the remaining arguments one at a time, each re-quoted, rather than
rem expanding `%1 %2 ... %9`: the positional form has a nine-argument ceiling and
rem drops anything beyond it.
set "HE_ARGS="
:he_collect
if "%~1"=="" goto :he_run
set "HE_ARGS=%HE_ARGS% "%~1""
shift
goto :he_collect

:he_run
rem `if not defined` rather than `if "%HE_ARGS%"==""`: the collected value contains
rem quotes, and cmd's `if` parser rejects an embedded quote pair.
if not defined HE_ARGS goto :he_default
rem Fail loudly rather than degrade the gate. cmd.exe splits an argument at `=` and `.`
rem BEFORE this script runs (see the header), so `-Dtest=SomeTest` arrives as `-Dtest` and
rem `SomeTest`, and `-Dsurefire.failIfNoSpecifiedTests=false` as three pieces. The comparison
rem operator swallows the second piece, the survivor (`-Dtest`) is a malformed assignment, and
rem Maven then drops the -pl list, so the "gate" silently becomes a build of the whole reactor,
rem where an unrelated module's failure looks like the recorded gate failing.
rem
rem The signature is exact and simple: a property argument that survived the split is a token
rem starting with `-D` that carries no `=`. Every faithfully forwarded property has one, because
rem cmd only splits at the `=` when the argument was NOT quoted at the call site.
rem
rem Consequence, stated because it is a real restriction: a boolean property must be written
rem `-DskipTests=true` (or passed as `-DskipTests` to Maven directly) when the shortcut is used.
rem That is a better failure than a silently wrong command line.
call :he_check %HE_ARGS%
if errorlevel 1 (
    echo [mvn-jdk25] ERROR: a property argument was split by cmd.exe before this script saw it. 1>&2
    echo [mvn-jdk25]   Quote it at the call site, and give boolean properties an explicit value: 1>&2
    echo [mvn-jdk25]     scripts\mvn-jdk25.cmd hipster-entity test "-Dtest=SomeTest" 1>&2
    echo [mvn-jdk25]     scripts\mvn-jdk25.cmd hipster-entity package "-DskipTests=true" 1>&2
    echo [mvn-jdk25]   Refusing to run rather than silently building the whole reactor. 1>&2
    exit /b 2
)
call "%JCODEBUDDY_MVN%" -o -pl %HE_MODULES% -am -Dmaven.compiler.useIncrementalCompilation=false %HE_ARGS%
exit /b %ERRORLEVEL%

rem Refuses (-D token without `=`) by returning 1; a faithfully forwarded argument list returns 0.
rem
rem Both invocations below also pass -Dmaven.compiler.useIncrementalCompilation=false. `clean` alone
rem removes the stale-class hazard at the start of a run, but the incremental check can still decide a
rem module's sources are up to date *within* one run and skip the compile; F-47's broken source must
rem fail this gate without any manual intervention, so the incremental path is disabled outright.
:he_check
if "%~1"=="" exit /b 0
set "HE_TOKEN=%~1"
if not "%HE_TOKEN:~0,2%"=="-D" goto :he_check_next
for /f "tokens=1,* delims==" %%a in ("%HE_TOKEN%") do if not "%%b"=="" exit /b 0
exit /b 1
:he_check_next
shift
goto :he_check

:he_default
rem The recorded gate is `clean test`, and `clean` is not optional here.
rem
rem Notes F-47 found that this gate can be satisfied by a PREVIOUS revision's class files:
rem maven-compiler-plugin's incremental check can decide a module's sources are up to date and
rem skip recompiling them, so a module whose tests were compiled from an earlier revision keeps
rem passing. F-6 and D-16 record the same hazard from the other side (ECJ leaving `Unresolved
rem compilation problems` markers in stale test classes), and a `NoClassDefFoundError` on one
rem of this round's own non-clean runs was the third instance. A gate that can lie is worth
rem less than the seconds `clean` costs.
rem
rem Only the DEFAULT invocation cleans: an explicit goal list is the caller's request
rem (`run-demo.cmd` passes `-DskipTests=true package` and reuses the built classes), so this
rem does not quietly turn every invocation into a full rebuild.
call "%JCODEBUDDY_MVN%" -o -pl %HE_MODULES% -am -Dmaven.compiler.useIncrementalCompilation=false clean test
exit /b %ERRORLEVEL%
