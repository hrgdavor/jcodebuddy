import * as fs from 'fs';
import * as http from 'http';
import * as path from 'path';
import {
    DEFAULT_PORT_ATTEMPTS,
    IDE_UNKNOWN,
    Occupant,
    PortDecision,
    PortTry,
    decidePinnedPort,
    decidePort,
    normalizeProject,
    notAHost,
    parseHealthDocument,
    publishedElsewhere
} from './BridgePolicy';

/** The project's committed preferences: the same file, and the same job, as webview-core's `HostConfig`. */
export const CONFIG_DIR = '.jcodebuddy/conf';
/** This host's configuration file. */
export const CONFIG_FILE_NAME = 'webview.json';

/**
 * The project's own record of the port this host serves on: `.jcodebuddy/webview/host.json`.
 *
 * The same file, in the same place, with the same fields as the Java hosts write (webview-core's
 * `HostDescriptor`). That is the point of it being a file rather than a setting: a page, a script or a
 * second IDE finds the port without being told it, and a second host finds out that this project already
 * has one.
 *
 * This module is the Node half — reading and writing the file, probing a port, and binding a server. The
 * rules themselves (who may keep a port, when a host must not start at all) live in `BridgePolicy`, which
 * is tested without Node and against the same vectors as the Java hosts.
 */

/** The directory, relative to the project, that holds the descriptor and (for some hosts) the token. */
export const STATE_DIR = '.jcodebuddy/webview';
/** The file name under `STATE_DIR`. */
export const FILE_NAME = 'host.json';

/** What the descriptor holds, as webview-core's `HostDescriptor` writes it. */
export interface PublishedHost {
    plugin: string;
    ide: string;
    pid: number;
    port: number;
    /**
     * Whether this port is pinned for this project. A local property of the port — a fact about this checkout,
     * not a preference, which is why it lives here and not in `conf/`.
     */
    sticky: boolean;
    project: string;
    tokenPath: string;
    capabilities: string[];
    startedAt: string;
    host: { name: string; available: boolean; lineNavigation: string; note: string } | null;
}

/** The project's committed port preference, and any problem reading it. */
export interface PortPreference {
    port: number | null;
    problem: string;
}

/** The port a run should ask for, and where the answer came from: `flag`, `current`, `default` or `fallback`. */
export interface RequestedPort {
    port: number;
    source: 'flag' | 'current' | 'default' | 'fallback';
    problem: string;
}

/** An absolute path the descriptor can be written to and read from. */
export function directoryOf(project: string): string {
    return path.join(project, STATE_DIR);
}

/** The descriptor path for a project. */
export function fileOf(project: string): string {
    return path.join(directoryOf(project), FILE_NAME);
}

/** The committed preference path: tracked, reviewed, and shared by everyone who clones the project. */
export function configFileOf(project: string): string {
    return path.join(project, CONFIG_DIR, CONFIG_FILE_NAME);
}

/**
 * The port this project asks for, from `.jcodebuddy/conf/webview.json`.
 *
 * It is a **default for a new checkout**, not a pin: several git worktrees of one project share one committed
 * file, so a mandatory port would collide — the pin is per worktree and lives in that worktree's `host.json`.
 * A malformed file is reported and ignored rather than fatal: refusing to serve a project over a config typo
 * would be a worse failure than serving it on the conventional port.
 */
export function readPortPreference(project: string): PortPreference {
    const file = configFileOf(project);
    if (!fs.existsSync(file)) {
        return { port: null, problem: '' };
    }
    let parsed: any;
    try {
        parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
        return { port: null, problem: `${file} is not valid JSON; ignoring it` };
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { port: null, problem: `${file} is not a JSON object; ignoring it` };
    }
    if (parsed.port === undefined || parsed.port === null) {
        return { port: null, problem: '' };
    }
    if (typeof parsed.port !== 'number' || !Number.isInteger(parsed.port)) {
        return { port: null, problem: `${file}: 'port' must be a whole number; ignoring it` };
    }
    if (parsed.port < 1 || parsed.port > 65535) {
        return { port: null, problem: `${file}: 'port' must be between 1 and 65535, was ${parsed.port}; ignoring it` };
    }
    return { port: parsed.port, problem: '' };
}

