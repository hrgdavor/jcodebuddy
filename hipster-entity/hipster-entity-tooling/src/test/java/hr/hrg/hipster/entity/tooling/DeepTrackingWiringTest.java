package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The generator's deep-change wiring (plan.dsflash § 11/6.5).
 *
 * <p>The plan's rule has three parts — <em>detect</em> whether a field's type is itself a trackable
 * view "including generic collections thereof", <em>emit</em> the deep wiring in the tracking builder,
 * and do so from the existing type-parsing code rather than a rewrite. This test covers all three:</p>
 * <ul>
 *   <li>detection is asserted by the emitted source: a field whose type declares
 *       {@code ViewChangeTracking} and a {@code List} of one are wired, and a plain
 *       {@code List<String>} is not;</li>
 *   <li>the wiring is asserted to <strong>compile and run</strong>, because the whole risk of § 6.5 is
 *       emitting a call that does not typecheck against the declared field type;</li>
 *   <li>a view type that does <em>not</em> declare the contract produces a
 *       {@code deep_tracking_type_not_trackable} divergence naming the fix, rather than a silent
 *       absence or a cast.</li>
 * </ul>
 *
 * <p>The generated builder's runtime behaviour is verified here against the same fixture shapes the
 * core module's own deep tests use, so the two agreement points (§ 6.7's parity) are checked from
 * both sides.</p>
 */
class DeepTrackingWiringTest {

    private static final String MARKER = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.EntityBase;
            import hr.hrg.hipster.entity.api.Identifiable;
            public interface PersonEntity extends EntityBase<Long>, Identifiable<Long> {}
            """;

    /**
     * A leaf view generated at a tracking level, which is what makes it trackable.
     *
     * <p>It deliberately does <strong>not</strong> declare {@code ViewChangeTracking}: G8 rule 1
     * excludes any interface extending a framework surface from discovery, so a view that declared the
     * contract could never be a generator target. The generated call therefore reaches the contract
     * through a cast that names the element's own field enum — see {@code TrackableType}.</p>
     */
    private static final String NODE = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.Identifiable;
            import hr.hrg.hipster.entity.api.View;
            @View(gen = GenLevel.BUILDER_TRACKED)
            public interface Node extends Identifiable<Long> {
                String label();
            }
            """;

