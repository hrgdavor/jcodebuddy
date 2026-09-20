@echo off
rem ---------------------------------------------------------------------------
rem gen.cmd - run JCodeBuddy on the side: regenerate this repository's entity
rem output with one command, or watch the sources and regenerate after each save.
rem
rem The converted module is hipster-entity-example; there is exactly one today
rem (DEC-026), so this script scopes itself to the entity module set.
rem
rem WHY THIS IS NOT A MAVEN BUILD STEP
rem   JCodeBuddy in this project uses no annotation processing and no compile
rem   hooks. The generator is NOT bound to any lifecycle phase, so `mvn compile`,
rem   `mvn package` and `mvn test` only ever compile the committed generated
rem   source that is already in src/main/java. This script is how the pass is
rem   actually run: manually, or in the `watch` mode below.
rem
rem WHY IT DOES NOT USE `mvn exec:java`
rem   Two independent reasons, both measured:
rem     1. A direct goal invocation (`mvn exec:java@id`) runs on EVERY module in
rem        the reactor, and fails on the parent and on every module that has no
rem        such execution. Maven has no per-module selector for a direct goal.
rem     2. `exec:java` resolves the `provided` tooling dependency from the local
rem        repository instead of the reactor, so it silently runs whatever jar
rem        happens to be installed in ~/.m2.
rem   Maven's official answer for "use the reactor's classes without install" is
rem   dependency:build-classpath, which maps a reactor dependency to that
rem   module's target/classes directory. That is exactly what happens below.
rem
rem NO JARS, NO INSTALL, COMPILE AT MOST
rem   The pass runs `java -cp <exported classpath>` against target/classes. No
rem   jar is packaged and nothing is installed into ~/.m2, so a re-run after an
rem   edit is: compile (incremental) + one JVM start. If the tooling and the
rem   example are already compiled, `scripts\gen.cmd` costs one incremental
rem   compile and the pass itself.
rem
rem Usage:
rem   scripts\gen.cmd                   regenerate, then render the HTML entity index; tests skipped
rem   scripts\gen.cmd with-tests        regenerate and run the entity test set
rem   scripts\gen.cmd watch             regenerate now, then regenerate on save
rem   scripts\gen.cmd <maven args...>   forwarded to the entity module set
rem
rem Environment: the same three overrides mvn-jdk25.cmd accepts
rem   JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES.
rem
rem The pass itself runs a plain `java`, so this script must select a JDK 25+
rem explicitly. mvn-jdk25.cmd exports the JDK it selected as
rem JCODEBUDDY_RESOLVED_JDK, and that is preferred over this script's own copy of
rem the default. Falling through to whatever `java` is on PATH is refused rather
rem than attempted: the tooling classes are class file version 69, so an older
rem runtime fails with `UnsupportedClassVersionError`, which reads like a
rem classpath problem and sends the reader after the wrong thing.
rem
rem This file is pure ASCII with CRLF endings, for the same reason
rem scripts/mvn-jdk25.cmd is: cmd.exe mis-parses a batch file with LF-only
rem endings (notes D-11, D-20).
rem ---------------------------------------------------------------------------
setlocal

rem `pushd ..` then `%CD%` is the reliable way to get an ..-free absolute repo root:
rem sub-string surgery on "%~dp0.." is not dependable in every cmd build.
pushd "%~dp0.."
set "REPO=%CD%"
popd
set "EXAMPLE=%REPO%\hipster-entity-example"
set "AGENTSTATE=%EXAMPLE%\.jcodebuddy\agent-state"
if not exist "%AGENTSTATE%" mkdir "%AGENTSTATE%"
set "LOG=%AGENTSTATE%\gen.log"
set "CPDEPSFILE=%AGENTSTATE%\gen-classpath.txt"

if /I "%~1"=="watch" goto :watch
if /I "%~1"=="with-tests" goto :tests
if "%~1"=="" goto :sidecar
call "%~dp0mvn-jdk25.cmd" hipster-entity %* > "%LOG%" 2>&1
goto :report

:tests
call "%~dp0mvn-jdk25.cmd" hipster-entity test > "%LOG%" 2>&1
goto :report