/**
 * The port to ask for, in one place for this host — the same four sources, in the same order, as
 * webview-core's `HostConfig.requestedPort`:
 *
 * 1. what the user chose for this window (`flag`) — the only source that means "the user decided";
 * 2. the port this **checkout** is currently on, from `.jcodebuddy/webview/host.json` (`current`) — local,
 *    never in git, and more specific than the default, so a checkout that had to take the next free port
 *    keeps it instead of colliding again on every start. Delete that file to go back to the default;
 * 3. the project's committed **default**, from `.jcodebuddy/conf/webview.json` (`default`) — optional, and
 *    there so a fresh clone needs no editor configuration;
 * 4. this host's own fallback (`fallback`).
 *
 * A pin is a different question and is not resolved here: when `host.json` says `sticky`, `claimPort` takes
 * that port as the only acceptable one and this list does not apply.
 */
export function requestedPort(project: string, explicitPort: number | null, fallbackPort: number): RequestedPort {
    if (explicitPort !== null && explicitPort > 0) {
        return { port: explicitPort, source: 'flag', problem: '' };
    }
    const current = readPublished(project);
    const preference = readPortPreference(project);
    if (current && current.port > 0) {
        return { port: current.port, source: 'current', problem: preference.problem };
    }
    if (preference.port !== null) {
        return { port: preference.port, source: 'default', problem: '' };
    }
    return { port: fallbackPort, source: 'fallback', problem: preference.problem };
}

/** One line naming the decision, for the host's log. Empty for the fallback case. */
export function describeRequestedPort(project: string, requested: RequestedPort): string {
    switch (requested.source) {
        case 'flag':
            return `port ${requested.port} was chosen for this window`;
        case 'current':
            return `port ${requested.port} is the port this checkout is currently on (${fileOf(project)})`;
        case 'default':
            return `port ${requested.port} is this project's default (${configFileOf(project)})`;
        default:
            return '';
    }
}

/**
 * The descriptor a previous run left behind, or null when there is none or it cannot be read.
 *
 * Unreadable counts as absent on purpose: a truncated descriptor (the editor was killed mid-write) must not
 * make the next start impossible.
 */
export function readPublished(project: string): PublishedHost | null {
    try {
        const parsed = JSON.parse(fs.readFileSync(fileOf(project), 'utf8'));
        if (parsed === null || typeof parsed !== 'object' || typeof parsed.port !== 'number'
            || parsed.port <= 0) {
            return null;
        }
        return parsed as PublishedHost;
    } catch {
        return null;
    }
}

/** True when the process the descriptor names is still running. */
export function isLive(pid: number): boolean {
    if (!Number.isInteger(pid) || pid <= 0) {
        return false;
    }
    try {
        // Signal 0 asks the operating system whether the process exists without touching it.
        process.kill(pid, 0);
        return true;
    } catch (error) {
        // EPERM means it exists and belongs to somebody else, which still counts as "there".
        return (error as NodeJS.ErrnoException).code === 'EPERM';
    }
}

/** Writes the descriptor, creating `.jcodebuddy/webview/` when it is missing. */
export function writePublished(project: string, descriptor: PublishedHost): void {
    const directory = directoryOf(project);
    fs.mkdirSync(directory, { recursive: true });
    ensureIgnored(directory);
    fs.writeFileSync(path.join(directory, FILE_NAME), `${JSON.stringify(descriptor, null, 2)}\n`, 'utf8');
}

