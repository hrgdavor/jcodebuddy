#!/usr/bin/env bun
/**
 * Annotation info in the core model (asked for by the maintainer 2026-10-02).
 *
 * The engine already records a declaration's kind, modifiers, file, checksum and relations; annotations are the
 * next general fact, and the one hipster-entity needs for its own entity shape (DEC-037's boundary: core records
 * that a declaration carries annotations, a consumer decides what they mean). This codemod carries the edits
 * with the reason for each, the way `apply-engine-inversions.js` does, because the reasons are the reviewable
 * part — the string replacements are not.
 *
 * The same two rules as `relations` (3.0b) apply, and for the same reasons:
 *   - the field is **always emitted**, so an older table without it reads as *not recorded* rather than *none*;
 *   - the name is recorded **as written** and resolved at query time.
 *
 * Usage: `bun scripts/add-annotations-to-index.js --dry-run` | `bun scripts/add-annotations-to-index.js`
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const dryRun = process.argv.includes('--dry-run');
const ENGINE = 'jcodebuddy/jcodebuddy-core/src/main/java/hr/hrg/jcodebuddy/engine';

/** `[file, from, to, why]` — applied in order; a `from` that is absent is reported, never guessed. */
const EDITS = [
    // ── TypeFacts: the component, its extraction, and the one factory that composes facts ────────────────
    [`${ENGINE}/index/TypeFacts.java`,
        ` * @param relations the type's supertypes, {@code extends} clause first and then {@code implements}, as
 *                  {@link TypeRelation}s — the names as written, since a row is a fact about one declaration
 *                  and resolving a name needs the whole index (DEC-029's relation half, plan step 3.0b)
 */
public record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                        List<TypeRelation> relations) {`,
        ` * @param relations the type's supertypes, {@code extends} clause first and then {@code implements}, as
 *                  {@link TypeRelation}s — the names as written, since a row is a fact about one declaration
 *                  and resolving a name needs the whole index (DEC-029's relation half, plan step 3.0b)
 * @param annotations the annotations on the declaration, as written, with their arguments as written — the
 *                  declaration's own text, never an interpretation of it ({@link TypeAnnotation})
 */
public record TypeFacts(String fqn, String kind, List<String> modifiers, String enclosing, int line, int depth,
                        List<TypeRelation> relations, List<TypeAnnotation> annotations) {`,
        'the facts of a declaration include what annotates it'],

    [`${ENGINE}/index/TypeFacts.java`,
        `        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }`,
        `        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }`,
        'a null annotation list is "none written", not a crash'],

    [`${ENGINE}/index/TypeFacts.java`,
        `        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with the type's relations (plan step 3.0b). */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations) {`,
        `        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, List.of(), List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with the type's relations (plan step 3.0b). */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations) {
        return of(packageName, simpleName, enclosingNames, kind, modifiers, line, relations, List.of());
    }

    /** {@link #of(String, String, List, String, List, int)} with relations and annotations. */
    public static TypeFacts of(String packageName, String simpleName, List<String> enclosingNames,
                               String kind, List<String> modifiers, int line, List<TypeRelation> relations,
                               List<TypeAnnotation> annotations) {`,
        'callers that know no annotations keep working; the full factory is the one to reach for'],

    [`${ENGINE}/index/TypeFacts.java`,
        `                enclosingNames == null ? 0 : enclosingNames.size(),
                relations);`,
        `                enclosingNames == null ? 0 : enclosingNames.size(),
                relations,
                annotations);`,
        'the composed facts carry the annotations too'],

    [`${ENGINE}/index/TypeFacts.java`,
        `                TreeQueries.lineOfChained(declaration, enclosingChain, source),
                relationsOf(declaration, TypeKinds.kindOf(declaration)));
    }`,
        `                TreeQueries.lineOfChained(declaration, enclosingChain, source),
                relationsOf(declaration, TypeKinds.kindOf(declaration)),
                annotationsOf(declaration));
    }

    /**
     * The annotations on a declaration, as written, in declaration order (asked for 2026-10-02).
     *
     * <p>Names come from {@link TreeQueries#annotationName}, which is the same reading the entity tooling's own
     * annotation queries use, so there is one answer to "what is this annotation called" rather than two.
     * Arguments come from {@link TreeQueries#expressionText} and are deliberately <em>not</em> evaluated: the
     * engine records what the source says, and a consumer that needs a value has the type to read it with.
     * A single {@code J.Empty} is what an annotation with an empty parameter list parses to (DEC-030's trap for
     * methods, and the same shape here), so it is skipped rather than recorded as one empty argument.</p>
     */
    public static List<TypeAnnotation> annotationsOf(J.ClassDeclaration declaration) {
        if (declaration == null) {
            return List.of();
        }
        List<J.Annotation> annotations = declaration.getLeadingAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return List.of();
        }
        List<TypeAnnotation> written = new ArrayList<>(annotations.size());
        for (J.Annotation annotation : annotations) {
            List<String> arguments = new ArrayList<>();
            List<org.openrewrite.java.tree.Expression> writtenArguments = annotation.getArguments();
            if (writtenArguments != null) {
                for (org.openrewrite.java.tree.Expression argument : writtenArguments) {
                    if (argument instanceof J.Empty) {
                        continue;
                    }
                    arguments.add(TreeQueries.expressionText(argument));
                }
            }
            written.add(new TypeAnnotation(TreeQueries.annotationName(annotation), arguments));
        }
        return List.copyOf(written);
    }`,
        'the extraction: name from the one resolver, arguments as text, J.Empty skipped'],

    // ── ClassRecord: the row carries it ──────────────────────────────────────────────────────────────────
    [`${ENGINE}/index/ClassRecord.java`,
        ` *                         always emits the field (an empty array) and the distinction stops mattering for new
 *                         tables
 */`,
        ` *                         always emits the field (an empty array) and the distinction stops mattering for new
 *                         tables
 * @param annotations      the annotations on the declaration, as written with their arguments as written, empty
 *                         for a declaration that carries none — always emitted, for the same not-recorded
 *                         reason as {@code relations} ({@link TypeAnnotation})
 */`,
        'the row documents its new field'],

    [`${ENGINE}/index/ClassRecord.java`,
        `                          long size, List<TypeRelation> relations) {

    public ClassRecord {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }`,
        `                          long size, List<TypeRelation> relations, List<TypeAnnotation> annotations) {

    public ClassRecord {
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        relations = relations == null ? List.of() : List.copyOf(relations);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }`,
        'the row component, and a null list meaning none written'],

    [`${ENGINE}/index/ClassRecord.java`,
        `                hashCalculatedAt, size, relations);
    }`,
        `                hashCalculatedAt, size, relations, annotations);
    }`,
        'file facts do not disturb annotations'],

    [`${ENGINE}/index/ClassRecord.java`,
        `                && relations.equals(other.relations);
    }`,
        `                && relations.equals(other.relations)
                // An annotation change is a type-fact change for the same reason a relation change is: adding
                // @Deprecated to a type whose bytes are otherwise identical must not look unchanged.
                && annotations.equals(other.annotations);
    }`,
        'a new annotation is a type-fact change'],

    // ── ClassIndex: the pass threads it, the writer emits it, the reader reads it ────────────────────────
    [`${ENGINE}/index/ClassIndex.java`,
        `                    type.enclosing(), type.line(), type.depth(), generated, "", null, -1L, type.relations()));`,
        `                    type.enclosing(), type.line(), type.depth(), generated, "", null, -1L, type.relations(),
                    type.annotations()));`,
        'the pass carries what it read into the row'],

    [`${ENGINE}/index/ClassIndex.java`,
        `            sb.append("{ \\"name\\": \\"").append(MetadataJson.escape(relation.name()))
                    .append("\\", \\"kind\\": \\"").append(relation.kind().json()).append("\\" }");
        }
        sb.append("]");`,
        `            sb.append("{ \\"name\\": \\"").append(MetadataJson.escape(relation.name()))
                    .append("\\", \\"kind\\": \\"").append(relation.kind().json()).append("\\" }");
        }
        sb.append("]");
        // Always emitted, like relations and for the same reason: a table without the field is one written
        // before annotations were recorded, and "not recorded" must not read as "carries none".
        sb.append(", \\"annotations\\": [");
        for (int i = 0; i < row.annotations().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            TypeAnnotation annotation = row.annotations().get(i);
            sb.append("{ \\"name\\": \\"").append(MetadataJson.escape(annotation.name())).append("\\", \\"args\\": [");
            for (int a = 0; a < annotation.arguments().size(); a++) {
                if (a > 0) {
                    sb.append(", ");
                }
                sb.append("\\"").append(MetadataJson.escape(annotation.arguments().get(a))).append("\\"");
            }
            sb.append("] }");
        }
        sb.append("]");`,
        'the writer always emits annotations, arguments as text'],

    [`${ENGINE}/index/ClassIndex.java`,
        `                relations.add(new TypeRelation(relation.path("name").asText(""), kind));
            }
            index.put(new ClassRecord(entry.getKey(), node.path("path").asText(""),`,
        `                relations.add(new TypeRelation(relation.path("name").asText(""), kind));
            }
            List<TypeAnnotation> annotations = new ArrayList<>();
            for (JsonNode annotation : node.path("annotations")) {
                List<String> arguments = new ArrayList<>();
                for (JsonNode argument : annotation.path("args")) {
                    arguments.add(argument.asText(""));
                }
                annotations.add(new TypeAnnotation(annotation.path("name").asText(""), arguments));
            }
            index.put(new ClassRecord(entry.getKey(), node.path("path").asText(""),`,
        'the reader reads them back, arguments in order'],

    [`${ENGINE}/index/ClassIndex.java`,
        `                    node.path("hashCalculatedAt").asText(null), node.path("size").asLong(-1L), relations));`,
        `                    node.path("hashCalculatedAt").asText(null), node.path("size").asLong(-1L), relations,
                    annotations));`,
        'and hands them to the row'],
];

let applied = 0;
const missing = [];
for (const [file, from, to, why] of EDITS) {
    const path = join(root, file);
    if (!existsSync(path)) {
        missing.push(`${file}: missing file`);
        continue;
    }
    const text = readFileSync(path, 'utf8');
    const eol = text.includes('\r\n') ? '\r\n' : '\n';
    const replacement = to.split('\n').join(eol);
    if (text.includes(replacement)) {
        applied++;
        continue;
    }
    const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const pattern = new RegExp(from.split('\n').map(escapeRegExp).join('\\r?\\n'));
    if (!pattern.test(text)) {
        missing.push(`${file}: pattern not found — ${why}`);
        continue;
    }
    if (!dryRun) {
        writeFileSync(path, text.replace(pattern, replacement));
    }
    applied++;
    console.log(`  ${dryRun ? 'would edit' : 'edited'} ${file} — ${why}`);
}

console.log(`${dryRun ? 'DRY RUN' : 'APPLIED'}: ${applied}/${EDITS.length} edit(s)`);
if (missing.length > 0) {
    console.log(`MISSING (${missing.length}) — fix by hand:`);
    for (const line of missing) {
        console.log(`  ${line}`);
    }
    process.exit(1);
}
