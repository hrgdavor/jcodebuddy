#!/usr/bin/env bun
/**
 * Step 3.0l: extract the marker leaf out of `jcodebuddy-core` (DEC-038 decision 1).
 *
 * WHY A LEAF. DEC-035's consumers are not generators: an external linter, a migration tool, an IDE
 * inspection, an AI agent reading a diff. What they need is "where does generated code stop", and since
 * 3.0f the module that answers it also carries OpenRewrite and Jackson, because that module became the
 * metadata engine (DEC-037). A tool that only wants the spans should resolve neither. So the vocabulary and
 * its parser move to `jcodebuddy-generated`, which has **no compile dependency at all** — the three types
 * import nothing but `java.util`, which is why this is a module split and not a library extraction.
 *
 * WHY THE PACKAGE DOES NOT CHANGE. They keep `hr.hrg.jcodebuddy.generated`, so no consumer edits an import
 * and the diff is a POM, a reactor entry and a gate line. Same choice as 3.0i, and here it costs nothing at
 * all: nothing outside the package referenced these types.
 *
 * WHAT THE SCRIPT CHANGES, in order: moves the three types and their three tests; writes the leaf POM; adds
 * the module to the root POM and to `dependencyManagement` (a dependent declares it without a version);
 * gives the two dependents their dependency; names the leaf in `GATE_MODULES` and in `GateContractTest`'s
 * recorded list. Prose (module-map, DEC-035, DEC-038, the plan) is edited by hand, where a reviewer can read
 * it.
 *
 * Usage: `bun scripts/extract-marker-leaf.js --dry-run` | `bun scripts/extract-marker-leaf.js`
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const dryRun = process.argv.includes('--dry-run');

const MODULE = 'jcodebuddy/jcodebuddy-generated';
const ARTIFACT = 'jcodebuddy-generated';
const PKG = 'hr/hrg/jcodebuddy/generated';
const CORE = 'jcodebuddy/jcodebuddy-core';

/** The three types and the three tests that are theirs. All pure JDK; nothing else references them. */
const FILES = [
    'GeneratedBlock.java',
    'GeneratedCodeMarkers.java',
    'GeneratedCodeParser.java',
    'GeneratedCodeMarkersTest.java',
    'GeneratedCodeParserTest.java',
    'GeneratedFilesInThisRepositoryTest.java',
];

const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
const say = (message) => console.log(`  ${dryRun ? 'would ' : ''}${message}`);
const eolOf = (text) => (text.includes('\r\n') ? '\r\n' : '\n');
const withEol = (text, template) => template.replace(/\n/g, eolOf(text));

const mustReplace = (text, from, to, what) => {
    if (!text.includes(from)) throw new Error(`expected text not found in ${what}:\n${from.slice(0, 200)}`);
    return text.replace(from, to);
};

// ── 1. the three types and their tests move, package unchanged ─────────────────────────────────────────
for (const file of FILES) {
    const isTest = file.endsWith('Test.java');
    const kind = isTest ? 'test' : 'main';
    const from = `${CORE}/src/${kind}/java/${PKG}/${file}`;
    const to = `${MODULE}/src/${kind}/java/${PKG}/${file}`;
    if (!existsSync(join(root, from))) {
        say(`${file}: already moved`);
        continue;
    }
    say(`${file} -> ${MODULE} (src/${kind})`);
    if (!dryRun) {
        mkdirSync(join(root, `${MODULE}/src/${kind}/java/${PKG}`), { recursive: true });
        git('mv', from, to);
    }
}