/**
 * Makes sure the published state cannot appear in `git status`.
 *
 * The state directory is derived and machine-local — a port, a token, a page's undo journal — so it must
 * never be committed, and it must not be left to each project to notice: a host is pointed at arbitrary
 * directories, most of which have no JCodeBuddy ignore rules at all. The mechanism is the ordinary one: a
 * `.gitignore` inside the state directory containing `*`, which ignores the directory's whole content
 * including itself. It is written only when absent, so a project that wants a narrower rule can write one.
 * The Java hosts use the same file, from `HostDescriptor.write`.
 */
function ensureIgnored(directory: string): void {
    const ignore = path.join(directory, '.gitignore');
    if (fs.existsSync(ignore)) {
        return;
    }
    try {
        fs.writeFileSync(ignore, [
            "# A running page host's state: the port it bound, the token a caller must present, and a page's",
            '# undo journal. Derived and machine-local, so nothing in this directory is ever committed',
            '# (DEC-032). Delete the directory, not a line here, if you want a clean start.',
            '*',
            ''
        ].join('\n'), 'utf8');
    } catch {
        // Not fatal: a read-only project still serves pages.
    }
}

/**
 * Deletes the descriptor.
 *
 * Nothing calls this automatically, and that is deliberate: the file is the workspace's record of the port
 * this checkout is on, and a bridge that stopped cleanly must not erase it — that record is what the next
 * start asks for, and where a `sticky` pin lives. Delete the file (or clear the pin) to reset.
 */
export function deletePublished(project: string): boolean {
    try {
        fs.unlinkSync(fileOf(project));
        return true;
    } catch {
        return false;
    }
}

/**
 * Asks `127.0.0.1:<port>` for its `/health`, and reads the identity out of it.
 *
 * Anything that is not a health document — no listener, a refused connection, a timeout, a non-200, a body
 * that is not JSON — is "nobody we know is there". Only a recognised occupant can make a host skip, so an
 * unrecognised one must always mean "take the next port".
 */
export function probe(port: number, timeoutMillis = 800): Promise<Occupant> {
    return new Promise((resolve) => {
        let settled = false;
        const finish = (occupant: Occupant) => {
            if (!settled) {
                settled = true;
                resolve(occupant);
            }
        };
        const request = http.get(
            { host: '127.0.0.1', port, path: '/health', timeout: timeoutMillis },
            (response) => {
                let body = '';
                response.setEncoding('utf8');
                response.on('data', (chunk) => {
                    body += chunk;
                });
                response.on('end', () => {
                    finish(response.statusCode === 200
                        ? parseHealthDocument(port, body)
                        : notAHost(`the port answered HTTP ${response.statusCode}, not a health document`));
                });
            }
        );
        request.on('timeout', () => {
            request.destroy();
            finish(notAHost(`nothing answered on port ${port} in time`));
        });
        request.on('error', (error) => {
            finish(notAHost(`nothing answered on port ${port} (${(error as NodeJS.ErrnoException).code
                ?? error.message})`));
        });
    });
}

/** A `listen` that reports the failure instead of throwing it at the event loop. */
function listen(server: http.Server, port: number, host = '127.0.0.1'): Promise<void> {
    return new Promise((resolve, reject) => {
        const onError = (error: Error) => {
            cleanup();
            reject(error);
        };
        const onListening = () => {
            cleanup();
            resolve();
        };
        const cleanup = () => {
            server.removeListener('error', onError);
            server.removeListener('listening', onListening);
        };
        server.once('error', onError);
        server.once('listening', onListening);
        server.listen(port, host);
    });
}

/** Closes a server that may never have listened, without throwing at the caller. */
export function closeQuietly(server: http.Server): Promise<void> {
    return new Promise((resolve) => {
        try {
            server.close(() => resolve());
        } catch {
            resolve();
        }
    });
}

/** The actual bound port of a listening server. */
export function boundPortOf(server: http.Server): number {
    const address = server.address();
    return typeof address === 'object' && address ? address.port : 0;
}

