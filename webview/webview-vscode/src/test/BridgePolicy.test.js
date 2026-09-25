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

run();
