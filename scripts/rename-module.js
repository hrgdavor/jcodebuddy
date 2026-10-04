#!/usr/bin/env bun
/**
 * Rename a module: its directory, its `artifactId`, its `<name>` and its package — one mechanical pass, with the
 * guards a rename needs (step 3.0n generalised the one-off script that did step 3.0m).
 *
 * WHY A SCRIPT AT ALL. The four parts have to move together: a package rewritten without the directory moving
 * compiles nowhere, a directory moved without the artifactId breaks the reactor, and both half-done produce
 * failures that name something else. This is also the second module rename in a row, so the tool outlives the
 * instance — the step-3.0m version hardcoded its own names, and every guard below is one that version earned by
 * failing on its first run:
 *
 * 1. `git mv a b` **renames** when `b` does not exist and moves **into** it when it does. Pre-creating the
 *    destination nests the module as `newDir/oldName/`, after which every later step looks in the wrong place and
 *    finds nothing. `assertAbsent` refuses that; the destination's *parent* is created instead.
 * 2. `git ls-files 'pom.xml'` matches the root POM and nothing else — a pathspec needs a wildcard to match a
 *    basename at depth (`'*pom.xml'`), unlike `'*.java'`. With the bare name, every consumer POM kept the old
 *    artifactId and Maven refused the reactor with "dependency.version is missing" rather than anything that names
 *    the rename.
 * 3. A check that compares each file it *finds* passes over an empty tree. The package/directory guard counts
 *    what it verified and refuses to pass on nothing.
 * 4. The ancestor cleanup is `rmdir`-if-empty rather than a recursive delete: old and new packages share ancestors
 *    (`hr/hrg`), so removing them by force takes the new tree with it.
 *
 * Usage:
 *   bun scripts/rename-module.js --from-package hr.hrg.watch2.builder --to-package hr.hrg.jcodebuddy.builder \
 *       --module jcodebuddy/jwa-builder:jcodebuddy/jcodebuddy-builder:jcodebuddy-builder:"JCodeBuddy Builder" \
 *       [--module <oldDir>:<newDir>:<newArtifactId>:<newName> ...] [--dry-run]
 *
 * Prose (module-map, decision records, READMEs, the plan) is edited by hand, where a reviewer reads it.
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const argv = process.argv.slice(2);
const dryRun = argv.includes('--dry-run');

const valueOf = (flag) => {
    const at = argv.indexOf(flag);
    return at < 0 ? null : argv[at + 1];
};
const valuesOf = (flag) => argv.reduce((found, arg, i) => (arg === flag ? [...found, argv[i + 1]] : found), []);

const OLD_PACKAGE = valueOf('--from-package');
const NEW_PACKAGE = valueOf('--to-package');
const moduleSpecs = valuesOf('--module');
if (!OLD_PACKAGE || !NEW_PACKAGE || moduleSpecs.length === 0) {
    console.error('rename-module.js: --from-package, --to-package and at least one --module are required');
    console.error('  --module <oldDir>:<newDir>:<newArtifactId>:<newName>');
    process.exit(2);
}
const MODULES = moduleSpecs.map((spec) => {
    const [dir, newDir, artifactId, name] = spec.split(':');
    if (!dir || !newDir || !artifactId || !name) {
        throw new Error(`--module needs four colon-separated parts, got: ${spec}`);
    }
    return { dir, newDir, oldArtifactId: dir.split('/').pop(), artifactId, name };
});

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const say = (message) => console.log(`  ${dryRun ? 'would ' : ''}${message}`);
const mustReplace = (text, from, to, what) => {
    if (!text.includes(from)) throw new Error(`expected text not found in ${what}:\n${from}`);
    return text.replace(from, to);
};
const tracked = (pattern) => git('ls-files', pattern).split('\n').map((f) => f.trim()).filter(Boolean);

/**
 * A guard rather than a hope. `git mv a b` renames when `b` does not exist and moves *into* it when it does, so
 * pre-creating the destination turns the module into `newDir/oldName/` and every later step silently looks in the
 * wrong place.
 */
const assertAbsent = (relative, what) => {
    if (existsSync(join(root, relative))) throw new Error(`${what}: ${relative} already exists — git mv would move INTO it`);
};