// ── 2. the leaf's POM: no compile dependency, and the reason written where it applies ──────────────────
const leafPomPath = join(root, `${MODULE}/pom.xml`);
if (!existsSync(leafPomPath) || dryRun) {
    say(`write ${MODULE}/pom.xml (a leaf: JUnit in test scope and nothing else)`);
    if (!dryRun) {
        mkdirSync(join(root, MODULE), { recursive: true });
        writeFileSync(leafPomPath, `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>hr.hrg.jcodebuddy</groupId>
        <artifactId>jcodebuddy-parent</artifactId>
        <version>1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>${ARTIFACT}</artifactId>
    <packaging>jar</packaging>

    <name>JCodeBuddy Generated</name>
    <description>
        The generated-code marker vocabulary and its parser: how a generator spells a marker, and how a tool
        that is not the generator finds the regions it wrote (DEC-035).
    </description>

    <!--
        WHY THIS MODULE EXISTS, AND WHY IT IS A LEAF.

        Generated code is cooperative (DEC-020): a file can be partly generated and partly hand-written, and
        the boundary used to be knowable only to the generator, because the recogniser WAS the generator.
        Every other reader — an external linter, a migration tool, an IDE inspection, an AI agent reading a
        diff — needs that boundary and has no access to a generator's internals. DEC-035 fixes the vocabulary;
        this module is the parser that reads it.

        The three types import nothing but \`java.util\`, and they used to live in \`jcodebuddy-core\`. That
        module became the metadata engine (DEC-037) and took OpenRewrite and Jackson with it, which is the
        point of the engine and also the reason the vocabulary cannot stay there: a tool that only wants to
        know where generated code stops must not resolve a Java parser or a JSON library. Being a leaf here is
        therefore a property, not a preference, and DEC-038 decision 1 is what separated the two.

        Nothing in this module may grow a dependency without that sentence being rewritten first. If a marker
        rule needs the engine's model, the rule belongs in the engine, and the leaf keeps answering only
        "where does it stop".
    -->

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
`);
    }
} else {
    say(`${MODULE}/pom.xml already exists`);
}

// ── 3. the root POM: the module, and the version its dependents rely on ────────────────────────────────
const rootPomPath = join(root, 'pom.xml');
let rootPom = readFileSync(rootPomPath, 'utf8');

const coreModuleLine = '        <module>jcodebuddy/jcodebuddy-core</module>';
if (!rootPom.includes(`<module>${MODULE}</module>`)) {
    say('root pom: add the leaf to the reactor, next to the engine it left');
    rootPom = mustReplace(rootPom, coreModuleLine,
        `${coreModuleLine}${eolOf(rootPom)}        <!-- The marker vocabulary and its parser, as a leaf: DEC-035's readers are not generators, and\n`
        + `             the engine they used to sit in carries OpenRewrite and Jackson (step 3.0l). -->\n`
        + `        <module>${MODULE}</module>`.replace(/\n/g, eolOf(rootPom)),
        'the root POM module list');
}

const managedCore = withEol(rootPom, `            <dependency>
                <groupId>hr.hrg.jcodebuddy</groupId>
                <artifactId>jcodebuddy-core</artifactId>
                <version>\${project.version}</version>
            </dependency>
`);
if (!rootPom.includes(`<artifactId>${ARTIFACT}</artifactId>`)) {
    say('root pom: manage the leaf, so a dependent declares it without a version');
    rootPom = mustReplace(rootPom, managedCore,
        managedCore + withEol(rootPom, `            <dependency>
                <groupId>hr.hrg.jcodebuddy</groupId>
                <artifactId>${ARTIFACT}</artifactId>
                <version>\${project.version}</version>
            </dependency>
`), 'the root POM dependencyManagement');
}

if (!dryRun) writeFileSync(rootPomPath, rootPom);

// ── 4. the two dependents are edited by hand, and this is why ──────────────────────────────────────────
// `hipster-entity-tooling`'s POM already carries a comment describing *this leaf* — written when the leaf was
// only planned — sitting above its `jcodebuddy-core` dependency, and `hipster-ioc-tooling`'s names
// `GeneratedCodeMarkers` as a reason for depending on the engine. Both comments therefore have to move to the
// artifact they are about, which is a sentence-level judgement rather than a block insertion. The script names
// the files and leaves them to the edit that follows, instead of guessing where the prose belongs.
for (const file of ['hipster-entity/hipster-entity-tooling/pom.xml', 'hipster-ioc/hipster-ioc-tooling/pom.xml']) {
    const already = readFileSync(join(root, file), 'utf8').includes(`<artifactId>${ARTIFACT}</artifactId>`);
    say(`${file}: ${already ? 'already depends on the leaf' : 'needs the leaf dependency, and its comment ' +
        'split between the leaf and the engine (by hand)'}`);
}

