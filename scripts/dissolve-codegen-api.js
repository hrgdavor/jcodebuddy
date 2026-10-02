#!/usr/bin/env bun
/**
 * Step 3.0i: dissolve `jcodebuddy-codegen-api` into the engine (DEC-037 decision 2).
 *
 * WHAT THE MODULE WAS, AND WHAT IT BECOMES. It held exactly five types, promoted out of
 * `project-automation` so that `java-watch-agent` (a JCodeBuddy library) could implement a generator
 * without depending on a project's private dev-time assistant (AGENTS.md § 1.1, DEC-W003). DEC-037
 * decision 2 dissolves it, and the five types are **not one thing**:
 *
 *   - `TypeResolver`, `TypeDefinition`  -> `hr.hrg.jcodebuddy.engine.query`   (engine metadata queries)
 *   - `CodeGenerator`, `CodeContext`,
 *     `CodeContextImpl`                 -> `hr.hrg.jcodebuddy.engine.codegen` (the generator SPI)
 *
 * WHY THE PACKAGE CHANGES, WHICH THE FIRST VERSION OF THIS SCRIPT GOT WRONG. That version argued for
 * keeping `hr.hrg.jcodebuddy.codegen` to avoid import churn. Two things beat the argument: the engine's own
 * convention is `hr.hrg.jcodebuddy.engine.<area>`, which `index`, `source`, `meta`, `fresh` and `query`
 * already follow; and the step's own gate is "no `import hr.hrg.jcodebuddy.codegen.` left anywhere", which
 * a surviving package cannot satisfy. Keeping it would leave a module-shaped name inside a module that no
 * longer exists, and would hide the split the decision is about. The churn is two consumer files.
 *
 * WHAT THE FIRST VERSION GOT WRONG ABOUT THE WATCHER, kept because the correction is the useful part. Its
 * header claimed `watch/java-watch-agent` declared a "stale" dependency whose sources imported nothing from
 * it. That was wrong twice over: the class that implemented `CodeGenerator` was `ActionToolAdapter`, it did
 * exist, and its own test imported the SPI. The watcher's dependency was not stale — it was real, and the
 * fix was a decision (the port belongs to the watcher, the bridge ran back into it) rather than a deletion.
 * Step 3.0s then deleted `ActionToolAdapter` — nothing used it but that test — and moved the module to
 * `jcodebuddy/jcodebuddy-agent`, so by the time this script runs there is **no dependency left to drop**:
 * what remains in its POM is a comment about a dependency that no longer exists, and that is all this
 * script has to fix there. The lesson is in the order: the finding was two revisions old by the time the
 * step ran, and a script written from a stale finding would have edited a POM that was already right.
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
const CORE = 'jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy';
const ENGINE_PKG = 'hr.hrg.jcodebuddy.engine';

/** Where each of the five types goes, which is DEC-037 decision 2's split rather than a package move. */
const MOVES = [
    { file: 'TypeResolver.java', area: 'query', why: 'an engine metadata query, next to the model it describes' },
    { file: 'TypeDefinition.java', area: 'query', why: 'the shape that query answers with' },
    { file: 'CodeGenerator.java', area: 'codegen', why: 'the generator SPI the engine publishes' },
    { file: 'CodeContext.java', area: 'codegen', why: 'the one argument a generator receives' },
    { file: 'CodeContextImpl.java', area: 'codegen', why: 'the SPI context as a value' },
];

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const say = (message) => console.log(`  ${dryRun ? 'would ' : ''}${message}`);
const eolOf = (text) => (text.includes('\r\n') ? '\r\n' : '\n');
const withEol = (text, template) => template.replace(/\n/g, eolOf(text));

/** Replace, or refuse. A migration script that silently matches nothing is worse than one that fails. */
const mustReplace = (text, from, to, what) => {
    if (!text.includes(from)) {
        throw new Error(`expected text not found in ${what}:\n${from.slice(0, 200)}`);
    }
    return text.replace(from, to);
};