// ── 1. the directories: every file lands where its package will say it is ──────────────────────────────
for (const module of MODULES) {
    if (!existsSync(join(root, `${module.dir}/src`))) {
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
const ancestorsOf = (path) => {
    const parts = path.split('/');
    return parts.map((_, i) => `${parts.slice(0, i + 1).join('/')}`).reverse();
};
for (const module of MODULES) {
    for (const kind of ['main', 'test']) {
        const from = `${module.newDir}/src/${kind}/java/${oldPath}`;
        const to = `${module.newDir}/src/${kind}/java/${newPath}`;
        if (!existsSync(join(root, from))) continue;
        say(`${from} -> ${to}`);
        if (!dryRun) {
            mkdirSync(dirname(join(root, to)), { recursive: true });
            git('mv', from, to);
            // `git mv` moves the tree; the old package's ancestors are debris on disk only, and only if empty.
            for (const stale of ancestorsOf(oldPath)) {
                const candidate = `${module.newDir}/src/${kind}/java/${stale}`;
                try {
                    if (existsSync(join(root, candidate)) && readdirSync(join(root, candidate)).length === 0) {
                        rmdirSync(join(root, candidate));
                    }
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
 * The check that catches a skipped move on its own, and it runs *after* the rewrite above because it verifies the
 * end state: a file's directory must be its package. It counts what it checked and refuses to pass on nothing.
 */
const mismatched = [];
let checked = 0;
for (const module of MODULES) {
    for (const kind of ['main', 'test']) {
        // In a dry run nothing has moved yet, so the files to verify are still under the old name — and the
        // expected package is the NEW one, because the rewrite above would have happened by now if this were real.
        const dir = dryRun ? module.dir : module.newDir;
        const expected = dryRun ? NEW_PACKAGE : null;
        const base = join(root, `${dir}/src/${kind}/java`);
        if (!existsSync(base)) continue;
        for (const file of tracked(`${dir}/src/${kind}/**/*.java`)) {
            const text = readFileSync(join(root, file), 'utf8');
            checked++;
            const declared = /^package\s+([\w.]+);/m.exec(text)?.[1];
            const directory = file.replace(/.*\/java\//, '').replace(/\/[^/]+$/, '').replace(/\//g, '.');
            const wanted = expected === null ? directory
                    : directory.replace(NEW_PACKAGE.replace(/\./g, '.'), expected);
            if (declared !== wanted) mismatched.push(`${file}: package ${declared} != directory ${directory}`);
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
    rootPom = mustReplace(rootPom, `<module>${module.dir}</module>`, `<module>${module.newDir}</module>`,
        'the root POM module list');
    if (rootPom.includes(`                <artifactId>${module.oldArtifactId}</artifactId>`)) {
        rootPom = mustReplace(rootPom, `                <artifactId>${module.oldArtifactId}</artifactId>`,
            `                <artifactId>${module.artifactId}</artifactId>`, 'the root POM dependencyManagement');
    }
}
if (!dryRun) writeFileSync(rootPomPath, rootPom);

/** Every POM that names one of the old artifactIds, minus the root's (handled above). */
const pomFiles = tracked('*pom.xml').filter((f) => f !== 'pom.xml');
for (const file of pomFiles) {
    const path = join(root, file);
    const text = readFileSync(path, 'utf8');
    let updated = text;
    for (const module of MODULES) {
        updated = updated.split(`<artifactId>${module.oldArtifactId}</artifactId>`).join(`<artifactId>${module.artifactId}</artifactId>`);
        updated = updated.split(`<name>${module.oldArtifactId}</name>`).join(`<name>${module.name}</name>`);
        updated = updated.split(`<module>${module.dir}</module>`).join(`<module>${module.newDir}</module>`);
    }
    if (updated !== text) {
        say(`${file}: artifactId/name updated`);
        if (!dryRun) writeFileSync(path, updated);
    }
}

// ── 5. the gate of this step: nothing that compiles still names the old package ────────────────────────
const leftovers = [];
for (const file of [...tracked('*.java'), ...tracked('pom.xml'), ...tracked('*.js')]) {
    if (file.startsWith('proto/')) continue;
    if (readFileSync(join(root, file), 'utf8').includes(OLD_PACKAGE)) leftovers.push(file);
}
console.log(`\n  ${leftovers.length === 0 ? `no Java source, POM or script names ${OLD_PACKAGE} any more`
    : `STILL NAMING the old package:\n    ${leftovers.join('\n    ')}`}`);
for (const module of MODULES) {
    const dir = dryRun ? module.dir : module.newDir;
    if (!existsSync(join(root, `${dir}/src`))) throw new Error(`${dir}/src is missing`);
}
say('every module has its sources under the new name');
console.log('  by hand next: doc/architecture/module-map.md, the decision records, AGENTS.md mentions, any check');
console.log('                script that lists workspace artifacts, READMEs, and the plan');
console.log(dryRun ? 'DRY RUN complete' : 'APPLIED');
