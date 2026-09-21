package hr.hrg.hipster.entity.tooling.validation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
     * <h3>Phase 6: the second-pass boundary</h3>
     * <p>This is the one queue file whose port needs the <em>emission</em> strategy rather than just
     * the read path: it mutates an enum's constant list and then prints the whole file, and the LST
     * printer will reformat what it prints (migration guide § 5). That decision belongs with the
     * generator port, so this method keeps JavaParser for now and is the explicit boundary of the
     * first pass.</p>
     *
     * <p>What changed here is only the parser it borrows: {@code SourceReader.parser()} used to hand
     * out a live parser, and that accessor is gone because handing out the parser is how the
     * single-read guarantee leaks. The same configured parser is now reached through
     * {@link hr.hrg.hipster.entity.tooling.SourceReader#portingParser()}, so this file cannot
     * construct a second, differently-configured parser behind the toolchain's back — which is the
     * exact defect ({@code new JavaParser()} reading at Java 11) that {@code SourceReader} exists to
     * prevent.</p>
     */
    static CompactionResult compactFile(Path file) throws IOException {
        ParseResult<CompilationUnit> parsed = hr.hrg.hipster.entity.tooling.SourceReader
                .portingParser().parse(Files.readString(file));
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            // A partial unit would compact the wrong thing, which is worse than not compacting.
            return CompactionResult.error("could not be parsed; left untouched");
        }
        CompilationUnit cu = parsed.getResult().get();
        EnumConstantOrderChecker.HeaderConfig header = EnumConstantOrderChecker.readHeader(cu);
        if (!header.marked()) {
            return CompactionResult.error("carries no " + EnumConstantOrderChecker.MARKER_KEY
                    + " marker, so it has no committed ledger to compact");
        }

        EnumDeclaration declaration = cu.findFirst(EnumDeclaration.class).orElse(null);
        if (declaration == null) {
            return CompactionResult.error("declares no enum");
        }
        String qualifiedName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString() + ".")
                .orElse("") + declaration.getNameAsString();

        List<EnumConstantDeclaration> entries = declaration.getEntries();
        List<String> survivors = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
            EnumConstantDeclaration entry = entries.get(ordinal);
            if (isTombstone(entry)) {
                dropped.add(new Dropped(qualifiedName, entry.getNameAsString(), ordinal));
            } else {
                survivors.add(entry.getNameAsString());
            }
        }
        if (dropped.isEmpty()) {
            return new CompactionResult(null, List.of(), List.of(), null);
        }

        // The report is computed against the original list, before anything is removed, so the
        // "moved" figures describe the migration rather than the result.
        List<String> original = new ArrayList<>();
        for (EnumConstantDeclaration entry : entries) {
            original.add(entry.getNameAsString());
        }
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

        for (EnumConstantDeclaration entry : new ArrayList<>(entries)) {
            if (isTombstone(entry)) {
                entry.remove();
            }
        }
        dropForNameArms(cu, dropped);

        PrettyPrinterConfiguration printer = new PrettyPrinterConfiguration();
        printer.setIndentSize(4);
        String source = cu.toString(printer).replace("switch(", "switch (");
        return new CompactionResult(source, moves, dropped, null);
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
     */
    private static boolean isTombstone(EnumConstantDeclaration entry) {
        boolean deprecated = entry.getAnnotations().stream()
                .anyMatch(annotation -> annotation.getNameAsString().endsWith("Deprecated"));
        if (!deprecated) {
            return false;
        }
        for (com.github.javaparser.ast.body.BodyDeclaration<?> member : entry.getClassBody()) {
            if (!(member instanceof MethodDeclaration method)
                    || !"retired".equals(method.getNameAsString())
                    || !method.getParameters().isEmpty()) {
                continue;
            }
            return method.getBody()
                    .filter(body -> body.getStatements().size() == 1)
                    .map(body -> body.getStatement(0))
                    .flatMap(com.github.javaparser.ast.stmt.Statement::toReturnStmt)
                    .flatMap(ReturnStmt::getExpression)
                    .filter(expression -> expression instanceof BooleanLiteralExpr)
                    .map(expression -> ((BooleanLiteralExpr) expression).getValue())
                    .orElse(false);
        }
        return false;
    }

    /**
     * Removes a {@code forName} arm whose label or returned constant names a dropped tombstone.
     *
     * <p>Leaving the arm behind would produce an enum that no longer compiles, and — worse — an enum
     * whose switch disagrees with its constant list, which is the {@code stale_switch} divergence the
     * generator reports on every later pass.</p>
     */
    private static void dropForNameArms(CompilationUnit cu, List<Dropped> dropped) {
        List<String> removed = dropped.stream().map(Dropped::constant).toList();
        for (SwitchStmt switchStmt : cu.findAll(SwitchStmt.class)) {
            for (SwitchEntry entry : new ArrayList<>(switchStmt.getEntries())) {
                boolean namesARemovedConstant = entry.getLabels().stream()
                        .filter(label -> label instanceof StringLiteralExpr)
                        .map(label -> ((StringLiteralExpr) label).asString())
                        .anyMatch(removed::contains)
                        || entry.getStatements().stream()
                                .flatMap(statement -> statement.findAll(
                                        com.github.javaparser.ast.expr.NameExpr.class).stream())
                                .anyMatch(name -> removed.contains(name.getNameAsString()));
                if (namesARemovedConstant) {
                    entry.remove();
                }
            }
        }
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