// ── 1. the five types move into the engine, into the package each belongs to (DEC-037 decision 2) ───────
const filesToRewrite = [];
if (existsSync(join(root, FROM))) {
    for (const move of MOVES) {
        const destination = `${CORE}/engine/${move.area}/${move.file}`;
        const target = join(root, destination);
        const source = join(root, `${FROM}/${move.file}`);
        if (!existsSync(source)) {
            say(`${move.file}: already moved`);
            continue;
        }
        say(`${move.file} -> engine/${move.area} (${move.why})`);
        if (!dryRun) {
            // `git mv` does not create the destination directory on every platform, and it aborted the
            // first attempt with "No such file or directory" before changing anything.
            mkdirSync(join(root, `${CORE}/engine/${move.area}`), { recursive: true });
            git('mv', `${FROM}/${move.file}`, destination);
        }
        filesToRewrite.push(target);
    }
} else {
    say('the five types are already in the engine (nothing to move)');
}

// ── 2. the package declaration of each moved file, and the imports the split creates ────────────────────
for (const move of MOVES) {
    const path = join(root, `${CORE}/engine/${move.area}/${move.file}`);
    if (!existsSync(path)) continue;
    let text = readFileSync(path, 'utf8');
    const before = text;

    text = mustReplace(text, 'package hr.hrg.jcodebuddy.codegen;',
        `package ${ENGINE_PKG}.${move.area};`, move.file);

    // `CodeContext` and `CodeContextImpl` both name `TypeResolver`, which shared their old package and
    // therefore had no import. It is in `engine.query` now, so each needs one.
    if (move.area === 'codegen') {
        const anchor = `import ${ENGINE_PKG}.meta.SourceMetadata;`;
        if (!text.includes(`import ${ENGINE_PKG}.query.TypeResolver;`) && text.includes(anchor)) {
            text = mustReplace(text, anchor,
                `${anchor}${eolOf(text)}import ${ENGINE_PKG}.query.TypeResolver;`, `${move.file}'s imports`);
        }
    }

    if (text !== before) {
        say(`${move.file}: package and imports rewritten`);
        if (!dryRun) writeFileSync(path, text);
    }
}

// ── 3. every remaining reference to the old package, in Java sources ────────────────────────────────────
// The class name decides the new package, so the rewrite is driven by the same split rather than by a
// blanket prefix replacement — which would put `TypeDefinition` next to the SPI it is not part of.
const OLD_PKG = 'hr\\.hrg\\.jcodebuddy\\.codegen\\.';
const REWRITES = [
    [new RegExp(`${OLD_PKG}(TypeDefinition|TypeResolver)`, 'g'), `${ENGINE_PKG}.query.$1`],
    [new RegExp(`${OLD_PKG}(CodeContextImpl|CodeContext|CodeGenerator)`, 'g'), `${ENGINE_PKG}.codegen.$1`],
];
const SKIP_DIRS = new Set(['.git', '.kilo', '.jsx6', 'node_modules', 'target', 'build', 'out', 'dist', '.jcodebuddy']);

function javaSources(dir, found = []) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (entry.isDirectory()) {
            if (SKIP_DIRS.has(entry.name)) continue;
            javaSources(join(dir, entry.name), found);
        } else if (entry.name.endsWith('.java')) {
            found.push(join(dir, entry.name));
        }
    }
    return found;
}

for (const file of javaSources(root)) {
    let text = readFileSync(file, 'utf8');
    const before = text;
    for (const [pattern, replacement] of REWRITES) {
        text = text.replace(pattern, replacement);
    }
    if (text !== before) {
        say(`${file.slice(root.length).replace(/\\/g, '/')}: re-pointed at the engine`);
        if (!dryRun) writeFileSync(file, text);
    }
}

// ── 4. the module goes: its directory, its reactor entry, its managed dependency ────────────────────────
if (existsSync(join(root, MODULE))) {
    say(`remove the ${MODULE} directory (git history is the archive)`);
    if (!dryRun) git('rm', '-r', '--quiet', MODULE);
}

const rootPomPath = join(root, 'pom.xml');
let rootPom = readFileSync(rootPomPath, 'utf8');

