package hr.hrg.hipster.entity.tooling.index;

import hr.hrg.jcodebuddy.engine.MetadataJson;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The affected-test selector over the generated test index (plan
 * {@code plans/incremental-test-selection.md} § 5.2, step T3).
 *
 * <h3>What it does</h3>
 * <p>It answers one question — "given these changed repository files, which tests must run?" — from the
 * measured table {@code <module>/.jcodebuddy/index/tests.json} (T1) rather than from a hand-written list,
 * and it answers with a <strong>reason chain</strong> for every selected test: the changed file, the FQN
 * it maps to, the closure path through the production graph, and the test. It runs no Maven goal and writes
 * no file; its only side effect is the text it prints. {@code --explain} in particular is a pure read, so a
 * developer can ask "why would this run?" without changing the state the next {@code test-affected} run
 * reads (plan T3 gate).</p>
 *
 * <h3>The rules (plan § 5.2)</h3>
 * <ol>
 *   <li><strong>Direct production hit.</strong> A changed production FQN in a unit test's
 *       {@code productionClosure} selects that test; a direct dependency names it, an indirect one reaches
 *       it through the closure.</li>
 *   <li><strong>Test-support hit.</strong> A changed test FQN selects the test itself and every test whose
 *       {@code testDeps} names it; the same rule selects the dependents of a changed test-support class
 *       ({@code CompileHarness}, a fixture).</li>
 *   <li><strong>A changed test file selects that test</strong> even when it is not a unit test. Such a test
 *       is reported as <em>selected but not runnable</em> — the affected lane cannot run a
 *       {@code component}/{@code generator}/{@code compiler}/{@code completeness} class, and the report
 *       says so rather than silently dropping it.</li>
 *   <li><strong>A changed {@code pom.xml} selects every unit test.</strong> It changes the module's compile
 *       inputs, so every measured dependency is suspect.</li>
 *   <li><strong>An unmapped changed path under a source or resource root selects every unit test.</strong>
 *       A file the index has no type for is exactly the under-selection this table exists to prevent, so
 *       the selector falls back conservatively and names the file.</li>
 *   <li><strong>A missing, corrupt or stale index selects every unit test</strong> and reports why. A
 *       missing/corrupt index has no rows to read, so the fallback enumerates the unit tests from the
 *       module's {@code src/test/java} {@code @Tag("unit")} classes; the selector still never guesses
 *       silently. A <em>stale</em> index is detected by re-hashing each test row's compiled class file
 *       against the checksum T1 recorded: a mismatch means the index predates the last
 *       {@code test-compile}.</li>
 * </ol>
 *
 * <h3>Lane policy</h3>
 * <p>By default only the {@code unit} lane is selected. {@code --include-component} also selects the
 * {@code component} lane. A class carrying {@code generator}, {@code compiler} or {@code completeness} is
 * never selected by a production or test-support hit — those lanes are the full suite's, not the affected
 * lane's (plan § 3). A changed test file is the one exception: it is reported so the caller can see that
 * this change cannot be validated by the affected lane at all.</p>
 *
 * <h3>Contract</h3>
 * <p>The CLI is the small surface {@code scripts/test-affected.js} (T4) will call:</p>
 * <pre>
 *   --index &lt;file&gt;        the generated .jcodebuddy/index/tests.json (required)
 *   --changed &lt;paths&gt;     comma-separated, repo-relative changed paths (repeatable)
 *   --root &lt;dir&gt;          the repository root (default: the current directory)
 *   --module-dir &lt;dir&gt;    the test module (default: derived from the index path or its rows)
 *   --all-unit            select every unit test
 *   --include-component   also select affected component tests
 *   --explain             print changed file -&gt; FQN -&gt; test chains; write nothing
 *   --json                print the machine-readable selection and reasons
 * </pre>
 *
 * <p>Changed paths are normalized to forward slashes and made repo-relative when they are absolute and
 * under {@code --root}. A path outside every recorded source/resource root is unrelated and selects
 * nothing; a path under one of them with no type in {@code files} is the unmapped fallback.</p>
 *
 * <p>This class is test-scoped for the same reason T1's generator is: the index it reads is a fact about a
 * test run's compiled classpath, and the selector is a dev-time probe, not a runtime dependency of the
 * library. It is invoked from {@code target/test-classes}, as {@code scripts/test-index.js} already does
 * for {@link TestIndexGenerator}.</p>
 */
public final class TestSelector {

    /** The index format this selector understands. An unknown value is refused, never guessed. */
    public static final int FORMAT = 1;

    /** The lane tag that is the affected selector's default unit. */
    public static final String UNIT = "unit";

    /** The small-subsystem lane, selected only on request ({@code --include-component}). */
    public static final String COMPONENT = "component";

    /** The lanes that never run through the affected selector (plan § 3). */
    private static final List<String> HEAVY_LANES = List.of("completeness", "compiler", "generator");