rem ---------------------------------------------------------------------------
rem The side-car pass: compile the tooling in the reactor, export the classpath
rem the reactor itself resolved (reactor modules -> target/classes), then run the
rem generator with that classpath. The absolute output path matters: relative
rem paths are resolved per module, so an absolute one keeps the file in exactly
rem one place.
rem ---------------------------------------------------------------------------
:sidecar
if exist "%CPDEPSFILE%" del "%CPDEPSFILE%"
call "%~dp0mvn-jdk25.cmd" -o -pl hipster-entity-tooling,hipster-entity-example -am compile dependency:build-classpath "-Dmdep.outputFile=%CPDEPSFILE%" > "%LOG%" 2>&1
if errorlevel 1 goto :report
set "CPROOT=%REPO%\hipster-entity-tooling"
call :classpath
if errorlevel 1 goto :report
call :selectjdk
if errorlevel 1 goto :report
>> "%LOG%" echo [gen] running the generator from the exported classpath
"%JAVA%" -cp "%JCPATH%" hr.hrg.hipster.entity.tooling.GeneratorPreflight >> "%LOG%" 2>&1
if errorlevel 1 goto :report
"%JAVA%" -cp "%JCPATH%" hr.hrg.hipster.entity.tooling.EntityMetadataGenerator "%EXAMPLE%\src\main\java" "%EXAMPLE%\.jcodebuddy\metadata\entity" --java-out "%EXAMPLE%\src\main\java" --packages hr.hrg.hipster.entityexample.person.entity,hr.hrg.hipster.entityexample.paymentMethod.entity --validate --run-record "%EXAMPLE%\.jcodebuddy\metadata\entity\generation.json" >> "%LOG%" 2>&1
if errorlevel 1 goto :report
call :htmlindex
goto :report

rem ---------------------------------------------------------------------------
rem The HTML entity index (DEC-027): a Bun script that reads the JSON the pass
rem just wrote and renders one self-contained page whose cells link to the exact
rem line of every field in every generated artifact.
rem
rem A missing Bun is reported and then ignored, not fatal: the JSON is the
rem deliverable and the page is a view over it, so a machine without Bun still
rem gets a complete generation pass. A page that renders but cannot verify a link
rem IS fatal, because a link to the wrong line is worse than no link at all.
rem ---------------------------------------------------------------------------
:htmlindex
where bun >nul 2>&1
if errorlevel 1 (
    echo [gen] bun was not found on PATH: skipping the HTML entity index ^(DEC-027^). 1>&2
    exit /b 0
)
bun run "%REPO%\scripts\entity-html\index.js" --module hipster-entity-example >> "%LOG%" 2>&1
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------
rem Watch mode: the same classpath, but project-automation's watcher, which
rem regenerates when a watched source's CONTENT changes. It is a long-running
rem foreground process; Ctrl+C stops it.
rem ---------------------------------------------------------------------------
:watch
rem Its own log and classpath file, so a watch session and a one-shot pass can be
rem running at the same time without truncating each other's log or fighting over
rem one file. A stale classpath file is deleted first: it could otherwise be read
rem after a failed export, and the watcher would run against a previous classpath.
set "LOG=%AGENTSTATE%\watch.log"
set "WCPFILE=%AGENTSTATE%\watch-classpath.txt"
if exist "%WCPFILE%" del "%WCPFILE%"
call "%~dp0mvn-jdk25.cmd" -o -pl project-automation -am compile dependency:build-classpath "-Dmdep.outputFile=%WCPFILE%" > "%LOG%" 2>&1
if errorlevel 1 goto :watchfail
set "CPDEPSFILE=%WCPFILE%"
set "CPROOT=%REPO%\project-automation"
call :classpath
if errorlevel 1 goto :watchfail
call :selectjdk
if errorlevel 1 goto :watchfail
echo [gen] watching %EXAMPLE%\src\main\java - press Ctrl+C to stop
"%JAVA%" -cp "%JCPATH%" hr.hrg.jcodebuddy.automation.entity.EntityRegenerationWatcher --source "%EXAMPLE%\src\main\java" --packages hr.hrg.hipster.entityexample.person.entity,hr.hrg.hipster.entityexample.paymentMethod.entity
exit /b %ERRORLEVEL%

:watchfail
echo [gen] could not build the watch classpath; see %LOG% 1>&2
exit /b 1

