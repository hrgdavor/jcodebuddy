package hr.hrg.hipster.entity.tooling;

import org.openrewrite.java.tree.J;

import hr.hrg.hipster.entity.tooling.index.ClassIndex;
import hr.hrg.hipster.entity.tooling.meta.ArtifactMeta;
import hr.hrg.hipster.entity.tooling.meta.InterfaceInfo;
import hr.hrg.hipster.entity.tooling.meta.Property;
import hr.hrg.hipster.entity.tooling.meta.SourceLocation;
import hr.hrg.hipster.entity.tooling.meta.ViewFieldMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The artifact-side half of a view's detail: which files belong to it, what each one declares, and
 * where inside it every field of the view actually is (DEC-028).
 *
 * <h3>Why the pass records this and a renderer used to guess it</h3>
 * <p>A field is in many places. It is an accessor in the interface that declares it, a constant and a
 * {@code forName} arm in the view's field enum, a component of the record materialization, and a stored
 * field, an accessor, a setter, an ordinal-switch arm and a name-switch arm in each builder. The
 * metadata recorded exactly one of those — the declaration start in the declaring interface — so the
 * only consumer that could answer "where is this field" was the Bun page, and it answered by scanning
 * the committed source and recognising members by line patterns and files by naming convention plus a
 * header comment. That works, but it is a heuristic answer to a question the pass already knows, and it
 * is a second place the same knowledge lives, able to disagree silently with the first.</p>
 *
 * <h3>The three things it produces</h3>
 * <ol>
 *   <li><strong>The artifact inventory</strong> ({@link ArtifactMeta}): the view's own file and the
 *       nested types it declares, the generated siblings the pass emitted, and the foreign declaring
 *       interfaces the view's fields reference ({@code own = false}, so a field's location map has an
 *       entry to key on without a second key space).</li>
 *   <li><strong>Per-field locations</strong>: every ({@link SourceLocation}) the pass can state — from
 *       the interface side ({@code parseProperty} recorded the declaring accessor and its
 *       {@code @FieldSource} line) merged with the artifact side (this class, reading back the files the
 *       emitters just wrote).</li>
 *   <li><strong>Per-field location maps</strong> ({@link ViewFieldMeta#at()}): artifact id → role → line,
 *       the shape the JSON writes and the page reads.</li>
 * </ol>
 *
 * <h3>Completeness and honesty</h3>
 * <p>Every role this class records is taken from a parser's positions, never from a regex: the
 * identifier's own line, not the declaration's, because an annotated accessor declares on a different
 * line than it is written on. A file it cannot read contributes nothing and is reported through the
 * existing {@code source_not_parsed} divergence — <strong>never</strong> a failed pass, because a missing
 * location is honest and a wrong one is not. A role that does not exist for a field is absent from the
 * map rather than present with a made-up line.</p>
 *
 * <p>De-duplication is on {@code (artifact, role, file, line)}: the interface-side and artifact-side
 * scans both see a view's own accessor, so the two collapse to one entry; and keying on the artifact as
 * well is what lets the <em>same</em> field appear twice in one file for two artifacts — an outer
 * interface and its nested {@code Write} both legitimately declare it.</p>
 *
 * <p>Public only so {@link hr.hrg.hipster.entity.tooling.index.TypeFacts} can reuse {@link #kindOf} — the
 * class index records a row's {@code kind} and must not grow a second kind resolver. Every other member
 * stays package-private, and the {@link Detail} record it produces is the pass's own currency.</p>
 *
 * <h3>Phase 6: where the positions come from now</h3>
 * <p>JavaParser answered every line here with {@code node.getBegin().line}. The LST exposes
 * <strong>no positions at all</strong>, so the lines come from javac's line map over the same text —
 * {@link TreeQueries#lineOf}, {@link TreeQueries#methodLineOf}, {@link TreeQueries#annotationLineOf},
 * {@link TreeQueries#memberLineOf} and {@link TreeQueries#caseLines}. Each of those carries the
 * matching rule it needs, and each returns {@code -1} when it cannot match: the fail-safe answer, which
 * this class already turns into "no location recorded" rather than a link that opens the wrong line.</p>
 *
 * <p>The roles are read from the LST — {@code J.EnumValueSet}, {@code getPrimaryConstructor()},
 * {@code J.VariableDeclarations}, {@code J.MethodDeclaration} — while the lines are read from javac.
 * That split is what keeps this class free of a second parser trying to be the first.</p>
 */
public final class MetadataLocations {

    /** The generator's artifact naming conventions, in the order the pass emits them (DEC-028 § 2.4). */
    private static final List<String> GENERATED_SUFFIXES = List.of(
            "_", "Record", "Builder", "BuilderTracking", "Validator", "RowAdapter", "Binder");

    /** The DEC-021 header's first line: {@code // {@link <fqn>} <description>}. */
    private static final Pattern DEC_021_HEADER = Pattern.compile("\\{@link\\s+([\\w.$]+)}\\s*(.*)");

    /** One type declaration found in a candidate file, with everything the inventory records. */
    private record ArtifactShape(String displayName, String kind, String file, int line, boolean generated,
                                String header) {
    }

    /** One view's detail: the artifact inventory and the per-field location maps. */
    record Detail(List<ArtifactMeta> artifacts, List<ViewFieldMeta> fields) {

        static Detail empty() {
            return new Detail(List.of(), List.of());
        }
    }

    /** One recorded location, in the currency this class works in: a display name and a file path. */
    private record Found(String artifact, String field, String role, String path, int line) {
    }

    private MetadataLocations() {
    }

    /**
     * Builds one view's detail from the files that exist for it right now.
     *
     * @param moduleRoot      the base every recorded path is relative to
     * @param javaOutputRoot  where the pass wrote its generated siblings
     * @param viewPackage     the package the view is declared in; the generated siblings sit beside it
     * @param viewName        the view's simple name
     * @param viewInfo        the view's discovered form, or {@code null} when it was not retained
     * @param fields          the view's fields in DEC-023 ledger order (what the emitters consumed)
     * @param ledgerSize      how many of those fields the field enum actually carries, so an appended
     *                        field can be recorded with no ordinal rather than a wrong one
     * @param index           the pass's class index, which the artifact files are registered with as they
     *                        are found — a document names a type, so the index must know a file's types
     *                        before any document can reference it
     * @param divergences     the pass's report; an artifact outside the module or an unreadable file is
     *                        reported here rather than dropped in silence
     */
    static Detail collect(Path moduleRoot, Path javaOutputRoot, String viewPackage, String viewName,
                          InterfaceInfo viewInfo, List<Property> fields, int ledgerSize,
                          ClassIndex index, DivergenceReporter divergences) throws IOException {
        if (fields == null || fields.isEmpty()) {
            // Nothing to locate. The inventory is still collected below when the files exist, because a
            // view with no fields still has an interface and an enum the page shows as aspects.
        }
        Set<String> fieldNames = new LinkedHashSet<>();
        if (fields != null) {
            for (Property property : fields) {
                fieldNames.add(property.name());
            }
        }

        // ── 1. the candidate files, in reading order ────────────────────────────────────────────────
        List<Path> candidates = candidateFiles(moduleRoot, javaOutputRoot, viewPackage, viewName, viewInfo);

        List<ArtifactShape> shapes = new ArrayList<>();
        List<Found> found = new ArrayList<>();
        boolean first = true;
        for (Path candidate : candidates) {
            // Only the view's OWN file contributes its nested types. The nested `record` and `Write` an
            // author declares in the view file are aspects of the view — the page has a
            // `record-component` and a `setter` column for them — while a helper class nested inside a
            // *generated* sibling is neither owned by the generator nor an aspect of the view (the
            // example's hand-written `PersonSummaryBuilderTracking.TrackingStrict`), and listing it
            // would add an artifact card for a class that has nothing to do with the field contract.
            readCandidate(moduleRoot, candidate, fieldNames, shapes, found, index, divergences, first);
            first = false;
        }

        // ── 2. the interface side: what parseProperty recorded while it had the AST ─────────────────
        for (Property property : fields) {
            for (SourceLocation location : property.locations()) {
                if (location.resolved() && location.path() != null && !location.path().isBlank()) {
                    found.add(new Found(location.artifact(), property.name(), location.role(),
                            location.path(), location.line()));
                }
            }
        }

        // ── 3. the foreign declaring interfaces, in first-reference order ───────────────────────────
        // An inherited or addon accessor lives in a file that is not the view's own and was not
        // generated for it, so its location needs an artifact entry of its own — marked `own = false`
        // — for the field's `at` map to key on.
        Map<String, ArtifactShape> byKey = new LinkedHashMap<>();
        for (ArtifactShape shape : shapes) {
            byKey.put(key(shape.displayName(), shape.file()), shape);
        }
        Map<String, Integer> idByKey = new LinkedHashMap<>();
        int nextId = 0;
        for (ArtifactShape shape : shapes) {
            idByKey.put(key(shape.displayName(), shape.file()), nextId++);
        }
        List<ArtifactShape> foreign = new ArrayList<>();
        for (Found item : found) {
            String k = key(item.artifact(), item.path());
            if (byKey.containsKey(k)) {
                continue;
            }
            ArtifactShape shape = new ArtifactShape(item.artifact(), kindOfForeign(moduleRoot, item.path()),
                    item.path(), declarationLineOf(moduleRoot, item.path(), item.artifact()), false, null);
            byKey.put(k, shape);
            idByKey.put(k, nextId++);
            foreign.add(shape);
        }

        // ── 4. the artifact inventory, in reading order then first-reference order ──────────────────
        List<ArtifactMeta> artifacts = new ArrayList<>();
        for (ArtifactShape shape : shapes) {
            artifacts.add(new ArtifactMeta(idByKey.get(key(shape.displayName(), shape.file())),
                    shape.displayName(), shape.kind(), shape.file(), shape.line(), shape.generated(), true,
                    shape.header()));
        }
        for (ArtifactShape shape : foreign) {
            artifacts.add(new ArtifactMeta(idByKey.get(key(shape.displayName(), shape.file())),
                    shape.displayName(), shape.kind(), shape.file(), shape.line(), false, false, null));
        }

        // ── 5. one ViewFieldMeta per field, in ledger order ─────────────────────────────────────────
        Map<String, List<Found>> byField = new LinkedHashMap<>();
        for (Found item : found) {
            byField.computeIfAbsent(item.field(), __ -> new ArrayList<>()).add(item);
        }

        List<ViewFieldMeta> fieldMetas = new ArrayList<>();
        for (int i = 0; i < (fields == null ? 0 : fields.size()); i++) {
            Property property = fields.get(i);
            // Earliest line wins, exactly as a source scan would report: an ordinal slot exists in both
            // `get(int)` and `set(int, Object)`, and the page has always pointed at the first.
            Map<Integer, Map<String, Integer>> at = new LinkedHashMap<>();
            Map<Integer, Map<String, Integer>> earliest = new HashMap<>();
            for (Found item : byField.getOrDefault(property.name(), List.of())) {
                Integer artifactId = idByKey.get(key(item.artifact(), item.path()));
                if (artifactId == null || item.line() < 1) {
                    continue;
                }
                Map<String, Integer> roles = earliest.computeIfAbsent(artifactId, __ -> new HashMap<>());
                Integer previous = roles.get(item.role());
                if (previous == null || item.line() < previous) {
                    roles.put(item.role(), item.line());
                }
            }
            // Emit in artifact order, then in the fixed role order, so two runs are byte-identical and
            // the page's column probe is stable.
            List<Integer> artifactIds = new ArrayList<>(earliest.keySet());
            artifactIds.sort(Comparator.naturalOrder());
            for (Integer artifactId : artifactIds) {
                Map<String, Integer> roles = earliest.get(artifactId);
                Map<String, Integer> ordered = new LinkedHashMap<>();
                for (String role : ViewFieldMeta.ROLES) {
                    Integer line = roles.get(role);
                    if (line != null) {
                        ordered.put(role, line);
                    }
                }
                if (!ordered.isEmpty()) {
                    at.put(artifactId, ordered);
                }
            }

            int ordinal = i < ledgerSize ? i + 1 : -1;
            // An unannotated accessor IS a column — that is S1's reading, and it is the same default
            // `EntityFieldMeta` has always applied for the marker-level union. Recording it as absent
            // would lose the distinction between "no @FieldSource, so writable" and "a kind nobody
            // recorded", and the page's kind chip is driven by this value.
            String fieldKind = property.fieldKind() != null ? property.fieldKind() : "COLUMN";
            fieldMetas.add(new ViewFieldMeta(property.name(), ordinal, property.type(), fieldKind,
                    property.column(), property.relation(), property.expression(), at));
        }

        return new Detail(artifacts, fieldMetas);
    }

    /**
     * The files that belong to a view, in the order the inventory numbers them: the view's own file
     * first (its nested types follow inside it), then the generated siblings in the order the pass emits
     * them.
     *
     * <p>A sibling is included only when it <strong>exists</strong>: the metadata says what this pass
     * produced, and a row for a file nobody wrote would be a claim about a file the reader cannot open.
     * This is also why the {@code --packages} filter needs no special case — a skipped view emitted
     * nothing, so its inventory is just its own file, and its field list is empty.</p>
     */
    private static List<Path> candidateFiles(Path moduleRoot, Path javaOutputRoot, String viewPackage,
                                             String viewName, InterfaceInfo viewInfo) {
        List<Path> candidates = new ArrayList<>();
        if (viewInfo != null && viewInfo.sourcePath() != null && !viewInfo.sourcePath().isBlank()) {
            candidates.add(moduleRoot.resolve(viewInfo.sourcePath()));
        }
        Path packageDir = viewPackage == null || viewPackage.isBlank()
                ? javaOutputRoot
                : javaOutputRoot.resolve(viewPackage.replace('.', '/'));
        for (String suffix : GENERATED_SUFFIXES) {
            candidates.add(packageDir.resolve(viewName + suffix + ".java"));
        }
        return candidates;
    }

    /**
     * Reads one candidate file into the inventory and the location list.
     *
     * <p>A file that does not exist is the normal case (a level that emits no validator, an optional
     * adapter) and is skipped without a word. A file that exists but cannot be parsed is reported and
     * contributes nothing: a partial unit is exactly what {@link SourceReader} exists to refuse, because
     * a half-read artifact produces locations that look plausible and point at the wrong lines.</p>
     */
    private static void readCandidate(Path moduleRoot, Path candidate, Set<String> fieldNames,
                                      List<ArtifactShape> shapes, List<Found> found, ClassIndex index,
                                      DivergenceReporter divergences, boolean viewOwnFile) throws IOException {
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return;
        }
        // The file must really be UNDER the module root. Testing the computed path alone is not enough:
        // `moduleRelativePath` falls back to the bare file name when the two share no ancestor (a
        // different Windows drive, which is exactly what a test's temp `--java-out` is), and a bare name
        // is shape-valid while pointing at a file that is not there. A path the module cannot own has to
        // be refused here, or the index would claim a file the renderer can never open.
        Path moduleBase = moduleRoot.toAbsolutePath().normalize();
        Path candidateAbsolute = candidate.toAbsolutePath().normalize();
        String moduleRelative = EntityMetadataGenerator.moduleRelativePath(moduleRoot, candidate);
        if (!candidateAbsolute.startsWith(moduleBase) || !ClassIndex.isModuleRelative(moduleRelative)) {
            // A `--java-out` outside the module: the file exists and is generated, but the metadata has
            // no honest way to name it — a module-relative path is the only currency the index and the
            // documents share, and `..` is not one. Reported rather than dropped, because the pass
            // really did write a file it cannot describe.
            if (divergences != null) {
                divergences.report("artifact_outside_module", moduleRelative,
                        "the generated artifact is outside the module the metadata is relative to, so no "
                                + "module-relative path exists for it",
                        candidate.toString(),
                        "an artifact under the module root, which is what --java-out is for",
                        "point --java-out at the module's source root, or accept that this artifact is "
                                + "absent from the metadata");
            }
            return;
        }
        // The text is read once and handed to both readers: `SourceReader` parses it into the LST the
        // roles are read from, and every line lookup re-reads the same text through javac's line map.
        // Splitting them — a `ReadJp` for the tree and a second read for the text — is what the port
        // removes, and it is also what would let the two disagree about a file that changed in between.
        String source = Files.readString(candidate);
        SourceReader.Read read = SourceReader.readText(source);
        if (!read.readable()) {
            SourceReader.reportUnparseable(divergences, "source_not_parsed", moduleRelative,
                    "the generated artifact could not be parsed, so it contributed no locations to this "
                            + "view's field map",
                    "record the locations it declares");
            return;
        }
        // Register the artifact in the class index (DEC-029). It is generated when its DEC-021 header says
        // so, and it declares the types a document will name — including any nested type the view's field
        // map keys on, which is why every declaration is registered and not just the top-level one.
        J.CompilationUnit unit = read.unit();
        index.addTypes(moduleRelative, unit, source, true);
        String headerDescription = dec021Description(source);
        boolean generated = headerDescription != null;
        // The arm pairing is file-wide by construction, so it is computed once per file rather than
        // per method: javac's arm list and the LST's are matched by position in the file, and a
        // per-method list would not line up with either.
        Map<J.Case, Integer> caseLines = TreeQueries.caseLines(unit, source);

        for (J.ClassDeclaration declaration : TreeQueries.topLevelTypes(unit)) {
            walkType(declaration, "", moduleRelative, source, caseLines, fieldNames, generated,
                    headerDescription, shapes, found, viewOwnFile);
        }
    }

    /** Walks one type declaration and, for the view's own file, its nested types — in reading order. */
    private static void walkType(J.ClassDeclaration declaration, String prefix, String file, String source,
                                 Map<J.Case, Integer> caseLines, Set<String> fieldNames, boolean generated,
                                 String headerDescription, List<ArtifactShape> shapes, List<Found> found,
                                 boolean walkNested) {
        String simpleName = declaration.getSimpleName();
        String displayName = prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
        boolean topLevel = prefix.isEmpty();
        // The NAME's line, not the declaration's: an annotated type begins on its annotation, and a link
        // that opens the annotation is wrong while looking authoritative. The enclosing chain has to be
        // supplied because the LST gives a node no way back to its parent, and the display name already
        // is that chain.
        List<String> enclosing = prefix.isEmpty() ? List.of() : List.of(prefix.split("\\."));
        int line = TreeQueries.lineOfChained(declaration, enclosing, source);
        // The five spellings are DEC-029's contract, and this is the one resolver for them — the class
        // index reuses it rather than growing a second opinion about what a record is.
        String kind = kindOf(declaration);
        shapes.add(new ArtifactShape(displayName, kind, file, line,
                generated && topLevel, topLevel ? headerDescription : null));

        collectFromType(declaration, displayName, file, source, caseLines, fieldNames, found);

        if (!walkNested) {
            return;
        }
        if (declaration.getBody() == null) {
            return;
        }
        for (org.openrewrite.java.tree.Statement member : declaration.getBody().getStatements()) {
            if (member instanceof J.ClassDeclaration nested) {
                walkType(nested, displayName, file, source, caseLines, fieldNames, generated,
                        headerDescription, shapes, found, true);
            }
        }
    }

    /**
     * The roles one type declaration contributes, for the fields of interest.
     *
     * <p>Every role is discovered from the LST, and every <em>line</em> from javac over the same text. The
     * discovery shapes are the ported ones: an enum's constants are the {@link J.EnumValue}s of its
     * {@link J.EnumValueSet} statement (JavaParser had a separate {@code EnumDeclaration} class with
     * {@code getEntries()}), a record's components are its primary constructor's parameters
     * ({@code getParameters()} on a separate {@code RecordDeclaration}), and a field is a
     * {@link J.VariableDeclarations} with one or more declarators (JavaParser had one
     * {@code FieldDeclaration} per declaration line with N variables — the same shape, one level down).</p>
     */
    private static void collectFromType(J.ClassDeclaration declaration, String displayName, String file,
                                        String source, Map<J.Case, Integer> caseLines,
                                        Set<String> fieldNames, List<Found> found) {
        // Enum constants, in declaration order, from the single `J.EnumValueSet` the LST groups them into.
        if (declaration.getKind() == J.ClassDeclaration.Kind.Type.Enum && declaration.getBody() != null) {
            for (org.openrewrite.java.tree.Statement statement : declaration.getBody().getStatements()) {
                if (!(statement instanceof J.EnumValueSet values)) {
                    continue;
                }
                for (J.EnumValue entry : values.getEnums()) {
                    String name = entry.getName().getSimpleName();
                    if (fieldNames.contains(name)) {
                        found.add(new Found(displayName, name, "enum-constant", file,
                                TreeQueries.memberLineOf(displayName, name, "enum-constant", source)));
                    }
                }
            }
        }
        // Record components: the primary constructor's parameter list, which is where a record declares
        // them — `getPrimaryConstructor()` returns that list of `Statement`s directly, not a method
        // declaration, because a record's header is not a member the author wrote.
        if (declaration.getKind() == J.ClassDeclaration.Kind.Type.Record
                && declaration.getPrimaryConstructor() != null) {
            for (org.openrewrite.java.tree.Statement parameter : declaration.getPrimaryConstructor()) {
                if (!(parameter instanceof J.VariableDeclarations component)
                        || component.getVariables() == null) {
                    continue;
                }
                for (J.VariableDeclarations.NamedVariable declarator : component.getVariables()) {
                    String name = declarator.getSimpleName();
                    if (fieldNames.contains(name)) {
                        found.add(new Found(displayName, name, "record-component", file,
                                TreeQueries.memberLineOf(displayName, name, "record-component", source)));
                    }
                }
            }
        }

        // Methods are read from the body's own statements, never by a recursive walk: a nested type's
        // accessors are its own, and `getMethods()` never returned them (MIGRATION-CAVEATS.md § 1.10).
        for (J.MethodDeclaration method : TreeQueries.methodsOf(declaration)) {
            String name = method.getSimpleName();
            if (fieldNames.contains(name)) {
                // The accessor's own line, read from the NAME token — an annotated accessor begins on its
                // annotation, which is a different line and is recorded separately below — and the arity
                // is what separates a getter from a setter.
                int arity = TreeQueries.hasNoParameters(method) ? 0
                        : (method.getParameters() == null ? 0 : method.getParameters().size());
                if (arity == 0) {
                    found.add(new Found(displayName, name, "accessor", file,
                            TreeQueries.methodLineOf(method, displayName, source)));
                } else if (arity == 1) {
                    found.add(new Found(displayName, name, "setter", file,
                            TreeQueries.methodLineOf(method, displayName, source)));
                }
                J.Annotation annotation = TreeQueries.annotationNamed(method, "FieldSource");
                if (annotation != null) {
                    found.add(new Found(displayName, name, "annotation", file,
                            TreeQueries.annotationLineOf(declaration.getSimpleName(), name, "FieldSource",
                                    source)));
                }
            }
            // The switch arms of this method, paired against javac's own source-ordered arm list.
            for (J.Case arm : TreeQueries.findAll(method, J.Case.class)) {
                collectSwitchEntry(arm, caseLines.getOrDefault(arm, -1), displayName, file, fieldNames, found);
            }
        }

        // Fields: one `J.VariableDeclarations` may declare several names.
        if (declaration.getBody() == null) {
            return;
        }
        for (org.openrewrite.java.tree.Statement member : declaration.getBody().getStatements()) {
            if (!(member instanceof J.VariableDeclarations fields) || fields.getVariables() == null) {
                continue;
            }
            for (J.VariableDeclarations.NamedVariable variable : fields.getVariables()) {
                String name = variable.getSimpleName();
                if (fieldNames.contains(name)) {
                    found.add(new Found(displayName, name, "field", file,
                            TreeQueries.memberLineOf(displayName, name, "field", source)));
                }
            }
        }
    }

    /**
     * The roles a switch arm contributes: a string label is the name lookup, an integer label is
     * DEC-023's ordinal slot.
     *
     * <p>An ordinal arm is attributed to the fields it actually writes — read from the tree, so
     * {@code case 3 -> age;} and {@code case 0 -> { this.id = value; }} both resolve to the field they
     * touch — rather than to the labels of the arm, which are integers.</p>
     *
     * @param line the arm's start line from {@link TreeQueries#caseLines}, or {@code -1} when the two
     *             parsers disagreed about how many arms the file has
     */
    private static void collectSwitchEntry(J.Case arm, int line, String displayName, String file,
                                           Set<String> fieldNames, List<Found> found) {
        List<J> labels = arm.getCaseLabels();
        if (labels == null) {
            return;
        }
        for (J label : labels) {
            if (label instanceof J.Literal literal && literal.getValue() instanceof String text) {
                if (fieldNames.contains(text)) {
                    found.add(new Found(displayName, text, "name-slot", file, line));
                }
                continue;
            }
            if (!(label instanceof J.Literal literal) || !isIntegerLiteral(literal)) {
                continue;
            }
            // The arm's body: every identifier it mentions, plus every `this.x` it assigns. The same set
            // the JavaParser walk collected, because a false positive needs a field and a local of one
            // name in the same arm.
            //
            // Both shapes have to be read, and missing one is silent: an old-style `case 0: stmt;` arm
            // keeps its statements, while an arrow arm (`case 0 -> stmt;`, and every arm the emitters
            // write) keeps its single body on `getBody()`. Reading only `getStatements()` finds no
            // ordinal slot in any builder — which is exactly the shape of a location that is absent
            // rather than wrong, so nothing complains.
            List<J> roots = new ArrayList<>();
            if (arm.getStatements() != null) {
                roots.addAll(arm.getStatements());
            }
            if (arm.getBody() != null) {
                roots.add(arm.getBody());
            }
            Set<String> targets = new LinkedHashSet<>();
            for (J root : roots) {
                for (J.Identifier identifier : TreeQueries.findAll(root, J.Identifier.class)) {
                    targets.add(identifier.getSimpleName());
                }
                for (J.FieldAccess access : TreeQueries.findAll(root, J.FieldAccess.class)) {
                    if (access.getTarget() instanceof J.Identifier scope && "this".equals(scope.getSimpleName())) {
                        targets.add(access.getName().getSimpleName());
                    }
                }
            }
            for (String target : targets) {
                if (fieldNames.contains(target)) {
                    found.add(new Found(displayName, target, "ordinal-slot", file, line));
                }
            }
        }
    }

    /** Whether a literal is an integer literal, whatever width it was written at. */
    private static boolean isIntegerLiteral(J.Literal literal) {
        Object value = literal.getValue();
        return value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte;
    }

    /**
     * The kind of one type declaration, in this tooling's vocabulary.
     *
     * <p>Public because the class index records a row's {@code kind} and must not grow a second kind
     * resolver: two spellings of "is this an interface or a record" is how the inventory and the index
     * would disagree about the same file. This class stays package-private; the resolver does not.</p>
     *
     * <h3>Phase 6: the five kinds are one class</h3>
     * <p>JavaParser gave five distinct types here — {@code EnumDeclaration},
     * {@code RecordDeclaration}, {@code AnnotationDeclaration}, and
     * {@code ClassOrInterfaceDeclaration} split by {@code isInterface()} — so the resolution was a
     * chain of {@code instanceof}. The LST has <strong>one</strong> type, {@link J.ClassDeclaration},
     * whose {@link J.ClassDeclaration#getKind()} returns exactly those five kinds. The mapping is
     * exact, and getting it wrong is silent in the worst way: dropping the kind test and returning
     * {@code "class"} for everything produces an index that compiles, validates, and mislabels every
     * record and enum in the tree. The vocabulary below is DEC-029's contract and must not change.</p>
     */
    public static String kindOf(J.ClassDeclaration declaration) {
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
    }

    private static String key(String artifact, String file) {
        return artifact + "\u0000" + (file == null ? "" : file);
    }

    /**
     * The DEC-021 description of a file, or {@code null} when the file carries no DEC-021 header.
     *
     * <p>Read from the source text's <strong>leading {@code //} run</strong> — the header is the two-line
     * pair above the {@code package} declaration, whose first line references the view it was generated
     * for. The restriction is not cosmetic: a generated class also carries a javadoc that names the view
     * in a {@code {@link …}} of its own ({@code An immutable {@link PersonCreateForm} whose component
     * order is …}), and reading the first {@code {@link}} in <em>any</em> comment labelled the builder
     * with half a javadoc sentence. A file without the header line is hand-written, and the page must say
     * so rather than claim it is generated.</p>
     *
     * <p>Phase 6: this is the one place where reading the source <em>text</em> is the right answer rather
     * than a fallback. JavaParser exposed a compilation unit's comments with their positions, so the
     * original code could sort them and stop at the first block comment; the LST exposes comments as
     * whitespace trivia with no position at all. The leading {@code //} run is exactly what DEC-021
     * defines the header to be, and it is trivially addressable in the text — so the text is read here
     * deliberately, and the {@code //} itself is stripped before matching so the pattern stays the one
     * that describes the header's content.</p>
     */
    private static String dec021Description(String source) {
        for (String rawLine : source.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("//")) {
                // The leading run of line comments ended: a javadoc, an annotation, or the package
                // declaration itself. Only comments above `package` can be the header.
                return null;
            }
            Matcher matcher = DEC_021_HEADER.matcher(line.substring(2).trim());
            if (matcher.find()) {
                return matcher.group(2).trim();
            }
        }
        return null;
    }

    /** The declaration line of a type in a file the pass did not otherwise read, or {@code -1}. */
    private static int declarationLineOf(Path moduleRoot, String moduleRelativePath, String displayName) {
        try {
            Path file = moduleRoot.resolve(moduleRelativePath);
            String source = Files.readString(file);
            J.CompilationUnit unit = SourceReader.readSourceText(source);
            if (unit == null) {
                return -1;
            }
            // The display name is the dotted chain, which is exactly what `typesWithEnclosing` already
            // carries — so the search is a name comparison rather than a recursive descent.
            for (TreeQueries.EnclosedType enclosed : TreeQueries.typesWithEnclosing(unit)) {
                String simpleName = enclosed.declaration().getSimpleName();
                String full = enclosed.enclosing().isEmpty() ? simpleName
                        : String.join(".", enclosed.enclosing().stream()
                                .map(J.ClassDeclaration::getSimpleName).toList()) + "." + simpleName;
                if (full.equals(displayName)) {
                    return TreeQueries.lineOfChained(enclosed.declaration(),
                            enclosed.enclosing().stream().map(J.ClassDeclaration::getSimpleName).toList(),
                            source);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // A foreign declaring interface the pass cannot re-read: the location itself is still true,
            // and -1 is the honest answer for the declaration line rather than a guess.
        }
        return -1;
    }

    /**
     * The kind of a foreign declaring interface.
     *
     * <p>Always {@code interface} in practice — a view's fields come from interfaces — and read from the
     * file when it is there so a foreign class or enum would not be mislabelled. A file that cannot be
     * re-read keeps the honest default rather than a guess dressed as a fact.</p>
     */
    private static String kindOfForeign(Path moduleRoot, String moduleRelativePath) {
        try {
            J.CompilationUnit unit = SourceReader.readSourceText(
                    Files.readString(moduleRoot.resolve(moduleRelativePath)));
            if (unit != null) {
                for (J.ClassDeclaration declaration : TreeQueries.topLevelTypes(unit)) {
                    // The same resolver the index uses, not a second opinion: one kind vocabulary.
                    return kindOf(declaration);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Fall through to the default below.
        }
        return "interface";
    }
}