    /**
     * The kinds for which the selection is "every unit test"; {@code --explain} prints them compactly
     * instead of repeating one chain per test.
     */
    private static final Set<String> BULK_KINDS =
            Set.of("pom", "unmapped", "all-unit", "missing-index", "corrupt-index", "stale-index");

    private static final String POM = "pom.xml";
    private static final String MAIN_JAVA = "/src/main/java/";
    private static final String TEST_JAVA = "/src/test/java/";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern UNIT_TAG = Pattern.compile("@Tag\\s*\\(\\s*\"unit\"\\s*\\)");

    private TestSelector() {
    }

    // ── the contract ───────────────────────────────────────────────────────────────────────────────

    /** One selection request. {@code root} and {@code moduleDir} may be {@code null}. */
    public record Options(Path root, Path indexFile, Path moduleDir, List<String> changed,
                          boolean allUnit, boolean includeComponent) {

        public Options {
            Objects.requireNonNull(indexFile, "indexFile");
            changed = List.copyOf(changed == null ? List.of() : changed);
        }
    }

    /**
     * One reason in a chain.
     *
     * @param kind        {@code direct-production}, {@code indirect-production}, {@code test-support},
     *                    {@code changed-test}, {@code changed-test-dependency}, {@code pom},
     *                    {@code unmapped}, {@code all-unit}, {@code no-dependent}, {@code unrelated}, or
     *                    one of the fallback kinds
     * @param changedFile the normalized changed path, or {@code null} for an index-level fallback
     * @param changedFqn  the FQN the changed path mapped to, or {@code null} when it mapped to nothing
     * @param test        the test this reason selects, or {@code null} for a trace/fallback reason
     * @param lane        the test's lane, or {@code null} for a trace/fallback reason
     * @param runnable    whether the affected lane may run it (unit, or component with
     *                    {@code --include-component})
     * @param chain       the FQNs from the changed class to the test's direct production dependency, or the
     *                    single changed FQN for a test-support/test-file hit
     * @param detail      one line a human can act on
     */
    public record Reason(String kind, String changedFile, String changedFqn, String test, String lane,
                         boolean runnable, List<String> chain, String detail) {

        public Reason {
            chain = List.copyOf(chain == null ? List.of() : chain);
        }
    }

    /** Why the selector stopped trusting the index. {@code kind} is also the reason kind of its rows. */
    public record Fallback(String kind, String detail) {
    }