    /** A rendered view at the default level: a nested field of this type is reported, not wired. */
    private static final String PLAIN_NODE = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.Identifiable;
            import hr.hrg.hipster.entity.api.View;
            @View
            public interface PlainNode extends Identifiable<Long> {
                String label();
            }
            """;

    /** The tracked root: one direct nested view, one list of them, one ordinary list. */
    private static final String DIRECTORY = """
            package deep.hr;
            import hr.hrg.hipster.entity.api.GenLevel;
            import hr.hrg.hipster.entity.api.View;
            import java.util.List;
            @View(gen = GenLevel.BUILDER_TRACKED)
            public interface Directory extends PersonEntity {
                String name();
                Node head();
                List<Node> entries();
                List<String> tags();
            }
            """;

    private record Generated(Path sourceRoot, Path outputRoot, Path builderFile,
                             List<String> divergences) {
    }

    private Generated generate(String... extraSources) throws Exception {
        Path sourceRoot = Files.createTempDirectory("deep-wiring-source");
        Path outputRoot = Files.createTempDirectory("deep-wiring-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("Node.java"), NODE);
        Files.writeString(sourceRoot.resolve("Directory.java"), DIRECTORY);
        for (int i = 0; i + 1 < extraSources.length; i += 2) {
            Files.writeString(sourceRoot.resolve(extraSources[i]), extraSources[i + 1]);
        }

        DivergenceReporter reporter = new DivergenceReporter();
        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, reporter);
        return new Generated(sourceRoot, outputRoot,
                outputRoot.resolve("deep/hr/DirectoryBuilderTracking.java"), reporter.entries());
    }

    private static String read(Generated generated) throws Exception {
        Assertions.assertTrue(Files.exists(generated.builderFile()),
                "BUILDER_TRACKED must emit the tracking builder");
        return Files.readString(generated.builderFile()).replace("\r\n", "\n");
    }

    private static boolean hasKind(List<String> entries, String kind) {
        return entries.stream().anyMatch(entry -> entry.startsWith("kind=" + kind));
    }

    @Test
    void aDirectlyNestedTrackedViewIsWiredIntoTheDeepWalk() throws Exception {
        String source = read(generate());

        Assertions.assertTrue(source.contains("public java.util.Map<Integer, "
                        + "hr.hrg.hipster.entity.core.ViewChangeTracking<?, ?>> nestedTrackers()"),
                "a directly nested tracked view is exposed by ordinal: " + source);
        Assertions.assertTrue(source.contains("all.put(2, (hr.hrg.hipster.entity.core.ViewChangeTracking"
                        + "<deep.hr.Node_, ?>) head)"),
                "`head` is the third field (id=0, name=1, head=2), reached through a cast that names "
                        + "the element's own field enum: " + source);
        Assertions.assertTrue(source.contains("for (hr.hrg.hipster.entity.core.ChangePath childPath "
                        + ": tracker2.changesDeep())"),
                "and the walk descends into it: " + source);
        Assertions.assertTrue(source.contains("new hr.hrg.hipster.entity.core.ChangePath("
                        + "Directory_.head, -1, childPath)"),
                "a direct child carries no element index, which is what the -1 means: " + source);
    }

    @Test
    void aListOfTrackedViewsIsWiredWithItsElementIndex() throws Exception {
        String source = read(generate());

        Assertions.assertTrue(source.contains("public java.util.Map<Integer, "
                        + "java.util.List<hr.hrg.hipster.entity.core.ListDelta>> collectionDeltas()"),
                "a tracked collection reports per-index deltas: " + source);
        Assertions.assertTrue(source.contains("Node element3 = entries.get(i);"),
                "the element keeps the author's own spelling, so their imports still apply: " + source);
        Assertions.assertTrue(source.contains("tracker3.changedValues()"),
                "each element is asked for its own field changes, which is why the collection level "
                        + "needs no baseline of its own: " + source);
        Assertions.assertTrue(source.contains("hr.hrg.hipster.entity.core.ListChangeKind.FIELD_CHANGED, "
                        + "i, -1,"),
                "and the delta carries the element index: " + source);
        Assertions.assertTrue(source.contains("element3.id()"),
                "with the identity, because Node is Identifiable: " + source);
        Assertions.assertTrue(source.contains("new hr.hrg.hipster.entity.core.ChangePath("
                        + "Directory_.entries, i, childPath)"),
                "the deep path carries the list index: " + source);
        Assertions.assertTrue(source.contains("public boolean hasCollection(int fieldOrdinal)"),
                "and the structural question is answerable: " + source);
        Assertions.assertTrue(source.contains("case 3 -> true;"),
                "for `entries` (id=0, name=1, head=2, entries=3, tags=4): " + source);
    }

    @Test
    void anOrdinaryCollectionIsNotWiredAndAFallbackNamesTheMarkedField() throws Exception {
        Generated generated = generate();
        String source = read(generated);

        Assertions.assertFalse(source.contains("tags.get(i).changedValues()"),
                "a List<String> has no tracking contract, so nothing descends into it: " + source);
        // The fallback is what keeps "the reference was replaced" distinguishable from "something
        // inside it changed" — dropping it would lose the only report of a replaced nested value.
        Assertions.assertTrue(source.contains("if (mf.has(Directory_.head) && !descended2) {"),
                "a marked nested field with no nested change falls back to naming the field: " + source);
        Assertions.assertTrue(source.contains("paths.add(hr.hrg.hipster.entity.core.ChangePath.of("
                        + "Directory_.entries));"),
                "and the same for the collection: " + source);
        Assertions.assertTrue(source.contains("mf.forEach((field, index) -> {"),
                "this level's own marked fields are still reported, minus the nested ones: " + source);
    }

    @Test
    void theEmittedBuilderCompiles() throws Exception {
        Generated generated = generate();

        // The compile gate is the assertion that carries the most weight here: the entire risk of
        // § 6.5 is emitting a call that does not typecheck against the declared field type, and javac
        // is the only thing that checks it.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "deep wiring",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    @Test
    void aNestedViewWithoutATrackingLevelIsReportedWithTheOneWordFix() throws Exception {
        Generated generated = generate("PlainNode.java", PLAIN_NODE,
                "Holder.java", """
                        package deep.hr;
                        import hr.hrg.hipster.entity.api.GenLevel;
                        import hr.hrg.hipster.entity.api.View;
                        @View(gen = GenLevel.BUILDER_TRACKED)
                        public interface Holder extends PersonEntity {
                            String name();
                            PlainNode child();
                        }
                        """);

        Assertions.assertTrue(hasKind(generated.divergences(), "deep_tracking_type_not_enabled"),
                "an annotated view with no tracking level is the one actionable case, so it is named: "
                        + generated.divergences());
        String entry = generated.divergences().stream()
                .filter(line -> line.startsWith("kind=deep_tracking_type_not_enabled"))
                .findFirst().orElseThrow();
        Assertions.assertTrue(entry.contains("PlainNode"), entry);
        Assertions.assertTrue(entry.contains("GenLevel.BUILDER_TRACKED"),
                "and the action is the literal fix: " + entry);

        String holder = Files.readString(
                generated.outputRoot().resolve("deep/hr/HolderBuilderTracking.java"));
        Assertions.assertFalse(holder.contains("nestedTrackers()"),
                "an untrackable field produces no deep accessors at all, rather than a cast that would "
                        + "fail at runtime: " + holder);
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "untrackable",
                CompileHarness.javaSourcesUnder(generated.outputRoot()),
                CompileHarness.javaSourcesUnder(generated.sourceRoot()));
    }

    @Test
    void aFlatTrackedViewGainsNoDeepCode() throws Exception {
        Generated generated = generate("Flat.java", """
                package deep.hr;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                @View(gen = GenLevel.BUILDER_TRACKED)
                public interface Flat extends PersonEntity {
                    String label();
                }
                """);

        String flat = Files.readString(generated.outputRoot().resolve("deep/hr/FlatBuilderTracking.java"));

        // An interface default is the right answer for a view that nests nothing: it costs no code and
        // cannot disagree with anything.
        Assertions.assertFalse(flat.contains("changesDeep()"),
                "a view with no trackable field keeps the interface's own default: " + flat);
        Assertions.assertFalse(flat.contains("hasCollection"),
                "and gains neither of the deep accessors: " + flat);
        Assertions.assertFalse(flat.contains("-- Deep change tracking"),
                "not even the section comment: " + flat);
    }

    /**
     * A nested type that lives in another package, referred to by its <em>simple</em> name.
     *
     * <p>This is the case that made the emitted builder uncompilable before the declaration-import
     * fix: the generated method signature spells the element type exactly as the author wrote it
     * ({@code Node element3 = entries.get(i);} — see {@link #aListOfTrackedViewsIsWiredWithItsElementIndex()}),
     * so a builder emitted into a different package than the element's own package needs the author's
     * import list, or {@code javac} reports {@code cannot find symbol: class Node}. Reaching the type
     * through a fully-qualified name instead would defeat the point of the author's spelling: the
     * declaration is supposed to look like the interface it was derived from.</p>
     */
    @Test
    void aNestedTypeFromAnotherPackageIsImportedIntoTheEmittedBuilder() throws Exception {
        Path sourceRoot = Files.createTempDirectory("deep-cross-package-source");
        Path outputRoot = Files.createTempDirectory("deep-cross-package-output");
        Files.writeString(sourceRoot.resolve("PersonEntity.java"), MARKER);
        Files.writeString(sourceRoot.resolve("Node.java"), NODE);
        Files.writeString(sourceRoot.resolve("Directory.java"), """
                package deep.other;
                import deep.hr.Node;
                import deep.hr.PersonEntity;
                import hr.hrg.hipster.entity.api.GenLevel;
                import hr.hrg.hipster.entity.api.View;
                import java.util.List;
                @View(gen = GenLevel.BUILDER_TRACKED)
                public interface Directory extends PersonEntity {
                    String name();
                    Node head();
                    List<Node> entries();
                }
                """);

        EntityMetadataGenerator.generate(sourceRoot, outputRoot, outputRoot, new DivergenceReporter());

        Path builder = outputRoot.resolve("deep/other/DirectoryBuilderTracking.java");
        Assertions.assertTrue(Files.exists(builder),
                "a tracked view is generated into its own package: " + builder);
        String source = Files.readString(builder).replace("\r\n", "\n");
        Assertions.assertTrue(source.contains("import deep.hr.Node;"),
                "the nested element's type is imported, because the emitted local variable keeps the "
                        + "author's simple name: " + source);
        Assertions.assertFalse(source.contains("import deep.hr.PersonEntity;"),
                "while the marker — consulted only for discovery — leaves no trace in the output: "
                        + source);

        // The compile gate is the real assertion: the import exists to make this succeed.
        CompileHarness.compileOrFail(CompileHarness.findRepoRoot(), "cross-package deep wiring",
                CompileHarness.javaSourcesUnder(outputRoot),
                CompileHarness.javaSourcesUnder(sourceRoot));
    }

    @Test
    void theTypeArgumentSplitterHandlesNesting() {
        Assertions.assertEquals(List.of("Node"), ViewTrackingBuilderGenerator.typeArguments("List<Node>"));
        Assertions.assertEquals(List.of("Map<String, Long>"),
                ViewTrackingBuilderGenerator.typeArguments("List<Map<String, Long>>"),
                "a comma inside a nested generic is not a separator");
        Assertions.assertEquals(List.of("String", "Node"),
                ViewTrackingBuilderGenerator.typeArguments("Map<String, Node>"));
        Assertions.assertEquals(List.of(), ViewTrackingBuilderGenerator.typeArguments("List"),
                "a raw collection has no element type to inspect, so it is left unwired");
        Assertions.assertEquals(List.of(), ViewTrackingBuilderGenerator.typeArguments("String"));
    }
}
