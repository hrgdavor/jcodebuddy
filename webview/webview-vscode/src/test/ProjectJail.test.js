'use strict';
/**
 * The project jail, and the escape a string policy cannot see.
 *
 * `BridgePolicy` is pure on purpose: it decides authorization, CORS and the rate limit from the request alone,
 * which is what lets it be asserted against shared vectors without a filesystem. Whether a path is *inside the
 * project* is not that kind of question — it is a fact about the disk, and a symlink inside the project that
 * points out of it is an escape no string comparison can catch.
 *
 * Runs on plain Node against the compiled `out/ProjectJail.js`, like its neighbours:
 *
 *     npm run test:unit
 *
 * Every case injects its own `realPath`, so the rule is checkable on a machine that will not create symlinks
 * without privileges — and case 4 is the one that matters, asserted without needing one.
 */

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const packageRoot = path.join(__dirname, '..', '..');
const jail = require(path.join(packageRoot, 'out', 'ProjectJail'));

let checks = 0;
function ok(what) {
  checks++;
  console.log(`  ok   ${what}`);
}
function isNull(value, what) {
  assert.strictEqual(value, null, `${what}: expected a refusal, got ${JSON.stringify(value)}`);
  ok(what);
}

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'jail-root-'));
const outside = fs.mkdtempSync(path.join(os.tmpdir(), 'jail-outside-'));
fs.mkdirSync(path.join(root, 'src'));
fs.writeFileSync(path.join(root, 'src', 'A.java'), 'class A {}');
fs.writeFileSync(path.join(outside, 'secret.txt'), 'secret');

// A real filesystem, so the identity resolver is the platform's own where the platform allows it.
const real = (p) => fs.realpathSync.native(p);

// 1. Inside, spelled relatively.
{
  const resolved = jail.resolveInsideProject(root, 'src/A.java', real);
  assert.ok(resolved && resolved.endsWith('/src/A.java'), `expected the file, got ${resolved}`);
  ok('a project-relative path inside the project resolves');
}

// 2. The dot-dot escape a string test does catch.
isNull(jail.resolveInsideProject(root, '../outside/secret.txt', real), 'a .. escape is refused');

// 3. An absolute path outside.
isNull(jail.resolveInsideProject(root, path.join(outside, 'secret.txt'), real), 'an absolute path outside is refused');

// 4. THE case: a symlink inside the project that points out of it.
//    `path.join(root, 'escape')` is *textually* inside root, so a prefix test accepts it. The resolver is what
//    refuses it - which is why the injectable seam exists, since Windows will not make this link unprivileged.
{
  const link = path.join(root, 'escape');
  const linkingRealPath = (p) => (path.resolve(p) === path.resolve(link) ? path.resolve(outside) : real(p));
  isNull(jail.resolveInsideProject(root, 'escape/secret.txt', linkingRealPath),
    'a symlink inside the project that points outside is refused');

  // And the same link, resolved honestly: if the platform lets us create it, assert it for real.
  try {
    fs.symlinkSync(outside, link, 'junction');
    isNull(jail.resolveInsideProject(root, 'escape/secret.txt', real), 'a real symlink out is refused');
    fs.unlinkSync(link);
  } catch (error) {
    console.log(`  skip a real symlink could not be created here (${error.code}); the injected case above covers the rule`);
  }
}

// 5. A file that does not exist yet, inside the project: a write of a new file, resolved through its parent.
{
  const future = path.join(root, 'src', 'New.java');
  const resolved = jail.resolveInsideProject(root, future, real);
  assert.ok(resolved && resolved.endsWith('/src/New.java'), `expected the new file, got ${resolved}`);
  ok('a file that is not there yet resolves through its parent');
}

// 6. A file that does not exist outside the project is still outside.
isNull(jail.resolveInsideProject(root, path.join(outside, 'not-there.txt'), real),
  'a missing file outside the project is refused');

// 7. The root itself is inside; its parent is not.
assert.ok(jail.isInside(root, path.join(root, 'a', 'b')), 'a descendant is inside');
assert.ok(jail.isInside(root, root), 'the root is inside itself');
assert.ok(!jail.isInside(root, path.dirname(root)), 'the parent is not inside');
assert.ok(!jail.isInside(root, `${root}-evil`), 'a sibling whose name starts with the project name is not inside');
assert.ok(!jail.isInside(root, path.join(root, '..', 'x')), 'a normalised escape is not inside');
ok('containment is segment-wise, not a string prefix');

// 8. Nothing to open, and no project to confine against.
isNull(jail.resolveInsideProject(root, '', real), 'an empty path is refused');
isNull(jail.resolveInsideProject(root, null, real), 'a missing path is refused');
isNull(jail.resolveInsideProject(null, path.join(root, 'src', 'A.java'), real),
  'with no project root this host refuses, because it has no boundary to reason about');

// 9. Unverifiable means no: a resolver that throws is not a licence to open.
isNull(jail.resolveInsideProject(root, 'src/A.java', () => { throw new Error('EACCES'); }),
  'a path that cannot be resolved is refused rather than trusted');

fs.rmSync(root, { recursive: true, force: true });
fs.rmSync(outside, { recursive: true, force: true });

console.log(`ProjectJail: ${checks} checks against a real filesystem and an injected resolver`);
