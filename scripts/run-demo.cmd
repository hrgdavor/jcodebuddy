@echo off
rem ---------------------------------------------------------------------------
rem run-demo.cmd - run the hipster-entity end-to-end demo (plan.dsflash.md 9/4.2).
rem
rem It builds the modules the demo needs (offline, JDK 25) and then runs
rem hr.hrg.hipster.entityexample.person.PersonDemo, which walks:
rem
rem   row array -> read view -> JSON -> tracking builder -> printed diff
rem             -> change-set patch -> the parameterised UPDATE it would send
rem
rem The classpath is derived from the module output directories plus the two
rem Jackson artifacts the root POM pins. It is deliberately explicit rather
rem than a repository glob: the local repository holds many Jackson versions,
rem and mixing them produces a NoSuchFieldError at runtime.
rem ---------------------------------------------------------------------------
setlocal
set "JCODEBUDDY_JDK25=%JCODEBUDDY_JDK25%"
if "%JCODEBUDDY_JDK25%"=="" set "JCODEBUDDY_JDK25=C:\Program Files\Java\jdk-25"
if not exist "%JCODEBUDDY_JDK25%\bin\java.exe" (
    echo [run-demo] ERROR: no JDK 25 at "%JCODEBUDDY_JDK25%". Set JCODEBUDDY_JDK25. 1>&2
    exit /b 1
)

rem The property is quoted and carries an explicit `=true`: an unquoted `-DskipTests` is a single
rem token with no `=`, and mvn-jdk25.cmd now refuses those because that is also what a property split
rem by cmd.exe looks like (see its header, and plan.dsflash.followup.md 4.1).
call "%~dp0mvn-jdk25.cmd" hipster-entity "-DskipTests=true" package
if errorlevel 1 (
    echo [run-demo] ERROR: the build failed; the demo was not run. 1>&2
    exit /b 1
)

set "M2=%USERPROFILE%\.m2\repository"
set "CP=%~dp0..\hipster-entity-example\target\classes"
set "CP=%CP%;%~dp0..\hipster-entity-jackson\target\classes"
set "CP=%CP%;%~dp0..\hipster-entity-core\target\classes"
set "CP=%CP%;%~dp0..\hipster-entity-api\target\classes"
set "CP=%CP%;%M2%\tools\jackson\core\jackson-core\3.2.1\jackson-core-3.2.1.jar"
set "CP=%CP%;%M2%\tools\jackson\core\jackson-databind\3.2.1\jackson-databind-3.2.1.jar"
set "CP=%CP%;%M2%\com\fasterxml\jackson\core\jackson-annotations\2.22\jackson-annotations-2.22.jar"

"%JCODEBUDDY_JDK25%\bin\java.exe" -cp "%CP%" hr.hrg.hipster.entityexample.person.PersonDemo
exit /b %ERRORLEVEL%
