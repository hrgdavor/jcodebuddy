/**
 * Where the JWA sidecar's JAR and the Java that runs it come from.
 *
 * <p>Pure rules, no `vscode` import and no filesystem of its own: every candidate is offered to an injected
 * `exists` function, so `src/test/SidecarPaths.test.js` can assert each fallback chain on plain Node. That is
 * the same split `BridgePolicy`/`HostRegistration` already use in this extension — the rule in a module the
 * unit test can load, the socket or the editor in the layer above it.</p>
 *
 * <p><strong>This is the merged half of step 3.0p/3.0q.</strong> The earlier `webview/vscode-jwa`
 * attempt discovered the same two things in `extension.js:11-51` and had two defects worth naming, because
 * this module exists partly to not repeat them: it called `path.join` without ever requiring `path`, so any
 * run that entered the `jwa.java.home` or `JAVA_HOME` branch threw, and its development fallback
 * (`'..','webview','jwa-sidecar','target','jwa-sidecar.jar'`) was left a level too high when DEC-039 moved
 * the directories, resolving to `webview/webview/jwa-sidecar/…`. The `path` module is imported here, and the
 * development path is built from the workspace root the caller passes, not from a relative walk out of
 * `__dirname`.</p>
 */

import * as fs from 'fs';
import * as path from 'path';

/** Whether a candidate path exists. Injected so the rules can be tested without a filesystem. */
export interface Exists {
    (candidate: string): boolean;
}

/** The bundle name the sidecar's `pom.xml` sets with `finalName`, and the name both old clients looked for. */
export const SIDECAR_JAR_NAME = 'jwa-sidecar.jar';

/**
 * The Java executable to run the sidecar with, following the order a developer expects:
 * the extension setting, then `JAVA_HOME`, then `java` from `PATH`.
 *
 * <p>The third case returns the bare name rather than a resolved path, because "let the OS find it on
 * `PATH`" is the correct instruction and resolving it first would only re-implement `PATH` search.</p>
 */
export function discoverJavaExecutable(options: {
    configuredJavaHome?: string | undefined;
    envJavaHome?: string | undefined;
    platform?: string | undefined;
    exists: Exists;
}): string {
    const platform = options.platform ?? process.platform;
    const executable = platform === 'win32' ? 'java.exe' : 'java';

    for (const home of [options.configuredJavaHome, options.envJavaHome]) {
        if (home) {
            const candidate = path.join(home, 'bin', executable);
            if (options.exists(candidate)) {
                return candidate;
            }
        }
    }
    return executable;
}

/**
 * The sidecar JAR to launch, and the list of places that were looked in.
 *
 * <p>The candidates are returned whether or not one was found: when none exists the caller has to say what it
 * looked for, and a message that names three paths is the difference between a five-second fix and a bug
 * report. The development candidate is derived from {@code workspaceRoot} — the repository root — so the
 * `webview/webview/…` mistake of the earlier attempt cannot recur by construction.</p>
 */
export function resolveSidecarJar(options: {
    configuredJarPath?: string | undefined;
    extensionPath: string;
    workspaceRoot?: string | undefined;
    exists: Exists;
}): { jarPath: string; found: boolean; candidates: string[] } {
    const candidates: string[] = [];
    if (options.configuredJarPath) {
        candidates.push(options.configuredJarPath);
    }
    // The JAR bundled inside the extension, which is what a published build ships.
    candidates.push(path.join(options.extensionPath, 'sidecar', SIDECAR_JAR_NAME));
    // Development: the module's own build output, found from the workspace root the caller knows.
    if (options.workspaceRoot) {
        candidates.push(path.join(options.workspaceRoot, 'webview', 'jwa-sidecar', 'target', SIDECAR_JAR_NAME));
    }

    for (const candidate of candidates) {
        if (options.exists(candidate)) {
            return { jarPath: candidate, found: true, candidates };
        }
    }
    return { jarPath: candidates[candidates.length - 1], found: false, candidates };
}

/** `fs.existsSync`, so a caller does not have to spell it out. */
export const fileExists: Exists = (candidate: string): boolean => fs.existsSync(candidate);
