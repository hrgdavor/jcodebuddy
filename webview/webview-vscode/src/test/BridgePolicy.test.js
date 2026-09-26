'use strict';
/**
 * The VS Code host's authorization, CORS and rate-limit decisions, asserted against the vectors the Java
 * host reads too.
 *
 * Runs on plain Node with no dependencies and no VS Code download, because the rules under test are the
 * ones that were wrong and they must be checkable in a second:
 *
 *     npm run test:unit
 *
 * `BridgePolicy.ts` is compiled by `npm run compile` first, so this file requires the built JavaScript.
 */

const assert = require('assert');
const fs = require('fs');
const path = require('path');

// package root (src/test is two levels down); the compiled policy and the shared vectors are resolved from
// there so the paths do not depend on where this file happens to live.
const packageRoot = path.join(__dirname, '..', '..');
const policy = require(path.join(packageRoot, 'out', 'BridgePolicy'));

function loadVectors() {
    // package root -> webview-vscode -> webview -> conformance
    const file = path.join(packageRoot, '..', 'conformance', 'bridge-decisions.json');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

let checks = 0;

/** The write surface runs with a token, since a state-changing route requires one. */
const writeConfig = { allowedOrigins: '', token: 's3cret' };

function check(condition, message) {
    checks++;
    assert.ok(condition, message);
}

function run() {
    const vectors = loadVectors();

    assert.ok(!vectors.allowedOrigins || vectors.allowedOrigins.length > 0,
        'the vectors must not be empty, or every host conforms trivially');

    // 1. The allow-list table.
    for (const vector of vectors.allowedOrigins) {
        const actual = policy.allowsOrigin(vector.allowed, vector.origin);
        assert.strictEqual(actual, vector.expected,
            `allowsOrigin(allowed="${vector.allowed}", origin=${JSON.stringify(vector.origin)})`
            + ` expected ${vector.expected} but was ${actual}`);
        checks++;
    }

    // 2. CORS emission: only an allowed origin, and always echoed verbatim.
    for (const vector of vectors.cors) {
        const grant = policy.corsGrant(vector.allowed, vector.origin);
        if (vector.sends) {
            assert.strictEqual(grant, vector.echoes,
                `corsGrant(allowed="${vector.allowed}", origin=${JSON.stringify(vector.origin)})`
                + ` must echo the caller's origin`);
        } else {
            assert.strictEqual(grant, null,
                `corsGrant(allowed="${vector.allowed}", origin=${JSON.stringify(vector.origin)})`
                + ' must send nothing');
        }
        assert.notStrictEqual(grant, '*', 'a grant must never be the wildcard');
        checks += 2;
    }

    // 3. Rate-limit windows, with the clock moved by hand.
    for (const vector of vectors.rateLimit) {
        let now = 0;
        const limiter = new policy.RateLimiter(vector.limit, vector.windowMillis, () => now);
        vector.tryAt.forEach((at, index) => {
            now = at;
            const actual = limiter.tryAcquire();
            assert.strictEqual(actual, vector.expected[index],
                `limit=${vector.limit} window=${vector.windowMillis} tryAt[${index}]=${at}`
                + ` expected ${vector.expected[index]} but was ${actual}`);
            checks++;
        });
    }

    // 4. The /health document: same keys, same order, as the Java hosts and the shared list.
    assert.ok(vectors.healthKeys && vectors.healthKeys.keys.length > 0,
        'the vectors must define the /health keys');
    const document = policy.healthDocument(18882, 2, true, ['open', 'serveFile']);
    assert.deepStrictEqual(Object.keys(document), vectors.healthKeys.keys,
        '/health keys must match conformance/bridge-decisions.json in order');
    checks++;
    assert.strictEqual(document.plugin, 'vscode-webview-explorer');
    assert.strictEqual(document.port, 18882);
    assert.strictEqual(document.allowedOrigins, 2);
    assert.strictEqual(document.tokenRequired, true);
    assert.strictEqual(document.bridgeVersion, 1);
    checks += 5;
    assert.strictEqual(document.ide, 'Visual Studio Code',
        'the document names the editor, so a second host can recognise who holds the port');
    assert.strictEqual(document.project, '', 'a host that does not know its project says so with an empty string');
    assert.strictEqual(policy.healthDocument(1, 0, false, [], '', 'D:\\wrk\\proj\\').project, 'D:/wrk/proj',
        'the project is written forward-slashed and without a trailing separator');
    assert.strictEqual(policy.healthDocument(1, 0, false, [], '   ', '').ide, 'unknown',
        'a blank IDE name becomes "unknown" rather than an empty string');
    checks += 4;
    assert.deepStrictEqual(document.capabilities, ['open', 'serveFile'],
        'capabilities are sorted so two hosts with the same abilities agree byte for byte');
    assert.deepStrictEqual(policy.healthDocument(1, 0, false, ['serveFile', 'open']).capabilities,
        ['open', 'serveFile']);
    checks += 2;
    assert.throws(() => policy.healthDocument(-1, 0, false, []), 'a negative port is refused');
    assert.throws(() => policy.healthDocument(1, -1, false, []), 'a negative origin count is refused');
    checks += 2;
    assert.strictEqual(policy.countAllowedOrigins(''), 0);
    assert.strictEqual(policy.countAllowedOrigins('http://a, http://b'), 2);
    assert.strictEqual(policy.countAllowedOrigins('   ,  '), 0);
    checks += 3;

    // 5. The route decisions: authorization must come before anything is acted on, and /health answers to
    //    anyone. These are the two properties the Java hosts are held to as well.
    const openQuery = { filePath: 'src/main.ts', line: '10', column: '5' };
    const noOrigins = { allowedOrigins: '' };
    const withOrigin = { allowedOrigins: 'http://localhost:3000' };

    assert.deepStrictEqual(
        policy.decideRoute('GET', '/open', openQuery, noOrigins, null, null, false),
        { action: 'forbidden', reason: 'origin' },
        'a request with NO Origin header must be refused, not skipped');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/open', openQuery, noOrigins, 'https://evil.example', null, false),
        { action: 'forbidden', reason: 'origin' },
        'an uninvited origin must be refused');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/open', openQuery, withOrigin, 'http://localhost:3000', null, false),
        { action: 'open', filePath: 'src/main.ts', line: 10, column: 5, corsGrant: 'http://localhost:3000' },
        'an allowed origin is authorized');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/open', openQuery, noOrigins, null, null, true),
        { action: 'forbidden', reason: 'origin' },
        'an uninvited caller is refused for its origin even when the rate limit is also exhausted');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/open', openQuery, withOrigin, 'http://localhost:3000', null, true),
        { action: 'forbidden', reason: 'rate' },
        'an authorized caller is rate limited');
    assert.deepStrictEqual(
        policy.decideRoute('POST', '/open', openQuery, withOrigin, 'http://localhost:3000', null, false),
        { action: 'methodNotAllowed' });
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/open', {}, withOrigin, 'http://localhost:3000', null, false),
        { action: 'notFound' },
        'a missing filePath is not an opening');
    checks += 7;

    assert.deepStrictEqual(policy.decideRoute('GET', '/health', {}, noOrigins, null, null, false),
        { action: 'health' }, '/health answers without credentials');
    assert.deepStrictEqual(policy.decideRoute('GET', '/health', {}, noOrigins, null, null, true),
        { action: 'health' }, '/health is not rate limited: discovering a bridge must stay cheap');
    checks += 2;

    // /file/ was the route that answered `*`: an uninvited caller must be refused here too.
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/file/a%20b.html', {}, noOrigins, null, null, false),
        { action: 'forbidden', reason: 'origin' },
        '/file/ must refuse an uninvited caller');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/file/a%20b.html', {}, withOrigin, 'http://localhost:3000', null, false),
        { action: 'serve', path: '/file/a%20b.html', filePath: 'a b.html',
          corsGrant: 'http://localhost:3000' },
        'an allowed caller gets the decoded path, not the raw one');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/file/..%2Fsecret.txt', {}, withOrigin, 'http://localhost:3000', null, false),
        { action: 'serve', path: '/file/..%2Fsecret.txt', filePath: '../secret.txt',
          corsGrant: 'http://localhost:3000' },
        'the decision hands over the decoded path so the jail sees the escape');
    assert.deepStrictEqual(
        policy.decideRoute('GET', '/unknown', {}, noOrigins, null, null, false), { action: 'notFound' });
    checks += 4;

    // 6. The rules that were actually wrong here, stated directly so a rewrite cannot quietly lose them.
    check(policy.allowsOrigin('', 'http://localhost:3000') === false,
        'an empty allow-list denies everyone');
    check(policy.allowsOrigin('http://localhost:3000', null) === false,
        'an ABSENT origin is a denial, not a skip');
    check(policy.allowsOrigin('http://localhost:3000', undefined) === false,
        'a missing origin header is a denial');
    check(policy.isAuthorized({ allowedOrigins: '' }, null, null) === false,
        'with nothing configured, nobody is authorized');
    check(policy.isAuthorized({ allowedOrigins: 'http://localhost:3000' }, 'http://localhost:3000', null) === true,
        'an allowed origin authorizes');
    check(policy.isAuthorized({ allowedOrigins: '', token: 's3cret' }, null, 's3cret') === true,
        'a token authorizes even with no allowed origin and no Origin header');
    check(policy.isAuthorized({ allowedOrigins: '', token: 's3cret' }, null, 'wrong') === false,
        'a wrong token does not authorize');
    check(policy.isAuthorized({ allowedOrigins: 'http://localhost:3000', token: 's3cret' },
        'http://evil.example', 's3cret') === true,
        'the token is an alternative to an allowed origin, not an addition to it');
    check(policy.PRODUCTION_RATE_LIMIT.count === 20 && policy.PRODUCTION_RATE_LIMIT.windowMillis === 20000,
        'the production limit matches webview-core: 20 per 20s');

    // --- the write surface: the buffer path, and the verbs this host deliberately does not own ------------
    console.log('  write routes:');
    check(policy.decideWriteRoute('GET', '/api/v1/applyEdit', writeConfig, null, 's3cret').action
        === 'methodNotAllowed', 'a write route is POST only');
    check(policy.decideWriteRoute('POST', '/api/v1/applyEdit', writeConfig, null, null).action === 'forbidden',
        'a write route is refused with no token');
    check(policy.decideWriteRoute('POST', '/api/v1/applyEdit',
        { allowedOrigins: 'http://localhost:3000' }, 'http://localhost:3000', null).action === 'forbidden',
        'an allowed Origin is NOT enough for a write: the token is required (D8)');
    check(policy.decideWriteRoute('POST', '/api/v1/applyEdit', writeConfig, null, 's3cret').verb === 'applyEdit',
        'the right token reaches the verb');
    check(policy.decideWriteRoute('POST', '/api/v1/nowhere', writeConfig, null, 's3cret').action === 'notFound',
        'an unknown write route is notFound');

    const digest = 'sha256:' + 'a'.repeat(64);
    const request = {
        filePath: 'C:/p/A.java', expectedDigest: digest, dryRun: false, target: 'auto',
        edits: [{ startLine: 2, startColumn: 1, endLine: 2, endColumn: 4, newText: 'TWO' }]
    };
    check(policy.decideEdit(request, digest, true).apply === true, 'a matching digest with an editor applies');
    check(policy.decideEdit({ ...request, dryRun: true }, digest, true).apply === false,
        'dryRun applies nothing');
    check(policy.decideEdit(request, 'sha256:' + 'b'.repeat(64), true).reason === 'stale',
        'a stale digest is refused, exactly as the Java host refuses it');
    check(policy.decideEdit(request, 'sha256:' + 'b'.repeat(64), true).digest === 'sha256:' + 'b'.repeat(64),
        'the refusal carries the current digest so the page can re-read');
    check(policy.decideEdit(request, null, true).reason === 'not-found',
        'a file this host cannot read is a notFound, not a write');
    check(policy.decideEdit(request, digest, false).reason === 'no-buffer-edit',
        'no open editor means no buffer edit');
    check(policy.decideEdit({ ...request, target: 'disk' }, digest, true).reason === 'no-disk-write',
        'this host refuses target disk rather than pretending to own the file');
    check(policy.decideEdit({ ...request, target: 'disk' }, digest, true).detail.includes('webviewd'),
        'and it names the host that can do it');

    const mapped = policy.mapEdits(request.edits);
    check(mapped[0].range.start.line === 1 && mapped[0].range.start.character === 0,
        "one-based line 2 column 1 becomes the API's zero-based 1:0");
    check(mapped[0].range.end.character === 3 && mapped[0].newText === 'TWO',
        'the exclusive end and the replacement text are passed through');

    const parsed = policy.parseEditRequest(JSON.stringify(request), true);
    check(parsed.ok === true && parsed.request.filePath === 'C:/p/A.java',
        'a well-formed body parses');
    check(policy.parseEditRequest('{ nope', true).status === 400, 'malformed JSON is a 400');
    check(policy.parseEditRequest('{"filePath":"A.java"}', true).detail.includes('expectedDigest'),
        'a request without a digest is refused with the same words the Java host uses');
    check(policy.parseEditRequest(JSON.stringify({ ...request, edits: [{ startLine: 0, startColumn: 1,
        endLine: 0, endColumn: 1, newText: 'x' }] }), true).detail.includes('one-based'),
        'line and column are one-based');
    check(policy.parseEditRequest(JSON.stringify({ ...request, target: 'elsewhere' }), true)
        .detail.includes('auto, buffer or disk'), 'an unknown target is refused by name');
    check(JSON.parse(policy.writeRefusalBody('stale', 'detail', digest)).digest === digest,
        'a refusal body carries the digest when there is one');
    check(JSON.parse(policy.bufferEditBody(digest, true, 'done')).target === 'buffer',
        'a buffer answer says where the change went');

    console.log(`BridgePolicy: ${checks} assertions against conformance/bridge-decisions.json`
        + ' and the rules this host used to get wrong. All green.');
}