// The comment that sat above the two module lines described the SPI, and the SPI is `jcodebuddy-core`'s
// now, so it moves onto core rather than being deleted with the module it was written for.
const moduleBlock = withEol(rootPom, `        <!-- The generator SPI: what a code generator is, the context it is handed, and how it resolves
             a type. A leaf library, and the reason it exists is a rule rather than a feature — a tool
             that generates code must not have to depend on anybody's \`project-automation\` to do it.
             See the module's POM and AGENTS.md § 1. -->
        <module>jcodebuddy/jcodebuddy-core</module>
        <module>jcodebuddy/jcodebuddy-codegen-api</module>
`);
if (rootPom.includes(moduleBlock)) {
    say('root pom: the module leaves the reactor, and its comment attaches to the engine that absorbed it');
    rootPom = mustReplace(rootPom, moduleBlock, withEol(rootPom,
        `        <!-- The engine, and since 2026-10-03 also the home of the generator SPI (step 3.0i,
             DEC-037 decision 2): what a code generator is, the context it is handed, and how it
             resolves a type. The reason it is a library rather than a project's own is a rule, not a
             feature — a tool that generates code must not depend on anybody's \`project-automation\`. -->
        <module>jcodebuddy/jcodebuddy-core</module>
`), 'the root POM module list');
} else {
    say('root pom: module list already updated');
}

const managedBlock = withEol(rootPom, `            <dependency>
                <groupId>hr.hrg.jcodebuddy</groupId>
                <artifactId>jcodebuddy-codegen-api</artifactId>
                <version>\${project.version}</version>
            </dependency>
`);
if (rootPom.includes(managedBlock)) {
    say('root pom: the managed dependency goes with the artifact');
    rootPom = mustReplace(rootPom, managedBlock, '', 'the root POM dependencyManagement');
} else {
    say('root pom: managed entry already absent');
}

if (!dryRun) writeFileSync(rootPomPath, rootPom);

