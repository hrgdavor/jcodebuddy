'use strict';
/**
 * The VS Code host's port claim, over real sockets and a real `.jcodebuddy/`.
 *
 * `BridgePolicy.test.js` asserts the *rule* without Node; this file asserts the half that only a socket can
 * show: that a second host for one project really does decline to bind, that a host for another project
 * really does take the next port, and that the port that was taken is the port that is published in the
 * project's `.jcodebuddy/webview/host.json`.
 *
 * Runs on plain Node with no dependencies and no VS Code download:
 *
 *     npm run test:unit
 *
 * `BridgePolicy.ts` and `HostRegistration.ts` are compiled by `npm run compile` first, so this file requires
 * the built JavaScript.
 */

const assert = require('assert');
const childProcess = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');
const path = require('path');

const packageRoot = path.join(__dirname, '..', '..');
const registration = require(path.join(packageRoot, 'out', 'HostRegistration'));
const policy = require(path.join(packageRoot, 'out', 'BridgePolicy'));

let checks = 0;

function check(condition, message) {
    checks++;
    assert.ok(condition, message);
}

/** A directory that goes away with the process. */
function temporaryProject(name) {
    return fs.mkdtempSync(path.join(os.tmpdir(), `webview-${name}-`));
}

/** A port nobody holds right now: bind 0, read what we got, let it go. */
function freePort() {
    return new Promise((resolve) => {
        const probe = net.createServer();
        probe.listen(0, '127.0.0.1', () => {
            const port = probe.address().port;
            probe.close(() => resolve(port));
        });
    });
}

/** A server that answers `/health` like a real host, and nothing else. */
function healthOnlyServer(project, ide) {
    const server = http.createServer((request, response) => {
        if (request.url !== '/health') {
            response.writeHead(404);
            response.end('Not Found');
            return;
        }
        response.setHeader('Content-Type', 'application/json');
        response.writeHead(200);
        response.end(JSON.stringify(policy.healthDocument(
            server.address().port, 0, false, ['edit', 'open'], ide, project)));
    });
    return server;
}

/** A server that is a bridge for the purposes of this test, but never answers a health document. */
function silentServer() {
    return http.createServer((request, response) => {
        response.writeHead(404);
        response.end('Not Found');
    });
}

function listenOn(server, port) {
    return new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(port, '127.0.0.1', () => resolve(server));
    });
}

function closeServer(server) {
    return new Promise((resolve) => {
        if (!server || !server.listening) {
            resolve();
            return;
        }
        server.close(() => resolve());
    });
}

/**
 * A process that is alive and is not this one.
 *
 * The descriptor fast path turns on "somebody else's pid is still running", so the test needs a live pid it
 * does not own. Spawning one is the only honest way to get it: `process.ppid` is not guaranteed to be alive
 * and a made-up number is exactly the case the check exists to reject.
 */
function spawnBystander() {
    // `stdio: 'ignore'` on purpose: a confined run cannot open pipes for a child, and this child is only a pid.
    const child = childProcess.spawn(process.execPath, ['-e', 'setTimeout(() => {}, 60000)'],
        { stdio: 'ignore' });
    return child;
}

