#!/usr/bin/env bun
/**
 * The six inversions plan step 3.0f-2 needs, as literal text edits (DEC-037's dependency direction).
 *
 * The move alone could not compile, because five engine files reached back into consumers. Each one is fixed
 * here by the shape the plan recommended, and the *kind* of fix is different in each case — which is why this
 * is a script with the reason written next to every edit rather than a mechanical rewrite:
 *
 *   1. `MetadataLocations.kindOf` delegates to `TypeKinds` (the kind is an index row's field).
 *   2. `SourceReader` reports through `DiagnosticSink` instead of the pass's `DivergenceReporter`.
 *   3. `DivergenceReporter` implements that port (and so keeps owning DEC-022's format).
 *   4. `SourceSplicer` takes the engine's `EntryPoint`, and the emitter converts at its one call site.
 *   5. `ClassIndex` uses `MetadataJson` and `JcodebuddyDirectory` instead of the pass's statics, and the
 *      pass's own statics become delegations so nothing else moves.
 *   6. `JcodebuddyDirectory.DIR` is defined in the engine — it was the one constant the consumer supplied.
 *
 * Usage: `bun scripts/apply-engine-inversions.js --dry-run` | `bun scripts/apply-engine-inversions.js`
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const dryRun = process.argv.includes('--dry-run');

const TOOLING = 'hipster-entity/hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling';
const ENGINE = 'jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine';

/** `[file, from, to, why]` — literal replacements, applied in order; a `from` that is absent is reported. */
const EDITS = [
    // 1. the kind resolver
    [`${TOOLING}/MetadataLocations.java`,
        `    public static String kindOf(J.ClassDeclaration declaration) {
        if (declaration == null) {
            return "class";
        }
        return switch (declaration.getKind()) {
            case Enum -> "enum";
            case Record -> "record";
            case Annotation -> "annotation";
            case Interface -> "interface";
            case Class -> "class";
            default -> "class";
        };
    }`,
        `    public static String kindOf(J.ClassDeclaration declaration) {
        // One implementation, in the engine: the kind is an index row's field (DEC-029), so the resolver
        // lives with the index. This method stays because the entity model and its rules call it — and its
        // old javadoc already said it was public "only so TypeFacts can reuse it" (plan step 3.0f-2).
        return hr.hrg.jcodebuddy.engine.index.TypeKinds.kindOf(declaration);
    }`,
        'the kind is a row field, so the resolver belongs in the engine'],

    // 2. the reader's diagnostic port
    [`${ENGINE}/source/SourceReader.java`,
        'static void reportUnparseable(DivergenceReporter divergences',
        'static void reportUnparseable(DiagnosticSink divergences',
        'the engine must not report through the entity pass'],
    [`${ENGINE}/source/SourceReader.java`,
        'import hr.hrg.hipster.entity.tooling.DivergenceReporter;',
        'import hr.hrg.jcodebuddy.engine.DiagnosticSink;',
        'the import follows the parameter type'],
    [`${ENGINE}/source/SourceReader.java`,
        'import java.nio.file.Path;',
        'import java.nio.file.Path;',
        'anchor (no-op, keeps the file list explicit)'],

    // 3. the reporter implements the port
    [`${TOOLING}/DivergenceReporter.java`,
        'public final class DivergenceReporter {',
        'public final class DivergenceReporter implements hr.hrg.jcodebuddy.engine.DiagnosticSink {',
        'the pass keeps DEC-022 and implements the engine port'],

    // 4. the splice path takes the engine's member shape
    [`${ENGINE}/source/SourceSplicer.java`,
        'List<ViewInterfaceGenerator.EntryPoint> members',
        'List<EntryPoint> members',
        'a write path must not need an emitter type'],
    [`${ENGINE}/source/SourceSplicer.java`,
        'for (ViewInterfaceGenerator.EntryPoint member : members)',
        'for (EntryPoint member : members)',
        'same, in the loop'],
    [`${ENGINE}/source/SourceSplicer.java`,
        'private static String render(ViewInterfaceGenerator.EntryPoint member)',
        'private static String render(EntryPoint member)',
        'same, in the renderer'],
    [`${TOOLING}/ViewInterfaceGenerator.java`,
        'String out = SourceSplicer.withMembers(text, viewName, missing, indentOf(text, viewName));',
        `// The splice path takes the engine's member shape, so the emitter converts at this one call site and
        // its own public API (and its tests) stay as they were (plan step 3.0f-2).
        List<hr.hrg.jcodebuddy.engine.source.EntryPoint> engineMembers = missing.stream()
                .map(entry -> new hr.hrg.jcodebuddy.engine.source.EntryPoint(entry.methodName(), entry.builderType()))
                .toList();
        String out = SourceSplicer.withMembers(text, viewName, engineMembers, indentOf(text, viewName));`,
        'the emitter converts; the engine stays independent'],

    // 5. the index uses the engine's JSON vocabulary, and the pass's statics delegate to it
    [`${ENGINE}/index/ClassIndex.java`,
        'import hr.hrg.hipster.entity.tooling.EntityMetadataGenerator;\n',
        '',
        'the index may not import the pass'],
    [`${ENGINE}/index/ClassIndex.java`,
        'EntityMetadataGenerator.escapeJson(',
        'MetadataJson.escape(',
        'one escaping rule, in the engine'],
    [`${ENGINE}/index/ClassIndex.java`,
        'EntityMetadataGenerator.OBJECT_MAPPER',
        'MetadataJson.mapper()',
        'one mapper, in the engine'],
    [`${ENGINE}/index/ClassIndex.java`,
        'EntityMetadataGenerator.JCODEBUDDY_DIR',
        'JcodebuddyDirectory.DIR',
        'the marker constant is the engine’s'],
    [`${TOOLING}/EntityMetadataGenerator.java`,
        'public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();',
        'public static final ObjectMapper OBJECT_MAPPER = hr.hrg.jcodebuddy.engine.MetadataJson.mapper();',
        'the pass delegates to the engine’s mapper'],
    [`${TOOLING}/EntityMetadataGenerator.java`,
        'public static final String JCODEBUDDY_DIR = ".jcodebuddy";',
        'public static final String JCODEBUDDY_DIR = hr.hrg.jcodebuddy.engine.JcodebuddyDirectory.DIR;',
        'the constant is defined once, in the engine'],
    [`${TOOLING}/EntityMetadataGenerator.java`,
        `        return value
                .replace("\\\\", "\\\\\\\\")
                .replace("\\"", "\\\\\\"")
                .replace("\\n", "\\\\n")
                .replace("\\r", "\\\\r")
                .replace("\\t", "\\\\t");`,
        '        return hr.hrg.jcodebuddy.engine.MetadataJson.escape(value);',
        'one escaping rule, and the pass is the one that delegates'],

    // 6. the engine defines its own directory name
    [`${ENGINE}/JcodebuddyDirectory.java`,
        'public static final String DIR = EntityMetadataGenerator.JCODEBUDDY_DIR;',
        'public static final String DIR = ".jcodebuddy";',
        'the engine cannot read the constant from a consumer'],
];

