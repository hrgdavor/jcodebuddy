#!/usr/bin/env bun
/**
 * Extract the metadata engine from `hipster-entity-tooling` into `jcodebuddy-core` (plan step 3.0f-2).
 *
 * The classification this executes is recorded in `plans/unified-plan.md` under 3.0f and was read per file:
 * the class index, the content identity it is keyed by, the read/query/position/write helpers and three of
 * the twelve `meta/` files are the engine; the emitters, the rules and the other nine `meta/` files are
 * consumers and stay. Nothing here changes behaviour.
 *
 * What it does:
 *   1. moves the twelve files into `hr.hrg.jcodebuddy.engine.{index,source,meta}`;
 *   2. rewrites each moved file's `package` declaration;
 *   3. rewrites every fully qualified reference (`import` lines and `{@link}` in comments) to the new name;
 *   4. **adds the import a staying file now needs.** This is the part a naive FQN rewrite misses: the emitters
 *      called `SourceReader`, `TreeQueries` and `SourceSplicer` *unqualified*, because they shared a package
 *      with them. After the move those are different packages, so each such file needs an import that did not
 *      exist before. It is added after the last import (or after the package line), and only when the simple
 *      name actually appears and no import for it is present.
 *
 * Usage: `bun scripts/extract-engine.js --dry-run` | `bun scripts/extract-engine.js`
 */

import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const dryRun = process.argv.includes('--dry-run');

/** `[path relative to the old package root, new subpackage, class name]` — the twelve engine files. */
const MOVES = [
    ['index/ClassIndex.java', 'index', 'ClassIndex'],
    ['index/ClassRecord.java', 'index', 'ClassRecord'],
    ['index/TypeFacts.java', 'index', 'TypeFacts'],
    ['index/ContentHash.java', 'index', 'ContentHash'],
    ['index/Wyhash64.java', 'index', 'Wyhash64'],
    ['SourceReader.java', 'source', 'SourceReader'],
    ['TreeQueries.java', 'source', 'TreeQueries'],
    ['JavaSyntaxCheck.java', 'source', 'JavaSyntaxCheck'],
    ['SourceSplicer.java', 'source', 'SourceSplicer'],
    ['meta/SourceMetadata.java', 'meta', 'SourceMetadata'],
    ['meta/SourceLocation.java', 'meta', 'SourceLocation'],
    // `meta/InterfaceInfo` was classified engine and the compiler refused it: it holds `Property` and
    // `ViewAttributes`, so it is the entity model's view of an interface and stays a consumer (3.0f-2).
    //
    // `JcodebuddyDirectory` came the other way: it is where the engine's own index lives, so the marker rule
    // belongs to the engine, and the one constant it needed from the consumer (`JCODEBUDDY_DIR`) is defined
    // here now instead. It lands at the engine's root package, which is why its sub-package is empty.
    ['JcodebuddyDirectory.java', '', 'JcodebuddyDirectory'],
];

const OLD_PKG_ROOT = 'hr.hrg.hipster.entity.tooling';
const NEW_PKG_ROOT = 'hr.hrg.jcodebuddy.engine';
const OLD_SRC = 'hipster-entity/hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling';
const NEW_SRC = 'jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine';
const SKIP_DIRS = new Set(['.git', 'target', 'node_modules', '.kilo', '.jsx6', 'build', 'out', 'dist']);

/** The engine's root package has no sub-package, so the two helpers have to tolerate an empty one. */
const oldFqn = (sub, name) => (sub ? `${OLD_PKG_ROOT}.${sub}.${name}` : `${OLD_PKG_ROOT}.${name}`);
const newFqn = (sub, name) => (sub ? `${NEW_PKG_ROOT}.${sub}.${name}` : `${NEW_PKG_ROOT}.${name}`);

const javaFiles = (dir, found = []) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        if (entry.isDirectory()) {
            if (!SKIP_DIRS.has(entry.name)) javaFiles(join(dir, entry.name), found);
        } else if (entry.name.endsWith('.java')) {
            found.push(join(dir, entry.name));
        }
    }
    return found;
};

const movedInto = new Map(); // old relative path -> {sub, name}

