package hr.hrg.hipster.entity.tooling.validation;

import org.openrewrite.java.tree.J;

import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The deliberate <strong>field-enum compaction mode</strong> of plan.dsflash § 12.4/7.13–7.16.
 *
 * <h3>What it does, and why it is not a normal pass</h3>
 * <p>It drops every R1.4 tombstone constant from a field enum and renumbers the survivors densely,
 * turning an append-only ledger back into a compact one. That is the <em>only</em> operation in the
 * plan that deliberately mutates a persisted ordinal layout, so it is not reachable from generation:
 * a normal pass never removes a constant from a marker-carrying enum, and this class refuses to act
 * unless it is invoked explicitly <em>and</em> the operator states that nothing positional survives
 * (see {@link #EXIT_REFUSED}).</p>
 *
 * <h3>The safety gate is two flags, never one</h3>
 * <p>{@code --allow-reorder} is the semantic acknowledgement and {@code
 * --acknowledge-drained-data} is the operational one. Requiring both is intentional redundancy: the
 * plan says the escape hatch must be "explicitly requested and the operator acknowledges that no
 * persisted positional array, JSON patch, or snapshot survives", and a single flag makes it possible
 * to type by reflex. Neither is implied by the other, and neither is implied by {@code --target}.</p>
 *
 * <h3>What it refuses to do</h3>
 * <ul>
 *   <li>It never touches an enum that does not carry the {@code entityFieldEnum:true} marker — a
 *       marker-less enum has no committed ledger, so a generation pass already bootstraps it and
 *       compacting it here would be a second, unreported opinion.</li>
 *   <li>It never guesses whether a constant is a tombstone. A constant is dropped only when it is
 *       annotated {@code @Deprecated} <strong>and</strong> declares {@code retired()} returning
 *       {@code true} — exactly the shape the generator emits. Anything else is reported and left
 *       alone, because misreading a live constant as a tombstone is precisely the data loss this
 *       class exists to prevent.</li>
 *   <li>It never renumbers silently: every ordinal that moved is named in the report.</li>
 *   <li>It refuses a file it cannot parse, rather than rewriting a partial one.</li>
 * </ul>
 *
 * <h3>The report is the migration record</h3>
 * <p>{@code allFields} in the metadata JSON carries a field's name, type and classification but
 * <strong>no ordinal</strong>, so there is nothing ordinal-shaped in it to rewrite. The migration
 * record is therefore this run's report — old ordinal, new ordinal, per constant — plus the
 * generator pass that follows it, which rewrites {@code allFields} from the compacted interfaces
 * (the tombstoned fields are gone, so they disappear from the JSON naturally). The procedure is
 * documented end to end in {@code user/patterns/field-enum-compaction.md}.</p>
 *
 * <h3>Phase 6: the deletion is a text splice, not an AST mutation</h3>
 * <p>The JavaParser version did {@code entry.remove()} on the constant and {@code entry.remove()} on
 * its {@code forName} arm, then printed the whole compilation unit. An LST node cannot be removed from
 * its parent — the tree is immutable — so the two deletions became two <strong>character spans</strong>
 * sliced out of the file, which is the same mechanism {@code CooperativeCodegen} uses to preserve a
 * member verbatim, and the same one {@code SourceSplicer} uses to add one.</p>
 *
 * <p>That has a consequence worth stating, because a later reader will notice it: the compacted file is
 * no longer the printer's normalisation of the whole unit, it is the original file minus two ranges.
 * For this command that is strictly better — nothing outside the deleted ranges moves, so a developer's
 * formatting anywhere else in the file survives a migration — and it changes no contract, because
 * {@code CompactionRoundTripTest} already records that compaction's output is not the generator's
 * canonical emission and that the documented procedure ends with a generation pass.</p>
 */
public final class EnumCompactionCli {

    /** Exit code for a refused run: the safety gate was not satisfied. */
    public static final int EXIT_REFUSED = 2;

    /** Exit code for a completed compaction. */
    public static final int EXIT_OK = 0;

    /** Exit code for a usage or I/O failure. */
    public static final int EXIT_ERROR = 3;

    private EnumCompactionCli() {
    }

    /** One constant that moved, for the report and for tests. */
    public record Move(String enumQualifiedName, String constant, int fromOrdinal, int toOrdinal) {
    }

    /** One constant that was dropped because it was a tombstone. */
    public record Dropped(String enumQualifiedName, String constant, int ordinal) {
    }

    /**
     * @param compactedFiles the files actually rewritten
     * @param moves          every constant whose ordinal changed
     * @param dropped        every tombstone removed
     * @param skipped        files examined and left alone, with the reason
     */
    public record Report(List<Path> compactedFiles, List<Move> moves, List<Dropped> dropped,
                         List<String> skipped) {

        public Report {
            compactedFiles = List.copyOf(compactedFiles);
            moves = List.copyOf(moves);
            dropped = List.copyOf(dropped);
            skipped = List.copyOf(skipped);
        }

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("field-enum compaction: ").append(compactedFiles.size()).append(" file(s) rewritten, ")
                    .append(dropped.size()).append(" tombstone(s) dropped, ")
                    .append(moves.size()).append(" ordinal(s) moved\n");
            for (Dropped drop : dropped) {
                sb.append("  dropped  ").append(drop.enumQualifiedName()).append('.')
                        .append(drop.constant()).append(" (was ordinal ").append(drop.ordinal()).append(")\n");
            }
            for (Move move : moves) {
                sb.append("  moved    ").append(move.enumQualifiedName()).append('.')
                        .append(move.constant()).append(' ').append(move.fromOrdinal())
                        .append(" -> ").append(move.toOrdinal()).append('\n');
            }
            for (String note : skipped) {
                sb.append("  skipped  ").append(note).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Compacts every marker-carrying field enum under {@code repoRoot}.
     *
     * @param target when non-null, only files whose path contains this string are considered
     */
    public static Report compact(Path repoRoot, String target) throws IOException {
        List<Path> candidates;
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            candidates = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("_.java"))
                    .filter(path -> !isExcluded(repoRoot, path))
                    .filter(path -> target == null || path.toString().replace('\\', '/').contains(target))
                    .sorted()
                    .toList();
        }

        List<Path> compacted = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Path file : candidates) {
            CompactionResult result = compactFile(file);
            if (result.error() != null) {
                skipped.add(relative(repoRoot, file) + ": " + result.error());
                continue;
            }
            if (result.dropped().isEmpty()) {
                skipped.add(relative(repoRoot, file) + ": no tombstones to drop");
                continue;
            }
            Files.writeString(file, result.source());
            compacted.add(file);
            moves.addAll(result.moves());
            dropped.addAll(result.dropped());
        }
        return new Report(compacted, moves, dropped, skipped);
    }

    private record CompactionResult(String source, List<Move> moves, List<Dropped> dropped, String error) {
        static CompactionResult error(String message) {
            return new CompactionResult(null, List.of(), List.of(), message);
        }
    }

    /**
     * Compacts one file's enum in memory, or explains why it will not.
     *
     * <p>Structure comes from the LST, the two deletions come from javac's character spans, and the
     * result is the file's own text with those spans cut out. Nothing else in the file is rewritten —
     * that is the property the JavaParser version's whole-unit print did not have.</p>
     */
    static CompactionResult compactFile(Path file) throws IOException {
        String source = Files.readString(file);
        // The shared, fail-safe read: a parser recovers from a syntax error and hands back a partial
        // unit, and compacting a partial unit would delete the wrong constants — which is worse than not
        // compacting. `SourceReader` refuses that case by asking javac for the syntax verdict.
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        if (unit == null) {
            return CompactionResult.error("could not be parsed; left untouched");
        }
        EnumConstantOrderChecker.HeaderConfig header = EnumConstantOrderChecker.readHeader(unit);
        if (!header.marked()) {
            return CompactionResult.error("carries no " + EnumConstantOrderChecker.MARKER_KEY
                    + " marker, so it has no committed ledger to compact");
        }

        J.ClassDeclaration declaration = firstEnum(unit);
        if (declaration == null) {
            return CompactionResult.error("declares no enum");
        }
        String qualifiedName = TreeQueries.packageName(unit) + declaration.getSimpleName();
        List<J.EnumValue> entries = enumConstants(declaration);

        List<String> survivors = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
            J.EnumValue entry = entries.get(ordinal);
            if (isTombstone(entry)) {
                dropped.add(new Dropped(qualifiedName, entry.getName().getSimpleName(), ordinal));
            } else {
                survivors.add(entry.getName().getSimpleName());
            }
        }
        if (dropped.isEmpty()) {
            return new CompactionResult(null, List.of(), List.of(), null);
        }

        // The report is computed against the original list, before anything is removed, so the
        // "moved" figures describe the migration rather than the result.
        List<String> original = entries.stream().map(entry -> entry.getName().getSimpleName()).toList();
        List<Move> moves = new ArrayList<>();
        for (int newOrdinal = 0; newOrdinal < survivors.size(); newOrdinal++) {
            String survivor = survivors.get(newOrdinal);
            int oldOrdinal = original.indexOf(survivor);
            if (oldOrdinal != newOrdinal) {
                moves.add(new Move(qualifiedName, survivor, oldOrdinal, newOrdinal));
            }
        }

        // The compacted list must still be a subsequence in order of the original: compaction removes,
        // it never shuffles. Checked with a dedicated test rather than with the R1 checker, because
        // the R1 checker reports a *removal* as `enum_constant_removed` — that is its whole job, since
        // a normal pass must never drop a constant. Compaction is the single sanctioned exception, so
        // asking the checker here would refuse the one operation it is meant to allow.
        if (!isSubsequenceInOrder(original, survivors)) {
            return CompactionResult.error("the compacted list would not be a subsequence in order of "
                    + "the original; refusing to renumber a live field");
        }

        Set<String> removedNames = dropped.stream().map(Dropped::constant)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new CompactionResult(withoutDroppedMembers(source, unit, declaration, removedNames),
                moves, dropped, null);
    }

    /**
     * {@code source} with every dropped constant, and every {@code forName} arm that names one, cut out.
     *
     * <p>Applied back to front, so the earlier ranges keep their offsets. Each deletion is widened to
     * whole lines: a constant sits in a comma-separated list and an arm is one line (or a block), so
     * removing only the declaration's own span would leave a dangling comma or a dangling
     * {@code case} label — source that does not compile, which is the failure this class exists to
     * avoid.</p>
     */
    private static String withoutDroppedMembers(String source, J.CompilationUnit unit,
                                                J.ClassDeclaration declaration, Set<String> removedNames) {
        List<TreeQueries.SourceSpan> removals = new ArrayList<>();
        for (String constant : removedNames) {
            TreeQueries.SourceSpan span = TreeQueries.memberTextSpan(declaration.getSimpleName(),
                    "enum-constant", constant, 0, source);
            if (span != null) {
                removals.add(constantRemoval(source, span));
            }
        }
        java.util.Map<J.Case, TreeQueries.SourceSpan> armSpans = TreeQueries.caseSpans(unit, source);
        for (J.Case arm : TreeQueries.findAll(unit, J.Case.class)) {
            TreeQueries.SourceSpan span = armSpans.get(arm);
            if (span != null && namesARemovedConstant(arm, removedNames)) {
                removals.add(lineRange(source, span));
            }
        }
        removals.sort(Comparator.comparingInt(TreeQueries.SourceSpan::start).reversed());
        StringBuilder out = new StringBuilder(source);
        for (TreeQueries.SourceSpan removal : removals) {
            out.replace(removal.start(), removal.end(), "");
        }
        return out.toString();
    }

    /**
     * The range to delete for one constant: its own lines, plus the comma that separated it.
     *
     * <p>Two shapes, and both have to be handled or the enum does not compile. A constant followed by
     * another one keeps its <em>trailing</em> comma, which goes with it. A constant that is the last in
     * the list keeps no comma, so the <em>preceding</em> one has to go instead — otherwise the enum ends
     * {@code firstName(...),;}.</p>
     */
    private static TreeQueries.SourceSpan constantRemoval(String source, TreeQueries.SourceSpan span) {
        int start = lineStart(source, span.start());
        int end = span.end();
        int probe = end;
        while (probe < source.length() && Character.isWhitespace(source.charAt(probe))) {
            probe++;
        }
        if (probe < source.length() && source.charAt(probe) == ',') {
            int to = probe + 1;
            while (to < source.length() && source.charAt(to) != '\n') {
                to++;
            }
            if (to < source.length()) {
                to++;
            }
            return new TreeQueries.SourceSpan(start, to);
        }
        // The last constant: take the comma in front of it, which also takes the line break and the
        // indentation between them, so the previous constant's line ends with its own `;` or `,`.
        int back = start - 1;
        while (back >= 0 && Character.isWhitespace(source.charAt(back))) {
            back--;
        }
        return new TreeQueries.SourceSpan(back >= 0 && source.charAt(back) == ',' ? back : start, end);
    }

    /** The whole lines one arm occupies. */
    private static TreeQueries.SourceSpan lineRange(String source, TreeQueries.SourceSpan span) {
        int to = span.end();
        while (to < source.length() && source.charAt(to) != '\n') {
            to++;
        }
        if (to < source.length()) {
            to++;
        }
        return new TreeQueries.SourceSpan(lineStart(source, span.start()), to);
    }

    /** The offset of the start of the line containing {@code offset}. */
    private static int lineStart(String source, int offset) {
        return source.lastIndexOf('\n', Math.max(offset - 1, 0)) + 1;
    }

    /**
     * Whether an arm's label or its body names a dropped constant.
     *
     * <p>Both halves are needed: {@code case "lastName": return lastName;} names it twice, and an arm
     * whose label is an ordinal may still assign the field. Leaving either behind produces an enum whose
     * switch disagrees with its constant list — the {@code stale_switch} divergence the generator reports
     * on every later pass.</p>
     */
    private static boolean namesARemovedConstant(J.Case arm, Set<String> removedNames) {
        if (arm.getCaseLabels() != null) {
            for (J label : arm.getCaseLabels()) {
                if (label instanceof J.Literal literal && literal.getValue() instanceof String text
                        && removedNames.contains(text)) {
                    return true;
                }
            }
        }
        List<J> roots = new ArrayList<>();
        if (arm.getStatements() != null) {
            roots.addAll(arm.getStatements());
        }
        if (arm.getBody() != null) {
            roots.add(arm.getBody());
        }
        for (J root : roots) {
            for (J.Identifier identifier : TreeQueries.findAll(root, J.Identifier.class)) {
                if (removedNames.contains(identifier.getSimpleName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first enum declaration in the unit, or {@code null}. */
    private static J.ClassDeclaration firstEnum(J.CompilationUnit unit) {
        List<J.ClassDeclaration> enums = TreeQueries.enums(unit);
        return enums.isEmpty() ? null : enums.get(0);
    }

    /** An enum's constants, in declaration order. */
    private static List<J.EnumValue> enumConstants(J.ClassDeclaration declaration) {
        List<J.EnumValue> constants = new ArrayList<>();
        if (declaration.getBody() == null) {
            return constants;
        }
        for (org.openrewrite.java.tree.Statement statement : declaration.getBody().getStatements()) {
            if (statement instanceof J.EnumValueSet values) {
                constants.addAll(values.getEnums());
            }
        }
        return constants;
    }

    /**
     * Whether {@code target} is {@code baseline} with entries removed and nothing reordered.
     *
     * <p>The exact property compaction must preserve, and the only one: every survivor appears in the
     * baseline, and the survivors appear in the same relative order. An entry that was never in the
     * baseline, or that overtook another survivor, fails.</p>
     */
    static boolean isSubsequenceInOrder(List<String> baseline, List<String> target) {
        int cursor = 0;
        for (String name : target) {
            int found = -1;
            for (int i = cursor; i < baseline.size(); i++) {
                if (baseline.get(i).equals(name)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return false;
            }
            cursor = found + 1;
        }
        return true;
    }

    /**
     * Whether a constant is an R1.4 tombstone: {@code @Deprecated} <strong>and</strong> a
     * {@code retired()} override returning {@code true}.
     *
     * <p>Both halves are required. The annotation alone can be a developer documenting an ordinary
     * deprecation, and dropping such a constant would silently move every ordinal after it.</p>
     *
     * <p>Phase 6: the class body of an enum constant hangs off its initialiser — an LST constant is an
     * annotation list, a name, and a {@code new} expression whose body is the block JavaParser held as
     * {@code getClassBody()}.</p>
     */
    private static boolean isTombstone(J.EnumValue entry) {
        boolean deprecated = entry.getAnnotations() != null && entry.getAnnotations().stream()
                .anyMatch(annotation -> TreeQueries.annotationName(annotation).endsWith("Deprecated"));
        if (!deprecated) {
            return false;
        }
        if (!(entry.getInitializer() instanceof J.NewClass creation) || creation.getBody() == null) {
            return false;
        }
        for (org.openrewrite.java.tree.Statement member : creation.getBody().getStatements()) {
            if (!(member instanceof J.MethodDeclaration method)
                    || !"retired".equals(method.getSimpleName())
                    || !TreeQueries.hasNoParameters(method)) {
                continue;
            }
            return returnsTrue(method);
        }
        return false;
    }

    /** Whether a method body is exactly {@code return true;}. */
    private static boolean returnsTrue(J.MethodDeclaration method) {
        if (method.getBody() == null || method.getBody().getStatements() == null
                || method.getBody().getStatements().size() != 1) {
            return false;
        }
        org.openrewrite.java.tree.Statement only = method.getBody().getStatements().get(0);
        if (!(only instanceof J.Return returns) || !(returns.getExpression() instanceof J.Literal literal)) {
            return false;
        }
        return Boolean.TRUE.equals(literal.getValue());
    }

    /** The build outputs and any second checkout must not be compacted (gate review GR-6). */
    private static boolean isExcluded(Path repoRoot, Path path) {
        String normalized = repoRoot.relativize(path).toString().replace('\\', '/');
        return normalized.startsWith("target/") || normalized.contains("/target/")
                || normalized.startsWith(".kilo/worktrees/") || normalized.startsWith("tmp/")
                || normalized.contains(".jcodebuddy/");
    }

    private static String relative(Path repoRoot, Path file) {
        return repoRoot.relativize(file).toString();
    }

    /** The CLI entry point, dispatched from {@code EntityMetadataGenerator}'s {@code compaction…} subcommand. */
    public static int run(String[] args) {
        Path repo = null;
        String target = null;
        boolean allowReorder = false;
        boolean drained = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> {
                    if (i + 1 < args.length) {
                        repo = Path.of(args[++i]);
                    }
                }
                case "--target" -> {
                    if (i + 1 < args.length) {
                        target = args[++i];
                    }
                }
                case "--allow-reorder" -> allowReorder = true;
                case "--acknowledge-drained-data" -> drained = true;
                default -> {
                    System.err.println("[compaction] unknown argument: " + args[i]);
                }
            }
        }

        if (repo == null) {
            System.err.println("Usage: enum-compact --repo <path> --allow-reorder "
                    + "--acknowledge-drained-data [--target <path-substring>]");
            return EXIT_ERROR;
        }
        if (!allowReorder || !drained) {
            System.err.println("[compaction] REFUSED. Compaction renumbers a persisted positional layout, "
                    + "so it needs both acknowledgements, not one:");
            System.err.println("  --allow-reorder             the ordinal layout may change");
            System.err.println("  --acknowledge-drained-data  no positional array, JSON patch or snapshot survives");
            System.err.println("Neither flag implies the other, and neither is implied by --target.");
            return EXIT_REFUSED;
        }

        try {
            Report report = compact(repo, target);
            System.out.println(report.render());
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("[compaction] failed: " + e.getMessage());
            return EXIT_ERROR;
        }
    }
}
