#!/usr/bin/env bun
/**
 * Step 3.0m: `metadata-server` becomes `jcodebuddy-meta`, and its MCP sibling follows (DEC-038 decisions 2
 * and 3).
 *
 * WHAT CHANGES AND WHAT DOES NOT. The module's groupId is already `hr.hrg.jcodebuddy`; its *package* is not,
 * and neither is its name. So the rename is four things — the directory, the `artifactId`, the `<name>` and
 * the package (`hr.hrg.watch2.server.metadata.*` -> `hr.hrg.jcodebuddy.meta.*`) — and the MCP module follows
 * as `jcodebuddy-meta-mcp`. **No class is renamed, no method moves, and no behaviour changes**: the serving
 * shapes and the transports are DEC-W006-W009's, and re-pointing the providers at the engine is 3.0j's work.
 *
 * THE PACKAGE IS WHY THIS IS ONE MECHANICAL PASS. `hr.hrg.watch2.server.metadata` is a prefix of every
 * subpackage (`...metadata.model`, `.rpc`, `.transport`, `.mcp`), so one prefix replacement rewrites the
 * `package` declarations, the `import`s and the javadoc references consistently — and moving the one
 * directory `.../server/metadata` to `.../jcodebuddy/meta` puts every file where its package says it is.
 *
 * `java-watch*` is NOT touched: DEC-038 keeps it an independent library this module depends on rather than
 * absorbs, and the module declares no dependency on a workspace artifact today.
 *
 * Prose (module-map, the decision records, READMEs, the plan) is edited by hand, where a reviewer reads it.
 *
 * THREE TRAPS, ALL OF THEM HIT ON THE FIRST RUN AND ALL OF THEM NOW GUARDED, because each one failed quietly:
 *
 * 1. `git mv a b` **renames** when `b` does not exist and moves **into** it when it does. Pre-creating the
 *    destination nests the module as `newDir/oldName/`, after which every later step looks in the wrong place
 *    and finds nothing. `assertAbsent` refuses that; the destination's *parent* is created instead.
 * 2. `git ls-files 'pom.xml'` matches the root POM and nothing else — a pathspec needs a wildcard to match a
 *    basename at depth (`'*pom.xml'`), unlike `'*.java'`. With the bare name, every consumer POM kept the old
 *    artifactId and Maven refused the reactor with "dependency.version is missing" rather than anything that
 *    names the rename.
 * 3. A check that compares each file it *finds* passes over an empty tree. The package/directory guard below
 *    reported "0 mismatches" while the sources had been deleted, which is worse than no check: it is a green
 *    light no build had earned. It counts what it verified and refuses to pass on nothing.
 *
 * The cleanup in step 2 is deliberately `rmdir`-if-empty rather than a recursive delete: the old `hr/hrg`
 * ancestors are shared with the package that replaces them, so removing them by force takes the new tree with
 * it — which is exactly how the sources were lost on the first run.
 *
 * Usage: `bun scripts/rename-metadata-module.js --dry-run` | `bun scripts/rename-metadata-module.js`
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const dryRun = process.argv.includes('--dry-run');

const OLD_PACKAGE = 'hr.hrg.watch2.server.metadata';
const NEW_PACKAGE = 'hr.hrg.jcodebuddy.meta';

/** The two modules, in the order the rename reads best: the server, then the MCP sibling that follows it. */
const MODULES = [
    { dir: 'jcodebuddy/metadata-server', newDir: 'jcodebuddy/jcodebuddy-meta', artifactId: 'jcodebuddy-meta', name: 'JCodeBuddy Meta' },
    { dir: 'jcodebuddy/metadata-mcp-server', newDir: 'jcodebuddy/jcodebuddy-meta-mcp', artifactId: 'jcodebuddy-meta-mcp', name: 'JCodeBuddy Meta MCP' },
];

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const say = (message) => console.log(`  ${dryRun ? 'would ' : ''}${message}`);
const eolOf = (text) => (text.includes('\r\n') ? '\r\n' : '\n');
const mustReplace = (text, from, to, what) => {
    if (!text.includes(from)) throw new Error(`expected text not found in ${what}:\n${from}`);
    return text.replace(from, to);
};

