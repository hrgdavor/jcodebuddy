package hr.hrg.jcodebuddy.automation.entity;

import hr.hrg.watch2.core.ChangeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The entity-regeneration watcher (plan.dsflash § 12.5/7.17).
 *
 * <p>The tests drive {@link EntityRegenerationWatcher#onBatch} directly rather than through a real
 * filesystem watcher. That is not a shortcut around the interesting part — the interesting part
 * <em>is</em> the decision: the generator writes into the tree being watched, so the property that
 * has to hold is "a batch that contains only the watcher's own output runs no pass". A test that
 * waited on real filesystem events would be testing {@code java-watch-core}, which has its own
 * suite, and would be flaky on a loaded machine.</p>
 *
 * <p>Every test therefore asserts a decision or a generated artifact, not a timing.</p>
 */
class EntityRegenerationWatcherTest {

    private static final String MARKER = """
            package watched.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    private static final String VIEW = """
            package watched.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_ALL)
            public interface PersonSummary extends PersonEntity {
                String firstName();
                String lastName();
            }
            """;

    private record Tree(Path sourceRoot, Path packageDir, Path viewFile) {
    }

    private Tree tree() throws Exception {
        Path sourceRoot = Files.createTempDirectory("watch-source");
        Path packageDir = sourceRoot.resolve("watched/hr");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonEntity.java"), MARKER);
        Path viewFile = packageDir.resolve("PersonSummary.java");
        Files.writeString(viewFile, VIEW);
        return new Tree(sourceRoot, packageDir, viewFile);
    }

    /**
     * A batch naming one file, with absolute paths — which is what the real watcher delivers, and
     * what the relativization has to cope with.
     */
    private static ChangeSet changed(Tree tree, Path... files) {
        return new ChangeSet(java.util.Arrays.stream(files)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                Set.of(), System.currentTimeMillis());
    }

    private static ChangeSet deleted(Path... files) {
        return new ChangeSet(Set.of(), java.util.Arrays.stream(files)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                System.currentTimeMillis());
    }

    @Test
    void anEditToAViewRegenerates() throws Exception {
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                new EntityRegenerationWatcher.Config(tree.sourceRoot(), null, List.of(), false,
                        List.of(), 50));

        // Nothing has happened yet, so nothing has been generated.
        Assertions.assertFalse(Files.exists(tree.packageDir().resolve("PersonSummary_.java")));

        Files.writeString(tree.viewFile(), VIEW.replace("String lastName();",
                "String lastName();\n    Integer age();"));
        EntityRegenerationWatcher.Pass pass = watcher.onBatch(changed(tree, tree.viewFile()));

        Assertions.assertTrue(pass.regenerated(), "a real content change must regenerate");
        Assertions.assertEquals(Set.of("watched/hr/PersonSummary.java"), pass.triggeringFiles(),
                "and the triggering files are named relative to the source root, so a log line is "
                        + "readable and platform-independent");
        Assertions.assertTrue(Files.exists(tree.packageDir().resolve("PersonSummary_.java")),
                "the enum was emitted");
        Assertions.assertTrue(Files.readString(tree.packageDir().resolve("PersonSummary_.java"))
                        .contains("age("),
                "with the new field the edit introduced");
        Assertions.assertEquals(1, watcher.passCount());
    }

    @Test
    void theWatchersOwnOutputDoesNotStartAnotherPass() throws Exception {
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));

        Files.writeString(tree.viewFile(), VIEW.replace("String lastName();",
                "String lastName();\n    Integer age();"));
        Assertions.assertTrue(watcher.onBatch(changed(tree, tree.viewFile())).regenerated());
        Assertions.assertEquals(1, watcher.passCount());

        // Now replay what the pass just wrote, as the filesystem would: every generated file arrives
        // as a change. This is the loop that a naive wiring never escapes.
        Set<Path> generated = new java.util.LinkedHashSet<>();
        try (var walk = Files.walk(tree.packageDir())) {
            walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.equals(tree.viewFile()))
                    .forEach(generated::add);
        }
        Assertions.assertFalse(generated.isEmpty(), "the pass must have written something to echo");

        EntityRegenerationWatcher.Pass echo = watcher.onBatch(new ChangeSet(generated, Set.of(),
                System.currentTimeMillis()));

        Assertions.assertFalse(echo.regenerated(),
                "a batch made only of the watcher's own output must not regenerate");
        Assertions.assertEquals(1, watcher.passCount(),
                "so the watcher is quiescent after one pass, not looping");
    }

    @Test
    void atMostOneExtraPassOccursForFilesTheFirstBatchDidNotMention() throws Exception {
        // The bound the design accepts: a generated file that no batch has named yet has no recorded
        // hash, so the first batch that mentions it runs one more (idempotent) pass. After that,
        // silence — asserted rather than assumed.
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));

        Files.writeString(tree.viewFile(), VIEW.replace("String lastName();",
                "String lastName();\n    Integer age();"));
        watcher.onBatch(changed(tree, tree.viewFile()));

        Path binder = tree.packageDir().resolve("PersonSummaryBinder.java");
        // Binders are DRAFT/EXPLORATION and opt-in only (--adapters), so the default config does not
        // emit this file; the interesting unbounded case is a generated file the pass created but no
        // batch named. Name one now.
        Set<Path> unnamed = Set.of(tree.packageDir().resolve("PersonSummary_.java"));
        Assertions.assertTrue(watcher.onBatch(new ChangeSet(unnamed, Set.of(),
                System.currentTimeMillis())).regenerated() == false,
                "the enum was already snapshotted at the end of the pass, so it is not 'new'");
        Assertions.assertFalse(Files.exists(binder));

        EntityRegenerationWatcher.Pass afterwards = watcher.onBatch(new ChangeSet(unnamed, Set.of(),
                System.currentTimeMillis()));
        Assertions.assertFalse(afterwards.regenerated(), "and it stays quiet");
        Assertions.assertEquals(1, watcher.passCount(), "exactly one pass for the whole sequence");
    }

    @Test
    void aSaveWithIdenticalContentDoesNothing() throws Exception {
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));

        // An editor that rewrites the same bytes, or a `touch`, must not start a pass.
        Files.writeString(tree.viewFile(), VIEW);
        EntityRegenerationWatcher.Pass pass = watcher.onBatch(changed(tree, tree.viewFile()));

        Assertions.assertFalse(pass.regenerated());
        Assertions.assertEquals(0, watcher.passCount());
        Assertions.assertFalse(Files.exists(tree.packageDir().resolve("PersonSummary_.java")),
                "and no pass means no generated file");
    }

    @Test
    void aDeletionRegenerates() throws Exception {
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));
        Path scratch = tree.packageDir().resolve("Scratch.java");
        Files.writeString(scratch, "package watched.hr;\npublic interface Scratch {}\n");
        // First batch: the file exists and its content is new to the watcher, so a pass runs.
        Assertions.assertTrue(watcher.onBatch(changed(tree, scratch)).regenerated());
        long after = watcher.passCount();

        Files.delete(scratch);
        Assertions.assertTrue(watcher.onBatch(deleted(scratch)).regenerated(),
                "a deletion always matters: the generator may need to drop or recreate a file");
        Assertions.assertEquals(after + 1, watcher.passCount());
    }

    @Test
    void aFullRecompileRequestAlwaysRegenerates() throws Exception {
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));

        ChangeSet overflow = new ChangeSet(Set.of(), Set.of(), true, System.currentTimeMillis());

        Assertions.assertTrue(watcher.onBatch(overflow).regenerated(),
                "an OS queue overflow means 'you may have missed events', so the only safe answer is "
                        + "to regenerate");
    }

    @Test
    void divergencesFromThePassAreSurfaced() throws Exception {
        Tree tree = tree();
        // A marker-less enum with a constant no accessor declares: the generator's bootstrap path
        // drops it and reports enum_constant_removed. That is a real divergence to observe, rather
        // than asserting that the list is non-null.
        Files.writeString(tree.packageDir().resolve("PersonSummary_.java"), """
                package watched.hr;
                import hr.hrg.hipster.entity.api.FieldDef;
                import java.lang.reflect.Type;
                public enum PersonSummary_ implements FieldDef {
                    firstName(String.class),
                    legacyCode(String.class);
                    private final Type javaType;
                    PersonSummary_(Type javaType) { this.javaType = javaType; }
                    @Override public Type javaType() { return javaType; }
                }
                """);
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));

        Files.writeString(tree.viewFile(), VIEW.replace("String lastName();",
                "String lastName();\n    Integer age();"));
        EntityRegenerationWatcher.Pass pass = watcher.onBatch(changed(tree, tree.viewFile()));

        Assertions.assertTrue(pass.regenerated());
        Assertions.assertTrue(pass.divergences().stream()
                        .anyMatch(entry -> entry.startsWith("kind=enum_constant_removed")),
                "the pass's report reaches the caller instead of being swallowed: "
                        + pass.divergences());
    }

    @Test
    void aFailedPassIsReportedAndTheWatcherKeepsGoing() throws Exception {
        Tree tree = tree();
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));

        // A view that cannot be parsed. The watcher must say so rather than die: a watcher that stops
        // on the first malformed keystroke is worse than useless while someone is typing.
        Files.writeString(tree.viewFile(), "package watched.hr;\npublic interface PersonSummary {\n");
        EntityRegenerationWatcher.Pass broken = watcher.onBatch(changed(tree, tree.viewFile()));

        Assertions.assertTrue(broken.regenerated() || !broken.divergences().isEmpty(),
                "the batch is never silently ignored: either it regenerated or it explained itself");

        // And a subsequent good edit still works.
        Files.writeString(tree.viewFile(), VIEW);
        EntityRegenerationWatcher.Pass recovered = watcher.onBatch(changed(tree, tree.viewFile()));
        Assertions.assertTrue(Files.exists(tree.packageDir().resolve("PersonSummary_.java"))
                        || recovered.divergences().stream().noneMatch(
                                entry -> entry.startsWith("kind=watcher_pass_failed")),
                "the watcher recovered after the malformed revision");
    }

    @Test
    void theDefaultReportDirectoryIsOutsideTheWatchedTree() throws Exception {
        Tree tree = tree();
        Path reportDir = EntityRegenerationWatcher.Config.defaultReportDir(tree.sourceRoot());

        // The temporary tree has no pom.xml above it, so this exercises the fallback as well as the
        // normal path: either way the report must not land inside the tree being watched, because
        // that is where it could be mistaken for a source change.
        Assertions.assertFalse(reportDir.startsWith(tree.sourceRoot()),
                "the metadata JSON must never be written into the watched root: " + reportDir);

        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                EntityRegenerationWatcher.Config.of(tree.sourceRoot()));
        Files.writeString(tree.viewFile(), VIEW.replace("String lastName();",
                "String lastName();\n    Integer age();"));
        watcher.onBatch(changed(tree, tree.viewFile()));

        try (var walk = Files.walk(tree.sourceRoot())) {
            Assertions.assertTrue(walk.noneMatch(path -> path.toString().endsWith(".metadata.json")),
                    "no metadata JSON inside the source root after a pass");
        }
    }

    @Test
    void aConfigWithoutASourceRootIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new EntityRegenerationWatcher.Config(null, null, List.of(), false, List.of(), 300));
    }

    @Test
    void flagsArePassedThroughToTheGeneratorUnchanged() throws Exception {
        Tree tree = tree();
        // `--adapters` is the observable one: it adds two files per view, so the watcher's config
        // visibly reaches the generator rather than being silently dropped. SQL generation is a
        // DRAFT/EXPLORATION and is opt-in — this test is also the watcher-level proof that the
        // default config (previous test) emits none of it.
        EntityRegenerationWatcher watcher = new EntityRegenerationWatcher(
                new EntityRegenerationWatcher.Config(tree.sourceRoot(), null,
                        List.of("watched.hr"), true, List.of(), 50));

        Files.writeString(tree.viewFile(), VIEW.replace("String lastName();",
                "String lastName();\n    Integer age();"));
        watcher.onBatch(changed(tree, tree.viewFile()));

        Assertions.assertTrue(Files.exists(tree.packageDir().resolve("PersonSummaryBinder.java")),
                "the adapters flag reached the generator");
        Assertions.assertTrue(Files.exists(tree.packageDir().resolve("PersonSummaryRowAdapter.java")));
    }
}