/**
 * The port claim, as the pure decision the host layer drives. The socket half of it (actually binding, and
 * reading a real `/health` off a port) is `HostRegistration.test.js`; what is asserted here is the rule —
 * who may keep a port, and the one case in which a host must not open an endpoint at all.
 */
async function runPortClaim() {
    const project = 'D:/wrk/one';
    const other = 'D:/wrk/two';

    // Reading an occupant out of a /health body.
    const health = JSON.stringify(policy.healthDocument(18882, 0, false, ['open'], 'Visual Studio Code', project));
    const occupant = policy.parseHealthDocument(18882, health);
    check(occupant.answered === true, 'a health document is recognised');
    check(occupant.ide === 'Visual Studio Code' && occupant.project === project,
        'the occupant carries the editor and the project, which is what the decision turns on');
    check(policy.parseHealthDocument(18882, 'not json').answered === false, 'a non-JSON answer is not an occupant');
    check(policy.parseHealthDocument(18882, '{"hello":"world"}').answered === false,
        'JSON without plugin and port is an unrelated application, not an occupant');
    check(policy.parseHealthDocument(18882, '').answered === false, 'an empty answer is not an occupant');

    // "The same project" as a path comparison, not a string comparison.
    check(policy.sameProject(project, 'D:/wrk/one/') === true, 'a trailing separator is the same directory');
    check(policy.sameProject(project, 'D:\\wrk\\one') === true, 'a backslash is the same directory');
    check(policy.sameProject(project, 'D:/wrk/one/sub/..') === true, 'a folded .. is the same directory');
    check(policy.sameProject(project, other) === false, 'a different directory is a different project');
    check(policy.sameProject(project, '') === false, 'an unknown project is never "the same project"');
    check(policy.servesSameProject(occupant, project) === true, 'the occupant serves this project');
    check(policy.servesSameProject(occupant, other) === false, 'and not the other one');
    checks += 12;

    // The port loop. Each case is one row of the table in HostPortClaim's javadoc.
    const free = async () => ({ free: true });
    const takenBy = (who) => async () => ({ free: false, occupant: who });

    const firstFree = await policy.decidePort(project, 18882, 20, free);
    check(firstFree.action === 'start' && firstFree.port === 18882,
        'a free port is used as asked, with no probing');

    const mine = policy.parseHealthDocument(18882, JSON.stringify(
        policy.healthDocument(18882, 0, false, [], 'IntelliJ Platform', project)));
    const sameProject = await policy.decidePort(project, 18882, 20, takenBy(mine));
    check(sameProject.action === 'skip',
        'another host serving THIS project means this host opens nothing at all');
    check(sameProject.reason.includes('will not open a second endpoint'),
        'and the reason says why, rather than leaving a silent bridge');
    check(sameProject.port === 18882, 'the port it declined to take is reported, so a log can name it');

    const theirs = policy.parseHealthDocument(18883, JSON.stringify(
        policy.healthDocument(18883, 0, false, [], 'IntelliJ Platform', other)));
    const foreignProject = await policy.decidePort(project, 18882, 20,
        async (port) => (port === 18882 ? { free: false, occupant: theirs } : { free: true }));
    check(foreignProject.action === 'start' && foreignProject.port === 18883,
        "a host for ANOTHER project does not stop this one: the next free port is taken");

    const stranger = policy.parseHealthDocument(18882, '{"hello":"world"}');
    const strangerCase = await policy.decidePort(project, 18882, 20,
        async (port) => (port === 18882 ? { free: false, occupant: stranger } : { free: true }));
    check(strangerCase.action === 'start' && strangerCase.port === 18883,
        'an unrelated application on the port is treated the same way: take the next free one');

    const exhaustion = await policy.decidePort(project, 18882, 3, takenBy(stranger));
    check(exhaustion.action === 'skip' && exhaustion.tried === 3,
        'when the whole range is taken the host serves nothing rather than binding a port at random');
    check(exhaustion.reason.includes('none of the 3 ports'),
        'and it says how far it looked');

    const ephemeral = await policy.decidePort(project, 0, 20, async () => {
        throw new Error('an ephemeral port must not be probed: there is nothing to conflict with');
    });
    check(ephemeral.action === 'start' && ephemeral.port === 0,
        'port 0 asks the operating system for a free port, so no probe happens');
    checks += 9;

    // The pinned port. Same table as the Java host's HostPortClaim: a pin is tried alone, and the last two
    // rows of the table become a failure rather than a move.
    const neverBindable = async () => {
        throw new Error('a pinned port that already serves this project must not be bound again');
    };
    const pinnedFree = await policy.decidePinnedPort(project, 19000, null, async () => ({ free: true }),
        async () => policy.parseHealthDocument(19000, '{}'));
    check(pinnedFree.action === 'start' && pinnedFree.port === 19000 && pinnedFree.sticky === true,
        'a free pinned port is taken, and the decision says it was pinned');
    check(pinnedFree.tried === 1, 'a pinned port is tried alone: no other port is a candidate');

    const pinnedServed = await policy.decidePinnedPort(project, 19000, null, neverBindable,
        async () => policy.parseHealthDocument(19000, JSON.stringify(
            policy.healthDocument(19000, 0, false, [], 'IntelliJ Platform', project))));
    check(pinnedServed.action === 'skip' && pinnedServed.sticky === true,
        'a pinned port already serving THIS project is the state the pin asked for, not an error');

    const pinnedTaken = await policy.decidePinnedPort(project, 19000, null,
        async () => ({ free: false, occupant: policy.notAHost('nothing answered') }),
        async () => policy.notAHost('nothing answered'));
    check(pinnedTaken.action === 'fail',
        'a pinned port held by anything else is an error: moving would be a silent lie about a choice');
    check(pinnedTaken.reason.includes('pinned') && pinnedTaken.reason.includes('sticky')
        && pinnedTaken.reason.includes('host.json'),
        `the message says which port, that it is pinned, and how to unpin it: ${pinnedTaken.reason}`);

    const pinnedByAnotherProject = await policy.decidePinnedPort(project, 19000, null,
        async () => ({ free: false, occupant: theirs }),
        async () => theirs);
    check(pinnedByAnotherProject.action === 'fail',
        'a pinned port held by a host for another project is a collision, not "already served"');
    check(pinnedByAnotherProject.reason.includes('IntelliJ Platform'),
        'and the message names the host that holds it');

    const pinnedButServedElsewhere = await policy.decidePinnedPort(project, 19000,
        { port: 19001, live: true, ours: false }, neverBindable,
        async (port) => policy.parseHealthDocument(port, JSON.stringify(
            policy.healthDocument(port, 0, false, [], 'IntelliJ Platform', project))));
    check(pinnedButServedElsewhere.action === 'skip' && pinnedButServedElsewhere.port === 19001,
        'one bridge per project outranks the pin: a live host elsewhere is not an invitation to start a second');
    checks += 10;

    // The descriptor fast path: a live host that moved to a port nobody asked about.
    const moved = await policy.publishedElsewhere(project, 18882, { port: 19000, live: true, ours: false },
        async (port) => policy.parseHealthDocument(port, JSON.stringify(
            policy.healthDocument(port, 0, false, [], 'IntelliJ Platform', project))));
    check(moved !== null && moved.action === 'skip' && moved.port === 19000,
        'a live descriptor for this project stops a second bridge even on a port nobody asked for');

    check(await policy.publishedElsewhere(project, 18882, { port: 19000, live: false, ours: false },
        async () => mine) === null, 'a dead descriptor is not an occupant');
    check(await policy.publishedElsewhere(project, 18882, { port: 19000, live: true, ours: true },
        async () => mine) === null,
        'a descriptor written by this process is ignored, or a host could never restart itself');
    check(await policy.publishedElsewhere(project, 18882, { port: 19000, live: true, ours: false },
        async () => theirs) === null, 'a descriptor for another project is not this project’s host');
    check(await policy.publishedElsewhere(project, 18882, null, async () => mine) === null,
        'no descriptor means the port loop decides');
    checks += 4;

    console.log(`PortClaim: ${checks} assertions so far — the port table, the occupant identity, and the`
        + ' descriptor fast path. All green.');
}

Promise.resolve()
    .then(run)
    .then(runPortClaim)
    .catch((error) => {
        console.error(error);
        process.exitCode = 1;
    });
