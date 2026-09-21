/**
 * Phase 6 curation: the two things a scanner cannot derive.
 *
 * <ol>
 *   <li><b>The queue order and per-file migration knowledge</b> — priority,
 *       risk, which OpenRewrite classes a port touches, and the concrete notes a
 *       porter needs. This is a judgement call, so it is hand-written and
 *       reviewed rather than generated.</li>
 *   <li><b>The allowlist</b> — files that legitimately keep a JavaParser
 *       reference, each with its reason and the condition that removes it.</li>
 * </ol>
 *
 * <p>The generated `Checklist.md` is inventory (measured) joined to this file
 * (judged) joined to `tracker.md` (state). A file the scanner finds but this
 * file does not name is reported as UNCLASSIFIED and fails
 * `verify-migration.js`, so the queue cannot silently fall behind the code.
 */

import { mappingFor } from './mappings.js';

/**
 * Priority tiers, in work order. The plan's own ordering (06-Migration-Checklist
 * § Step 3) is by source location; this keeps that intent and adds the
 * dependency-correct reason to start with the read/print foundation.
 */
export const PRIORITIES = ['high', 'medium', 'low'];

/**
 * Risk levels. `high` marks a file where a plausible port compiles and produces
 * wrong output, which is strictly worse than one that fails to compile.
 */
export const RISKS = ['high', 'medium', 'low'];

/**
 * A queue entry.
 *
 * @typedef {object} QueueEntry
 * @property {string} note what a porter needs to know, in one paragraph
 * @property {'high'|'medium'|'low'} priority
 * @property {'high'|'medium'|'low'} risk
 * @property {string} [riskReason] why it is risky, when it is
 * @property {string[]} [openrewrite] OpenRewrite classes the port touches
 * @property {string[]} [steps] the ordered port steps, when the file needs more
 *   than the general recipe
 * @property {string} [blocks] file(s) that cannot be ported before this one
 */

/**
 * Per-file migration knowledge, keyed by repository-relative path.
 *
 * <p>Files found by the scanner and absent here are UNCLASSIFIED — a gate
 * failure, deliberately, so that adding a JavaParser use to a new file cannot
 * quietly escape the migration.
 *
 * @type {Record<string, QueueEntry>}
 */