// ── 5. the consumers: what each one depended on the artifact for ───────────────────────────────────────
// `hipster-ioc-tooling` already declares `jcodebuddy-core` (for `GeneratedCodeMarkers`), so its entry is
// dropped rather than re-pointed — a second, duplicate dependency on the same artifact is a POM smell and
// Maven says so. `project-automation` reaches the engine only transitively today (through
// `hipster-entity-tooling`), which is how it already uses `SourceReader`/`TreeQueries`; naming the engine
// is both the honest replacement for the SPI it needed and the fix for that transitive reach.
// Each entry is the text to find and the text to leave, in LF form; the file's own line endings are
// applied when it is read, because this repository's checkouts are CRLF and a mixed-ending POM is a
// reviewer's noise rather than a migration's business.
const consumers = [
    {
        file: 'hipster-ioc/hipster-ioc-tooling/pom.xml',
        mode: 'drop-and-merge-comment',
        find: `        <!--
            The generator SPI, from the shared library rather than from \`project-automation\`.

            \`IocContextGenerator\` is a \`CodeGenerator\`, so the dev-time pass can offer it to every file
            without knowing what it is for, and a watch agent can apply its output through an editor. That
            interface lives in a library of its own precisely so this module can implement it without
            depending on a project's private assistant (AGENTS.md § 1/§ 1.1).
        -->
        <dependency>
            <groupId>hr.hrg.jcodebuddy</groupId>
            <artifactId>jcodebuddy-codegen-api</artifactId>
            <version>\${project.version}</version>
        </dependency>

        <!-- \`GeneratedCodeMarkers\`, the one place DEC-035's file marker is spelled. -->
`,
        leave: `        <!--
            The engine: \`GeneratedCodeMarkers\` (the one place DEC-035's file marker is spelled) and, since
            2026-10-03, the generator SPI. \`IocContextGenerator\` is a \`CodeGenerator\` from that SPI, so
            the dev-time pass can offer it to every file without knowing what it is for, and this module
            implements it without depending on a project's private assistant (AGENTS.md § 1/§ 1.1). Step
            3.0i merged the SPI's own module into this one.
        -->
`,
    },
    {
        file: 'project-automation/pom.xml',
        mode: 'replace',
        find: `        <!--
            The generator SPI, which is a shared library and not this module's own. It lives in
            \`jcodebuddy-codegen-api\` so that a tool which generates code (java-watch-agent is the one
            that forced the split) can implement a generator without depending on a project's private
            dev-time assistant. See that module's POM.
        -->
        <dependency>
            <groupId>hr.hrg.jcodebuddy</groupId>
            <artifactId>jcodebuddy-codegen-api</artifactId>
        </dependency>
`,
        leave: `        <!--
            The engine — the metadata model, the parse path and, since 2026-10-03 (step 3.0i, DEC-037
            decision 2), the generator SPI \`MetadataTypeResolver\` extends. It is a shared library and not
            this module's own, so a tool which generates code can implement a generator without depending
            on a project's private dev-time assistant. This module already used the engine's
            \`SourceReader\` and \`TreeQueries\` through \`hipster-entity-tooling\`; naming it here is the same
            dependency stated rather than inherited.
        -->
        <dependency>
            <groupId>hr.hrg.jcodebuddy</groupId>
            <artifactId>jcodebuddy-core</artifactId>
        </dependency>
`,
    },
    {
        // The agent's POM declares no SPI dependency — 3.0s deleted the class that needed one — so all that
        // is left is a comment describing a dependency that is not there.
        file: 'jcodebuddy/jcodebuddy-agent/pom.xml',
        mode: 'comment-only',
        find: `        <!--
            The generator SPI, from the shared library rather than from \`project-automation\`.

            This dependency used to name \`project-automation\`, which was wrong twice over: a JCodeBuddy
            library must never depend on a project's private dev-time assistant, and that module must
            never be installed or deployed. The dependency was only satisfiable because that artifact
            was being installed into the local repository, so the two faults concealed each other.
            \`ActionToolAdapter\` implements \`CodeGenerator\`, and the types it needs are now a library of
            their own. See AGENTS.md § 1 and \`jcodebuddy/jcodebuddy-codegen-api/pom.xml\`.
        -->
`,
        leave: `        <!--
            This module declares no generator SPI, and the comment that used to sit here described one it
            had already stopped having. The history is worth keeping straight: the class that forced the SPI
            out of \`project-automation\` was \`ActionToolAdapter\`, a bridge from the watcher's own
            \`ActionTool\` port to the JCodeBuddy \`CodeGenerator\` SPI — and step 3.0s deleted it, because
            nothing used it but its own test. Step 3.0i then dissolved the SPI's module into the engine.
            This module is not a consumer: its code-action tools drive \`jwa-builder\`'s processors directly.
        -->
`,
    },
];

for (const consumer of consumers) {
    const path = join(root, consumer.file);
    let pom = readFileSync(path, 'utf8');
    const find = withEol(pom, consumer.find);
    if (!pom.includes(find)) {
        say(`${consumer.file}: already updated`);
        continue;
    }
    say(`${consumer.file}: ${consumer.mode === 'replace'
        ? 'the SPI dependency becomes the engine'
        : consumer.mode === 'drop-and-merge-comment'
            ? 'the SPI dependency goes (the engine is already declared) and its comment merges into the engine\'s'
            : 'the comment about a dependency it no longer declares is corrected'}`);
    pom = mustReplace(pom, find, withEol(pom, consumer.leave), consumer.file);
    if (!dryRun) writeFileSync(path, pom);
}

// ── 6. the check the step's gate names, run by the script that does the move ────────────────────────────
const leftovers = javaSources(root)
    .filter((file) => readFileSync(file, 'utf8').includes('hr.hrg.jcodebuddy.codegen'))
    .map((file) => file.slice(root.length).replace(/^[\\/]/, '').replace(/\\/g, '/'));

// A dry run cannot move anything, so the old package is still there *by definition* — saying so is the
// difference between a check and a scare.
console.log(dryRun
    ? `\n(dry run: ${leftovers.length} Java sources still name the old package, which is what the move itself fixes)`
    : leftovers.length === 0
        ? '\nOK: no `hr.hrg.jcodebuddy.codegen` reference remains in any Java source.'
        : `\nSTILL REFERENCING THE OLD PACKAGE (${leftovers.length}):\n  ${leftovers.join('\n  ')}`);
console.log(dryRun ? 'DRY RUN complete' : 'APPLIED');