/** Two edits are inserts (a file that shared a package needs an import it never had). */
const INSERTS = [
    [`${ENGINE}/source/SourceReader.java`, 'import java.nio.file.Path;',
        'import hr.hrg.jcodebuddy.engine.DiagnosticSink;',
        'the reader had no import for its reporter while they shared a package'],
    [`${ENGINE}/index/ClassIndex.java`, 'import org.openrewrite.java.tree.J;',
        'import hr.hrg.jcodebuddy.engine.MetadataJson;',
        'the index uses the engine’s JSON vocabulary now'],
];

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

let applied = 0;
const missing = [];
for (const [file, from, to, why] of EDITS) {
    const path = join(root, file);
    if (!existsSync(path)) {
        missing.push(`${file}: missing file`);
        continue;
    }
    if (from === to) continue; // the explicit anchor
    const text = readFileSync(path, 'utf8');
    // Line endings: this working tree is CRLF, so a multi-line literal pasted from a read has LF and would
    // never match. Match either, and write back the file's own ending so the edit does not mix them.
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const replacement = to.split('\n').join(eol);
    // Idempotent: a second run must not report the edits it already made as missing ones (it did once).
    if (text.includes(replacement)) {
        applied++;
        continue;
    }
    const pattern = new RegExp(from.split('\n').map(escapeRegExp).join('\\r?\\n'));
    if (!pattern.test(text)) {
        missing.push(`${file}: pattern not found — ${why}`);
        continue;
    }
    if (!dryRun) writeFileSync(path, text.replace(pattern, replacement));
    applied++;
    console.log(`  ${dryRun ? 'would edit' : 'edited'} ${file} — ${why}`);
}

for (const [file, anchor, line, why] of INSERTS) {
    const path = join(root, file);
    if (!existsSync(path)) {
        missing.push(`${file}: missing file`);
        continue;
    }
    const text = readFileSync(path, 'utf8');
    if (text.includes(line)) continue; // already there
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const lines = text.split(/\r?\n/);
    const at = lines.findIndex((candidate) => candidate.includes(anchor));
    if (at < 0) {
        missing.push(`${file}: anchor not found — ${why}`);
        continue;
    }
    lines.splice(at + 1, 0, line);
    if (!dryRun) writeFileSync(path, lines.join(eol));
    applied++;
    console.log(`  ${dryRun ? 'would insert' : 'inserted'} ${file} — ${why}`);
}

console.log(`${dryRun ? 'DRY RUN' : 'APPLIED'}: ${applied}/${EDITS.length - 1} inversion edit(s)`);
if (missing.length > 0) {
    console.log(`MISSING (${missing.length}) — fix by hand:`);
    for (const line of missing) console.log(`  ${line}`);
    process.exit(1);
}