async function run() {
    const project = temporaryProject('one');
    const otherProject = temporaryProject('two');
    const ide = 'Visual Studio Code';
    const servers = [];
    let bystander = null;

    try {
        // 1. The published descriptor: the same file, the same place, as the Java hosts write.
        const published = registration.descriptorFor(project, 'vscode-webview-explorer', ide, 18882,
            ['edit', 'open'], { name: 'vscode', available: true, lineNavigation: 'exact', note: '' });
        registration.writePublished(project, published);

        check(fs.existsSync(path.join(project, '.jcodebuddy', 'webview', 'host.json')),
            "the port is published in the project's .jcodebuddy/webview/host.json");
        const read = registration.readPublished(project);
        check(read !== null && read.port === 18882 && read.ide === ide && read.pid === process.pid,
            'and it carries the port, the editor name and the pid');
        check(read.project.includes('/'), 'paths are stored forward-slashed so a page can use them');
        check(registration.isLive(process.pid) === true, 'this process is alive');
        check(registration.isLive(999_999_999) === false, 'an absurd pid is not');

        // A host is pointed at arbitrary directories, most of which have no JCodeBuddy ignore policy, so it
        // ignores its own state rather than leaving untracked files behind.
        const stateIgnore = path.join(project, '.jcodebuddy', 'webview', '.gitignore');
        check(fs.readFileSync(stateIgnore, 'utf8').includes('*'),
            'the state directory ignores itself, so a host never litters git status');
        fs.writeFileSync(stateIgnore, 'mine\n', 'utf8');
        registration.writePublished(project, published);
        check(fs.readFileSync(stateIgnore, 'utf8') === 'mine\n',
            'and a project that wrote a narrower policy of its own keeps it');

        // The record outlives the host: a bridge that stops leaves it in place, because it is the workspace's
        // port — what the next start asks for, and where a `sticky` pin lives — not this process's record.
        check(typeof registration.deletePublished === 'function'
            && typeof registration.deleteIfOurs === 'undefined',
            'nothing deletes the record automatically; deleting it is a deliberate call');
        registration.writePublished(project, published);
        check(registration.readPublished(project).port === 18882,
            'and the published port is still readable after a stop');
        check(registration.deletePublished(project) === true, 'deleting it works when asked');
        check(registration.readPublished(project) === null, 'and then there is no record');

        // A truncated descriptor — an editor killed mid-write — must not stop the next start.
        fs.mkdirSync(registration.directoryOf(project), { recursive: true });
        fs.writeFileSync(registration.fileOf(project), '{ truncated', 'utf8');
        check(registration.readPublished(project) === null, 'a half-written descriptor counts as absent');
        registration.deletePublished(project);

        // 2. A free port is taken as asked.
        const port = await freePort();
        const first = healthOnlyServer(project, ide);
        servers.push(first);
        const firstDecision = await registration.claimPort(first, project, port);
        check(firstDecision.action === 'start' && firstDecision.port === port,
            'the requested port is used when it is free');
        check(first.listening === true, 'and the server is left listening, not merely checked');
        check(registration.boundPortOf(first) === port, 'the bound port is readable from the server');

        // 3. A second host for THE SAME project must open nothing at all. The first host is a real one here:
        //    the same editor, the same project, answering /health on the port being asked for.
        const second = silentServer();
        const sameProject = await registration.claimPort(second, project, port);
        check(sameProject.action === 'skip',
            'another editor already serving this project means this host opens no endpoint');
        check(second.listening === false, 'and it is not quietly listening on some other port either');
        check(sameProject.reason.includes('will not open a second endpoint'),
            'the refusal explains itself rather than leaving a bridge that silently does nothing');
        check(sameProject.occupant !== null && sameProject.occupant.ide === ide,
            'the decision names the editor that is already there');
        await closeServer(second);

        // 4. A host for ANOTHER project on that port does not stop this one: take the next free port.
        await closeServer(first);
        const foreign = healthOnlyServer(otherProject, 'IntelliJ Platform');
        await listenOn(foreign, port);
        servers.push(foreign);

        const third = silentServer();
        servers.push(third);
        const nextFree = await registration.claimPort(third, project, port);
        check(nextFree.action === 'start' && nextFree.port === port + 1,
            'a host serving another project moves this one to the next free port');
        check(nextFree.tried === 2, 'and the decision says how many ports it tried');
        check(nextFree.reason.includes(`port ${port + 1} was free`),
            'the reason names the port it settled on');
        await closeServer(third);

        // 5. An unrelated application on the port is treated the same way: something that is not a health
        //    document must never be mistaken for a host for this project.
        await closeServer(foreign);
        const stranger = http.createServer((request, response) => {
            response.writeHead(200, { 'Content-Type': 'application/json' });
            response.end('{"hello":"world"}');
        });
        await listenOn(stranger, port);
        servers.push(stranger);

        const fourth = silentServer();
        servers.push(fourth);
        const pastStranger = await registration.claimPort(fourth, project, port);
        check(pastStranger.action === 'start' && pastStranger.port === port + 1,
            'an unrelated application on the port is stepped over, not mistaken for a host');
        check(pastStranger.occupant !== null && pastStranger.occupant.answered === false,
            'and the decision records that nothing recognizable answered there');
        await closeServer(fourth);
        await closeServer(stranger);

        // 6. The descriptor fast path over a real socket: a live host for this project published a DIFFERENT
        //    port, which is the case that would otherwise start a second bridge for one project.
        bystander = spawnBystander();
        const elsewherePort = await freePort();
        const moved = healthOnlyServer(project, 'IntelliJ Platform');
        await listenOn(moved, elsewherePort);
        servers.push(moved);
        check(registration.isLive(bystander.pid) === true, 'the bystander process is alive');
        registration.writePublished(project, {
            ...registration.descriptorFor(project, 'hr.hrg.jetbrains.webview', 'IntelliJ Platform',
                elsewherePort, ['open'],
                { name: 'lsp', available: true, lineNavigation: 'exact', note: '' }),
            pid: bystander.pid
        });

        const fifth = silentServer();
        const freeButServed = await freePort();
        const movedDecision = await registration.claimPort(fifth, project, freeButServed);
        check(movedDecision.action === 'skip',
            'a live descriptor for this project stops a second bridge even when the requested port is free');
        check(movedDecision.port === elsewherePort,
            'and the decision names the port that is actually serving');
        check(fifth.listening === false, 'nothing is bound by the host that declined');
        await closeServer(fifth);

        // 7. A descriptor this process wrote is not an occupant: a host must be able to restart itself.
        registration.writePublished(project, registration.descriptorFor(project,
            'vscode-webview-explorer', ide, elsewherePort, ['edit', 'open'], null));
        const sixth = silentServer();
        servers.push(sixth);
        const restartPort = await freePort();
        const restart = await registration.claimPort(sixth, project, restartPort);
        check(restart.action === 'start' && restart.port === restartPort,
            'a host restarts on its own published descriptor instead of refusing to start');
        await closeServer(sixth);

        // 8. The committed preference: a default for a new checkout, never a pin.
        check(registration.configFileOf(project) === path.join(project, '.jcodebuddy', 'conf', 'webview.json'),
            'the preference lives in the tracked conf/ subtree, not beside the running host\'s state');
        check(registration.readPortPreference(project).port === null,
            'no file means no preference, and that is the normal state');

        fs.mkdirSync(path.join(project, '.jcodebuddy', 'conf'), { recursive: true });
        fs.writeFileSync(registration.configFileOf(project), '{ "port": 19999 }', 'utf8');
        check(registration.readPortPreference(project).port === 19999, 'a committed port is read');
        check(registration.readPortPreference(project).problem === '', 'and reading it is not a problem');

        fs.writeFileSync(registration.configFileOf(project), '{ "port": "19999" }', 'utf8');
        const quoted = registration.readPortPreference(project);
        check(quoted.port === null && quoted.problem.includes('whole number'),
            `a quoted number is a typo, and it is reported rather than used: ${quoted.problem}`);

        fs.writeFileSync(registration.configFileOf(project), '{ "port": 70000 }', 'utf8');
        check(registration.readPortPreference(project).port === null
            && registration.readPortPreference(project).problem.includes('65535'),
            'an impossible port is refused with a message, and the host still starts on the default');

        fs.rmSync(registration.configFileOf(project));

        // 9. A pinned port. Free: taken. Held by a stranger: an error, not a move — which is the whole point
        //    of pinning, and the case a user hits when something else grabs the port between two runs.
        const pinnedPort = await freePort();
        const pinnedServer = silentServer();
        servers.push(pinnedServer);
        registration.writePublished(project, {
            ...registration.descriptorFor(project, 'vscode-webview-explorer', ide, pinnedPort,
                ['edit', 'open'], null),
            pid: 999_999_999,
            sticky: true
        });

        const pinned = await registration.claimPort(pinnedServer, project, pinnedPort);
        check(pinned.action === 'start' && pinned.port === pinnedPort && pinned.sticky === true,
            'a free pinned port is taken, and the decision records the pin');
        check(registration.readPublished(project).sticky === true,
            'the published record still says the port is pinned, so a later run keeps honouring it');
        await closeServer(pinnedServer);

        const strangerOnPinned = http.createServer((request, response) => {
            response.writeHead(404);
            response.end('Not Found');
        });
        await listenOn(strangerOnPinned, pinnedPort);
        servers.push(strangerOnPinned);

        const refused = silentServer();
        servers.push(refused);
        const pinnedDecision = await registration.claimPort(refused, project, await freePort());
        check(pinnedDecision.action === 'fail',
            'a pinned port held by another application is a failure, never a quiet move to the next port');
        check(refused.listening === false, 'and nothing is bound by the host that refused');
        check(pinnedDecision.reason.includes(String(pinnedPort)) && pinnedDecision.reason.includes('sticky'),
            `the message names the pinned port and how to unpin it: ${pinnedDecision.reason}`);
        await closeServer(refused);

        // 10. The pin can be cleared, and a cleared pin behaves like an ordinary preference again.
        registration.writePublished(project, { ...registration.readPublished(project), sticky: false });
        const unpinned = silentServer();
        servers.push(unpinned);
        const unpinnedDecision = await registration.claimPort(unpinned, project,
            registration.readPublished(project).port);
        check(unpinnedDecision.action === 'start' && unpinnedDecision.port === pinnedPort + 1,
            'with the pin cleared, a taken port is a reason to take the next free one again');
        await closeServer(unpinned);

        // 11. The four sources for the port to ask for, in one place. `host.json` holds the port this CHECKOUT
        //     is currently on (local, never in git); `conf/webview.json` holds the project's DEFAULT
        //     (optional, tracked). The current one wins, so a checkout that had to move keeps its port.
        fs.writeFileSync(registration.configFileOf(project), '{ "port": 18882 }', 'utf8');
        registration.writePublished(project, {
            ...registration.descriptorFor(project, 'vscode-webview-explorer', ide, 19010, ['open'], null),
            pid: 999_999_999
        });

        check(registration.requestedPort(project, null, 18882).port === 19010
            && registration.requestedPort(project, null, 18882).source === 'current',
            'the port this checkout is currently on beats the project default');
        check(registration.requestedPort(project, 18899, 18882).source === 'flag',
            'an explicit setting beats both files');
        check(registration.requestedPort(project, 18899, 18882).port === 18899,
            'and its value is used, not merely its precedence');

        fs.rmSync(registration.fileOf(project));
        const fromDefault = registration.requestedPort(project, null, 18882);
        check(fromDefault.port === 18882 && fromDefault.source === 'default',
            'with no current port the committed default bootstraps the checkout');

        fs.rmSync(registration.configFileOf(project));
        const fromFallback = registration.requestedPort(project, null, 18882);
        check(fromFallback.port === 18882 && fromFallback.source === 'fallback',
            'and with neither file the host uses its own fallback');

        fs.writeFileSync(registration.configFileOf(project), '{ "port": "18882" }', 'utf8');
        const withTypo = registration.requestedPort(project, null, 18882);
        check(withTypo.port === 18882 && withTypo.source === 'fallback' && withTypo.problem !== '',
            `an unusable default is reported and the host still starts: ${withTypo.problem}`);
        fs.rmSync(registration.configFileOf(project));

        console.log(`HostRegistration: ${checks} assertions over real sockets and a real .jcodebuddy/.`
            + ' All green.');
    } finally {
        for (const server of servers) {
            await closeServer(server);
        }
        if (bystander) {
            bystander.kill();
        }
    }
}

run().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
