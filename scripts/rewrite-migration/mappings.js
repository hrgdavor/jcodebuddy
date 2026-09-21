/**
 * JavaParser → OpenRewrite symbol mappings.
 *
 * <p>Every `status` is either `verified` or `inferred`, and the distinction is
 * load-bearing rather than decorative:
 *
 * <ul>
 *   <li><b>verified</b> — the OpenRewrite side is used in this repository today,
 *       in {@code merge-java/src/main/java/com/codebuddy/merge/}. A port that
 *       follows a verified mapping compiles against a shape this repo has
 *       already compiled.</li>
 *   <li><b>inferred</b> — the OpenRewrite side is the documented LST type for
 *       the concept, but no file in this repository uses it yet. Treat an
 *       inferred mapping as a starting point to confirm against the OpenRewrite
 *       LST reference for the pinned version ({@link OPENREWRITE_VERSION}),
 *       never as a fact.</li>
 * </ul>
 *
 * <p>{@code evidence} records the file that proves a `verified` mapping. The
 * Phase 6 gate ({@code verify-migration.js}) does not check these, so a
 * `verified` entry that loses its evidence is a review defect, not a build
 * failure — which is why the evidence is written down here rather than held in
 * someone's head.
 */

/** The OpenRewrite version the parent POM manages. */
export const OPENREWRITE_VERSION = '8.90.4';

/** The JavaParser version still on the classpath ({@code javaparser.version}). */
export const JAVAPARSER_VERSION = '3.28.0';

/** Repository file whose OpenRewrite usage backs the `verified` mappings. */
export const EVIDENCE_FILE =
  'merge-java/src/main/java/com/codebuddy/merge/ResolvedTypeReader.java';

/**
 * The pinned upstream source, for mappings a repository file cannot evidence
 * because nothing here uses the type yet. Citing the exact tag matters: a
 * mapping verified against `main` is a claim about an unreleased version.
 */
export const EVIDENCE_UPSTREAM = `openrewrite/rewrite v${OPENREWRITE_VERSION} rewrite-java/src/main/java/org/openrewrite/java/tree/J.java`;

/**
 * One FQN's mapping. `rewrite` is the fully qualified OpenRewrite name, or a
 * short shape description when the replacement is not a single type.
 *
 * @typedef {object} Mapping
 * @property {string} rewrite
 * @property {'verified'|'inferred'} status
 * @property {string} note
 * @property {string} [evidence]
 */