if (!dryRun) {
    for (const [rel, sub, name] of MOVES) {
        const from = join(root, OLD_SRC, rel);
        const to = join(root, NEW_SRC, sub, name + '.java');
        if (!existsSync(from)) {
            console.error(`SKIP (already moved?): ${rel}`);
            continue;
        }
        if (existsSync(to)) {
            console.error(`REFUSING: ${relative(root, to)} already exists`);
            process.exit(1);
        }
        mkdirSync(dirname(to), { recursive: true });
        renameSync(from, to);
        movedInto.set(rel, { sub, name });
    }
}

// 2. the moved files' own package declarations.
//
// The *old* package comes from the file's old relative path, not from its new sub-package: the four
// `source/` helpers used to live in the tooling's root package, so looking for `...tooling.source;` found
// nothing and left them declaring the module they had just left. The move is the thing that knows both
// sides, so the rewrite happens per move.
let packageLines = 0;
for (const [rel, sub, name] of MOVES) {
    const file = join(root, NEW_SRC, sub, name + '.java');
    if (!existsSync(file)) continue;
    const before = readFileSync(file, 'utf8');
    const oldPackage = rel.includes('/') ? `${OLD_PKG_ROOT}.${rel.split('/')[0]}` : OLD_PKG_ROOT;
    const expected = sub ? `package ${NEW_PKG_ROOT}.${sub};` : `package ${NEW_PKG_ROOT};`;
    const after = before.replace(new RegExp(`^package ${oldPackage.replace(/\./g, '\\.')};`, 'm'), expected);
    if (after !== before) {
        packageLines++;
        if (!dryRun) writeFileSync(file, after);
    }
}

// 3. every fully qualified reference anywhere.
let references = 0;
for (const file of javaFiles(root)) {
    const before = readFileSync(file, 'utf8');
    let after = before;
    for (const [, sub, name] of MOVES) {
        const from = oldFqn(sub, name);
        if (after.includes(from)) {
            const count = after.split(from).length - 1;
            references += count;
            after = after.split(from).join(newFqn(sub, name));
        }
    }
    if (after !== before && !dryRun) writeFileSync(file, after);
}

// 4. the import a staying file now needs, for a class it used to share a package with.
let importsAdded = 0;
const filesWithNewImports = [];
if (!dryRun) {
    for (const file of javaFiles(root)) {
        const relativeToNew = relative(join(root, NEW_SRC), file).split(sep).join('/');
        if (!relativeToNew.startsWith('..')) continue; // moved files were handled above
        let text = readFileSync(file, 'utf8');
        // The simple name is only evidence when it appears in *code*. Three ways this went wrong before it
        // was tightened, all of them real: `java-watch-core` imports its own `hr.hrg.wyhash.Wyhash64` (same
        // simple name, different class), `jwa-builder`'s `SourceSplicer` declares a class of that very name,
        // and `metadata-server` mentioned `ContentHash` only in a javadoc `{@code}`.
        const code = text.split('\n')
            .filter((line) => {
                const t = line.trim();
                return !t.startsWith('*') && !t.startsWith('//') && !t.startsWith('/*');
            }).join('\n');
        const declaredHere = new Set();
        for (const [, , name] of MOVES) {
            if (new RegExp(`\\b(class|interface|record|enum)\\s+${name}\\b`).test(code)) declaredHere.add(name);
        }
        // An import is only ever needed by a file that **shared the class's old package** and therefore used
        // the simple name with nothing to point at. Every other reference was already an import (rewritten in
        // step 3) or a fully qualified name. Getting this wrong is not theoretical: `jwa-builder` has its own
        // `SourceSplicer` in its own package, so an import for the engine's copy both broke the build and
        // pointed the reader at the wrong class.
        const filePackage = (() => {
            const match = file.replace(/\\/g, '/').match(/\/src\/(?:main|test)\/java\/(.+)\.java$/);
            return match ? match[1].split('/').slice(0, -1).join('.') : null;
        })();
        const needed = [];
        for (const [rel, sub, name] of MOVES) {
            if (declaredHere.has(name)) continue;
            const oldPackage = rel.includes('/') ? `${OLD_PKG_ROOT}.${rel.split('/')[0]}` : OLD_PKG_ROOT;
            if (filePackage !== oldPackage) continue;
            const word = new RegExp(`\\b${name}\\b`);
            const already = new RegExp(`^import\\s+(static\\s+)?${newFqn(sub, name).replace(/\./g, '\\.')};`, 'm');
            const otherImport = new RegExp(`^import\\s+(static\\s+)?[\\w.]*\\b${name};`, 'm');
            if (word.test(code) && !already.test(text) && !otherImport.test(text)) {
                needed.push(`import ${newFqn(sub, name)};`);
            }
        }
        if (needed.length === 0) continue;
        const lines = text.split('\n');
        let lastImport = -1;
        for (let i = 0; i < lines.length; i++) {
            if (/^import\s/.test(lines[i])) lastImport = i;
        }
        let at;
        if (lastImport >= 0) {
            at = lastImport + 1;
        } else {
            const packageLine = lines.findIndex((line) => /^package\s/.test(line));
            at = packageLine >= 0 ? packageLine + 2 : 0;
        }
        lines.splice(at, 0, ...needed.sort());
        writeFileSync(file, lines.join('\n'));
        importsAdded += needed.length;
        filesWithNewImports.push(`${relative(root, file).split(sep).join('/')} (+${needed.length})`);
    }
}

