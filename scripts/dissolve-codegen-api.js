#!/usr/bin/env bun
/**
 * Step 3.0i: dissolve `jcodebuddy-codegen-api` into the engine (DEC-037 decision 2).
 *
 * WHAT MOVES, AND WHY THE PACKAGE DOES NOT CHANGE. The five types become the engine's own SPI package
 * (`hr.hrg.jcodebuddy.codegen`), because moving them *between modules* while keeping the package means no
 * consumer changes an import — the churn a rename would cause buys nothing, and `CodeContext`'s
 * `SourceMetadata` edge stops being a cross-module edge the moment both sides live in `jcodebuddy-core`
 * (which is exactly what DEC-037 predicted would break the cycle).
 *
 * THE FINDING THIS SCRIPT ALSO FIXES. `watch/java-watch-agent` declared a dependency on the module whose POM
 * comment says "the types it needs are now a library of their own" — and its sources import nothing from it.
 * The `CodeGenerator` implementation it once needed (`ActionToolAdapter`) lives in `project-automation`. So the
 * dissolution does not give the watcher library an engine dependency; it removes a stale one. Without noticing
 * that, "dissolve the API into the engine" would have looked like it forced `java-watch*` to pull OpenRewrite —
 * and DEC-038 keeps `java-watch*` an independent library.
 *
 * Usage: `bun scripts/dissolve-codegen-api.js --dry-run` | `bun scripts/dissolve-codegen-api.js`
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const dryRun = process.argv.includes('--dry-run');
const MODULE = 'jcodebuddy/jcodebuddy-codegen-api';
const PKG = 'hr/hrg/jcodebuddy/codegen';
const FROM = `${MODULE}/src/main/java/${PKG}`;
const TO = `jcodebuddy/jcodebuddy-core/src/main/java/${PKG}`;

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const say = (message) => console.log(`  ${dryRun ? 'would ' : ''}${message}`);

// ── 1. the five types move into the engine, same package ────────────────────────────────────────────────
if (existsSync(join(root, FROM))) {
    // `git mv` does not create the destination directory on every platform (it failed with "No such file or
    // directory" on Windows and aborted the first attempt before it changed anything), so the directory is
    // made first and each file is checked, which also makes a re-run after a partial move safe.
    if (!dryRun) {
        mkdirSync(join(root, TO), { recursive: true });
    }
    for (const file of readdirSync(join(root, FROM))) {
        if (!existsSync(join(root, `${FROM}/${file}`))) {
            say(`${PKG}/${file}: already moved`);
            continue;
        }
        say(`move ${PKG}/${file} -> the engine's SPI package`);
        if (!dryRun) {
            git('mv', `${FROM}/${file}`, `${TO}/${file}`);
        }
    }
} else {
    say('the five types are already in the engine (nothing to move)');
}

// ── 2. the module itself goes: its POM, its directory, and its entry in the root reactor ────────────────
if (existsSync(join(root, MODULE))) {
    say(`remove the ${MODULE} directory (git history is the archive)`);
    if (!dryRun) {
        git('rm', '-r', '--quiet', MODULE);
    }
}
const rootPomPath = join(root, 'pom.xml');
let rootPom = readFileSync(rootPomPath, 'utf8');
const before = rootPom;
rootPom = rootPom.replace(/^[ \t]*<module>jcodebuddy\/jcodebuddy-codegen-api<\/module>\r?\n/m, '');
if (rootPom !== before) {
    say('root pom: drop the module from the reactor');
    if (!dryRun) {
        writeFileSync(rootPomPath, rootPom);
    }
} else {
    say('root pom: module already absent');
}

// ── 3. every consumer of the artifact ───────────────────────────────────────────────────────────────────
/** The `<dependency>` block that names `artifactId`, or null. */
const dependencyBlock = (pom, artifactId) => {
    const blocks = pom.match(/[ \t]*<dependency>[\s\S]*?<\/dependency>\r?\n/g) ?? [];
    return blocks.find((block) => block.includes(`<artifactId>${artifactId}</artifactId>`)) ?? null;
};

const consumers = [
    // Depends on the SPI *and* already on the engine: the artifact simply becomes the engine.
    ['hipster-ioc/hipster-ioc-tooling/pom.xml', 'replace'],
    // Same, and the check below proves whether it already reaches the engine another way.
    ['project-automation/pom.xml', 'replace-or-drop'],
    // The stale one: nothing in its sources imports the SPI, so the dependency goes rather than moving.
    ['watch/java-watch-agent/pom.xml', 'drop-stale'],
];

for (const [file, mode] of consumers) {
    const path = join(root, file);
    let pom = readFileSync(path, 'utf8');
    const block = dependencyBlock(pom, 'jcodebuddy-codegen-api');
    if (block === null) {
        say(`${file}: no codegen-api dependency (already done)`);
        continue;
    }
    const alreadyEngine = pom.includes('<artifactId>jcodebuddy-core</artifactId>');
    if (mode === 'drop-stale') {
        say(`${file}: drop the dependency (its sources import nothing from it — the finding above)`);
        pom = pom.replace(block, '');
    } else if (mode === 'replace' || !alreadyEngine) {
        say(`${file}: the dependency becomes jcodebuddy-core`);
        pom = pom.replace(block, block.replace('jcodebuddy-codegen-api', 'jcodebuddy-core'));
    } else {
        say(`${file}: drop the dependency (it already depends on jcodebuddy-core)`);
        pom = pom.replace(block, '');
    }
    if (!dryRun) {
        writeFileSync(path, pom);
    }
}

// ── 4. the stale sentence in the watcher's POM, which described a state that no longer holds ────────────
const watcherPom = join(root, 'watch/java-watch-agent/pom.xml');
if (existsSync(watcherPom)) {
    let pom = readFileSync(watcherPom, 'utf8');
    const stale = /`ActionToolAdapter` implements `CodeGenerator`, and the types it needs are now a library of\r?\n\s*their own\. See AGENTS\.md § 1 and `jcodebuddy\/jcodebuddy-codegen-api\/pom\.xml`\./;
    if (stale.test(pom)) {
        say('watch/java-watch-agent/pom.xml: the comment says it needs the SPI; its sources say otherwise');
        pom = pom.replace(stale,
            '`ActionToolAdapter` (which implements `CodeGenerator`) lives in `project-automation`, so this module\n'
            + '        needs no generator SPI: the dependency it used to declare was removed on 2026-10-02 (step 3.0i, when\n'
            + '        the SPI moved into the engine) and its sources had imported nothing from it.');
        if (!dryRun) {
            writeFileSync(watcherPom, pom);
        }
    } else {
        say('watch/java-watch-agent/pom.xml: comment already updated');
    }
}

console.log(dryRun ? 'DRY RUN complete' : 'APPLIED');
