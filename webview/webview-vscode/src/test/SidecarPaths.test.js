'use strict';
/**
 * The sidecar discovery rules, on plain Node with no editor and no filesystem.
 *
 * `SidecarPaths.ts` takes an injected `exists`, so every fallback chain can be walked exactly: which
 * candidate wins when several exist, what is returned when none does, and — the reason this file exists —
 * that the development path is derived from the workspace root rather than from a relative walk that the
 * DEC-039 move turned into `webview/webview/…`.
 *
 * Runs on plain Node with no dependencies and no VS Code download:
 *
 *     npm run test:unit
 *
 * `SidecarPaths.ts` is compiled by `npm run compile` first, so this file requires the built JavaScript.
 */

const assert = require('assert');
const path = require('path');

const packageRoot = path.join(__dirname, '..', '..');
const paths = require(path.join(packageRoot, 'out', 'SidecarPaths'));

let checks = 0;
let failures = 0;

function check(condition, message) {
    checks++;
    if (!condition) {
        failures++;
        console.log(`FAIL  ${message}`);
        return;
    }
    console.log(`ok    ${message}`);
}

/** An `exists` that answers true for exactly the given paths, so a chain can be walked candidate by candidate. */
function existing(...present) {
    return (candidate) => present.includes(candidate);
}

const never = () => false;

console.log('--- discoverJavaExecutable: the order is the setting, then JAVA_HOME, then PATH ---');

const configuredWin = 'C:\\jdk-configured';
const envWin = 'C:\\jdk-env';
check(paths.discoverJavaExecutable({
    configuredJavaHome: configuredWin,
    envJavaHome: envWin,
    platform: 'win32',
    exists: existing(path.join(configuredWin, 'bin', 'java.exe')),
}) === path.join(configuredWin, 'bin', 'java.exe'),
    'the extension setting wins when its java.exe exists');

check(paths.discoverJavaExecutable({
    configuredJavaHome: configuredWin,
    envJavaHome: envWin,
    platform: 'win32',
    exists: existing(path.join(envWin, 'bin', 'java.exe')),
}) === path.join(envWin, 'bin', 'java.exe'),
    'JAVA_HOME is used when the setting is set but has no java.exe');

check(paths.discoverJavaExecutable({
    configuredJavaHome: configuredWin,
    envJavaHome: envWin,
    platform: 'win32',
    exists: never,
}) === 'java.exe',
    'when neither resolves, the bare java.exe is returned so the OS searches PATH');

const linuxHome = '/opt/jdk';
check(paths.discoverJavaExecutable({
    configuredJavaHome: linuxHome,
    envJavaHome: '/other/jdk',
    platform: 'linux',
    exists: existing(path.join(linuxHome, 'bin', 'java')),
}) === path.join(linuxHome, 'bin', 'java'),
    'on a non-Windows platform the executable is named java, not java.exe '
    + '(both sides are joined by path, so this asserts the name and not the separator)');

check(paths.discoverJavaExecutable({ platform: 'linux', exists: never }) === 'java',
    'with no settings at all the answer is the bare java');

console.log('--- resolveSidecarJar: bundled, then development, and the candidates are always reported ---');

const extensionPath = path.join('C:', 'extensions', 'webview-explorer');
const workspaceRoot = path.join('C:', 'work', 'jcodebuddy');
const bundled = path.join(extensionPath, 'sidecar', 'jwa-sidecar.jar');
const development = path.join(workspaceRoot, 'webview', 'jwa-sidecar', 'target', 'jwa-sidecar.jar');

check((() => {
    const verdict = paths.resolveSidecarJar({ extensionPath, workspaceRoot, exists: existing(bundled) });
    return verdict.found && verdict.jarPath === bundled;
})(), 'the JAR bundled in the extension is used when it exists');

check((() => {
    const verdict = paths.resolveSidecarJar({ extensionPath, workspaceRoot, exists: existing(development) });
    return verdict.found && verdict.jarPath === development;
})(), 'a development build is used when the bundled JAR is absent');

check(development === path.join(workspaceRoot, 'webview', 'jwa-sidecar', 'target', 'jwa-sidecar.jar'),
    'the development candidate is the module target, under the workspace root — not webview/webview/... '
    + 'as the earlier attempt resolved after the directories moved');

check((() => {
    const verdict = paths.resolveSidecarJar({ extensionPath, workspaceRoot, exists: never });
    return !verdict.found && verdict.candidates.length === 2;
})(), 'when nothing exists the verdict says so and reports both candidates it looked in');

check((() => {
    const configured = path.join('D:', 'builds', 'jwa-sidecar.jar');
    const verdict = paths.resolveSidecarJar({
        configuredJarPath: configured, extensionPath, workspaceRoot, exists: existing(configured),
    });
    return verdict.found && verdict.jarPath === configured && verdict.candidates[0] === configured;
})(), 'an explicitly configured JAR wins and is reported first');

check(paths.SIDECAR_JAR_NAME === 'jwa-sidecar.jar',
    'the name matches the sidecar artifact\'s finalName, which is what the old clients looked for too');

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