    /** The result: the runnable selection, the affected-but-unrunnable tests, and every reason. */
    public record Selection(String module, String index, List<String> changed, boolean allUnit,
                            Fallback fallback, List<Reason> reasons, List<String> notes) {

        public Selection {
            changed = List.copyOf(changed == null ? List.of() : changed);
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            notes = List.copyOf(notes == null ? List.of() : notes);
        }

        /** The FQNs the affected lane may run, sorted and de-duplicated. */
        public List<String> selectedFqns() {
            return fqns(true);
        }

        /** The affected tests the affected lane cannot run (only a changed non-unit test reaches here). */
        public List<String> notRunnableFqns() {
            return fqns(false);
        }

        private List<String> fqns(boolean runnable) {
            Set<String> names = new TreeSet<>();
            for (Reason reason : reasons) {
                if (reason.test() != null && reason.runnable() == runnable) {
                    names.add(reason.test());
                }
            }
            return List.copyOf(names);
        }

        /**
         * The human {@code --explain} report: the changed file, the type it maps to, and for every test the
         * chain that reaches it. A bulk fallback is printed once with its selected tests rather than once
         * per test, because eleven identical "pom.xml -&gt; test" lines say less than one line and a list.
         */
        public String explain() {
            StringBuilder text = new StringBuilder();
            text.append("test selection for ").append(module.isEmpty() ? "(unknown module)" : module)
                    .append(" (index: ").append(index).append(")\n");
            if (fallback != null) {
                text.append("  fallback: ").append(fallback.kind()).append(" - ").append(fallback.detail())
                        .append("\n");
            }
            text.append("  changed files: ").append(changed.size()).append("\n");

            Map<String, List<Reason>> byFile = new LinkedHashMap<>();
            List<Reason> global = new ArrayList<>();
            for (Reason reason : reasons) {
                if (reason.changedFile() == null) {
                    global.add(reason);
                } else {
                    byFile.computeIfAbsent(reason.changedFile(), key -> new ArrayList<>()).add(reason);
                }
            }

            for (String path : changed) {
                text.append("  ").append(path).append("\n");
                List<Reason> group = byFile.get(path);
                if (group == null || group.isEmpty()) {
                    text.append("    -> no indexed type, and not under a source/resource root: "
                            + "nothing selected\n");
                    continue;
                }
                appendGroup(text, group);
            }
            if (!global.isEmpty()) {
                appendGroup(text, global);
            }

            text.append("  selected: ").append(selectedFqns().size()).append(" runnable test(s)\n");
            for (String fqn : selectedFqns()) {
                text.append("    ").append(fqn).append("\n");
            }
            if (!notRunnableFqns().isEmpty()) {
                text.append("  affected but NOT RUN by this lane (use the fast/full lane):\n");
                for (String fqn : notRunnableFqns()) {
                    text.append("    ").append(fqn).append("\n");
                }
            }
            for (String note : notes) {
                text.append("  note: ").append(note).append("\n");
            }
            return text.toString();
        }

        private static void appendGroup(StringBuilder text, List<Reason> group) {
            List<Reason> bulk = new ArrayList<>();
            for (Reason reason : group) {
                if (reason.test() == null) {
                    text.append("    -> ")
                            .append(reason.changedFqn() == null ? "(" + reason.kind() + ")"
                                    : reason.changedFqn())
                            .append(": ").append(reason.detail()).append("\n");
                } else if (BULK_KINDS.contains(reason.kind())) {
                    bulk.add(reason);
                } else {
                    appendChain(text, reason);
                }
            }
            if (!bulk.isEmpty()) {
                Set<String> tests = new TreeSet<>();
                for (Reason reason : bulk) {
                    tests.add(reason.test());
                }
                Reason sample = bulk.get(0);
                text.append("    -> all unit tests (").append(tests.size()).append("): ")
                        .append(String.join(", ", tests)).append("\n");
                text.append("       ").append(sample.kind()).append(": ").append(sample.detail()).append("\n");
            }
        }

        private static void appendChain(StringBuilder text, Reason reason) {
            text.append("    -> ");
            List<String> chain = reason.chain();
            boolean self = chain.size() == 1 && chain.get(0).equals(reason.test());
            if (!self) {
                if (chain.isEmpty()) {
                    text.append(reason.changedFqn() == null ? "(unknown)" : reason.changedFqn());
                } else {
                    text.append(String.join(" -> ", chain));
                }
                text.append(" -> ");
            }
            text.append(reason.test());
            text.append(" [").append(reason.lane()).append(", ").append(reason.kind());
            if (!reason.runnable()) {
                text.append(", NOT RUN BY THE AFFECTED LANE");
            }
            text.append(']');
            if (reason.detail() != null && !reason.detail().isBlank()) {
                text.append(" - ").append(reason.detail());
            }
            text.append("\n");
        }

        /** The default (neither {@code --explain} nor {@code --json}) one-screen answer. */
        public String summary() {
            StringBuilder text = new StringBuilder();
            text.append("test selection for ").append(module.isEmpty() ? "(unknown module)" : module)
                    .append(": ").append(selectedFqns().size()).append(" runnable test(s)");
            if (allUnit) {
                text.append(" (all unit)");
            }
            text.append("\n");
            for (String fqn : selectedFqns()) {
                text.append("  ").append(fqn).append("\n");
            }
            if (!notRunnableFqns().isEmpty()) {
                text.append("affected but NOT RUN by this lane (use the fast/full lane):\n");
                for (String fqn : notRunnableFqns()) {
                    text.append("  ").append(fqn).append("\n");
                }
            }
            if (fallback != null) {
                text.append("fallback: ").append(fallback.kind()).append(" - ").append(fallback.detail())
                        .append("\n");
            }
            for (String note : notes) {
                text.append("note: ").append(note).append("\n");
            }
            return text.toString();
        }

        /**
         * The machine-readable form (plan § 5.3). {@code selected} is what the runner may run;
         * {@code notRunnable} is what the change affected but this lane cannot run; {@code reasons} keeps
         * the whole chain for a caller that wants to explain itself.
         */
        public String toJson() {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("format", FORMAT);
            root.put("module", module);
            root.put("index", index);
            root.put("allUnit", allUnit);
            if (fallback != null) {
                ObjectNode node = root.putObject("fallback");
                node.put("kind", fallback.kind());
                node.put("detail", fallback.detail());
            }
            var changedNode = root.putArray("changed");
            changed.forEach(changedNode::add);
            var selectedNode = root.putArray("selected");
            selectedFqns().forEach(selectedNode::add);
            var notRunnableNode = root.putArray("notRunnable");
            notRunnableFqns().forEach(notRunnableNode::add);
            var reasonsNode = root.putArray("reasons");
            for (Reason reason : reasons) {
                ObjectNode node = reasonsNode.addObject();
                node.put("kind", reason.kind());
                if (reason.changedFile() != null) {
                    node.put("changedFile", reason.changedFile());
                }
                if (reason.changedFqn() != null) {
                    node.put("changedFqn", reason.changedFqn());
                }
                if (reason.test() != null) {
                    node.put("test", reason.test());
                }
                if (reason.lane() != null) {
                    node.put("lane", reason.lane());
                }
                node.put("runnable", reason.runnable());
                var chainNode = node.putArray("chain");
                reason.chain().forEach(chainNode::add);
                if (reason.detail() != null) {
                    node.put("detail", reason.detail());
                }
            }
            var notesNode = root.putArray("notes");
            notes.forEach(notesNode::add);
            return MetadataJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
        }
    }

