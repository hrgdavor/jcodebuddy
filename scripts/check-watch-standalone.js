#!/usr/bin/env bun
/**
 * `java-watch*` is standalone: it must not know about Jackson, OpenRewrite, or anything else from this
 * workspace.
 *
 * The rule, as given by the maintainer on 2026-10-02:
 *
 *   > java-watch* must not know about jackson or openrewrite or anything else from this workspace
 *
 * What that forbids, precisely — because a boundary nobody can check is the one that rots:
 *
 *   1. a **dependency** in a `watch/*` POM on a workspace artifact (`hr.hrg.jcodebuddy:*`,
 *      `hr.hrg.hipster:*`, or any `jcodebuddy-*` / `hipster-*` / `metadata-*` / `jwa-*` artifactId), on
 *      Jackson (`com.fasterxml.jackson*`, `tools.jackson*`) or on OpenRewrite (`org.openrewrite*`);
 *   2. a **source import** of `hr.hrg.jcodebuddy.*` or `hr.hrg.hipster.*` in a `watch/*` module;
 *   3. Jackson/OpenRewrite imports anywhere in `watch/*`.
 *
 * What it does NOT forbid, and saying so matters: the shared **build parent** (`jcodebuddy-parent`) is a
 * build relationship — version management for plugins and test libraries — and adds no code dependency, so
 * every reactor module inherits it and this check does not flag it. The `java-watch*` family may depend on
 * itself, and on ordinary third-party libraries it chooses for itself (`directory-watcher`, `wyhash`,
 * `jsch*`, `ecj`, `slf4j`).
 *
 * The watcher knows a directory changed; it decides nothing about Java. A port the watcher needs must be
 * **the watcher's own** (`ActionTool` is), with the adapter that bridges it to a JCodeBuddy SPI living in the
 * module that legitimately depends on both.
 *
 * Usage: `bun scripts/check-watch-standalone.js` — exit 0 when clean, 1 with the report otherwise.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const WATCH = join(root, 'watch');

/** Artifact families that mean "this workspace" when they appear as a dependency. */
const WORKSPACE_ARTIFACT = /^(jcodebuddy|hipster|metadata|jwa)-/;
const WORKSPACE_GROUP = /^(hr\.hrg\.jcodebuddy|hr\.hrg\.hipster)$/;
/** The library families the boundary names outright. */
const FORBIDDEN_LIBRARY = /^(com\.fasterxml\.jackson|tools\.jackson|org\.openrewrite)/;
const FORBIDDEN_LIBRARY_ARTIFACT = /^(jackson-|rewrite-)/;
/** The family itself, which is allowed. */
const WATCH_FAMILY = /^java-watch-/;

const problems = [];
const modules = readdirSync(WATCH).filter((name) => statSync(join(WATCH, name)).isDirectory());

/** The `<dependency>` blocks of a POM, as `{ groupId, artifactId }`. */
const dependenciesOf = (pom) => {
    const blocks = pom.match(/<dependency>[\s\S]*?<\/dependency>/g) ?? [];
    return blocks.map((block) => ({
        groupId: (block.match(/<groupId>([^<]+)<\/groupId>/) ?? [, ''])[1].trim(),
        artifactId: (block.match(/<artifactId>([^<]+)<\/artifactId>/) ?? [, ''])[1].trim(),
    }));
};

for (const module of modules) {
    const pomPath = join(WATCH, module, 'pom.xml');
    let pom;
    try {
        pom = readFileSync(pomPath, 'utf8');
    } catch {
        continue; // a directory without a POM is not a module
    }

    for (const dependency of dependenciesOf(pom)) {
        if (dependency.artifactId === module) {
            continue; // naming itself in its own POM is not a dependency
        }
        const workspace = WORKSPACE_GROUP.test(dependency.groupId)
            || WORKSPACE_ARTIFACT.test(dependency.artifactId);
        if (workspace && !WATCH_FAMILY.test(dependency.artifactId)) {
            problems.push(`${module}: depends on the workspace artifact `
                + `${dependency.groupId}:${dependency.artifactId}`);
        }
        const library = FORBIDDEN_LIBRARY.test(dependency.groupId)
            || FORBIDDEN_LIBRARY_ARTIFACT.test(dependency.artifactId);
        if (library) {
            problems.push(`${module}: depends on ${dependency.groupId}:${dependency.artifactId} `
                + `(Jackson or OpenRewrite, which the watcher must not know about)`);
        }
    }

    for (const source of javaFiles(join(WATCH, module))) {
        const text = readFileSync(source, 'utf8');
        const shown = relative(root, source).replace(/\\/g, '/');
        for (const line of text.split(/\r?\n/)) {
            const trimmed = line.trim();
            if (!trimmed.startsWith('import ')) {
                continue;
            }
            if (/^import (hr\.hrg\.jcodebuddy|hr\.hrg\.hipster)\./.test(trimmed)) {
                problems.push(`${shown}: imports a workspace type — ${trimmed}`);
            }
            if (/^import (com\.fasterxml\.jackson|tools\.jackson|org\.openrewrite)\./.test(trimmed)) {
                problems.push(`${shown}: imports Jackson or OpenRewrite — ${trimmed}`);
            }
        }
    }
}

function* javaFiles(directory) {
    for (const entry of readdirSync(directory)) {
        // Derived trees are not the module's sources: `target` is the build, and a dot-directory is output
        // the watcher itself wrote — `.watch/metadata/java/audit/**` holds whole source *copies* per audit
        // run, and reading those made this check report 40-odd phantom violations on its first run.
        if (entry === 'target' || entry === 'node_modules' || entry.startsWith('.')) {
            continue;
        }
        const path = join(directory, entry);
        if (statSync(path).isDirectory()) {
            yield* javaFiles(path);
        } else if (entry.endsWith('.java')) {
            yield path;
        }
    }
}

console.log(`checked ${modules.length} module(s) under watch/ — the boundary is: no Jackson, no OpenRewrite,`
    + ` nothing else from this workspace`);
if (problems.length === 0) {
    console.log('OK: java-watch* is standalone');
    process.exit(0);
}
console.log(`\nVIOLATIONS (${problems.length}):`);
for (const problem of problems) {
    console.log(`  ${problem}`);
}
process.exit(1);
