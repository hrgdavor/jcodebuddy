package hr.hrg.jcodebuddy.automation;

import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;
import hr.hrg.jcodebuddy.automation.entity.EntityRegenerationWatcher;
import hr.hrg.watch2.core.ChangeSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.tree.J;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The automation layer, end to end: a watched generation pass, then a chained run of source-visible
 * transformations over the tree it produced, then the engine's own validation of every file in it.
 *
 * <h3>Why this test exists, and why it is shaped like this</h3>
 * <p>Each class involved already has its own unit tests — {@code AutomationEngineTest} owns the chaining
 * rules, {@code EntityRegenerationWatcherTest} owns the generation loop — and none of them exercises the
 * seam between them. That seam is what a developer actually runs: an edit triggers a pass, the pass writes
 * committed source, and the same process then reads that source back through {@link SourceFacts}. Every
 * assumption the migration ported — one parse per read, an unreadable file reported rather than mistaken
 * for an empty one, positions that resolve — has to hold across that hand-over, and a unit test on either
 * side cannot see it break.</p>
 *
 * <p>The transformations are declared in this file and registered by {@code new}, in the order a caller
 * lists them: the same way a project's {@code ProjectAutomation} wiring does it, and the only shape
 * AGENTS.md § 1 permits. Nothing is scanned, discovered or reflected; the chain a reader sees is the chain
 * that runs. This is a test-scope chain on purpose — the migration removed no production wiring, so
 * inventing one to benchmark would be a fixture pretending to be a feature.</p>
 *
 * <p>No timing is asserted. The watcher is driven through {@link EntityRegenerationWatcher#onBatch}, so no
 * filesystem-event thread is involved and nothing here can go red on a loaded machine.</p>
 */
class AutomationChainIntegrationTest {

    private static final String MARKER = """
            package chain.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package chain.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
            }
            """;

    /** What step one writes, and what step two counts. */
    private static final String STAMP = "// chain: reviewed";

    private static List<Path> javaFiles(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    /** A tree with one entity marker and one view; the generation pass adds the rest. */
    private static Path generationTree(Path base) throws Exception {
        Path sourceRoot = base.resolve("src");
        Path packageDir = sourceRoot.resolve("chain/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Files.writeString(packageDir.resolve("PersonSummary.java"), VIEW);
        return sourceRoot;
    }

    /**
     * One generation pass over a tree, through the watcher. A full-recompile batch is the honest way to
     * ask for "generate everything once": naming individual files would make the batch the thing under
     * test rather than the tree it leaves behind.
     */
    private static EntityRegenerationWatcher.Pass generate(Path sourceRoot) {
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                new EntityRegenerationWatcher.Config(sourceRoot, sourceRoot.resolveSibling("reports"),
                        List.of("chain.hr"), false, List.of(), 50));
        return watcher.onBatch(new ChangeSet(Set.of(), Set.of(), true, System.currentTimeMillis()));
    }

    @Test
    void aGenerationPassLeavesATreeTheAutomationLayerCanReadAndChainOver(@TempDir Path base)
            throws Exception {
        Path sourceRoot = generationTree(base);
        Assertions.assertTrue(generate(sourceRoot).regenerated(), "a fresh tree with a view must generate");

        List<Path> produced = javaFiles(sourceRoot);
        Assertions.assertTrue(produced.size() > 2,
                "the pass must leave more than it was given, found " + produced);
        Assertions.assertTrue(produced.stream().anyMatch(path -> path.getFileName().toString()
                        .equals("PersonSummary_.java")),
                "the field enum is among what the chain now reads: " + produced);

        // The hand-over this test exists for. Every file the pass wrote must be readable Java by the same
        // reader the pass used, or the chain's first step is built on a file that does not exist.
        AutomationEngine engine = new AutomationEngine();
        List<String> rejected = new ArrayList<>();
        for (Path file : produced) {
            ValidationResult validation = engine.validate(file);
            if (!validation.isValid()) {
                rejected.add(file.getFileName() + " " + validation.errors());
            }
        }
        Assertions.assertEquals(List.of(), rejected,
                "a generation pass must not leave a file the next step cannot read");

        engine.register(new StampHeader());
        engine.register(new CountStamps());
        Map<String, List<TransformationResult>> results =
                engine.applyAllSequential(sourceRoot, List.of("stamp-header", "count-stamps"), true);

        Assertions.assertEquals(List.of("stamp-header", "count-stamps"), List.copyOf(results.keySet()),
                "the report is keyed and ordered by the chain as given, so a caller can count per step");
        Assertions.assertEquals(produced.size(), results.get("stamp-header").size());
        Assertions.assertTrue(results.values().stream().flatMap(List::stream)
                        .allMatch(TransformationResult::succeeded),
                "every step over every file succeeded");
        for (Path file : produced) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Assertions.assertTrue(text.contains(STAMP), file + " carries the first step's stamp");
            Assertions.assertTrue(text.contains("reviewed 1 time"),
                    file + " carries the second step's count of the first step's work");
        }
    }

    /**
     * The ordering promise, asserted where it is easy to believe and hard to get by accident: the third
     * step counts what the second step wrote, so its answer is only correct if each step received the
     * previous step's output rather than the file's original text. Give it the original text and it
     * refuses, which is the same fact stated as a failure the test would notice.
     */
    @Test
    void eachStepOfAChainSeesThePreviousStepsOutput(@TempDir Path base) throws Exception {
        Path file = base.resolve("One.java");
        Files.writeString(file, "package chain.hr;\npublic interface One { }\n");

        AutomationEngine engine = new AutomationEngine();
        engine.register(new StampHeader());
        engine.register(new CountStamps());
        engine.register(new CountReviews());

        Map<String, TransformationResult> one = engine.applySequential(file,
                List.of("stamp-header", "count-stamps", "count-reviews"));

        Assertions.assertEquals(List.of("stamp-header", "count-stamps", "count-reviews"),
                List.copyOf(one.keySet()));
        Assertions.assertTrue(one.get("count-stamps").output().contains("reviewed 1 time"),
                "the second step counted the stamp the first step added");
        Assertions.assertTrue(one.get("count-reviews").succeeded()
                        && one.get("count-reviews").output().contains("audited 1"),
                "the third step counted what the second step wrote, so the chain passed text along: "
                        + one.get("count-reviews").output());
    }

    /**
     * A refusal is one step's answer about one file. It is reported, it contributes nothing, and it does
     * not stop the steps around it: the text on disk is exactly what the two cooperative steps produce on
     * their own. That is the behaviour an in-place batch over committed source has to have — a step that
     * declines must not corrupt the work of the steps that did not — and it is worth writing down where a
     * reader will find it before relying on the opposite.
     */
    @Test
    void aRefusedStepContributesNothingAndTheChainContinuesAroundIt(@TempDir Path base) throws Exception {
        Path sourceRoot = generationTree(base);
        generate(sourceRoot);
        List<Path> produced = javaFiles(sourceRoot);
        Assertions.assertTrue(produced.stream().anyMatch(path -> path.getFileName().toString()
                        .equals("PersonSummaryBuilder.java")),
                "the chain needs a file the refusing step will reject");

        Map<Path, String> before = new LinkedHashMap<>();
        for (Path file : produced) {
            before.put(file, Files.readString(file, StandardCharsets.UTF_8));
        }

        AutomationEngine engine = new AutomationEngine();
        engine.register(new StampHeader());
        engine.register(new RefuseBuilders());
        engine.register(new CountStamps());

        Map<String, List<TransformationResult>> results = engine.applyAllSequential(sourceRoot,
                List.of("stamp-header", "refuse-builders", "count-stamps"), true);

        List<TransformationResult> refusals = results.get("refuse-builders");
        Assertions.assertEquals(produced.size(), refusals.size(), "one answer per file, refused or not");
        List<Path> refusedFiles = refusals.stream().filter(result -> !result.succeeded())
                .map(TransformationResult::inputFile).toList();
        Assertions.assertFalse(refusedFiles.isEmpty(),
                "the generated builder must actually meet the refusal, or this proves nothing");
        Assertions.assertTrue(refusals.stream().filter(result -> !result.succeeded())
                        .allMatch(result -> result.error().contains("boilerplate")),
                "and a refusal says why, in the step's own words");

        for (TransformationResult refusal : refusals) {
            Assertions.assertFalse(refusal.changed(),
                    "a refused step reports no change even though the file on disk did change, because the "
                            + "change was not its own");
        }
        for (Map.Entry<Path, String> entry : before.entrySet()) {
            String text = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
            Assertions.assertTrue(text.contains(STAMP), entry.getKey() + " kept the step before: " + text);
            Assertions.assertTrue(text.contains("reviewed 1 time"),
                    entry.getKey() + " kept the step after: " + text);
            Assertions.assertFalse(text.contains("generated boilerplate"),
                    "the refusal is reported, not written into the source");
        }
    }

    /**
     * The engine's structural facts and the tooling layer's own count of the same file, compared.
     *
     * <p>Chosen subject: the generated field enum — a file no human wrote, whose shape the generator
     * derives from the view, so its counts could not have been tuned to agree with the assertion. Two
     * independent readings of one file, over the seam, is the whole point of the migration: the
     * automation layer must not disagree with the generators about what a tree declares.</p>
     */
    @Test
    void theEngineAndTheToolingLayerCountTheSameDeclarations(@TempDir Path base) throws Exception {
        Path sourceRoot = generationTree(base);
        generate(sourceRoot);
        Path fieldEnum = sourceRoot.resolve("chain/hr/PersonSummary_.java");
        Assertions.assertTrue(Files.exists(fieldEnum), "the pass emits the field enum");

        AutomationEngine engine = new AutomationEngine();
        AnalysisResult analysis = engine.analyze(fieldEnum);

        String source = Files.readString(fieldEnum, StandardCharsets.UTF_8);
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the enum the pass just wrote must read back");

        Assertions.assertEquals(TreeQueries.typeDeclarations(unit).size(),
                analysis.count(AnalysisResult.TYPE_COUNT),
                "the engine's type count is the tooling layer's count, from one parse each");
        Assertions.assertEquals(1, analysis.count(AnalysisResult.ENUM_COUNT),
                "and the engine agrees it is an enum");
        Assertions.assertTrue(analysis.issues().isEmpty(),
                "a generated file is not a problem: " + analysis.issues());
    }

    // -------------------------------------------------- the chain's three steps ---

    /** Step one: a review stamp under the package declaration. */
    static final class StampHeader implements Transformation {
        @Override
        public String getName() {
            return "stamp-header";
        }

        @Override
        public String getDescription() {
            return "adds the chain's review stamp";
        }

        @Override
        public String apply(String source) throws TransformationException {
            int packageAt = source.indexOf("package ");
            if (packageAt < 0) {
                throw new TransformationException(getName(), "no package declaration to stamp after");
            }
            int lineEnd = source.indexOf('\n', packageAt);
            if (lineEnd < 0) {
                throw new TransformationException(getName(), "unterminated package declaration");
            }
            return source.substring(0, lineEnd + 1) + STAMP + "\n" + source.substring(lineEnd + 1);
        }
    }

    /** Step two: counts the stamps present, so its answer is evidence that step one ran. */
    static final class CountStamps implements Transformation {
        @Override
        public String getName() {
            return "count-stamps";
        }

        @Override
        public String getDescription() {
            return "counts the review stamps in the text it was handed";
        }

        @Override
        public String apply(String source) throws TransformationException {
            int count = 0;
            for (int at = source.indexOf(STAMP); at >= 0; at = source.indexOf(STAMP, at + 1)) {
                count++;
            }
            if (count == 0) {
                throw new TransformationException(getName(), "the text carries no review stamp, so this "
                        + "step received the original file rather than the previous step's output");
            }
            return source + "\n// reviewed " + count + " time" + (count == 1 ? "" : "s") + "\n";
        }
    }

    /**
     * Step three: counts what step two wrote. Its whole purpose is to be unable to succeed out of order —
     * hand it the original file and it has nothing to audit.
     */
    static final class CountReviews implements Transformation {
        @Override
        public String getName() {
            return "count-reviews";
        }

        @Override
        public String getDescription() {
            return "counts the review lines the previous step left behind";
        }

        @Override
        public String apply(String source) throws TransformationException {
            int count = 0;
            for (int at = source.indexOf("// reviewed "); at >= 0; at = source.indexOf("// reviewed ", at + 1)) {
                count++;
            }
            if (count == 0) {
                throw new TransformationException(getName(), "nothing to audit, so this step ran before "
                        + "the step that reviews");
            }
            return source + "\n// audited " + count + " review line" + (count == 1 ? "" : "s") + "\n";
        }
    }

    /**
     * A step that refuses generated boilerplate. It exists because an engine that only ever runs
     * cooperative steps never shows what a refusal costs the tree.
     */
    static final class RefuseBuilders implements Transformation {
        @Override
        public String getName() {
            return "refuse-builders";
        }

        @Override
        public String getDescription() {
            return "refuses any file that mentions a builder";
        }

        @Override
        public String apply(String source) throws TransformationException {
            if (source.contains("Builder")) {
                throw new TransformationException(getName(), "this file is generated boilerplate");
            }
            return source;
        }
    }
}