export const QUEUE = {
  // --------------------------------------------------- added by the first pass ---
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TreeQueries.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.JavaIsoVisitor', 'org.openrewrite.java.tree.J', 'org.openrewrite.java.tree.TypeTree'],
    note:
      'Added by the first porting pass, and the reason the rest of it was tractable. Every ported ' +
      'rule needs the same three things — a `findAll` replacement, a package name, and a kind test — ' +
      'and `J.ClassDeclaration` covers five kinds, so the kind test is where a port silently starts ' +
      'matching records and enums. It deliberately builds nothing: the generator port must stay free ' +
      'to mix `JavaTemplate` with `withXxx` construction, and a helper that owned construction would ' +
      'force that choice early. Public because the validation rules are the heaviest users and live ' +
      'in a subpackage. Two shapes it absorbs that a mechanical port gets wrong silently: an ' +
      'interface\'s `extends` clause is held in `getImplements()`, not `getExtends()`; and a supertype ' +
      'is an Identifier when bare but a ParameterizedType when generic.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JavaSyntaxCheck.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['javax.tools.JavaCompiler', 'com.sun.source.util.JavacTask'],
    note:
      'Added by the first porting pass to restore a guard OpenRewrite does not provide, and the most ' +
      'important finding of the pass: OpenRewrite **recovers** from syntax errors. On F-34\'s own ' +
      'fixture (`id(java.lang.Long.class;` inside an enum) `parseInputs` neither throws, nor attaches ' +
      'a `ParseExceptionResult` marker, nor returns anything but a well-formed `J.CompilationUnit` ' +
      'with one enum in it — so the marker check the migration guide prescribes cannot see this class ' +
      'of breakage, and a straight port silently loses the fail-safe `SourceReader` exists for. javac ' +
      'is asked instead, because OpenRewrite\'s Java parser *is* a javac front end, so this adds no ' +
      'dependency and cannot drift from the parser\'s own grammar. Syntax only: type errors are ' +
      'ignored by diagnostic code, because this reader is handed single files whose dependencies are ' +
      'not on any classpath.',
  },

  // ------------------------------------------------------------ foundation ---
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/SourceReader.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'It is the one place the tooling reads source, and its whole design is a ' +
      'fail-safe against a mis-read being mistaken for a fresh file (notes F-23, ' +
      'F-34). A port that loses the "could not read" signal reintroduces the ' +
      'silent renumbering of a persisted positional array.',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.SourceFile', 'org.openrewrite.ParseExceptionResult'],
    note:
      'Port this first: 33 of the 34 queue files reach the AST through it, so a ' +
      'mistake here is inherited everywhere. The contract to preserve is exact: a ' +
      'clean parse yields a unit; a broken file yields a distinguishable ' +
      '"unparseable" outcome, never a partial tree. JavaParser gave that for free ' +
      'via `ParseResult.isSuccessful()`; OpenRewrite needs two checks — catch the ' +
      'throw, and treat "the source file is not a `J.CompilationUnit`" as a ' +
      'failure. The `LanguageLevel.JAVA_25` pin exists because a default parser ' +
      'cannot read the generator output it produced; the replacement is the ' +
      '`rewrite-java-25` artifact, and the property to re-assert in a test is ' +
      '"a record, a switch expression and a sealed type all parse".',
    steps: [
      'Add `rewrite-core` / `rewrite-java` / `rewrite-java-25` to hipster-entity-tooling/pom.xml, parent-managed, mirroring merge-java.',
      'Replace the static `JavaParser` field with a `JavaParser.fromJavaVersion()...build()` instance; keep one shared instance per parse set and reset between sets declaring the same FQNs.',
      'Keep `Read` and its `readable()` / `ofUnparseable()` factories unchanged in shape — callers depend on the distinction, not on the parser.',
      'Map `parse(source)` to `parseInputs(...)` over a `Parser.Input`; take the first `SourceFile`, require `instanceof J.CompilationUnit`, and collect `ParseExceptionResult` markers as the unparseable reason.',
      'Re-point the JAVA_25 regression test at "these three language features parse" and confirm it fails when the parser artifact is wrong.',
    ],
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/CooperativeCodegen.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'It implements DEC-020 block preservation, whose recognition is structural ' +
      'and whose range/comment inputs are exactly what the LST models differently. ' +
      'A port that compiles here can start overwriting hand-edited generated code.',
    openrewrite: ['org.openrewrite.java.tree.J', 'org.openrewrite.java.JavaIsoVisitor', 'org.openrewrite.marker.SearchResult'],
    note:
      'The riskiest file in the phase and the one to leave until last, after the ' +
      'read and print paths are proven. DEC-020 recognition is "the generator ' +
      'recognises its previous output by the same structural shape it emitted it ' +
      'with", so the port must keep recognising a block whose body a human edited. ' +
      '`Range` has no LST equivalent: JavaParser supplies line/column, the LST ' +
      'supplies character offsets that must be resolved to lines through the ' +
      '`SourceFile` text, and a location that shifts by one line changes which ' +
      'generated block is considered present. Comments are the other half — ' +
      '`com.github.javaparser.ast.comments.Comment` is a positioned node, while the ' +
      'LST keeps comment text as a prefix on the following tree. Port only with ' +
      'the DEC-020 three-state behaviour covered by a test on each side ' +
      '(present-and-tweaked, present-and-pristine, deleted).',
    steps: [
      'Port `SourceReader` first and re-run the DEC-020 tests against the unchanged CooperativeCodegen, so the read path is proven before the recognition logic moves.',
      'Replace `Range` comparisons with an offset→line helper over the `SourceFile` text, and assert the helper against a fixture whose line numbers are known.',
      'Replace comment-node reads with the LST printer’s comment accessors; add the deleted-block case to the test set before changing the recognition code.',
      'Keep the emitted hint comment (`// generated by ... — edit freely, delete to regen`) byte-identical: it is a UX aid, and DEC-020 forbids relying on it for recognition.',
    ],
    blocks: 'every generator that emits a cooperative block',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/GenLevelResolver.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration'],
    note:
      'Reads a declaration to decide the generation level. `RecordDeclaration` has ' +
      'no LST counterpart: a record is a `J.ClassDeclaration` whose kind is Record, ' +
      'so both `instanceof` branches collapse into kind tests. Small file, but it ' +
      'decides which builder levels are emitted, so a wrong kind test changes ' +
      'generated output rather than failing loudly.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/TypeLiterals.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.java.tree.J.Identifier'],
    note:
      'Supports DEC-021’s `{@link …}` first header line. Two fully-qualified ' +
      '`RecordDeclaration` references survive with no import (a half-migrated ' +
      'file), so port by FQN search, not by import list. The `{@link}` target is ' +
      'refactor-sensitive and must stay generated from the type’s own name.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/TypeFacts.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J', 'org.openrewrite.java.tree.J.Modifier'],
    note:
      'Feeds DEC-029’s class index: the per-type kind and modifiers written into ' +
      '`.jcodebuddy/index/classes.json`. The index is keyed by FQN and consumers ' +
      'read `kind` as a string, so the port must preserve the *spelling* of every ' +
      'kind it reports even though the LST discriminates differently. A changed ' +
      'spelling is a silently broken index, not a compile error. Ported: the FQN ' +
      'comes from the cursor-captured enclosing chain, and `non-sealed` is rendered ' +
      'explicitly rather than via the enum constant’s `toString()` — the latter ' +
      'would emit `NON_SEALED` and break DEC-029’s vocabulary for exactly the two ' +
      'hyphenated keywords. One caveat: `line` is -1 pending ' +
      'MIGRATION-CAVEATS.md § 4.1, because the LST exposes no positions.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/index/ClassIndex.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J', 'org.openrewrite.SourceFile'],
    note:
      'DEC-029 owner: one row per compiled type, keyed by FQN, carrying the file ' +
      'path, content checksum, checksum instant, size, kind and modifiers. It ' +
      'parses generated artifacts to describe them, so it depends on the ported ' +
      '`SourceReader`. The published contract is the JSON shape — keep it ' +
      'byte-identical and let only the tree access change. The type-collection path ' +
      'is ported (`addTypes(J.CompilationUnit, source, generated)`); a documented ' +
      'JavaParser bridge remains for `EntityMetadataGenerator`, which still holds a ' +
      'JavaParser unit, and it delegates to the same `List<TypeFacts>` overload so ' +
      'there is one index-building implementation rather than two that can drift.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/MetadataLocations.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'Location and comment semantics both change, and those are the two inputs ' +
      'this file exists to compute.',
    openrewrite: ['org.openrewrite.java.tree.J', 'org.openrewrite.java.tree.Expression', 'org.openrewrite.SourceFile'],
    note:
      'Turns a declaration into the `SourceLocation` records that DEC-028/029 ' +
      'address a member by. Twenty imports, nearly all renamed: fields become ' +
      '`J.VariableDeclarations`, enums become `J.ClassDeclaration` with ' +
      '`J.EnumValue` children, records are kind-Record class declarations, and ' +
      'comments lose their node. Treat the emitted `SourceLocation` values as the ' +
      'contract and re-verify every one against a real file before trusting the ' +
      'port; a location that is off by a line makes the generated HTML report link ' +
      'to the wrong member, which is what DEC-029’s link verification exists to ' +
      'catch.',
  },

  // ------------------------------------------------------------- generators ---
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/EntityMetadataGenerator.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'The largest generator (3487 lines) and the module’s shaded main class; it ' +
      'mixes parsing, annotation reading and node construction, so it needs all ' +
      'three ported foundations at once.',
    openrewrite: ['org.openrewrite.java.tree.J', 'org.openrewrite.java.tree.J.Annotation', 'org.openrewrite.java.JavaIsoVisitor'],
    note:
      'Split the port rather than attempting it whole: the annotation-reading half ' +
      'is mechanical (AnnotationExpr → J.Annotation, MemberValuePair → ' +
      'J.Assignment, NameExpr/SimpleName → J.Identifier), while the node-emitting ' +
      'half is a rewrite because the LST is immutable. Decide the emission ' +
      'strategy deliberately — `JavaTemplate` for readable generated source, or ' +
      '`withXxx` builders where a template is not worth it — and record the choice, ' +
      'because mixing the two styles in one generator is how this becomes ' +
      'unmaintainable.',
    steps: [
      'Port the annotation readers first and cover them with the existing tests; they are behaviour-preserving.',
      'Choose and record the emission strategy for the whole module before porting any emitter.',
      'Port one generated artifact end-to-end and diff its output against the JavaParser result byte-for-byte.',
      'Only then port the remaining artifacts, one diff at a time.',
    ],
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/FieldBoilerplateGenerator.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      '35 JavaParser imports and an array-construction path (ArrayCreationLevel / ' +
      'ArrayCreationExpr / ArrayInitializer) whose LST shape differs most from ' +
      'JavaParser’s.',
    openrewrite: ['org.openrewrite.java.tree.J.NewArray', 'org.openrewrite.java.tree.J.Literal', 'org.openrewrite.java.template.JavaTemplate'],
    note:
      'The module’s broadest single surface. Two specifics: `NodeList.nodeList(...)` ' +
      'construction becomes immutable list construction plus `withXxx` calls, and ' +
      'the array-default path collapses three JavaParser nodes onto one `J.NewArray`. ' +
      'The emitted `get(int)` methods use switch *expressions* ' +
      '(`J.SwitchExpression`, distinct from the statement `J.Switch`), and the ' +
      'default values are string literals — keep escape sequences exactly as ' +
      'emitted, since OpenRewrite normalises literals and a changed escape is a ' +
      'changed generated file rather than a changed tree.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ValidationGenerator.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.java.tree.J.Annotation', 'org.openrewrite.java.tree.J.Assignment'],
    note:
      'Reads constraint annotations off methods. Note `J.Annotation.getArguments()` ' +
      'normalises the single-element form `@Foo(Bar.class)` into an assignment named ' +
      '`value`, where JavaParser leaves the name implicit — a reader keyed by pair ' +
      'name must handle that or it will miss the common single-argument case.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewInterfaceGenerator.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.java.tree.J.NewClass'],
    note:
      'The one generator whose output is *added to an existing file*, so it is the ' +
      'clearest test of the print path: `LexicalPreservingPrinter` disappears ' +
      'because the LST preserves surrounding formatting by construction. The ' +
      '`ThisExpr`/`ObjectCreationExpr`/`ReturnStmt`/`BlockStmt` construction of the ' +
      'entry-point method becomes immutable node construction.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewBuilderGenerator.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.SourceFile'],
    note:
      'No imports at all: its three references are fully qualified inline ' +
      '(`com.github.javaparser.ast.CompilationUnit cu = read.unit();`), so a port ' +
      'driven by the import list would miss this file entirely. That is the case ' +
      'the scanner’s `qualified-only` classification exists to catch.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReader.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.Annotation', 'org.openrewrite.java.tree.J.Assignment', 'org.openrewrite.java.tree.Expression'],
    note:
      'Reads `@View` attributes: annotation arguments, array initialisers, class ' +
      'literals and enum-constant references. `ClassExpr` is the weakest mapping in ' +
      'the table (a class literal is modelled in expression position rather than by ' +
      'a dedicated node) — confirm that one against the LST reference before porting ' +
      'the rest, because several attributes are class-valued.',
  },

  // ----------------------------------------------------------- validators ----
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRulesValidator.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.SourceFile', 'org.openrewrite.ParseExceptionResult', 'org.openrewrite.java.tree.J'],
    note:
      'The validator entry point: it takes a parsed unit and runs the rules, so it ' +
      'is the seam where `ParseResult` becomes the `ParseExceptionResult` marker ' +
      'check. Port it with `SourceReader` so the rules receive one unit type.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/MarkerEntityRule.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.J.MethodDeclaration'],
    note:
      'The other rule that renames across the board: interfaces become ' +
      '`J.ClassDeclaration` with an interface kind, and methods lose nothing but ' +
      '`getNameAsString()`. Together with `EntityRulesValidator` it is the template ' +
      'for the remaining rules — port these two first, then the rest are copies.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewInterfaceRule.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.TypeTree'],
    note:
      'Interface + supertype check: `ClassOrInterfaceType` splits into ' +
      '`J.Identifier` (bare) or `J.ParameterizedType` (generic), so a supertype ' +
      'comparison must handle both spellings of the same type.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/ViewAnnotationRule.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.Annotation', 'org.openrewrite.java.tree.J.ClassDeclaration'],
    note:
      'Annotation presence and attribute checks, same shape as MarkerEntityRule ' +
      'plus annotation arguments. Apply the single-argument `value` normalisation ' +
      'noted in ValidationGenerator.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/AuditableRule.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration'],
    note: 'Smallest rule (24 lines): one interface + one method lookup. Port it first as the pattern check.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityRule.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.CompilationUnit'],
    note: 'The rule interface itself — one `CompilationUnit` parameter. Changing this signature changes every rule, so port it at the same time as the first rule, not before.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EntityFieldEnumOrderRule.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.CompilationUnit'],
    note: 'Order check over fields; follows the MarkerEntityRule template.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumConstantOrderChecker.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'It derives enum constant order — a persisted positional contract — and reads ' +
      'comments to hold that order. Both inputs change shape in the LST.',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.J.EnumValue'],
    note:
      'Enum constants are `J.EnumValue` entries inside the class body’s statement ' +
      'list rather than a dedicated constant list, and the comment that anchors the ' +
      'order is no longer a node. Since the compaction output is a positional array, ' +
      'an off-by-one in the constant list silently renumbers persisted data — port ' +
      'this with the compaction round-trip test as the gate, not a compile check.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCli.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'Same persisted-order hazard as EnumConstantOrderChecker, plus it prints whole ' +
      'files through the pretty printer, so its output diff is expected to change.',
    openrewrite: ['org.openrewrite.java.tree.J.EnumValue', 'org.openrewrite.java.tree.J.SwitchExpression', 'org.openrewrite.java.tree.J.Case'],
    note:
      'The one file where dropping `PrettyPrinterConfiguration` changes the artifact: ' +
      'it formats a complete file rather than preserving it, so the compaction CLI’s ' +
      'output diff must be reviewed and accepted deliberately. `SwitchEntry` maps to ' +
      '`J.Case`, which covers both `case X:` and the arrow form — confirm label ' +
      'extraction on both, since the CLI matches arms by literal.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/SourceQuery.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.SourceFile'],
    note:
      'Ported and renamed in the first pass — it was `JavaParserTool`, which is no longer a truthful ' +
      'name for a class that builds OpenRewrite trees, and a grep for `JavaParser` landing on the ' +
      'tooling\'s own query helper is a trap. The port also tightened one behaviour: the old code ' +
      'called `result.getResult().orElseThrow(...)`, which accepted a *partial* tree (the F-34 ' +
      'hazard); the read now goes through `SourceReader`, so an unreadable file fails explicitly. ' +
      'It is kept rather than deleted because it is a public entry point of the module, even though ' +
      'nothing in this repository calls it.',
  },

  // -------------------------------------------------------- project automation
  // No QUEUE entries for project-automation. The `hr.hrg.rewrite` staging package
  // contains no `com.github.javaparser` reference at all — it was written against
  // a mixture of OpenRewrite and invented types — so it is not migration work.
  // It is Phase-0 repair work; see PREREQUISITES P0-1..P0-3 below. Note the trap
  // that made an earlier draft of this file list it: `org.openrewrite.java.JavaParser`
  // contains the substring "javaparser", so a case-insensitive `grep javaparser`
  // reports the already-converted staging package as unmigrated.

  // ------------------------------------------------------------- jwa-builder ---
  'jwa-builder/src/main/java/hr/hrg/watch2/builder/RecordBuilderProcessor.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'It is a build-time processor: its input is generated record source and its ' +
      'output is edited source, so a port that reformats output breaks the build for ' +
      'every consumer of the module.',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.J.MethodDeclaration'],
    note:
      'Record support is the crux: `RecordDeclaration` becomes a kind-Record ' +
      '`J.ClassDeclaration` whose components live on the primary constructor. ' +
      'Depends on hipster-entity-tooling only for tooling classes, not for the ' +
      'parser, so it needs its own `rewrite-java-25` dependency.',
  },
  'jwa-builder/src/main/java/hr/hrg/watch2/builder/BuilderTransformationEngine.java': {
    priority: 'high',
    risk: 'high',
    riskReason:
      'Lexical preservation is the file’s entire purpose; the LST guarantees it ' +
      'differently, and `requirePrintEqualsInput` will reject non-idempotent output.',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.java.tree.J.ClassDeclaration'],
    note:
      'Removes a `LexicalPreservingPrinter` round trip. The replacement guarantee is ' +
      'weaker in one respect and stronger in another: weaker because the LST printer ' +
      'reformats anything it believes it owns, stronger because OpenRewrite verifies ' +
      'print-idempotency and fails loudly rather than silently reformatting. Keep ' +
      'that verification on here — this is generation, not fragment analysis.',
  },
  'jwa-builder/src/test/java/hr/hrg/watch2/builder/RecordBuilderProcessorTest.java': {
    priority: 'medium',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.SourceFile'],
    note:
      'Test-side port. It asserts on `LexicalPreservingPrinter` output; rewrite the ' +
      'assertion as "the transformed source equals the expected source" and let the ' +
      'print-idempotency check carry the formatting guarantee.',
  },

  // -------------------------------------------------------- java-watch-agent --
  'java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/ContextualAnalyzer.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration', 'org.openrewrite.java.tree.J'],
    note:
      'Analyses a file for context: types, records and members. Record handling ' +
      'collapses into the kind test, and `Node`-typed traversal should become a ' +
      '`JavaIsoVisitor` rather than a hand-rolled parent walk.',
  },
  'java-watch-agent/src/main/java/hr/hrg/watch2/agent/core/JavaParserFactory.java': {
    priority: 'high',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.JavaParser'],
    note:
      'Twenty lines, and the natural first port in this module: it owns parser ' +
      'construction, so every other file in the module inherits the change. Rename ' +
      'it in the same commit — a class named `JavaParserFactory` that builds ' +
      'OpenRewrite parsers is a trap for the next reader.',
  },
  'java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/AccessorGenerator.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.java.tree.J.VariableDeclarations'],
    note:
      'One of three near-identical tools (Accessor/Builder/Constructor) sharing a ' +
      'shape: parse, find a class, read fields, add methods. Port the trio together ' +
      'and prove the shared helper once; `BodyDeclaration` has no LST supertype, so ' +
      'each filter becomes a predicate over `J`.',
  },
  'java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/BuilderGenerator.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.java.tree.J.VariableDeclarations'],
    note: 'See AccessorGenerator — identical shape, port as one unit with it.',
  },
  'java-watch-agent/src/main/java/hr/hrg/watch2/agent/tools/ConstructorGenerator.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.tree.J.MethodDeclaration', 'org.openrewrite.java.tree.J.VariableDeclarations'],
    note:
      'See AccessorGenerator. Constructors are `J.MethodDeclaration` with ' +
      '`isConstructor()`, so any `instanceof ConstructorDeclaration` becomes that ' +
      'test.',
  },

  // ------------------------------------------------------------- jwa-sidecar --
  'jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java': {
    priority: 'high',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.SourceFile'],
    note:
      'The LSP text-document service. The plan lists this under the module ' +
      '`webview-jetbrains`, which does not exist; the real path is `jwa-sidecar`. ' +
      'It needs only record detection, so the parse itself is the whole port — but ' +
      'it is on an interactive path, where a parser built per request would be a ' +
      'latency regression. Keep the parser shared and reset it between parse sets.',
  },

  // ------------------------------------------------------------------ tests ---
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ViewAnnotationReaderTest.java': {
    priority: 'medium',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.java.tree.J.Annotation'],
    note: 'Test-side port; follows the main-source annotation mapping verbatim.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/index/TypeFactsTest.java': {
    priority: 'medium',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J'],
    note:
      'Ported, and now exercising the production route rather than a parallel one: it parses ' +
      'through `SourceReader.readSourceText` and walks `TreeQueries.typesWithEnclosing`, the same ' +
      'calls `ClassIndex.collectTypes` makes — so it cannot pass while the index is broken. Its ' +
      'three line assertions are suspended at `-1` with the requirement written out beside them, so ' +
      'finishing the position matching (MIGRATION-CAVEATS.md § 4.1) is a deliberate change to this ' +
      'expectation rather than a silent behaviour shift.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/AddonAndInheritanceTest.java': {
    priority: 'medium',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.EnumValue'],
    note:
      'Fully-qualified `CompilationUnit` / `EnumConstantDeclaration` uses with no ' +
      'imports, including a `cu.findAll(...)` call that becomes a visitor walk.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/CompactionRoundTripTest.java': {
    priority: 'medium',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.java.tree.J.EnumValue'],
    note:
      'The round-trip gate for enum compaction, and the test that should catch a ' +
      'constant-order port error. Keep it passing at every step of the ' +
      'EnumCompactionCli port rather than porting it afterwards.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/validation/EnumCompactionCliTest.java': {
    priority: 'medium',
    risk: 'medium',
    openrewrite: ['org.openrewrite.java.JavaParser', 'org.openrewrite.java.tree.J.EnumValue'],
    note: 'Same shape as CompactionRoundTripTest; port with the CLI, not after it.',
  },

  // ------------------------------------------------------------------- low ----
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/InterfaceInfo.java': {
    priority: 'low',
    risk: 'low',
    openrewrite: ['org.openrewrite.java.tree.J.ClassDeclaration'],
    note:
      'Two fully-qualified `ClassOrInterfaceDeclaration` parameters, no imports. It ' +
      'sits in the `meta` package, which is otherwise JavaParser-free, so it is the ' +
      'one file keeping that package off the finished list.',
  },
};

/**
 * Files that legitimately keep a JavaParser reference. Each entry must say why
 * and what removes it; an allowlist without an exit condition is a hole.
 *
 * @type {Record<string, {reason: string, deferredTo: string, status: 'exempt'}>}
 */
export const ALLOWLIST = {
  'project-automation/src/main/java/hr/hrg/rewrite/**': {
    status: 'exempt',
    reason:
      'The staged ports. Every file here is already written against OpenRewrite and ' +
      'mentions JavaParser only in a provenance annotation — ' +
      '`<p>Original JavaParser location: <path></p>` or "maintains the JavaParser API ' +
      'while internally using OpenRewrite". Those annotations are the DEC-019 ' +
      'requirement that a reader can navigate from the ported file back to what it ' +
      'replaced, so they must not be "cleaned up" as leftover JavaParser usage. The ' +
      'same comment also explains why a case-insensitive grep is misleading here: the ' +
      'package has no `com.github.javaparser` reference at all.',
    deferredTo:
      'Never for the provenance annotation — it is the navigational half of the port. ' +
      'The package itself is blocked on prerequisites P0-1..P0-3 (it does not compile), ' +
      'which is Phase-0 repair work and not a Phase 6 migration.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DependencyBoundaryTest.java': {
    status: 'exempt',
    reason:
      'References JavaParser by bare name only — in prose, and in ' +
      '`javaParserIsPinnedOnceInTheRootPom()`, which asserts that the tooling POM ' +
      'declares `javaparser-core` with no version of its own and that the root POM ' +
      'is the single place the version property appears. That test is load-bearing ' +
      'for notes F-23: the local repository holds eleven JavaParser versions, an ' +
      'ad-hoc classpath picked an old one, and the generator silently produced ' +
      'nothing for five example files. It also polices the pom-dependency warning ' +
      '`verify-migration.js` raises, so it is the file that tracks the removal.',
    deferredTo:
      'Retire in the commit that removes `javaparser-core` from the tree: the ' +
      'single-sourcing property becomes vacuous when there is no dependency to ' +
      'single-source. Until then, deleting the test would remove the guard that ' +
      'keeps the version pinned while the migration is in flight.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/SourceReaderTest.java': {
    status: 'exempt',
    reason:
      'Asserts the configured parser language level is `ParserConfiguration.LanguageLevel.JAVA_25` ' +
      '(by fully-qualified name, with no import). That assertion is the regression ' +
      'guard for notes F-23/F-34 — the whole reason the generator can read its own ' +
      'output. The JavaParser constant is the thing under test, so removing the ' +
      'reference removes the guard.',
    deferredTo:
      'Stays as long as javaparser-core remains on the module classpath. When the ' +
      'dependency is finally dropped this test must first be re-expressed as ' +
      '"a record, a switch expression and a sealed type all parse cleanly", which ' +
      'preserves the property without naming the library.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DependencyBoundaryTest.java': {
    status: 'exempt',
    reason:
      'Mentions JavaParser only in a comment. It is the test that asserts the ' +
      'module’s dependency boundary, so it is the file that will police the ' +
      'JavaParser removal.',
    deferredTo: 'Never — a comment naming the dependency it polices is correct.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/JdkImportSupport.java': {
    status: 'exempt',
    reason:
      'One prose mention, explaining why a general solution is out of scope: ' +
      '"The general solution is JavaParser\'s symbol solver". It names the library ' +
      'as the thing *not* being used, so it carries no dependency.',
    deferredTo:
      'Remove the sentence when JavaParser leaves the tree: the symbol solver it ' +
      'declines to use will no longer exist to decline.',
  },
  'hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/meta/FieldConstraint.java': {
    status: 'exempt',
    reason:
      'One prose mention in a doc comment ("read with JavaParser exactly as …"), ' +
      'describing how the constraint annotations are interpreted. It does not name ' +
      'a JavaParser type.',
    deferredTo:
      'Reword to name the LST instead, in whichever edit next touches that comment.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/DivergenceKindTest.java': {
    status: 'exempt',
    reason:
      'One prose mention explaining a platform-dependent expectation: "The emitter ' +
      'prints through JavaParser, which uses the platform line separator". It names ' +
      'the printer as the cause of a Windows-vs-Linux difference.',
    deferredTo:
      'Reword when the emitter prints through the LST: the property under test (the ' +
      'platform line separator) stays, only its cause changes.',
  },
  'hipster-entity-tooling/src/test/java/hr/hrg/hipster/entity/tooling/ParseGuardTest.java': {
    status: 'exempt',
    reason:
      'One prose mention in a comment recording why the guard exists: "JavaParser ' +
      'returns a PARTIAL unit for this, which is how the …". It documents the ' +
      'JavaParser behaviour the fail-safe was written against.',
    deferredTo:
      'Stays while the guard exists: the comment is the record of the failure mode ' +
      '(a partial unit mistaken for a readable file) that the port must not ' +
      'reintroduce. Update the wording when the parser changes, not the test.',
  },

  // The already-ported reference implementation. These files use OpenRewrite and
  // mention JavaParser only while explaining a JavaParser behaviour they had to
  // replace, or in an `{@link JavaParser}` that resolves to OpenRewrite's own
  // class. They are the *destination* of this migration, so they are not work.
  'merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java': {
    status: 'exempt',
    reason:
      'The reference port: it is the file this migration’s `verified` mappings are ' +
      'evidenced from. Its bare-name mentions are an `{@link JavaParser}` that ' +
      'resolves to `org.openrewrite.java.JavaParser`, plus prose explaining the ' +
      'parser-reuse rule it had to discover (a JavaParser caches parsed sources and ' +
      'refuses a second set declaring the same FQNs).',
    deferredTo:
      'Never. This is the destination, not the source: it is what the queue is being ' +
      'ported towards.',
  },
};

/**
 * OpenRewrite classpath every ported module needs. Recorded here so the checklist
 * states one version set rather than each file stating its own.
 *
 * @type {Array<{artifact: string, version: string, why: string}>}
 */
export const DEPENDENCIES = [
  {
    artifact: 'org.openrewrite:rewrite-core',
    version: '(parent-managed)',
    why: 'LST, visitors, execution context, markers.',
  },
  {
    artifact: 'org.openrewrite:rewrite-java',
    version: '(parent-managed)',
    why: 'The Java LST types (`J`), `JavaIsoVisitor`, `org.openrewrite.java.JavaParser`.',
  },
  {
    artifact: 'org.openrewrite:rewrite-java-25',
    version: '8.90.4 (pinned, not parent-managed)',
    why:
      'The version-specific parser implementation. `rewrite-java` is a facade and ' +
      'needs exactly one of these; -25 is the newest in 8.90.4 and matches the ' +
      'parent’s `maven.compiler.release=25`.',
  },
];

/**
 * Ordered Phase-0 prerequisites: things that must be true before any file in the
 * queue can be ported. Discovered by running the tooling, not assumed.
 *
 * @type {Array<{id: string, title: string, why: string, evidence: string, done: boolean}>}
 */
export const PREREQUISITES = [
  {
    id: 'P0-1',
    title: 'Repair project-automation staging code so the module compiles',
    why:
      'A clean compile of the module fails. Stale class files made a plain ' +
      '`compile` report success ("Nothing to compile - all classes are up to ' +
      'date"), so the breakage is invisible until someone cleans — the same ' +
      'stale-class hazard the repo records as note F-47.',
    evidence:
      'project-automation/src/main/java/hr/hrg/rewrite/tooling/OpenRewriteValidationGenerator.java:83 ' +
      '— `sb.append("}")\\n\\n");` is an unbalanced close paren; the escape never ' +
      'reaches the string. OpenRewriteFieldBoilerplateGenerator.java:131 — ' +
      '`sb.append("    public String ").append(property.name()).append "() {\\n");` ' +
      'is missing the parens on the second `.append`.',
    done: false,
  },
  {
    id: 'P0-2',
    title: 'Re-point the hr.hrg.rewrite package at the real OpenRewrite API',
    why:
      'Behind P0-1’s syntax errors the package references types that do not exist: ' +
      '`hr.hrg.hipster.entity.tooling.TypeTree`, `InterfaceTree`, `MethodTree`, ' +
      '`ClassTree`. The real names are `org.openrewrite.java.tree.TypeTree` and the ' +
      '`J.*` node types; there is no `MethodTree` at all. It also calls ' +
      '`SourceReader.readText()` (package-private) and `getTypes()`/`addMember()` on ' +
      'JavaParser’s `CompilationUnit` as though it were an LST.',
    evidence:
      'OpenRewriteViewInterfaceGenerator.java:141-221, OpenRewriteViewBuilderGenerator.java:132, ' +
      'plus the same pattern across hr/hrg/rewrite/validation/*.',
    done: false,
  },
  {
    id: 'P0-3',
    title: 'Add the OpenRewrite dependency set to project-automation',
    why:
      'The module declares no `org.openrewrite` dependency at all, so even once ' +
      'P0-2 is fixed the `hr.hrg.rewrite` package has no OpenRewrite API to compile ' +
      'against. Without this, Phase 5’s automation engine cannot be wired into the ' +
      'module Phase 6 depends on.',
    evidence: 'project-automation/pom.xml declares javaparser-core (line 42) and no OpenRewrite artifact.',
    done: false,
  },
  {
    id: 'P0-4',
    title: 'Establish the clean-build baseline for every module in the queue',
    why:
      'A port cannot be verified against a build that was already broken. Record ' +
      'the JavaParser-era result of `scripts\\mvn-jdk25.cmd` per module before the ' +
      'first port, so a later failure is attributable.',
    evidence:
      'Measured 2026-09-21: hipster-entity-tooling compiles clean on JDK 25 ' +
      '(`clean compile`, BUILD SUCCESS); project-automation does not (7 errors). ' +
      'jwa-builder and jwa-sidecar still need a recorded baseline.',
    done: false,
  },
  {
    id: 'P0-5',
    title: 'Reconcile the plan’s file list with the tree',
    why:
      'The plan names paths that do not exist and omits files that do, so following ' +
      'it literally migrates the wrong set.',
    evidence:
      'Listed: `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/HttpBridgeStartupActivity.java` ' +
      'and `webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/JwaTextDocumentService.java` — ' +
      'neither exists; the real file is ' +
      'jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/JwaTextDocumentService.java. ' +
      'Also listed `hipster-entity-tooling/.../validation/JavaParserTool.java` under ' +
      'test with an extra `validation/EnumCompactionCliTest.java`; the real ' +
      'JavaParserTool is main-source and there is exactly one EnumCompactionCliTest. ' +
      'Omitted entirely: the six files whose JavaParser use is fully qualified and ' +
      'therefore invisible to the plan’s own scan — ' +
      'ViewBuilderGenerator.java and meta/InterfaceInfo.java in main, plus ' +
      'AddonAndInheritanceTest, CompactionRoundTripTest, SourceReaderTest and ' +
      'validation/EnumCompactionCliTest in test. `project-automation` is listed as ' +
      '"all files using JavaParser", but its `hr.hrg.rewrite` package uses none.',
    done: false,
  },
];

/** Look up a queue entry, or `null`. */
export function queueEntryFor(repoPath) {
  return QUEUE[repoPath] ?? null;
}

/**
 * Compile an allowlist key into a matcher. A key containing `*` is a glob
 * (`**` crosses directory separators, `*` does not); anything else must match
 * exactly.
 *
 * @param {string} key
 * @returns {(repoPath: string) => boolean}
 */
function allowlistMatcher(key) {
  if (!key.includes('*')) {
    return (repoPath) => repoPath === key;
  }
  const pattern = key
    .split('/')
    .map((segment) => {
      if (segment === '**') {
        return '.*';
      }
      return segment
        .split('*')
        .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
        .join('[^/]*');
    })
    .join('/');
  const regex = new RegExp(`^${pattern}$`);
  return (repoPath) => regex.test(repoPath);
}

const ALLOWLIST_MATCHERS = Object.keys(ALLOWLIST).map((key) => [key, allowlistMatcher(key)]);

/**
 * Look up the allowlist entry covering a path, or `null`.
 *
 * <p>A glob key lets one reviewed rationale cover a whole tree. That is the right
 * shape when every file in the tree carries the same provenance annotation, and
 * the wrong shape when they differ — so a glob key must still state its reason
 * precisely enough that a reader can tell which files it is claiming.
 *
 * @param {string} repoPath
 */
export function allowlistEntryFor(repoPath) {
  for (const [key, matches] of ALLOWLIST_MATCHERS) {
    if (matches(repoPath)) {
      return { ...ALLOWLIST[key], matchedKey: key };
    }
  }
  return null;
}

/** The mapping status of an FQN, or `unknown` when it is not in the table. */
export function mappingStatusOf(fqn) {
  return mappingFor(fqn)?.status ?? 'unknown';
}