// ── 5. the gate names the leaf, and GateContractTest records that it does ──────────────────────────────
const gatePath = join(root, 'scripts/lib/gate.js');
let gate = readFileSync(gatePath, 'utf8');
if (!gate.includes(`'${ARTIFACT}'`)) {
    say('scripts/lib/gate.js: name the leaf in GATE_MODULES, and say why in the comment above it');
    gate = mustReplace(gate, `export const GATE_MODULES = [\n  'jcodebuddy-core',`,
        `export const GATE_MODULES = [\n  'jcodebuddy-core',\n  '${ARTIFACT}',`, 'scripts/lib/gate.js');
    gate = mustReplace(gate,
        ` * \`jcodebuddy-core\` is named rather than left to arrive transitively through \`hipster-entity-tooling\`'s
 * dependency on it. It would be built either way, but a module reached only as a dependency is one whose
 * tests nobody chose to run — and this module holds the generated-code parser, which is the vocabulary
 * every other consumer of generated code reads. Being in the gate is a decision, so it is written down.`,
        ` * \`jcodebuddy-core\` is named rather than left to arrive transitively through \`hipster-entity-tooling\`'s
 * dependency on it. It would be built either way, but a module reached only as a dependency is one whose
 * tests nobody chose to run — and this module is the engine every consumer reads. Being in the gate is a
 * decision, so it is written down.
 *
 * \`jcodebuddy-generated\` is named for the same reason, and it is the sharpest case of it: it holds the
 * generated-code vocabulary and its parser (DEC-035), the one thing in this reactor that a tool outside the
 * reactor reads without wanting anything else. Step 3.0l made it a leaf so that stays true; the gate keeps
 * its tests running now that it is no longer a package inside a module that is already in the set.`,
        'the GATE_MODULES comment');
    if (!dryRun) writeFileSync(gatePath, gate);
} else {
    say('scripts/lib/gate.js: already names the leaf');
}

const contractPath = (() => {
    const found = execFileSync('git', ['ls-files', '*GateContractTest.java'], { cwd: root, encoding: 'utf8' })
        .split('\n').map((f) => f.trim()).filter(Boolean);
    return found.length === 1 ? join(root, found[0]) : null;
})();
if (contractPath === null) {
    say('GateContractTest: NOT FOUND — update its recorded list by hand');
} else {
    let contract = readFileSync(contractPath, 'utf8');
    if (contract.includes(`"${ARTIFACT}"`)) {
        say('GateContractTest: already records the leaf');
    } else {
        say('GateContractTest: record the leaf in the same change that added it');
        contract = mustReplace(contract,
            `            "jcodebuddy-core", "hipster-entity-api", "hipster-entity-core", "hipster-entity-tooling",\n`
            + `            "hipster-entity-jackson", "hipster-entity-test", "hipster-entity-example");`,
            `            "jcodebuddy-core", "${ARTIFACT}", "hipster-entity-api", "hipster-entity-core",\n`
            + `            "hipster-entity-tooling", "hipster-entity-jackson", "hipster-entity-test",\n`
            + `            "hipster-entity-example");`,
            'the GateContractTest list');
        contract = mustReplace(contract,
            '"the -pl set is the recorded six modules in order (plan.dsflash 0.3)"',
            '"the -pl set is the recorded modules in order (plan.dsflash 0.3)"',
            'the GateContractTest message');
        if (!dryRun) writeFileSync(contractPath, contract);
    }
}

// ── 6. what is left to check by hand, printed rather than assumed ──────────────────────────────────────
console.log('\n  still to do by hand: module-map.md, DEC-035, DEC-038, jcodebuddy-core\'s POM prose, the plan');
console.log(dryRun ? 'DRY RUN complete' : 'APPLIED');