/** @type {Record<string, Mapping>} */
export const MAPPINGS = {
  // ---------------------------------------------------------------- parser ---
  'com.github.javaparser.JavaParser': {
    rewrite: 'org.openrewrite.java.JavaParser',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      'Trap: OpenRewrite has its own class named `JavaParser`, reachable as ' +
      '`org.openrewrite.java.JavaParser`. Do not keep a wildcard import of ' +
      'either package. Build with the fluent entry point ' +
      '`JavaParser.fromJavaVersion().classpath(...).build()`; the result exposes ' +
      '`parseInputs(...)`, not `parse(...)`, and returns a ' +
      '`Stream<SourceFile>` that must be closed.',
  },
  'com.github.javaparser.ParserConfiguration': {
    rewrite:
      'org.openrewrite.java.JavaParser.Builder (`classpath`, `styles`, `logCompilationWarningsAndErrors`)',
    status: 'inferred',
    note:
      'There is no language-level knob and none is needed: the parser is ' +
      'selected by which `rewrite-java-<N>` artifact is on the classpath ' +
      '(`rewrite-java-25` here, pinned by `merge-java/pom.xml`). The delicate ' +
      'part of the JavaParser value is ' +
      '`LanguageLevel.JAVA_25`, chosen because an unconfigured parser reads at ' +
      'Java 11 and cannot read the generator output it produced. Confirm the ' +
      'replacement parses records, switch expressions and sealed types.',
  },
  'com.github.javaparser.ParserConfiguration.LanguageLevel': {
    rewrite: '(none — supplied by the rewrite-java-<N> artifact)',
    status: 'inferred',
    note:
      'See ParserConfiguration. A test that asserts JAVA_25 ' +
      '(hipster-entity-tooling SourceReaderTest) becomes an assertion that a ' +
      'record / switch-expression / sealed-type fixture parses cleanly, which ' +
      'is the property the constant was ever standing in for.',
  },
  'com.github.javaparser.ParseResult': {
    rewrite: 'org.openrewrite.ParseExceptionResult (a Marker)',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      'Not a wrapper type. A failed parse surfaces as a `ParseExceptionResult` ' +
      'marker found via `sourceFile.getMarkers().findAll(ParseExceptionResult.class)`. ' +
      'The JavaParser idiom `result.getResult().orElse(null)` has no equivalent ' +
      'and must become the marker check, because "the parse returned something" ' +
      'is exactly the wrong question (see SourceReader, note F-34).',
  },
  'com.github.javaparser.ParseProblemException': {
    rewrite: 'try/catch around parseInputs + the ParseExceptionResult marker',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      'Two different failure channels, both required: OpenRewrite throws where ' +
      'JavaParser was error-tolerant, and a tree that is not a ' +
      '`J.CompilationUnit` means the input did not parse.',
  },
  'com.github.javaparser.Range': {
    rewrite: '(none direct — byte offsets on the tree)',
    status: 'inferred',
    note:
      'This is the sharpest semantic loss in the port and it lands on the ' +
      'highest-risk files. JavaParser gives line/column ranges; the OpenRewrite ' +
      'LST gives character offsets that must be turned back into lines through ' +
      'the `SourceFile` text. `CooperativeCodegen` and `MetadataLocations` ' +
      'compare saved locations against parsed ones, so a location that shifts by ' +
      'one line silently changes which generated block is treated as present.',
  },
  'com.github.javaparser.ast.Node': {
    rewrite: 'org.openrewrite.Tree',
    status: 'inferred',
    note:
      '`Tree` is the supertype of every LST node. Most uses of `Node` are only ' +
      'holding a reference or walking parents; `getParentNode()` becomes ' +
      '`getPadding().getParent()`-style access through the cursor, which is the ' +
      'larger change. Prefer a `JavaIsoVisitor` over hand-rolled parent walks.',
  },

  // ------------------------------------------------------------- structure ---
  'com.github.javaparser.ast.CompilationUnit': {
    rewrite: 'org.openrewrite.java.tree.J.CompilationUnit (inside a SourceFile)',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      '`parser.parseInputs(...)` yields `SourceFile`, whose tree is a ' +
      '`J.CompilationUnit`. `getTypes()` returns the JVM list spread across ' +
      'nested `J.ClassDeclaration` / `J.InterfaceDeclaration` / ' +
      '`J.EnumDeclaration` / `J.RecordDeclaration` (a record is a ' +
      '`J.ClassDeclaration` whose `getKind()` is `Record`) — **not** the flat ' +
      '`getTypes()` that JavaParser returns at the top level only. Anywhere the ' +
      'JavaParser code relied on top-level-only types must decide explicitly ' +
      'whether nested types count.',
  },
  'com.github.javaparser.ast.NodeList': {
    rewrite: 'java.util.List (immutable)',
    status: 'inferred',
    note:
      '`NodeList.nodeList(...)` disappears. This is the deepest structural ' +
      'change in the port: an LST is immutable, so `nodeList(a, b)` becomes ' +
      '`List.of(a, b)` and every mutator becomes a `withXxx(...)` call returning ' +
      'a new node. Node construction is where the bulk of the porting effort is.',
  },
  'com.github.javaparser.ast.body.TypeDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.ClassDeclaration (all five kinds)',
    status: 'verified',
    evidence: EVIDENCE_UPSTREAM + ' (ClassDeclaration.Kind.Type)',
    note:
      'There is no `TypeDeclaration` supertype, and no per-kind declaration type ' +
      'either: `J.ClassDeclaration.getKind()` returns a `Kind.Type` whose values are ' +
      'exactly `Class, Enum, Interface, Annotation, Record`. One class, five kinds, ' +
      'discriminated by accessor rather than by Java type. So every ' +
      '`instanceof ClassOrInterfaceDeclaration` / `EnumDeclaration` / ' +
      '`RecordDeclaration` in this codebase becomes a kind test, and a filter that ' +
      'used the type becomes a predicate over `J`. This is the single most common ' +
      'compile error in the port and the reason the JavaParser idiom cannot be ' +
      'translated mechanically.',
  },
  'com.github.javaparser.ast.body.ClassOrInterfaceDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.ClassDeclaration',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      'One of the five kinds `J.ClassDeclaration` covers (see TypeDeclaration). ' +
      '`isInterface()` becomes `getKind() == J.ClassDeclaration.Kind.Type.Interface`; ' +
      '`getNameAsString()` becomes `getSimpleName()`. Because the same class covers ' +
      'records and enums, a `instanceof ClassOrInterfaceDeclaration` test that was ' +
      'meant to mean "is a class or interface" silently starts matching records too ' +
      'unless the port makes the kind explicit.',
  },
  'com.github.javaparser.ast.body.RecordDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.ClassDeclaration (kind Record)',
    status: 'inferred',
    note:
      'There is no `J.RecordDeclaration` to move to. A record is a ' +
      '`J.ClassDeclaration` with `getKind() == J.ClassDeclaration.Kind.Type.Record`, ' +
      'and its components live on `getPrimaryConstructor()` rather than on a ' +
      'record-specific accessor. Every `instanceof RecordDeclaration` in ' +
      '`GenLevelResolver`, `TypeLiterals`, `jwa-builder` and `jwa-sidecar` is ' +
      'affected.',
  },
  'com.github.javaparser.ast.body.EnumDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.ClassDeclaration (getKind() == Kind.Type.Enum)',
    status: 'verified',
    evidence: EVIDENCE_UPSTREAM + ' (ClassDeclaration.Kind.Type)',
    note:
      'Verified against the pinned source: `J.ClassDeclaration` carries a ' +
      '`Kind.Type` whose values are `Class, Enum, Interface, Annotation, Record` — ' +
      'five kinds, one class. So records, enums and annotations are *not* ' +
      'distinguishable by Java type, only by `getKind()` or the `isEnum()`-style ' +
      'accessors. Enum constants are `J.EnumValue` entries held in a ' +
      '`J.EnumValueSet` statement inside the class body — not a dedicated ' +
      'constant list, and not a `List<EnumValue>` hanging off the declaration. ' +
      'This lands on `EnumCompactionCli` and `EnumConstantOrderChecker`, where the ' +
      'constant **order** is persisted numerically, so getting the list wrong is a ' +
      'silent data corruption rather than a compile error.',
  },
  'com.github.javaparser.ast.body.EnumConstantDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.EnumValue (inside a J.EnumValueSet)',
    status: 'verified',
    evidence: EVIDENCE_UPSTREAM + ' (J.EnumValue)',
    note:
      'Verified against the pinned source. Note the different noun: a JavaParser ' +
      'constant *declaration* is an OpenRewrite enum *value*. `getNameAsString()` ' +
      'becomes `getName().getSimpleName()` — the name is a `J.Identifier`, not a ' +
      '`String` — and the constant’s arguments/body live on ' +
      '`getInitializer()` (a `J.NewClass`), so a constant with a constructor ' +
      'argument list must be read through the initializer rather than a direct ' +
      'argument accessor.',
  },
  'com.github.javaparser.ast.body.AnnotationDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.ClassDeclaration (kind Annotation)',
    status: 'inferred',
    note: 'Java has no separate `J.AnnotationDeclaration` in the LST.',
  },
  'com.github.javaparser.ast.body.BodyDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J (no single supertype for members)',
    status: 'inferred',
    note:
      'Class members are `List<J>` in `getBody().getStatements()`, mixing ' +
      'fields, methods, initialisers and nested types in one list. Filters that ' +
      'used the Java type must become predicate filters over `J`.',
  },
  'com.github.javaparser.ast.body.MethodDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.MethodDeclaration',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      '`getNameAsString()` → `getSimpleName()`; `getParameters()` → ' +
      '`getParameters()`. Two traps the repo has already hit: an empty parameter ' +
      'list is modelled as a single `J.Empty` placeholder rather than an empty ' +
      'list, and a varargs parameter is only distinguishable from its array form ' +
      'by the declaration text (`getTypeExpression().toString().contains("...")`).',
  },
  'com.github.javaparser.ast.body.ConstructorDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.MethodDeclaration (constructor)',
    status: 'inferred',
    note:
      'A constructor is a `J.MethodDeclaration` whose `isConstructor()` is true ' +
      'and whose return type is null. There is no separate constructor type, so ' +
      '`instanceof ConstructorDeclaration` must become `isConstructor()`.',
  },
  'com.github.javaparser.ast.body.FieldDeclaration': {
    rewrite: 'org.openrewrite.java.tree.J.VariableDeclarations',
    status: 'inferred',
    note:
      'The cardinality changes and this is easy to get wrong: one JavaParser ' +
      '`FieldDeclaration` (`int a, b;`) is one OpenRewrite ' +
      '`J.VariableDeclarations` carrying **two** `J.VariableDeclarator`s. Code ' +
      'counting `FieldDeclaration`s to decide "one field per column" counts ' +
      'correctly today and would undercount after a naive port. There is likewise ' +
      'no `J.FieldDeclaration`.',
  },
  'com.github.javaparser.ast.body.VariableDeclarator': {
    rewrite: 'org.openrewrite.java.tree.J.VariableDeclarator',
    status: 'inferred',
    note:
      'Same name, different owner: it hangs off `J.VariableDeclarations` and does ' +
      'not carry modifiers or annotations, which now sit on the parent.',
  },
  'com.github.javaparser.ast.body.Parameter': {
    rewrite: 'org.openrewrite.java.tree.J.VariableDeclarations',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      'A parameter is a `J.VariableDeclarations` (or `J.Empty` for the empty ' +
      'list), not a `Parameter`. The resolved type is on ' +
      '`getType()`, the declared spelling on `getTypeExpression()`.',
  },
  'com.github.javaparser.ast.Modifier': {
    rewrite: 'List<J.Modifier> on the declaration (keyword strings)',
    status: 'inferred',
    note:
      'JavaParser’s `Modifier.Keyword` enum becomes `J.Modifier` values whose ' +
      'keyword is a `J.Modifier.Type`. Prefer the boolean helpers ' +
      '(`hasModifier(J.Modifier.Type.Public)`) over comparing enum constants, ' +
      'because the LST models modifiers as an ordered list.',
  },
  'com.github.javaparser.ast.ArrayCreationLevel': {
    rewrite: 'dimension expressions on the J.NewArray node',
    status: 'inferred',
    note:
      '`J.NewArray` carries `getDimensions()` and `getInitializer()` directly; ' +
      'the separate "creation level" concept is gone. Relevant to ' +
      '`FieldBoilerplateGenerator`’s array default construction.',
  },

  // ------------------------------------------------------------ expressions ---
  'com.github.javaparser.ast.expr.Expression': {
    rewrite: 'org.openrewrite.java.tree.Expression',
    status: 'inferred',
    note: 'Closest 1:1 in the whole table; the subclasses are where it diverges.',
  },
  'com.github.javaparser.ast.expr.AnnotationExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Annotation',
    status: 'inferred',
    note:
      '`annotation.getAnnotation()` (a `Name`/`Type`) becomes ' +
      '`annotation.getAnnotationType()` (a `NameTree`). `MemberValuePair` becomes ' +
      '`J.Assignment` over `getArguments()`. A marker annotation with no ' +
      'arguments has a single `J.Empty` argument rather than an empty list.',
  },
  'com.github.javaparser.ast.expr.MemberValuePair': {
    rewrite: 'org.openrewrite.java.tree.J.Assignment',
    status: 'inferred',
    note:
      '`getNameAsString()` → `getVariable()` (an `Identifier`); ' +
      '`getValue()` → `getAssignment()`. `J.Annotation.getArguments()` also ' +
      'normalises the single-element form `@Foo(Bar.class)` into an assignment ' +
      'whose name is `value`, which JavaParser leaves implicit — any code reading ' +
      'pairs by name must account for that.',
  },
  'com.github.javaparser.ast.expr.NameExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Identifier',
    status: 'inferred',
    note:
      'There is no `J.NameExpr`. A bare identifier is a `J.Identifier`, which ' +
      'also carries an optional resolved `JavaType` — useful, but it means a ' +
      '`J.Identifier` is not always "an expression", so a filter on it can match ' +
      'in positions JavaParser never produced a `NameExpr`.',
  },
  'com.github.javaparser.ast.expr.SimpleName': {
    rewrite: 'org.openrewrite.java.tree.J.Identifier',
    status: 'inferred',
    note:
      '`SimpleName` is a *name* type in JavaParser’s hierarchy; `J.Identifier` ' +
      'is an *expression*. `asString()` / `getIdentifier()` → `getSimpleName()`. ' +
      'Code typed against `SimpleName` as a name carrier has no exact equivalent.',
  },
  'com.github.javaparser.ast.expr.FieldAccessExpr': {
    rewrite: 'org.openrewrite.java.tree.J.FieldAccess',
    status: 'inferred',
    note:
      '`getScope()` → `getTarget()`; `getName()` → `getName()`. The scope/target ' +
      'distinction matters for `MetadataLocations`, which renders locations back ' +
      'into source text.',
  },
  'com.github.javaparser.ast.expr.MethodCallExpr': {
    rewrite: 'org.openrewrite.java.tree.J.MethodInvocation',
    status: 'inferred',
    note:
      '`getScope()` → `getSelect()`. The argument list is modelled as ' +
      '`getArguments()` with `J.Empty` meaning "no arguments". Method type ' +
      'attribution (`getMethodType()`) needs a classpath, so it is null in ' +
      'fragment parsing.',
  },
  'com.github.javaparser.ast.expr.ObjectCreationExpr': {
    rewrite: 'org.openrewrite.java.tree.J.NewClass',
    status: 'inferred',
    note:
      '`getType()` → `getClazz()`; `getArguments()` → `getArguments()`; an ' +
      'anonymous class body moves to `getBody()`. Used by every generator that ' +
      'emits `new Builder()`.',
  },
  'com.github.javaparser.ast.expr.ArrayCreationExpr': {
    rewrite: 'org.openrewrite.java.tree.J.NewArray',
    status: 'inferred',
    note: 'Dimensions and initializer collapse onto one node (see ArrayCreationLevel).',
  },
  'com.github.javaparser.ast.expr.ArrayInitializerExpr': {
    rewrite: 'org.openrewrite.java.tree.J.NewArray.getInitializer()',
    status: 'inferred',
    note: '`getValues()` becomes `getInitializer().getElements()`.',
  },
  'com.github.javaparser.ast.expr.AssignExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Assignment',
    status: 'inferred',
    note: '`getTarget()` / `getValue()` survive as `getVariable()` / `getAssignment()`.',
  },
  'com.github.javaparser.ast.expr.BinaryExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Binary',
    status: 'inferred',
    note: '`getLeft()` / `getRight()` keep their names; the operator enum differs.',
  },
  'com.github.javaparser.ast.expr.ClassExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Empty (a TypeTree in expression position)',
    status: 'inferred',
    note:
      'Weakest mapping in this table and worth confirming first. A class literal ' +
      '`Foo.class` is modelled as a `J.Identifier`/`J.ParameterizedType` in ' +
      'expression position rather than by a dedicated node, and reading the ' +
      'literal back out means matching on the shape.',
  },
  'com.github.javaparser.ast.expr.StringLiteralExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Literal',
    status: 'inferred',
    note:
      'Every literal is one `J.Literal` with a `JavaType.Primitive`; ' +
      '`asString()` becomes `getValue()` plus a cast. **OpenRewrite normalises ' +
      'string literals**, so not all inputs are print-idempotent — always keep ' +
      'the escape sequences (`\\n`, `\\"`) exactly as emitted when generating ' +
      'source text, and read the idempotency section of the migration guide.',
  },
  'com.github.javaparser.ast.expr.BooleanLiteralExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Literal (JavaType.Primitive.Boolean)',
    status: 'inferred',
    note: '`getValue()` becomes `getValue()` cast to `Boolean`.',
  },
  'com.github.javaparser.ast.expr.NullLiteralExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Literal (JavaType.Primitive.Null)',
    status: 'inferred',
    note: 'One `J.Literal` node, discriminated by its primitive.',
  },
  'com.github.javaparser.ast.expr.ThisExpr': {
    rewrite: 'org.openrewrite.java.tree.J.Identifier (the `this` keyword)',
    status: 'inferred',
    note: 'No dedicated node; `this` is a keyword identifier.',
  },
  'com.github.javaparser.ast.expr.SwitchExpr': {
    rewrite: 'org.openrewrite.java.tree.J.SwitchExpression',
    status: 'inferred',
    note:
      'Distinct from `J.Switch` (the statement form) — a distinction JavaParser ' +
      'also draws, but the accessors differ. `FieldBoilerplateGenerator` emits ' +
      'switch *expressions* in its `get(int)` methods.',
  },

  // ------------------------------------------------------------ statements ---
  'com.github.javaparser.ast.stmt.Statement': {
    rewrite: 'org.openrewrite.java.tree.Statement',
    status: 'inferred',
    note: 'Closest 1:1 among the statement types.',
  },
  'com.github.javaparser.ast.stmt.BlockStmt': {
    rewrite: 'org.openrewrite.java.tree.J.Block',
    status: 'inferred',
    note:
      '`getStatements()` → `getStatements()`; the braces move to `J.Block.getEnd()` ' +
      'formatting. `new BlockStmt(nodeList(stmt))` becomes a `J.Block` built ' +
      'through its constructor or a template.',
  },
  'com.github.javaparser.ast.stmt.ExpressionStmt': {
    rewrite: 'org.openrewrite.java.tree.J.ExpressionStatement (a Tree in statement position)',
    status: 'inferred',
    note:
      'Unlike JavaParser there is no wrapper class to construct: an expression ' +
      'in statement position is held as `J`/`Tree` and distinguished by position, ' +
      'so `new ExpressionStmt(expr)` has no direct constructor equivalent.',
  },
  'com.github.javaparser.ast.stmt.ReturnStmt': {
    rewrite: 'org.openrewrite.java.tree.J.Return',
    status: 'inferred',
    note: '`getExpression()` → `getExpression()`.',
  },
  'com.github.javaparser.ast.stmt.IfStmt': {
    rewrite: 'org.openrewrite.java.tree.J.If',
    status: 'inferred',
    note:
      '`getThenStmt()` → `getThenPart()`; `getElseStmt()` → `getElsePart()`.',
  },
  'com.github.javaparser.ast.stmt.SwitchStmt': {
    rewrite: 'org.openrewrite.java.tree.J.Switch',
    status: 'inferred',
    note: 'The statement form; see SwitchExpr for the expression form.',
  },
  'com.github.javaparser.ast.stmt.SwitchEntry': {
    rewrite: 'org.openrewrite.java.tree.J.Case',
    status: 'inferred',
    note:
      'One `J.Case` per arm, with `getCaseLabels()` covering both the legacy ' +
      '`case X:` and the arrow `case X ->` forms. `EnumCompactionCli` matches ' +
      'arms by literal, so confirm label extraction on both forms.',
  },

  // --------------------------------------------------------------- other ---
  'com.github.javaparser.ast.comments.Comment': {
    rewrite: 'comment text is a prefix on the owning tree (no Comment node)',
    status: 'inferred',
    note:
      '**Highest-risk mapping in the table.** JavaParser has a real `Comment` ' +
      'node with its own range and content. The LST keeps comments as ' +
      'whitespace/comment text attached to the node that follows, reachable ' +
      'through the printer’s comment accessors, with no standalone comment tree. ' +
      '`CooperativeCodegen` recognises previously generated blocks by their ' +
      'structure (DEC-020) and `EnumConstantOrderChecker` reads comments to hold ' +
      'order; both depend on comment *identity and position*, which this change ' +
      'removes. Port these two only with the DEC-020 three-state behaviour ' +
      'covered by tests on both sides.',
  },
  'com.github.javaparser.ast.type.Type': {
    rewrite: 'org.openrewrite.java.tree.TypeTree',
    status: 'inferred',
    note:
      'Note the name collision with the invented `hr.hrg.hipster.entity.tooling.TypeTree` ' +
      'in `project-automation`: that type does not exist, and the real ' +
      '`org.openrewrite.java.tree.TypeTree` is what was meant.',
  },
  'com.github.javaparser.ast.type.ClassOrInterfaceType': {
    rewrite: 'org.openrewrite.java.tree.J.Identifier / J.ParameterizedType',
    status: 'inferred',
    note:
      'Generic arguments split a bare type name into a parameterized type, so ' +
      '`new ClassOrInterfaceType(null, "Foo")` maps to a `J.Identifier` and ' +
      '`Foo<Bar>` maps to a `J.ParameterizedType` wrapping one. A generator that ' +
      'emits a type name from a string should build an identifier, not try to ' +
      'reconstruct a parameterized type.',
  },
  'com.github.javaparser.ast.type.WildcardType': {
    rewrite: 'org.openrewrite.java.tree.J.Wildcard',
    status: 'inferred',
    note: '`getExtendedType()` / `getSuperType()` map to the bound plus a variance flag.',
  },

  // -------------------------------------------------------------- printing ---
  'com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter': {
    rewrite: '(none needed — the LST printer preserves formatting by construction)',
    status: 'verified',
    evidence: EVIDENCE_FILE,
    note:
      'This is the one place the port **removes** code rather than translating ' +
      'it. JavaParser needs opt-in lexical preservation to avoid reformatting a ' +
      'file; every LST node carries its own formatting, so a modified tree prints ' +
      'back with surrounding code untouched. Instead of preserving by default, ' +
      'OpenRewrite *verifies*: with `org.openrewrite.requirePrintEqualsInput` at ' +
      'its default, a tree whose print output differs from its input is rejected. ' +
      'That guards generation. It must be **disabled deliberately** for fragment ' +
      'analysis, because a fragment by definition does not print back to itself ' +
      '(see the evidence file) — and disabling it for generation would remove the ' +
      'guard this port depends on.',
  },
  'com.github.javaparser.printer.configuration.PrettyPrinterConfiguration': {
    rewrite: 'org.openrewrite.java.JavaPrinter (no configuration object to replace)',
    status: 'inferred',
    note:
      'There is no pretty-print configuration to mirror. Dropping it changes ' +
      'output formatting, which matters for the enum-compaction CLI: it prints a ' +
      'whole file rather than preserving it, so its output diff will change. ' +
      'Decide that diff deliberately rather than discovering it in review.',
  },
};

/**
 * The mapping for one FQN, or `null` when the type is not in the table.
 *
 * @param {string} fqn
 * @returns {Mapping|null}
 */
export function mappingFor(fqn) {
  return MAPPINGS[fqn] ?? null;
}

/** Every mapping entry as `[fqn, mapping]`, ordered by FQN. */
export function mappingEntries() {
  return Object.entries(MAPPINGS).sort(([a], [b]) => a.localeCompare(b));
}

/** Counts of `verified` versus `inferred` mappings. */
export function mappingStats() {
  const entries = mappingEntries();
  return {
    total: entries.length,
    verified: entries.filter(([, m]) => m.status === 'verified').length,
    inferred: entries.filter(([, m]) => m.status === 'inferred').length,
  };
}