rem Reads the exported classpath file (one line) into JCPATH, prepending the
rem module's own target/classes: dependency:build-classpath lists a module's
rem DEPENDENCIES, not its own output, and the entry point being run lives there.
rem `set /p` keeps the rest verbatim, including the semicolons, which `for /f`
rem would not.
:classpath
if not exist "%CPDEPSFILE%" (
    echo [gen] no classpath file at %CPDEPSFILE% - the export step failed. 1>&2
    exit /b 1
)
set "CPDEPS="
set /p CPDEPS=<"%CPDEPSFILE%"
if not defined CPDEPS (
    echo [gen] the exported classpath file is empty: %CPDEPSFILE% 1>&2
    exit /b 1
)
if not exist "%CPROOT%\target\classes" (
    echo [gen] no compiled classes at %CPROOT%\target\classes - the compile step failed. 1>&2
    exit /b 1
)
set "JCPATH=%CPROOT%\target\classes;%CPDEPS%"
exit /b 0

rem ---------------------------------------------------------------------------
rem Picks the JDK 25+ that runs the pass, in this order:
rem   1. JCODEBUDDY_JDK25
rem   2. JCODEBUDDY_RESOLVED_JDK, exported by the mvn-jdk25.cmd call above
rem   3. the default location mvn-jdk25.cmd also defaults to
rem The candidate is verified by running it, so a missing directory or an older
rem runtime fails here with a message about the JDK instead of failing inside the
rem generator with an `UnsupportedClassVersionError` that reads like a classpath
rem bug. The version is read through a temp file and `set /p` because `for /f`
rem cannot carry the quoted path of a JDK installed under "C:\Program Files\...".
rem ---------------------------------------------------------------------------
:selectjdk
if defined JCODEBUDDY_JDK25 set "JCAND=%JCODEBUDDY_JDK25%"
if not defined JCAND if defined JCODEBUDDY_RESOLVED_JDK set "JCAND=%JCODEBUDDY_RESOLVED_JDK%"
if not defined JCAND set "JCAND=C:\Program Files\Java\jdk-25"
if not exist "%JCAND%\bin\java.exe" (
    echo [gen] no JDK 25 at "%JCAND%". Set JCODEBUDDY_JDK25 to a JDK 25 home and re-run. 1>&2
    exit /b 1
)
set "JVERFILE=%TEMP%\jcodebuddy-jdk-version.txt"
if exist "%JVERFILE%" del "%JVERFILE%"
"%JCAND%\bin\java.exe" -version > "%JVERFILE%" 2>&1
set "JLINE="
set /p JLINE=<"%JVERFILE%"
if exist "%JVERFILE%" del "%JVERFILE%"
set "JMAJOR="
for /f "tokens=3 delims= " %%v in ("%JLINE%") do set "JMAJOR=%%v"
set "JMAJOR=%JMAJOR:"=%"
for /f "tokens=1 delims=." %%a in ("%JMAJOR%") do set "JMAJOR=%%a"
if not defined JMAJOR (
    echo [gen] cannot determine the version of "%JCAND%\bin\java.exe". 1>&2
    exit /b 1
)
if %JMAJOR% LSS 25 (
    echo [gen] "%JCAND%\bin\java.exe" is Java %JMAJOR%; the tooling needs 25 or newer. Set JCODEBUDDY_JDK25. 1>&2
    exit /b 1
)
set "JAVA=%JCAND%\bin\java.exe"
exit /b 0

:report
set "RC=%ERRORLEVEL%"
echo [gen] what the pass did:
findstr /C:"preflight ok" /C:"source root" /C:"Writing generated java" /C:"HTML entity index" /C:"Link check" /C:"Divergences:" /C:"Validation:" /C:"divergences" /C:"BUILD" "%LOG%"
echo [gen] full log: %LOG%
if not "%RC%"=="0" (
    echo [gen] FAILED with exit code %RC%. 1>&2
    echo [gen] A failure naming GeneratorPreflight means the tooling being run is older than this invocation, so it would ignore --java-out and write generated .java into the metadata directory. Re-run; if it persists, the local repository copy is being resolved instead of the reactor: compile with `scripts\mvn-jdk25.cmd -o -pl hipster-entity-tooling -am compile`. 1>&2
)
exit /b %RC%
