package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reusable half of rule R1 (plan.dsflash § 4.6/R1.2, § 4.6/R1.3).
 *
 * <p>It parses a Java file, finds the enum that carries the {@code entityFieldEnum:true} marker in
 * its DEC-021 header, and returns the constant names in declaration order. It has <strong>no git
 * dependency</strong> — it takes two parsed enums and compares them — so it is unit-testable in
 * isolation, and {@link EntityFieldEnumOrderRule} supplies the two revisions.</p>
 *
 * <h3>Why the marker is read from the parsed comment</h3>
 * <p>The marker rides the DEC-021 two-line header above {@code package}. It must be decoded from the
 * parsed comment, never by string-matching the file, and a malformed marker is a diagnostic rather
 * than a silent skip — silently skipping would defeat the rule.</p>
 *
 * <h3>Phase 6: how the header is recovered from an LST</h3>
 * <p>JavaParser had a real comment model, {@code cu.getAllComments()}, and the old code leaned on a
 * subtlety of it: JavaParser attaches the last comment of a run to the <em>following</em>
 * declaration, so the {@code {...}} config line above {@code package} was reachable at all. The LST
 * has no comment nodes — comment text is a prefix on the tree that follows it — so there is nothing
 * equivalent to ask.</p>
 *
 * <p>The replacement was established by measurement rather than assumption, and the shape is not what
 * a first guess suggests: the two header lines are held on the <strong>compilation unit's own
 * prefix</strong> as a <em>single</em> {@link org.openrewrite.java.tree.Comment} whose text contains
 * an embedded newline. The {@code package} declaration's prefix is empty. So
 * {@link #readHeader(J.CompilationUnit)} reads the unit prefix and {@link #configLine} splits the
 * comment text on line breaks — which is why that split exists and must not be "simplified" away.</p>
 *
 * <p>The JavaParser overload ({@link #readHeader(com.github.javaparser.ast.CompilationUnit)}) is kept
 * for the queue files that have not been ported, so both trees yield the same answer from one set of
 * attribute rules rather than two implementations that could drift.</p>
 */
public final class EnumConstantOrderChecker {

    /** The header key that opts an enum into R1. Absence is opt-out. */
    public static final String MARKER_KEY = "entityFieldEnum";

    /**
     * The pinned JSON5 subset of DEC-021 § 4.
     *
     * <p>The seven {@code JsonReadFeature}s below are the <em>only</em> extensions allowed in a
     * generated header; anything else must be rejected with a diagnostic rather than guessed at.
     * Jackson 3 exposes them directly through {@code JsonReadFeature}; note the two renames
     * relative to the DEC-021 table's Jackson 2 spelling — {@code ALLOW_UNQUOTED_FIELD_NAMES} is
     * {@code ALLOW_UNQUOTED_PROPERTY_NAMES}, and the features are enabled on the mapper builder
     * rather than through a {@code mappedFeature()} accessor.</p>
     */
    private static final List<JsonReadFeature> DEC_021_FEATURES = List.of(
            JsonReadFeature.ALLOW_SINGLE_QUOTES,
            JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES,
            JsonReadFeature.ALLOW_TRAILING_COMMA,
            JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS,
            JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS,
            JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS,
            JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER);

    private static final ObjectMapper JSON5 = buildJson5Mapper();

    private EnumConstantOrderChecker() {
    }

    private static ObjectMapper buildJson5Mapper() {
        var builder = JsonMapper.builder();
        for (JsonReadFeature feature : DEC_021_FEATURES) {
            builder.enable(feature);
        }
        return builder.build();
    }

    /** One enum's parsed state: its name, its constants in declaration order and its header config. */
    public record EnumLedger(
            String qualifiedName,
            List<String> constants,
            boolean marked,
            boolean allowReorder,
            List<String> diagnostics) {

        public EnumLedger {
            constants = List.copyOf(constants);
            diagnostics = List.copyOf(diagnostics);
        }

        /** Whether R1 applies to this enum at all. */
        public boolean guarded() {
            return marked;
        }
    }

    /**
     * Parses one compilation unit and returns every field-enum ledger it declares, keyed by
     * qualified enum name.
     *
     * <p>A malformed header <strong>fails safe toward "marked"</strong> (R1.2/§ 4.5 G7): treating an
     * unparseable marker as absent would let the generator drop live ordinals.</p>
     *
     * <p>The source is parsed through {@link SourceReader} rather than a local parser, so the
     * "was this readable at all" question has one answer in the toolchain. An unreadable file yields
     * an empty ledger map, which is the fail-safe direction: nothing is reported about a file the
     * pass could not read.</p>
     */
    public static Map<String, EnumLedger> readLedgers(String source) {
        Map<String, EnumLedger> ledgers = new LinkedHashMap<>();
        J.CompilationUnit cu = SourceReader.readSourceText(source);
        if (cu == null) {
            return ledgers;
        }
        String packageName = TreeQueries.packageName(cu);
        HeaderConfig header = readHeader(cu);

        for (J.ClassDeclaration decl : TreeQueries.enums(cu)) {
            String name = decl.getSimpleName();
            String qualified = packageName.isEmpty() ? name : packageName + "." + name;
            // Enum constants are a J.EnumValueSet *statement* inside the class body, not a
            // constant list hanging off the declaration — see the migration guide § 4.2.
            List<String> constants = new ArrayList<>();
            for (J.EnumValueSet valueSet : TreeQueries.findAll(decl, J.EnumValueSet.class)) {
                for (J.EnumValue value : valueSet.getEnums()) {
                    constants.add(value.getName().getSimpleName());
                }
            }
            ledgers.put(qualified, new EnumLedger(qualified, constants,
                    header.marked(), header.allowReorder(), header.diagnostics()));
        }
        return ledgers;
    }

    /** The DEC-021 header of one file: its JSON5 config blob, if any. */
    public record HeaderConfig(boolean present, boolean marked, boolean allowReorder, List<String> diagnostics) {

        public HeaderConfig {
            diagnostics = List.copyOf(diagnostics);
        }

        static HeaderConfig none() {
            return new HeaderConfig(false, false, false, List.of());
        }
    }

    /**
     * Decodes the DEC-021 header above the {@code package} declaration.
     *
     * <p>The header is a two-line {@code //} comment pair; the second line is a single-line JSON5
     * blob. Only the configuration keys are read here; the {@code {@link …}} first line is
     * presentation.</p>
     *
     * <h3>Phase 6: where the comment lives in an LST</h3>
     * <p>JavaParser had a comment model, so {@code cu.getAllComments()} returned both header lines and
     * the old code scanned them for the first line starting with {@code &#123;}. The LST has no comment
     * nodes; comment text is a <em>prefix</em> on the following tree. Verified against this parser: the
     * two header lines above {@code package} are held on the compilation unit's own
     * {@code getPrefix().getComments()} as a <strong>single</strong> {@link TextComment} whose text is
     * {@code " {@link …}\n {@…}"} — the newlines are inside one comment, not two. The package
     * declaration's prefix is empty, so the unit-level prefix is the place to look, and reading it is
     * what keeps the marker visible.</p>
     *
     * <p>Reading the unit prefix rather than rescanning the file text also keeps the old behaviour's
     * safety: a comment that is not a header cannot be mistaken for one, because
     * {@link #configLine} only accepts a line whose trimmed content starts with
     * {@code &#123;}.</p>
     */
    public static HeaderConfig readHeader(J.CompilationUnit cu) {
        List<String> diagnostics = new ArrayList<>();
        if (cu == null) {
            return HeaderConfig.none();
        }
        for (com.github.javaparser.ast.comments.Comment comment : jpComments(cu)) {
            Optional<String> config = configLine(comment);
            if (config.isEmpty()) {
                continue;
            }
            String json = config.get().trim();
            if (json.isEmpty() || json.charAt(0) != '{') {
                continue;
            }
            return decode(json, diagnostics);
        }
        return HeaderConfig.none();
    }

    /**
     * The comment texts attached to a compilation unit's prefix, in order.
     *
     * <p>Kept as {@link com.github.javaparser.ast.comments.Comment} so {@link #configLine} has one
     * implementation for both parsers: the AST comment type is used only as a text carrier here, never
     * for its position.</p>
     */
    private static List<com.github.javaparser.ast.comments.Comment> jpComments(J.CompilationUnit cu) {
        List<com.github.javaparser.ast.comments.Comment> comments = new ArrayList<>();
        for (org.openrewrite.java.tree.Comment comment : cu.getPrefix().getComments()) {
            if (comment instanceof org.openrewrite.java.tree.TextComment textComment) {
                comments.add(new com.github.javaparser.ast.comments.LineComment(textComment.getText()));
            }
        }
        return comments;
    }

    /**
     * {@link #readHeader(J.CompilationUnit)} for the queue files that are <strong>not yet
     * ported</strong>.
     *
     * <p>Kept for the call sites that still hold a JavaParser tree — {@code FieldBoilerplateGenerator}
     * and {@code EntityMetadataGenerator} — where the comment is reachable through
     * {@code getAllComments()} and the JavaParser attachment rule (the last comment of a run attaches
     * to the following declaration) makes the {@code {...}} line above {@code package} visible. It is
     * deleted with the last of those callers.</p>
     *
     * @param cu a JavaParser compilation unit, from a call site that has not yet been ported
     */
    public static HeaderConfig readHeader(com.github.javaparser.ast.CompilationUnit cu) {
        List<String> diagnostics = new ArrayList<>();
        if (cu == null) {
            return HeaderConfig.none();
        }
        // Note: do NOT filter on getCommentedNode().isPresent(). JavaParser attaches the last
        // comment of a run to the following declaration, so the `{...}` config line that sits
        // immediately above `package` is *not* an orphan. An earlier version of this method
        // skipped exactly those comments and therefore never saw the marker at all.
        // The risk of reading a non-header comment is bounded: configLine() only accepts a line
        // whose trimmed content starts with '{', and a javadoc body never does.
        for (com.github.javaparser.ast.comments.Comment comment : cu.getAllComments()) {
            Optional<String> config = configLine(comment);
            if (config.isEmpty()) {
                continue;
            }
            String json = config.get().trim();
            if (json.isEmpty() || json.charAt(0) != '{') {
                continue;
            }
            return decode(json, diagnostics);
        }
        return HeaderConfig.none();
    }

    /** Decodes one header JSON5 blob, mapping a malformed one to the fail-safe "marked". */
    private static HeaderConfig decode(String json, List<String> diagnostics) {
        try {
            JsonNode node = JSON5.readTree(json);
            boolean marked = node.path(MARKER_KEY).asBoolean(false);
            boolean allowReorder = node.path("allowReorder").asBoolean(false);
            return new HeaderConfig(true, marked, allowReorder, diagnostics);
        } catch (RuntimeException e) {
            // Malformed marker: fail safe toward "marked".
            diagnostics.add("malformed_generator_header: could not decode '" + json
                    + "' (" + e.getMessage() + "); treating the enum as marked");
            return new HeaderConfig(true, true, false, diagnostics);
        }
    }

    /**
     * The configuration line of a two-line generator header: the JSON5 blob, or empty when the comment
     * carries none.
     *
     * <p>Takes a {@link com.github.javaparser.ast.comments.Comment} as a text carrier only, so both
     * parsers share one implementation. The comment's text may contain embedded newlines: the LST holds
     * the whole two-line DEC-021 header as <em>one</em> comment, so splitting on line breaks is what
     * finds the config line.</p>
     *
     * <p><strong>{@code &#123;@link} is not a config line.</strong> The header's first line is
     * {@code // {@link com.example.Foo} description}, which also begins with a brace. The JavaParser
     * version never had to distinguish the two because it scanned {@code getAllComments()}, where the
     * two header lines arrived as separate comments and the {@code &#123;@link} one produced no
     * {@code {…}} line at all. Concatenated into one LST comment, the first line is now a candidate —
     * and accepting it yields a {@code malformed_generator_header} diagnostic on a perfectly good
     * header, which the ledger tests catch. Rejecting the Javadoc-inline form is what keeps the marker
     * readable.</p>
     */
    private static Optional<String> configLine(com.github.javaparser.ast.comments.Comment comment) {
        for (String rawLine : comment.getContent().split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("//")) {
                line = line.substring(2).trim();
            }
            // `{@link …}`, `{@code …}`: a Javadoc inline tag, never JSON.
            if (line.startsWith("{@")) {
                continue;
            }
            if (line.startsWith("{")) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /**
     * The R1 comparison: is {@code baseline} a subsequence of {@code target}, preserving order?
     *
     * <p>A pure subsequence test is exactly right for append-only ledgers, and it is what makes
     * reordering and middle-insertion fail while appending passes:</p>
     * <ul>
     *   <li>append at the end &rarr; subsequence holds;</li>
     *   <li>reorder &rarr; fails, and the offending constant is reported with its old and new index;</li>
     *   <li>insert in the middle &rarr; fails and reads as a reorder of the following constant;</li>
     *   <li>remove &rarr; fails as a removal (a subsequence cannot skip an element of the
     *       <em>baseline</em>).</li>
     * </ul>
     */
    public static OrderVerdict compare(List<String> baseline, List<String> target) {
        List<String> violations = new ArrayList<>();

        // Removal first: every baseline constant must still exist, so a delete is reported as a
        // removal rather than as a reorder of everything after it.
        for (String constant : baseline) {
            if (!target.contains(constant)) {
                violations.add("enum_constant_removed: " + constant + " (was at index "
                        + baseline.indexOf(constant) + ", now absent)");
            }
        }
        if (!violations.isEmpty()) {
            return new OrderVerdict(false, violations);
        }

        // Then order. The check must catch BOTH a swap and a middle insertion, and a plain
        // "is baseline a subsequence" test does NOT catch the insertion:
        //
        //   baseline [id, firstName]   target [id, email, firstName]
        //
        // is a valid subsequence, yet `firstName`'s ordinal shifted 1 -> 2, which corrupts every
        // persisted array. The rule that catches both is: every constant that is still present in
        // the target and stands BEFORE the current baseline constant must itself be an EARLIER
        // baseline constant. An element that jumps the queue (a new constant, or a later baseline
        // constant) means the current constant moved.
        int maxSeenBaselineIndex = -1;
        java.util.Set<String> alreadyReported = new java.util.HashSet<>();
        for (String constant : baseline) {
            int baselineIndex = baseline.indexOf(constant);
            int targetIndex = target.indexOf(constant);
            for (int i = 0; i < targetIndex; i++) {
                int precedingBaselineIndex = baseline.indexOf(target.get(i));
                if (precedingBaselineIndex < 0) {
                    // A brand-new constant placed before an existing one: an insertion in the
                    // middle, so the existing constant's ordinal moved.
                    if (alreadyReported.add(constant)) {
                        violations.add("enum_order_shuffled: " + constant + " (old index " + baselineIndex
                                + ", new index " + targetIndex + "; " + target.get(i)
                                + " was inserted before it)");
                    }
                    break;
                }
                if (precedingBaselineIndex > baselineIndex && alreadyReported.add(target.get(i))) {
                    // Report the constant that jumped the queue, naming its old and new index: that
                    // is the constant whose ordinal actually changed.
                    violations.add("enum_order_shuffled: " + target.get(i) + " (old index "
                            + precedingBaselineIndex + ", new index " + i + ")");
                    break;
                }
            }
            if (baselineIndex < maxSeenBaselineIndex && alreadyReported.add(constant)) {
                violations.add("enum_order_shuffled: " + constant + " (old index " + baselineIndex
                        + ", new index " + targetIndex + ")");
            }
            maxSeenBaselineIndex = Math.max(maxSeenBaselineIndex, baselineIndex);
        }
        return new OrderVerdict(violations.isEmpty(), violations);
    }

    /** The outcome of one R1 comparison. */
    public record OrderVerdict(boolean ok, List<String> violations) {

        public OrderVerdict {
            violations = List.copyOf(violations);
        }
    }

    /** Convenience for tests and callers holding raw source text. */
    public static OrderVerdict compareSources(String baselineSource, String targetSource, String enumQualifiedName) {
        EnumLedger baseline = readLedgers(baselineSource).get(enumQualifiedName);
        EnumLedger target = readLedgers(targetSource).get(enumQualifiedName);
        if (baseline == null || target == null) {
            return new OrderVerdict(true, List.of());
        }
        // Opt-in by absence (R1.2/R1.3): an enum unmarked in the BASELINE revision is skipped, so
        // the bootstrap commit that first adds the marker is not reported as a mass removal.
        if (!baseline.guarded()) {
            return new OrderVerdict(true, List.of());
        }
        return compare(baseline.constants(), target.constants());
    }
}