    // ── selection ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the selection. It reads the index (and, for a missing/corrupt one, the module's unit tags from
     * source) and writes nothing.
     *
     * @throws IOException when the test source tree cannot be read for the fallback discovery
     */
    public static Selection select(Options options) throws IOException {
        Path root = (options.root() == null ? Path.of("") : options.root()).toAbsolutePath().normalize();
        Path indexFile = options.indexFile().isAbsolute()
                ? options.indexFile().toAbsolutePath().normalize()
                : root.resolve(options.indexFile()).normalize();
        Path explicitModule = null;
        if (options.moduleDir() != null) {
            explicitModule = options.moduleDir().isAbsolute()
                    ? options.moduleDir().toAbsolutePath().normalize()
                    : root.resolve(options.moduleDir()).normalize();
        }
        List<String> changed = normalizeChanged(options.changed(), root);

        List<Reason> reasons = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String indexDisplay = display(root, indexFile);
        Fallback fallback = null;
        Index index = null;
        String moduleName = "";

        if (!Files.isRegularFile(indexFile)) {
            fallback = new Fallback("missing-index", indexDisplay + " does not exist");
        } else {
            try {
                JsonNode rootNode = MetadataJson.mapper()
                        .readTree(Files.readString(indexFile, StandardCharsets.UTF_8));
                index = parse(rootNode);
                moduleName = index.module();
            } catch (Exception failure) {
                fallback = new Fallback("corrupt-index", indexDisplay + " is not a usable format-" + FORMAT
                        + " test index: " + oneLine(failure.getMessage()));
            }
        }

        Path moduleDir = explicitModule;
        if (moduleDir == null) {
            moduleDir = moduleDirFromIndexPath(indexFile);
            if (moduleDir == null && index != null) {
                moduleDir = moduleDirFromRows(root, index);
            }
        }
        if (moduleName.isEmpty() && moduleDir != null && moduleDir.getFileName() != null) {
            moduleName = moduleDir.getFileName().toString();
        }

        if (fallback == null && index != null && !options.allUnit()) {
            String stale = staleDetail(index, moduleDir);
            if (stale != null) {
                fallback = new Fallback("stale-index", stale);
            }
        }

        if (fallback != null) {
            List<String> units;
            if (index != null) {
                units = unitFqns(index);
                if (units.isEmpty() && moduleDir != null) {
                    units = discoverUnitTests(moduleDir);
                }
            } else {
                units = discoverUnitTests(moduleDir);
            }
            for (String fqn : units) {
                reasons.add(new Reason(fallback.kind(), null, null, fqn, UNIT, true, List.of(),
                        fallback.detail()));
            }
            if (units.isEmpty()) {
                notes.add("cannot enumerate this module's unit tests: "
                        + (index == null ? "the index is unusable and no src/test/java was found"
                                : "the index has no unit row")
                        + ". Run bun scripts/test-index.js, or the fast lane.");
            }
            if (options.allUnit()) {
                notes.add("--all-unit was requested; the fallback selects every unit test");
            }
            return new Selection(moduleName, indexDisplay, changed, true, fallback, reasons, notes);
        }

        if (options.allUnit()) {
            addAllUnit(index, reasons, "all-unit", null, null, "--all-unit selects every unit test");
            if (reasons.isEmpty()) {
                notes.add("the index has no unit test rows");
            }
            return new Selection(moduleName, indexDisplay, changed, true, null, reasons, notes);
        }

        if (changed.isEmpty()) {
            notes.add("no changed paths and no --all-unit: nothing selected");
            return new Selection(moduleName, indexDisplay, changed, false, null, reasons, notes);
        }

        boolean allUnit = false;
        for (String path : changed) {
            if (isPom(path)) {
                allUnit = true;
                addAllUnit(index, reasons, "pom", path, null,
                        "a pom.xml change invalidates the module's compile inputs");
                continue;
            }
            String fqn = index.files().get(path);
            if (fqn == null) {
                if (underSourceRoot(path, index, moduleDir, root)) {
                    allUnit = true;
                    addAllUnit(index, reasons, "unmapped", path, null,
                            "no type in the index for this path under a source/resource root");
                } else {
                    reasons.add(new Reason("unrelated", path, null, null, null, false, List.of(),
                            "outside the module's source/resource roots; no test can depend on it"));
                }
                continue;
            }
            selectForFqn(index, path, fqn, options.includeComponent(), reasons);
        }

        if (index.tests().values().stream().anyMatch(row -> "unclassified".equals(lane(row.tags())))) {
            notes.add("the index has an untagged test row; the lane contract should fail (run "
                    + "bun scripts/check-test-lanes.js) and affected selection cannot see it");
        }
        return new Selection(moduleName, indexDisplay, changed, allUnit, null, reasons, notes);
    }

    /** The three ways a mapped FQN selects tests: it is a test, a production class, or test support. */
    private static void selectForFqn(Index index, String changedFile, String fqn, boolean includeComponent,
                                     List<Reason> reasons) {
        TestRow changedTest = index.tests().get(fqn);
        if (changedTest != null) {
            String lane = lane(changedTest.tags());
            boolean runnable = runnable(lane, includeComponent);
            String detail;
            if (runnable) {
                detail = COMPONENT.equals(lane)
                        ? "the changed file is this test (component, selected by --include-component)"
                        : "the changed file is this test";
            } else {
                detail = "lane '" + lane + "' cannot run through the affected lane; use the fast/full lane"
                        + (COMPONENT.equals(lane) ? " or --include-component" : "");
            }
            reasons.add(new Reason("changed-test", changedFile, fqn, fqn, lane, runnable,
                    List.of(fqn), detail));
            selectTestDependents(index, changedFile, fqn, includeComponent, reasons,
                    "changed-test-dependency", false);
            return;
        }

        boolean production = index.production().containsKey(fqn) || !isTestPath(changedFile);
        if (production) {
            selectProductionDependents(index, changedFile, fqn, includeComponent, reasons);
        } else {
            selectTestDependents(index, changedFile, fqn, includeComponent, reasons, "test-support", true);
        }
    }

    /**
     * Rule 3: a changed production class selects every runnable test whose {@code productionClosure}
     * contains it, directly or through the recorded production graph.
     */
    private static void selectProductionDependents(Index index, String changedFile, String changedFqn,
                                                   boolean includeComponent, List<Reason> reasons) {
        String changed = topLevel(changedFqn);
        int selected = 0;
        int outsideLane = 0;
        for (TestRow row : index.tests().values()) {
            String lane = lane(row.tags());
            boolean runnable = runnable(lane, includeComponent);
            Set<String> direct = topLevels(row.productionDeps());
            boolean affected = topLevels(row.productionClosure()).contains(changed)
                    || direct.contains(changed);
            if (!affected) {
                continue;
            }
            if (!runnable) {
                outsideLane++;
                continue;
            }
            boolean directHit = direct.contains(changed);
            List<String> chain = directHit
                    ? List.of(changed)
                    : pathToDirect(changed, direct, index.dependents());
            reasons.add(new Reason(directHit ? "direct-production" : "indirect-production", changedFile,
                    changedFqn, row.fqn(), lane, true, chain,
                    directHit ? "the test names the changed class directly"
                            : "the changed class reaches the test through its production closure"));
            selected++;
        }
        if (selected == 0) {
            reasons.add(new Reason("no-dependent", changedFile, changedFqn, null, null, false,
                    List.of(changed), outsideLane == 0
                            ? "no test's production closure contains this class"
                            : "no unit test's production closure contains this class; " + outsideLane
                                    + " affected test(s) are outside the unit lane"));
        }
    }

    /**
     * Rules 4-5: a changed test or test-support FQN selects every test whose {@code testDeps} names it.
     * The changed test itself is handled by the caller.
     */
    private static void selectTestDependents(Index index, String changedFile, String changedFqn,
                                             boolean includeComponent, List<Reason> reasons, String kind,
                                             boolean traceWhenNone) {
        String changed = topLevel(changedFqn);
        int selected = 0;
        int outsideLane = 0;
        for (TestRow row : index.tests().values()) {
            if (row.fqn().equals(changedFqn) || !topLevels(row.testDeps()).contains(changed)) {
                continue;
            }
            String lane = lane(row.tags());
            if (!runnable(lane, includeComponent)) {
                outsideLane++;
                continue;
            }
            reasons.add(new Reason(kind, changedFile, changedFqn, row.fqn(), lane, true, List.of(changed),
                    "the test names the changed class in its testDeps"));
            selected++;
        }
        if (traceWhenNone && selected == 0) {
            reasons.add(new Reason("no-dependent", changedFile, changedFqn, null, null, false,
                    List.of(changed), outsideLane == 0
                            ? "no test names this test-support class in testDeps"
                            : outsideLane + " dependent test(s) are outside the unit lane"));
        }
    }

    /** Selects every unit test, for one of the conservative rules 6-7 or for {@code --all-unit}. */
    private static void addAllUnit(Index index, List<Reason> reasons, String kind, String changedFile,
                                   String changedFqn, String detail) {
        for (String fqn : unitFqns(index)) {
            reasons.add(new Reason(kind, changedFile, changedFqn, fqn, UNIT, true, List.of(), detail));
        }
    }

    // ── the index ─────────────────────────────────────────────────────────────────────────────────

    /** The parsed table: the files map, the test rows, the production rows, and the reverse graph. */
    private record Index(String module, Map<String, String> files, Map<String, TestRow> tests,
                         Map<String, ProductionRow> production, Map<String, Set<String>> dependents) {
    }

    private record TestRow(String fqn, String file, Set<String> tags, Set<String> productionDeps,
                           Set<String> testDeps, Set<String> productionClosure, String checksum) {
    }

    private record ProductionRow(String fqn, String file, String module, Set<String> deps) {
    }

    /**
     * Parses and validates the table. A structural problem is {@link IndexProblem}, which the caller turns
     * into the conservative all-unit fallback; a row with a missing optional field is tolerated because the
     * selector's job is selection, not re-litigating T1's writer.
     */
    private static Index parse(JsonNode root) {
        if (!root.isObject()) {
            throw new IndexProblem("the root is not a JSON object");
        }
        JsonNode format = root.path("format");
        if (!format.isIntegralNumber() || format.asInt() != FORMAT) {
            throw new IndexProblem("format is " + format + ", expected " + FORMAT);
        }
        JsonNode filesNode = root.path("files");
        JsonNode testsNode = root.path("tests");
        JsonNode productionNode = root.path("production");
        if (!filesNode.isObject() || !testsNode.isObject() || !productionNode.isObject()) {
            throw new IndexProblem("the files, tests and production tables must all be JSON objects");
        }

        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : filesNode.properties()) {
            files.put(entry.getKey(), entry.getValue().asString());
        }

        Map<String, TestRow> tests = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : testsNode.properties()) {
            JsonNode row = entry.getValue();
            String fqn = entry.getKey();
            tests.put(fqn, new TestRow(fqn, row.path("file").asString(""),
                    strings(row.path("tags")), strings(row.path("productionDeps")),
                    strings(row.path("testDeps")), strings(row.path("productionClosure")),
                    row.path("checksum").asString("")));
        }

        Map<String, ProductionRow> production = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : productionNode.properties()) {
            JsonNode row = entry.getValue();
            String fqn = entry.getKey();
            production.put(fqn, new ProductionRow(fqn, row.path("file").asString(""),
                    row.path("module").asString(""), strings(row.path("deps"))));
        }

        Map<String, Set<String>> dependents = new TreeMap<>();
        for (ProductionRow row : production.values()) {
            for (String dep : row.deps()) {
                String dependency = topLevel(dep);
                if (!dependency.equals(row.fqn())) {
                    dependents.computeIfAbsent(dependency, key -> new TreeSet<>()).add(row.fqn());
                }
            }
        }
        return new Index(root.path("module").asString(""), files, tests, production, dependents);
    }

    /** A table whose format, shape or values the selector must not trust. */
    private static final class IndexProblem extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private IndexProblem(String message) {
            super(message);
        }
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new TreeSet<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                values.add(node.asString());
            }
        }
        return values;
    }

    /** The unit rows, sorted because the table's keys are. */
    private static List<String> unitFqns(Index index) {
        List<String> units = new ArrayList<>();
        for (TestRow row : index.tests().values()) {
            if (UNIT.equals(lane(row.tags()))) {
                units.add(row.fqn());
            }
        }
        return units;
    }

    /**
     * The stale-index check: T1 records the compiled class file's checksum in every test row, so a mismatch
     * is direct evidence that the table predates the last {@code test-compile}. A row whose class file is
     * not present is not checked — the index may have been produced in another checkout, and refusing it
     * there would make the table unusable without local compiled output.
     */
    private static String staleDetail(Index index, Path moduleDir) {
        if (moduleDir == null) {
            return null;
        }
        Path testClasses = moduleDir.resolve("target").resolve("test-classes");
        if (!Files.isDirectory(testClasses)) {
            return null;
        }
        for (TestRow row : index.tests().values()) {
            if (row.checksum().isBlank()) {
                continue;
            }
            Path classFile = testClasses.resolve(row.fqn().replace('.', '/') + ".class");
            if (!Files.isRegularFile(classFile)) {
                continue;
            }
            String actual;
            try {
                actual = ContentHash.of(Files.readAllBytes(classFile));
            } catch (IOException failure) {
                return "cannot read " + classFile + " to verify the index row " + row.fqn() + ": "
                        + failure.getMessage();
            }
            if (!row.checksum().equals(actual)) {
                return "test row " + row.fqn() + " records checksum " + row.checksum()
                        + " but its compiled class is " + actual
                        + "; the index predates the last test-compile, so regenerate it with "
                        + "bun scripts/test-index.js";
            }
        }
        return null;
    }

    // ── the reason chain ──────────────────────────────────────────────────────────────────────────

    /**
     * A shortest path from the changed production class <em>up</em> the recorded production graph to one of
     * the test's direct dependencies: {@code changed -> ... -> directDep}. The graph is read in the
     * dependent direction ({@code production} rows record what each class depends on), which is the
     * direction a change propagates.
     */
    private static List<String> pathToDirect(String changed, Set<String> directDeps,
                                             Map<String, Set<String>> dependents) {
        if (directDeps.contains(changed)) {
            return List.of(changed);
        }
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        parent.put(changed, null);
        queue.add(changed);
        String found = null;
        while (!queue.isEmpty() && found == null) {
            String current = queue.poll();
            for (String next : dependents.getOrDefault(current, Set.of())) {
                if (parent.containsKey(next)) {
                    continue;
                }
                parent.put(next, current);
                if (directDeps.contains(next)) {
                    found = next;
                    break;
                }
                queue.add(next);
            }
        }
        if (found == null) {
            // The closure said the class is reachable but the recorded edges give no path: keep the
            // reason truthful rather than inventing intermediate hops.
            return List.of(changed);
        }
        List<String> chain = new ArrayList<>();
        for (String at = found; at != null; at = parent.get(at)) {
            chain.addFirst(at);
        }
        return List.copyOf(chain);
    }

    // ── paths and lanes ──────────────────────────────────────────────────────────────────────────

    private static List<String> normalizeChanged(List<String> changed, Path root) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : changed) {
            String value = normalizeChangedPath(raw, root);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizeChangedPath(String raw, Path root) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip().replace('\\', '/');
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.isEmpty()) {
            return "";
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                Path absolute = path.normalize();
                if (absolute.startsWith(root)) {
                    return root.relativize(absolute).toString().replace('\\', '/');
                }
                return absolute.toString().replace('\\', '/');
            }
            return path.normalize().toString().replace('\\', '/');
        } catch (InvalidPathException notAPath) {
            return value;
        }
    }

    /** The path as the index spells it: repo-relative when it is under the root. */
    private static String display(Path root, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return root.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString().replace('\\', '/');
    }

    private static boolean isPom(String path) {
        return path.equals(POM) || path.endsWith("/" + POM);
    }

    private static boolean isTestPath(String path) {
        return path.startsWith("src/test/java/") || path.contains(TEST_JAVA);
    }

    /** Whether an unmapped path is under one of the source/resource roots the plan's inputs name. */
    private static boolean underSourceRoot(String path, Index index, Path moduleDir, Path root) {
        Set<String> roots = new TreeSet<>();
        for (String file : index.files().keySet()) {
            addSourceRoots(roots, file);
        }
        for (TestRow row : index.tests().values()) {
            addSourceRoots(roots, row.file());
        }
        if (moduleDir != null && moduleDir.startsWith(root)) {
            addModuleRoots(roots, root.relativize(moduleDir).toString().replace('\\', '/'));
        }
        for (String sourceRoot : roots) {
            if (path.startsWith(sourceRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    private static void addSourceRoots(Set<String> roots, String file) {
        int at = file.indexOf(TEST_JAVA);
        if (at < 0) {
            at = file.indexOf(MAIN_JAVA);
        }
        if (at >= 0) {
            addModuleRoots(roots, file.substring(0, at));
        } else if (file.startsWith("src/test/java/") || file.startsWith("src/main/java/")) {
            addModuleRoots(roots, "");
        }
    }

    private static void addModuleRoots(Set<String> roots, String modulePath) {
        String prefix = modulePath.isEmpty() ? "" : modulePath + "/";
        roots.add(prefix + "src/main/java");
        roots.add(prefix + "src/test/java");
        roots.add(prefix + "src/main/resources");
        roots.add(prefix + "src/test/resources");
    }

    private static Path moduleDirFromIndexPath(Path indexFile) {
        Path indexDir = indexFile.getParent();
        if (indexDir == null || !"index".equals(name(indexDir))) {
            return null;
        }
        Path buddy = indexDir.getParent();
        if (buddy == null || !".jcodebuddy".equals(name(buddy))) {
            return null;
        }
        return buddy.getParent();
    }

    private static Path moduleDirFromRows(Path root, Index index) {
        for (TestRow row : index.tests().values()) {
            int at = row.file().indexOf(TEST_JAVA);
            if (at > 0) {
                return root.resolve(row.file().substring(0, at)).normalize();
            }
        }
        return null;
    }

    private static String name(Path path) {
        return path == null || path.getFileName() == null ? "" : path.getFileName().toString();
    }

    private static String topLevel(String fqn) {
        int at = fqn.indexOf('$');
        return at < 0 ? fqn : fqn.substring(0, at);
    }

    private static Set<String> topLevels(Set<String> fqns) {
        Set<String> tops = new TreeSet<>();
        for (String fqn : fqns) {
            tops.add(topLevel(fqn));
        }
        return tops;
    }

    /** The single lane a row belongs to: heavy wins, then unit, then component, then unclassified. */
    private static String lane(Set<String> tags) {
        for (String heavy : HEAVY_LANES) {
            if (tags.contains(heavy)) {
                return heavy;
            }
        }
        if (tags.contains(UNIT)) {
            return UNIT;
        }
        if (tags.contains(COMPONENT)) {
            return COMPONENT;
        }
        return "unclassified";
    }

    private static boolean runnable(String lane, boolean includeComponent) {
        return UNIT.equals(lane) || (includeComponent && COMPONENT.equals(lane));
    }

    // ── the missing/corrupt fallback ──────────────────────────────────────────────────────────────

    /**
     * Enumerates the module's unit tests from {@code src/test/java} when there is no usable index.
     *
     * <p>This is deliberately a source-tag read rather than a class-file read: the case it exists for is
     * "the derived table is gone", and a checkout that has the sources but no compiled output can still
     * answer. It is the only place in the selector that trusts the source tag over the measured class file,
     * and it is reached only when the measurement does not exist — the T2 contract is what keeps the two
     * spellings agreeing in normal operation.</p>
     */
    private static List<String> discoverUnitTests(Path moduleDir) throws IOException {
        if (moduleDir == null) {
            return List.of();
        }
        Path testRoot = moduleDir.resolve("src").resolve("test").resolve("java");
        if (!Files.isDirectory(testRoot)) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(testRoot)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String name = file.getFileName().toString();
                if (name.equals("package-info.java") || name.equals("module-info.java")) {
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!UNIT_TAG.matcher(text).find()) {
                    continue;
                }
                Matcher packageName = PACKAGE.matcher(text);
                String simple = name.substring(0, name.length() - ".java".length());
                units.add(packageName.find() ? packageName.group(1) + "." + simple : simple);
            }
        }
        return units;
    }

    private static String oneLine(String message) {
        if (message == null) {
            return "(no message)";
        }
        String line = message.replace('\r', ' ').replace('\n', ' ').strip();
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }

    // ── the command line (the Bun runner's entry point) ───────────────────────────────────────────

    private static final String USAGE = """
            TestSelector - select affected unit tests from the generated test index (plan T3)

              --index <file>        the generated .jcodebuddy/index/tests.json (required)
              --changed <paths>     comma-separated, repo-relative changed paths (repeatable)
              --root <dir>          the repository root paths are relative to (default: the current dir)
              --module-dir <dir>    the test module (default: derived from the index path or its rows)
              --all-unit            select every unit test
              --include-component   also select affected component tests
              --explain             print changed file -> FQN -> test chains; write nothing
              --json                print the machine-readable selection and reasons
              --help                this text

            It reads the index (and, for a missing/corrupt one, the module's src/test/java unit tags) and
            writes nothing. A changed pom.xml, an unmapped source/resource file, or an unusable index
            selects every unit test and is reported as a fallback.""".stripTrailing();

    /** The CLI. Returns the process exit code: 0 a selection was produced, 1 a read failed, 2 usage. */
    public static int run(String[] args) {
        String index = null;
        String root = null;
        String moduleDir = null;
        List<String> changed = new ArrayList<>();
        boolean allUnit = false;
        boolean includeComponent = false;
        boolean explain = false;
        boolean json = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    System.out.println(USAGE);
                    return 0;
                }
                case "--all-unit" -> allUnit = true;
                case "--include-component" -> includeComponent = true;
                case "--explain" -> explain = true;
                case "--json" -> json = true;
                case "--index", "--root", "--module-dir", "--changed" -> {
                    if (i + 1 >= args.length) {
                        return usageError(arg + " needs a value");
                    }
                    String value = args[++i];
                    switch (arg) {
                        case "--index" -> index = value;
                        case "--root" -> root = value;
                        case "--module-dir" -> moduleDir = value;
                        default -> {
                            for (String part : value.split(",")) {
                                if (!part.isBlank()) {
                                    changed.add(part.strip());
                                }
                            }
                        }
                    }
                }
                default -> {
                    return usageError("unexpected argument '" + arg + "'");
                }
            }
        }
        if (index == null) {
            return usageError("--index is required");
        }

        try {
            Selection selection = select(new Options(
                    root == null ? null : Path.of(root),
                    Path.of(index),
                    moduleDir == null ? null : Path.of(moduleDir),
                    changed, allUnit, includeComponent));
            if (json) {
                System.out.print(selection.toJson());
            } else if (explain) {
                System.out.print(selection.explain());
            } else {
                System.out.print(selection.summary());
            }
            return 0;
        } catch (IOException | RuntimeException failure) {
            System.err.println("test-selector: " + oneLine(failure.getMessage()));
            return 1;
        }
    }

    private static int usageError(String message) {
        System.err.println("test-selector: " + message);
        System.err.println(USAGE);
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }
}
