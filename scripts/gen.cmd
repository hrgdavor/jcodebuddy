@echo off
rem ---------------------------------------------------------------------------
rem gen.cmd - regenerate this repository's JCodeBuddy output with one command,
rem and print what the pass actually did.
rem
rem The converted module is hipster-entity-example; there is exactly one today
rem (DEC-026), so this script scopes itself to the six-module entity set.
rem
rem Why this exists, and why it is NOT `mvn ... generate-sources`:
rem   The example's generator is bound to generate-sources, a phase that runs
rem   BEFORE compile. A reactor invocation that stops at that phase never builds
rem   hipster-entity-tooling, so Maven resolves the provided tooling dependency
rem   from the local repository -- and an outdated jar there ignores the java-out
rem   flag, treats it as a positional argument, writes generated .java into
rem   .jcodebuddy/metadata/entity and reports BUILD SUCCESS. The binding now
rem   fails loudly in that case (the hipster-entity-preflight execution), and
rem   this script runs a phase that compiles the tooling in the same reactor,
rem   which is the workflow that always works. The full account is in
rem   hipster-entity-example/codebuddy.md section 6.
rem
rem Usage:
rem   scripts\gen.cmd                   regenerate; tests skipped
rem   scripts\gen.cmd with-tests        regenerate and run the entity test set
rem   scripts\gen.cmd <maven args...>   forwarded to the entity module set
rem
rem Environment: the same three overrides mvn-jdk25.cmd accepts
rem   JCODEBUDDY_JDK25, JCODEBUDDY_MVN, JCODEBUDDY_HE_MODULES.
rem
rem This file is pure ASCII with CRLF endings, for the same reason
rem scripts/mvn-jdk25.cmd is: cmd.exe mis-parses a batch file with LF-only
rem endings (notes D-11, D-20).
rem ---------------------------------------------------------------------------
setlocal
set "REPO=%~dp0.."
set "AGENTSTATE=%REPO%\hipster-entity-example\.jcodebuddy\agent-state"
set "LOG=%AGENTSTATE%\gen.log"
if not exist "%AGENTSTATE%" mkdir "%AGENTSTATE%"

if /I "%~1"=="with-tests" goto :tests
if "%~1"=="" goto :default
call "%~dp0mvn-jdk25.cmd" hipster-entity %* > "%LOG%" 2>&1
goto :report

:tests
call "%~dp0mvn-jdk25.cmd" hipster-entity test > "%LOG%" 2>&1
goto :report

:default
rem `package` rather than `generate-sources`: it reaches compile, so the reactor
rem builds the tooling before the example's generate-sources runs. `-DskipTests`
rem because regeneration is the point here; use `with-tests` to verify as well.
call "%~dp0mvn-jdk25.cmd" hipster-entity package "-DskipTests=true" > "%LOG%" 2>&1

:report
set "RC=%ERRORLEVEL%"
echo [gen] what the pass did:
findstr /C:"preflight ok" /C:"source root" /C:"Writing generated java" /C:"Validation:" /C:"divergences" /C:"BUILD" "%LOG%"
echo [gen] full log: %LOG%
if not "%RC%"=="0" (
    echo [gen] FAILED with exit code %RC%. 1>&2
    echo [gen] A failure naming GeneratorPreflight means the tooling on the classpath is older than the build binding: refresh it with `scripts\mvn-jdk25.cmd hipster-entity install` and run this again. 1>&2
)
exit /b %RC%