const tracked = (pattern) => git('ls-files', pattern).split('\n').map((f) => f.trim()).filter(Boolean);

/**
 * A guard rather than a hope. `git mv a b` renames when `b` does not exist and moves *into* it when it does,
 * so pre-creating the destination turns the module into `newDir/oldName/` and every later step silently
 * looks in the wrong place — which is exactly what happened on this script's first run. The parent directory
 * must exist; the target must not.
 */
const assertAbsent = (relative, what) => {
    if (existsSync(join(root, relative))) throw new Error(`${what}: ${relative} already exists — git mv would move INTO it`);
};

// ── 1. the directories: every file lands where its package will say it is ──────────────────────────────
for (const module of MODULES) {
    const from = `${module.dir}/src`;
    if (!existsSync(join(root, from))) {
        say(`${module.dir}: already renamed`);
        continue;
    }
    assertAbsent(module.newDir, 'the rename');
    say(`${module.dir} -> ${module.newDir}`);
    if (!dryRun) {
        mkdirSync(dirname(join(root, module.newDir)), { recursive: true });
        git('mv', module.dir, module.newDir);
        if (!existsSync(join(root, `${module.newDir}/src`))) {
            throw new Error(`after the move ${module.newDir}/src does not exist — the module was nested, not renamed`);
        }
    }
}

// ── 2. the packages: one prefix, so package declarations, imports and javadoc stay consistent ──────────
const oldPath = OLD_PACKAGE.replace(/\./g, '/');
const newPath = NEW_PACKAGE.replace(/\./g, '/');
for (const module of MODULES) {
    const pairs = [
        [`${module.newDir}/src/main/java/${oldPath}`, `${module.newDir}/src/main/java/${newPath}`],
        [`${module.newDir}/src/test/java/${oldPath}`, `${module.newDir}/src/test/java/${newPath}`],
    ];
    for (const [from, to] of pairs) {
        if (!existsSync(join(root, from))) continue;
        say(`${from} -> ${to}`);
        if (!dryRun) {
            mkdirSync(dirname(join(root, to)), { recursive: true });
            git('mv', from, to);
            // `git mv` moves the tree; the empty `watch2/server` ancestors are now debris on disk only.
            for (const stale of [`${module.newDir}/src/main/java/hr/hrg/watch2/server`,
                `${module.newDir}/src/main/java/hr/hrg/watch2`, `${module.newDir}/src/main/java/hr/hrg`,
                `${module.newDir}/src/test/java/hr/hrg/watch2/server`,
                `${module.newDir}/src/test/java/hr/hrg/watch2`, `${module.newDir}/src/test/java/hr/hrg`]) {
                try {
                    if (existsSync(join(root, stale)) && readdirSync(join(root, stale)).length === 0) rmdirSync(join(root, stale));
                } catch { /* a non-empty directory is not debris; leave it */ }
            }
        }
    }
}

// ── 3. every reference to the package, wherever it lives ───────────────────────────────────────────────
const javaFiles = tracked('*.java').filter((f) => !f.startsWith('proto/'));
let rewritten = 0;
for (const file of javaFiles) {
    const text = readFileSync(join(root, file), 'utf8');
    if (!text.includes(OLD_PACKAGE)) continue;
    rewritten++;
    if (!dryRun) writeFileSync(join(root, file), text.split(OLD_PACKAGE).join(NEW_PACKAGE));
}
say(`${rewritten} Java file(s) reference ${OLD_PACKAGE} — rewritten to ${NEW_PACKAGE}`);

/**
 * The check that would have caught the nesting bug on its own, and it runs *after* the rewrite above because
 * it verifies the end state of steps 1-3: a file's directory must be its package. A skipped `git mv` leaves
 * the declarations rewritten and the tree in the old place, which compiles nowhere and is otherwise only
 * noticed by a build.
 */