/**
 * Decides whether to serve, and on which port — and leaves `server` **listening** when the answer is
 * `start`.
 *
 * The server is created by the caller and bound here, which is what closes the window a "check, then bind"
 * design leaves open: the port this function reports as free is a port the caller already holds. A taken
 * port is classified by probing its `/health` — "the port is busy" is not "a bridge is there", and only the
 * occupant can say which project it serves.
 *
 * Three requests are possible, and the middle one is the reason this is not a one-liner:
 *
 * - `requestedPort` of 0 (or less) means "any free port": the operating system picks one, so there is nothing
 *   to conflict with and the answer is always `start`;
 * - a project whose `host.json` says `sticky: true` **pins** its port, so the answer is `start` on that port,
 *   `skip` when it already serves this project, and `fail` when anybody else holds it — never a move;
 * - otherwise the port loop runs, and a taken port is a reason to take the next free one.
 *
 * `stickyOverride` is how a caller that does not read `host.json` (a test, or a host with its own pinning UI)
 * states the pin directly; `undefined` means "read the project's own record".
 */
export async function claimPort(
    server: http.Server,
    project: string,
    requestedPort: number,
    attempts: number = DEFAULT_PORT_ATTEMPTS,
    stickyOverride?: boolean
): Promise<PortDecision> {
    if (requestedPort <= 0) {
        // An ephemeral port cannot conflict — the operating system hands out one nobody holds — so this branch
        // comes before the pin: a pin on "any free port" would not be a pin. The bind happens here, so the port
        // the decision reports is a port already held.
        await listen(server, 0);
        return {
            action: 'start',
            requestedPort,
            port: boundPortOf(server),
            sticky: false,
            tried: 1,
            occupant: null,
            reason: 'an ephemeral port was requested, so there is no conflict to resolve'
        };
    }

    const published = readPublished(project);
    const publishedForDecision = published
        ? { port: published.port, live: isLive(published.pid), ours: published.pid === process.pid }
        : null;
    const sticky = stickyOverride ?? (published?.sticky === true && published.port > 0);

    const attempt = async (port: number): Promise<PortTry> => {
        try {
            await listen(server, port);
            return { free: true };
        } catch (error) {
            const code = (error as NodeJS.ErrnoException).code;
            if (code !== 'EADDRINUSE' && code !== 'EACCES') {
                throw error;
            }
            // A failed listen leaves the server not listening; close it so the next attempt starts clean.
            await closeQuietly(server);
            return { free: false, occupant: await probe(port) };
        }
    };

    if (sticky) {
        return decidePinnedPort(project, published && published.port > 0 ? published.port : requestedPort,
            publishedForDecision, attempt, probe);
    }

    // The descriptor first: a live host for this project may be on a port nobody asked about, and probing
    // only the requested one would find it free and start a second bridge for one project.
    const decision = await publishedElsewhere(project, requestedPort, publishedForDecision, probe);
    if (decision) {
        return decision;
    }

    return decidePort(project, requestedPort, attempts, attempt);
}

/** The descriptor this host wants published, with the identity its `/health` also answers. */
export function descriptorFor(
    project: string,
    plugin: string,
    ide: string,
    port: number,
    capabilities: readonly string[],
    hostDetail: PublishedHost['host'],
    tokenPath = '',
    sticky = false
): PublishedHost {
    return {
        plugin,
        ide: (ide ?? '').trim() === '' ? IDE_UNKNOWN : ide,
        pid: process.pid,
        port,
        // Carried through from whatever pinned it: this host decided nothing about the pin, and must not
        // silently unpin a port somebody chose on purpose.
        sticky,
        project: normalizeProject(project),
        // This host keeps its token in the editor's settings, not in a file, so the path is empty rather than
        // pointing at something that does not exist. The key is still written: a reader can tell "no token
        // file" from "an older descriptor that never carried the field".
        tokenPath,
        capabilities: [...capabilities],
        startedAt: new Date().toISOString(),
        host: hostDetail
    };
}
