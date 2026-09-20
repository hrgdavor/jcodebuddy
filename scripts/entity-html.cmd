@echo off
rem ---------------------------------------------------------------------------
rem entity-html.cmd - render the HTML entity index (DEC-027) with Bun.
rem
rem The renderer reads the JSON metadata a generation pass wrote
rem (hipster-entity-example\.jcodebuddy\metadata\entity\*.metadata.json) and writes one
rem self-contained page beside it, at .jcodebuddy\metadata\entity\index.html.
rem
rem   scripts\entity-html.cmd                       the example module, default paths
rem   scripts\entity-html.cmd --soft                do not fail on an unverified link
rem   scripts\entity-html.cmd --module <dir>        another converted module
rem   scripts\entity-html.cmd --help                the renderer's own flag surface
rem
rem Run a generation pass first (scripts\gen.cmd): the page is a view over that JSON,
rem and it reports a field the metadata claims but the field enum does not carry.
rem
rem Open the result in IntelliJ with the WebView Explorer plugin: right-click the file
rem in the Project view and choose "Open in WebView Explorer" (Ctrl+Alt+Shift+W).
rem
rem This file is pure ASCII with CRLF endings, for the same reason scripts\gen.cmd is:
rem cmd.exe mis-parses a batch file with LF-only endings.
rem ---------------------------------------------------------------------------
setlocal
pushd "%~dp0.."
where bun >nul 2>&1
if errorlevel 1 (
    echo [html] bun was not found on PATH. Install Bun ^(https://bun.sh^) and re-run. 1>&2
    popd
    exit /b 1
)
bun run "scripts\entity-html\index.js" %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