console.log(`${dryRun ? 'DRY RUN' : 'EXTRACTED'} — ${MOVES.length} engine files -> ${NEW_SRC}`);
for (const [rel, sub, name] of MOVES) console.log(`  ${rel}  ->  ${sub}/${name}.java`);
console.log(`  package declarations rewritten: ${dryRun ? '(dry run)' : packageLines}`);
console.log(`  fully qualified references rewritten: ${references}`);
console.log(`  imports added for the classes that changed package: ${dryRun ? '(dry run)' : importsAdded}`);
for (const line of filesWithNewImports) console.log(`    ${line}`);

// 5. Markdown links to the moved files, in *current* documents only.
//
// The rewrite-migration trees are deliberately excluded: `doc/brainstorm/rewrite-migration/`,
// `plans/rewrite-migration/` and `scripts/rewrite-migration/` are the historical record of a finished
// migration and name the paths files had *then*. Rewriting them would falsify history, which is the one
// thing an archive must not do; a reader who follows such a path today is reading history, and the record
// says so.
const HISTORICAL = ['doc/brainstorm/rewrite-migration/', 'plans/rewrite-migration/', 'scripts/rewrite-migration/'];
const OLD_TOOLING = 'hipster-entity/hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling';
const NEW_ENGINE = 'jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine';
const PATH_REWRITES = [
    [`${OLD_TOOLING}/index/`, `${NEW_ENGINE}/index/`],
    ...[['SourceMetadata', 'meta'], ['SourceLocation', 'meta'], ['InterfaceInfo', 'meta'],
        ['SourceReader', 'source'], ['TreeQueries', 'source'], ['JavaSyntaxCheck', 'source'],
        ['SourceSplicer', 'source']].map(([name, sub]) =>
        [`${OLD_TOOLING}/${name}.java`, `${NEW_ENGINE}/${sub}/${name}.java`]),
];

if (process.argv.includes('--fix-doc-paths')) {
    const markdown = [];
    const walk = (dir) => {
        for (const entry of readdirSync(dir, { withFileTypes: true })) {
            if (entry.isDirectory()) {
                if (!SKIP_DIRS.has(entry.name)) walk(join(dir, entry.name));
            } else if (entry.name.endsWith('.md')) {
                markdown.push(join(dir, entry.name));
            }
        }
    };
    walk(root);
    let fixed = 0;
    const touched = [];
    for (const file of markdown) {
        const rel = relative(root, file).split(sep).join('/');
        if (rel.startsWith('proto/') || HISTORICAL.some((prefix) => rel.startsWith(prefix))) continue;
        const before = readFileSync(file, 'utf8');
        let after = before;
        for (const [from, to] of PATH_REWRITES) after = after.split(from).join(to);
        if (after !== before) {
            fixed += before.split('\n').filter((line, index) => line !== after.split('\n')[index]).length;
            touched.push(rel);
            if (!dryRun) writeFileSync(file, after);
        }
    }
    console.log(`  markdown links to the moved files rewritten: ${dryRun ? '(dry run)' : fixed} line(s)`);
    for (const line of touched) console.log(`    ${line}`);
}

console.log('  next: jcodebuddy-core needs OpenRewrite + Jackson, and jcodebuddy-codegen-api needs core instead of the tooling');
