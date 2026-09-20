package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;

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
 * <p>Every role this class records is taken from JavaParser's positions, never from a regex: the
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
        SourceReader.Read read = SourceReader.read(candidate);
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
        index.addTypes(moduleRelative, read.unit(), true);
        CompilationUnit unit = read.unit();
        String headerDescription = dec021Description(unit);
        boolean generated = headerDescription != null;

        for (TypeDeclaration<?> declaration : unit.getTypes()) {
            walkType(declaration, "", moduleRelative, fieldNames, generated, headerDescription, shapes, found,
                    viewOwnFile);
        }
    }

    /** Walks one type declaration and, for the view's own file, its nested types — in reading order. */
    private static void walkType(TypeDeclaration<?> declaration, String prefix, String file,
                                 Set<String> fieldNames, boolean generated, String headerDescription,
                                 List<ArtifactShape> shapes, List<Found> found, boolean walkNested) {
        String simpleName = declaration.getNameAsString();
        String displayName = prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
        boolean topLevel = prefix.isEmpty();
        int line = declaration.getName().getBegin().map(position -> position.line).orElse(-1);
        // The DEC-021 description is a property of the FILE, and the page labels the file's top-level
        // type with it; a nested type is described as "nested type" by whoever reads this.
        shapes.add(new ArtifactShape(displayName, kindOf(declaration), file, line,
                generated && topLevel, topLevel ? headerDescription : null));

        collectFromType(declaration, displayName, file, fieldNames, found);

        if (!walkNested) {
            return;
        }
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                walkType(nested, displayName, file, fieldNames, generated, headerDescription, shapes, found,
                        true);
            }
        }
    }

    /** The roles one type declaration contributes, for the fields of interest. */
    private static void collectFromType(TypeDeclaration<?> declaration, String displayName, String file,
                                        Set<String> fieldNames, List<Found> found) {
        if (declaration instanceof EnumDeclaration enumDeclaration) {
            for (EnumConstantDeclaration entry : enumDeclaration.getEntries()) {
                String name = entry.getNameAsString();
                if (fieldNames.contains(name)) {
                    found.add(new Found(displayName, name, "enum-constant", file, lineOf(entry.getName())));
                }
            }
        }
        if (declaration instanceof RecordDeclaration record) {
            for (Parameter parameter : record.getParameters()) {
                String name = parameter.getNameAsString();
                if (fieldNames.contains(name)) {
                    found.add(new Found(displayName, name, "record-component", file, lineOf(parameter.getName())));
                }
            }
        }

        for (MethodDeclaration method : declaration.getMethods()) {
            String name = method.getNameAsString();
            if (fieldNames.contains(name)) {
                int arity = method.getParameters().size();
                if (arity == 0) {
                    // The NAME token's line, never the method's begin: an annotated accessor begins on
                    // its annotation, which is a different line and is recorded separately below.
                    found.add(new Found(displayName, name, "accessor", file, lineOf(method.getName())));
                } else if (arity == 1) {
                    found.add(new Found(displayName, name, "setter", file, lineOf(method.getName())));
                }
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    if ("FieldSource".equals(annotation.getNameAsString())) {
                        found.add(new Found(displayName, name, "annotation", file, lineOf(annotation)));
                    }
                }
            }
            for (SwitchEntry entry : method.findAll(SwitchEntry.class)) {
                collectSwitchEntry(entry, displayName, file, fieldNames, found);
            }
        }

        for (FieldDeclaration field : declaration.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                if (fieldNames.contains(name)) {
                    found.add(new Found(displayName, name, "field", file, lineOf(variable.getName())));
                }
            }
        }
    }

    /**
     * The roles a switch arm contributes: an integer label is DEC-023's ordinal slot, a string label is
     * the name lookup.
     *
     * <p>An ordinal arm is attributed to the fields it actually writes — read from the AST, so
     * {@code case 3 -> age;} and {@code case 0 -> { this.id = value; }} both resolve to the field they
     * touch — rather than to the labels of the arm, which are integers.</p>
     */
    private static void collectSwitchEntry(SwitchEntry entry, String displayName, String file,
                                           Set<String> fieldNames, List<Found> found) {
        int line = entry.getBegin().map(position -> position.line).orElse(-1);
        for (com.github.javaparser.ast.expr.Expression label : entry.getLabels()) {
            if (label instanceof StringLiteralExpr literal) {
                String name = literal.asString();
                if (fieldNames.contains(name)) {
                    found.add(new Found(displayName, name, "name-slot", file, line));
                }
                continue;
            }
            if (!label.isIntegerLiteralExpr()) {
                continue;
            }
            for (Statement statement : entry.getStatements()) {
                Set<String> targets = new LinkedHashSet<>();
                for (NameExpr name : statement.findAll(NameExpr.class)) {
                    targets.add(name.getNameAsString());
                }
                for (FieldAccessExpr access : statement.findAll(FieldAccessExpr.class)) {
                    if (access.getScope() instanceof com.github.javaparser.ast.expr.ThisExpr) {
                        targets.add(access.getNameAsString());
                    }
                }
                for (String target : targets) {
                    if (fieldNames.contains(target)) {
                        found.add(new Found(displayName, target, "ordinal-slot", file, line));
                    }
                }
            }
        }
    }

    /**
     * The kind of one type declaration, in this tooling's vocabulary.
     *
     * <p>Public because the class index records a row's {@code kind} and must not grow a second kind
     * resolver: two spellings of "is this an interface or a record" is how the inventory and the index
     * would disagree about the same file. This class stays package-private; the resolver does not.</p>
     */
    public static String kindOf(TypeDeclaration<?> declaration) {
        if (declaration instanceof EnumDeclaration) {
            return "enum";
        }
        if (declaration instanceof RecordDeclaration) {
            return "record";
        }
        if (declaration instanceof AnnotationDeclaration) {
            return "annotation";
        }
        if (declaration instanceof ClassOrInterfaceDeclaration classOrInterface) {
            return classOrInterface.isInterface() ? "interface" : "class";
        }
        return "class";
    }

    private static int lineOf(Node node) {
        return node.getBegin().map(position -> position.line).orElse(-1);
    }

    private static String key(String artifact, String file) {
        return artifact + "\u0000" + (file == null ? "" : file);
    }

    /**
     * The DEC-021 description of a file, or {@code null} when the file carries no DEC-021 header.
     *
     * <p>Read from the compilation unit's own comments, restricted to the <strong>leading {@code //}
     * run</strong> — the header is the two-line pair above the {@code package} declaration, whose first
     * line references the view it was generated for. The restriction is not cosmetic: a generated class
     * also carries a javadoc that names the view in a {@code {@link …}} of its own
     * ({@code An immutable {@link PersonCreateForm} whose component order is …}), and reading the first
     * {@code {@link}} in <em>any</em> comment labelled the builder with half a javadoc sentence. A file
     * without the header line is hand-written, and the page must say so rather than claim it is
     * generated.</p>
     */
    private static String dec021Description(CompilationUnit unit) {
        // Ordered by position, because `getAllComments` promises no order: the header comes first in
        // the file, and "first" is the only thing that distinguishes it from a javadoc {@link}.
        List<Comment> comments = new ArrayList<>(unit.getAllComments());
        comments.sort(Comparator.comparingInt(
                comment -> comment.getBegin().map(position -> position.line).orElse(Integer.MAX_VALUE)));
        for (Comment comment : comments) {
            if (!comment.isLineComment()) {
                // The header is a run of `//` lines; the first block comment ends the run.
                break;
            }
            for (String rawLine : comment.getContent().split("\\R")) {
                Matcher matcher = DEC_021_HEADER.matcher(rawLine.trim());
                if (matcher.find()) {
                    return matcher.group(2).trim();
                }
            }
        }
        return null;
    }

    /** The declaration line of a type in a file the pass did not otherwise read, or {@code -1}. */
    private static int declarationLineOf(Path moduleRoot, String moduleRelativePath, String displayName) {
        try {
            Path file = moduleRoot.resolve(moduleRelativePath);
            SourceReader.Read read = SourceReader.read(file);
            if (!read.readable()) {
                return -1;
            }
            for (TypeDeclaration<?> declaration : read.unit().getTypes()) {
                int line = findDeclaration(declaration, "", displayName);
                if (line > 0) {
                    return line;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // A foreign declaring interface the pass cannot re-read: the location itself is still true,
            // and -1 is the honest answer for the declaration line rather than a guess.
        }
        return -1;
    }

    private static int findDeclaration(TypeDeclaration<?> declaration, String prefix, String displayName) {
        String simpleName = declaration.getNameAsString();
        String full = prefix.isEmpty() ? simpleName : prefix + "." + simpleName;
        if (full.equals(displayName)) {
            return declaration.getName().getBegin().map(position -> position.line).orElse(-1);
        }
        for (BodyDeclaration<?> member : declaration.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                int line = findDeclaration(nested, full, displayName);
                if (line > 0) {
                    return line;
                }
            }
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
            SourceReader.Read read = SourceReader.read(moduleRoot.resolve(moduleRelativePath));
            if (read.readable()) {
                for (TypeDeclaration<?> declaration : read.unit().getTypes()) {
                    return kindOf(declaration);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Fall through to the default below.
        }
        return "interface";
    }
}