const mismatched = [];
let checked = 0;
for (const module of MODULES) {
    for (const kind of ['main', 'test']) {
        const base = join(root, `${module.newDir}/src/${kind}/java`);
        if (!existsSync(base)) continue;
        for (const file of tracked(`${module.newDir}/src/${kind}/**/*.java`)) {
            const text = readFileSync(join(root, file), 'utf8');
            checked++;
            const declared = /^package\s+([\w.]+);/m.exec(text)?.[1];
            const directory = file.replace(/.*\/java\//, '').replace(/\/[^/]+$/, '').replace(/\//g, '.');
            if (declared !== directory) mismatched.push(`${file}: package ${declared} != directory ${directory}`);
        }
    }
}
if (mismatched.length > 0) throw new Error(`package and directory disagree:\n  ${mismatched.join('\n  ')}`);
if (checked === 0) throw new Error('the package/directory check found no files at all — nothing was verified');
say(`every one of the ${checked} file(s) sits where its package says it does and declares it`);

// ── 4. the POMs: coordinates, the reactor, and the versionless entries consumers rely on ───────────────
const rootPomPath = join(root, 'pom.xml');
let rootPom = readFileSync(rootPomPath, 'utf8');
for (const module of MODULES) {
    if (rootPom.includes(`<module>${module.newDir}</module>`)) continue;
    say(`root pom: <module>${module.dir}</module> -> ${module.newDir}, and the managed ${module.artifactId}`);
    rootPom = mustReplace(rootPom, `<module>${module.dir}</module>`, `<module>${module.newDir}</module>`, 'the root POM module list');
    rootPom = mustReplace(rootPom,
        `                <artifactId>${module.dir.split('/').pop()}</artifactId>`,
        `                <artifactId>${module.artifactId}</artifactId>`, 'the root POM dependencyManagement');
}
rootPom = rootPom.replace('Referenced versionless by project-automation and metadata-mcp-server.',
    'Referenced versionless by project-automation and jcodebuddy-meta-mcp (both were `metadata-*` before step 3.0m).');
if (!dryRun) writeFileSync(rootPomPath, rootPom);

/** Every POM that names one of the old artifactIds, minus the root's (handled above). */
const pomFiles = tracked('*pom.xml').filter((f) => f !== 'pom.xml');
for (const file of pomFiles) {
    const path = join(root, file);
    const text = readFileSync(path, 'utf8');
    let updated = text;
    for (const module of MODULES) {
        const oldArtifact = module.dir.split('/').pop();
        updated = updated.split(`<artifactId>${oldArtifact}</artifactId>`).join(`<artifactId>${module.artifactId}</artifactId>`);
        updated = updated.split(`<name>${oldArtifact}</name>`).join(`<name>${module.name}</name>`);
        updated = updated.split(`<module>${module.dir}</module>`).join(`<module>${module.newDir}</module>`);
    }
    if (updated !== text) {
        say(`${file}: artifactId/name updated`);
        if (!dryRun) writeFileSync(path, updated);
    }
}

// ── 5. the check this step is gated on: nothing that compiles still names the old package ──────────────
const leftovers = [];
for (const file of [...tracked('*.java'), ...tracked('pom.xml')]) {
    if (file.startsWith('proto/')) continue;
    if (readFileSync(join(root, file), 'utf8').includes(OLD_PACKAGE)) leftovers.push(file);
}
console.log(`\n  ${leftovers.length === 0 ? 'no Java source or POM names ' + OLD_PACKAGE + ' any more'
    : 'STILL NAMING the old package:\n    ' + leftovers.join('\n    ')}`);
for (const module of MODULES) {
    if (!existsSync(join(root, `${module.newDir}/src`))) throw new Error(`${module.newDir}/src is missing`);
}
say('both modules have their sources under the new name');
console.log('  by hand next: module-map.md, README.md, project-automation/README.md, metadata-arena/README.md,');
console.log('                the DEC-W00x records + DEC-037/038/039, decisions/README.md, the plan');
console.log(dryRun ? 'DRY RUN complete' : 'APPLIED');
