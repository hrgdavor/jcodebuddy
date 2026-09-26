// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.tools;

import hr.hrg.jcodebuddy.codegen.CodeContextImpl;
import hr.hrg.watch2.agent.core.ContextualAnalyzer;
import hr.hrg.watch2.agent.tools.ActionTool.FileChange;
import hr.hrg.watch2.agent.tools.ActionTool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam tests for the agent's tool layer: the context record, the registry, the adapter into the
 * {@code project-automation} generator SPI, the {@code hello} dummy tool, and the marker analyzer.
 * Assertions follow measured behaviour; where reality differs from what a doc suggests, the test
 * says so in a "measured:" comment instead of pretending otherwise.
 */
class ToolSeamTest {

    @TempDir
    static Path repo;

    /** The tool stand-in the registry, adapter, and order tests are run against; captures contexts. */
    private static final class StubTool implements ActionTool {
        private final String name;
        private final boolean applicable;
        private final List<FileChange> changes;
        ToolContext lastContext;

        StubTool(String name, boolean applicable, List<FileChange> changes) {
            this.name = name;
            this.applicable = applicable;
            this.changes = changes;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isApplicable(ToolContext context) {
            this.lastContext = context;
            return applicable;
        }

        @Override
        public List<FileChange> execute(ToolContext context) {
            this.lastContext = context;
            return changes;
        }
    }

    private static Path written(String name, String content) throws IOException {
        Path p = repo.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    // ---- SimpleToolContext ---------------------------------------------------------------

    /** The two-argument constructor is what LSP-path callers use; its defaults are load-bearing. */
    @Test
    void theTwoArgumentContextConstructorDefaultsToLineOneAndFourSpacesOfIndent() {
        var ctx = new SimpleToolContext(repo, repo.resolve("A.java"));
        assertEquals(1, ctx.line());
        assertEquals("    ", ctx.indent());
    }

    /**
     * The three-argument constructor must honour an explicit line while still defaulting the indent;
     * measured: {@code SimpleToolContext} carries no javadoc at all, so the "4 spaces per its
     * javadoc" claim in the task could only be verified against the constructor body itself.
     */
    @Test
    void theThreeArgumentContextConstructorKeepsTheGivenLineAndDefaultsToFourSpacesOfIndent() {
        var ctx = new SimpleToolContext(repo, repo.resolve("B.java"), 42);
        assertEquals(42, ctx.line());
        assertEquals("    ", ctx.indent());
    }

    /**
     * The record exists to implement {@link ToolContext}; a getter that stopped mirroring its
     * component would silently hand every tool stale context data.
     */
    @Test
    void theRecordComponentsAndTheToolContextGettersReturnTheSameValues() {
        var ctx = new SimpleToolContext(repo, repo.resolve("C.java"), 7, "\t");
        assertEquals(ctx.root(), ctx.getRootPath());
        assertEquals(ctx.file(), ctx.getFilePath());
        assertEquals(ctx.line(), ctx.getLine());
        assertEquals("\t", ctx.getIndent());
    }

    // ---- ToolRegistry --------------------------------------------------------------------

    /**
     * Lookup is the registry's whole contract: a registered name resolves; measured, an unknown one
     * is {@code Optional.empty()} — it neither returns null nor throws.
     */
    @Test
    void aRegisteredToolIsFoundByItsNameAndAnUnknownNameYieldsAnEmptyOptional() {
        var registry = new ToolRegistry();
        var tool = new StubTool("hello", true, List.of());
        registry.register(tool);

        assertEquals(Optional.of(tool), registry.getTool("hello"));
        assertEquals(Optional.empty(), registry.getTool("no-such-tool"));
    }

    /**
     * Registration lower-cases the key and lookup lower-cases the query, so a marker written
     * {@code @gen HELLO} still finds the {@code hello} tool; this pins both halves of that.
     */
    @Test
    void registrationAndLookupAreCaseInsensitiveBecauseEveryNameIsLowerCased() {
        var registry = new ToolRegistry();
        var tool = new StubTool("Builder", true, List.of());
        registry.register(tool);

        assertEquals(Optional.of(tool), registry.getTool("builder"));
        assertEquals(Optional.of(tool), registry.getTool("BUILDER"));
    }

    /**
     * The registry is a plain {@code Map.put}, so a second registration under the same name silently
     * replaces the first rather than throwing — the engine's boot sequence depends on that.
     */
    @Test
    void registeringTheSameNameTwiceReplacesTheEarlierTool() {
        var registry = new ToolRegistry();
        var first = new StubTool("hello", true, List.of());
        var second = new StubTool("hello", false, List.of());
        registry.register(first);
        registry.register(second);

        assertEquals(Optional.of(second), registry.getTool("hello"));
        assertEquals(1, registry.getAllTools().size());
    }

    /**
     * Measured: the backing map is a {@code HashMap}, so {@code getAllTools()} iterates in hash-bucket
     * order, NOT registration order — registered as hello, builder, getters (buckets 11, 8, 12;
     * String.hashCode is spec-fixed so these are stable), iteration comes back builder, hello, getters.
     */
    @Test
    void getAllToolsIteratesInHashMapBucketOrderRatherThanRegistrationOrder() {
        var registry = new ToolRegistry();
        registry.register(new StubTool("hello", true, List.of()));
        registry.register(new StubTool("builder", true, List.of()));
        registry.register(new StubTool("getters", true, List.of()));

        var names = registry.getAllTools().stream().map(ActionTool::getName).toList();
        assertEquals(List.of("builder", "hello", "getters"), names);
    }

    // ---- ActionToolAdapter ---------------------------------------------------------------

    /**
     * The adapter is handed to code that keys generators by {@code name()}; a mismatch with the
     * wrapped tool's name would make the audit trail attribute changes to the wrong tool.
     */
    @Test
    void theAdapterPresentsTheWrappedToolsName() {
        var adapter = new ActionToolAdapter(new StubTool("record_builder", true, List.of()));
        assertEquals("record_builder", adapter.name());
    }

    /**
     * The adapter's real job is the context conversion; every field of the {@code CodeContext} must
     * arrive in the {@code SimpleToolContext} the ActionTool sees, or tools read the wrong file.
     */
    @Test
    void isApplicableAsksTheWrappedToolAndPassesAllFourCodeContextFieldsThrough() {
        var tool = new StubTool("hello", true, List.of());
        var adapter = new ActionToolAdapter(tool);
        var file = repo.resolve("D.java");
        var context = new CodeContextImpl(repo, file, 7, "\t");

        assertTrue(adapter.isApplicable(context));
        var seen = assertInstanceOf(SimpleToolContext.class, tool.lastContext);
        assertEquals(repo, seen.getRootPath());
        assertEquals(file, seen.getFilePath());
        assertEquals(7, seen.getLine());
        assertEquals("\t", seen.getIndent());
    }

    /**
     * A non-applicable tool produces an empty change list, and applicability is checked by the
     * caller, not the adapter: measured, {@code generate} runs {@code execute} and hands the list
     * back verbatim even when the wrapped tool would answer {@code false} to {@code isApplicable}.
     */
    @Test
    void generateHandsBackTheToolsChangesVerbatimEvenWhenTheToolSaysItIsNotApplicable() {
        var changes = List.of(new FileChange(repo.resolve("E.java"), "x", ActionTool.ChangeType.CHANGE));
        var tool = new StubTool("hello", false, changes);
        var adapter = new ActionToolAdapter(tool);
        var context = new CodeContextImpl(repo, repo.resolve("E.java"), 1);

        assertFalse(adapter.isApplicable(context));
        List<FileChange> generated = adapter.generate(context);
        assertEquals(changes, generated);
        assertEquals(1, generated.size());
        assertEquals(ActionTool.ChangeType.CHANGE, generated.get(0).type());
    }

    // ---- HelloTool -----------------------------------------------------------------------

    /**
     * Measured contradiction: {@code HelloTool.isApplicable} unconditionally returns {@code true}
     * (HelloTool.java:17-19) — the {@code // @gen hello} trigger is NOT inspected anywhere in the
     * tool; trigger matching lives in {@code ContextualAnalyzer}/{@code ActionEngine}. So this pins
     * the real always-applicable behaviour, with and without the trigger.
     */
    @Test
    void helloToolIsApplicableToEverythingWhetherOrNotTheTriggerCommentIsThere() throws IOException {
        var triggered = written("WithTrigger.java", "// @gen hello\nclass A {}\n");
        var plain = written("Plain.java", "class B {}\n");
        var tool = new HelloTool();

        assertEquals("hello", tool.getName());
        assertTrue(tool.isApplicable(new SimpleToolContext(repo, triggered, 1)));
        assertTrue(tool.isApplicable(new SimpleToolContext(repo, plain, 1)));
    }

    /**
     * The tool's in-code comment says it "adds a comment at the top of the file", and measured that
     * is exactly what it does: the greeting is PREPENDED (not appended), and the whole rewrite comes
     * back as a single {@code CHANGE} for the same path — the engine applies, it does not diff.
     */
    @Test
    void helloToolExecutePrependsTheGreetingLineAndReportsASingleChange() throws IOException {
        String original = "// @gen hello\nclass A {\n}\n";
        var file = written("HelloSubject.java", original);

        List<FileChange> changes = new HelloTool().execute(new SimpleToolContext(repo, file));

        assertEquals(1, changes.size());
        var change = changes.get(0);
        assertEquals(file, change.path());
        assertEquals(ActionTool.ChangeType.CHANGE, change.type());
        assertEquals("// Hello from Java Watch Agent!\n" + original, change.content());
    }

    /**
     * Execute reads the file eagerly and wraps any failure in a bare {@code RuntimeException}
     * (HelloTool.java:28-30); a dangling trigger pointing at a deleted buffer is the realistic
     * way that fires, and the engine's error reporting depends on getting an exception, not null.
     */
    @Test
    void helloToolExecuteWrapsAnUnreadableFileInARuntimeException() {
        var missing = repo.resolve("gone.java");
        assertThrows(RuntimeException.class,
                () -> new HelloTool().execute(new SimpleToolContext(repo, missing)));
    }

    // ---- ContextualAnalyzer ----------------------------------------------------------------

    /**
     * The trigger scan returns the FIRST marker's tool name and its 1-based line; a second marker
     * lower in the file must not win, because the engine processes one trigger per edit pass.
     */
    @Test
    void findTriggerReportsTheFirstGenMarkerWithItsToolNameAndOneBasedLine() throws IOException {
        var file = written("Trigger.java", "package a;\n// @gen hello\nclass X {}\n// @gen other\n");
        var analyzer = new ContextualAnalyzer();

        Optional<ContextualAnalyzer.Trigger> trigger = analyzer.findTrigger(file);
        assertTrue(trigger.isPresent());
        assertEquals("hello", trigger.get().toolName());
        assertEquals(2, trigger.get().line());
        assertFalse(trigger.get().isDiscovery());
    }

    /**
     * A bare {@code // @gen} with no tool name is a discovery request (empty tool name), while files
     * with no marker — and files that do not exist, whose IOException is silently swallowed — both
     * answer {@code Optional.empty()}. All three contracts live in findTrigger's parsing loop.
     */
    @Test
    void aBareGenMarkerIsADiscoveryTriggerWhileMarkerlessAndMissingFilesYieldEmpty() throws IOException {
        var discovery = written("Bare.java", "// @gen\nclass Y {}\n");
        var markerless = written("NoMarker.java", "class Z {}\n");
        var analyzer = new ContextualAnalyzer();

        var trigger = analyzer.findTrigger(discovery).orElseThrow();
        assertTrue(trigger.isDiscovery());
        assertEquals(1, trigger.line());
        assertEquals(Optional.empty(), analyzer.findTrigger(markerless));
        assertEquals(Optional.empty(), analyzer.findTrigger(repo.resolve("absent.java")));
    }

    /**
     * Watches are collected across the whole file (unlike triggers, which stop at the first hit),
     * each carrying its 1-based line and only its first whitespace-delimited token as the target;
     * a missing file is swallowed to an empty list rather than an exception.
     */
    @Test
    void findWatchesCollectsEveryTargetWithItsLineAndIgnoresEverythingAfterTheFirstToken()
            throws IOException {
        var file = written("Watched.java",
                "// @watch: src/main/A.java trailing noise\nclass W {}\n\t// @watch:B.java\n");
        var analyzer = new ContextualAnalyzer();

        List<ContextualAnalyzer.Watch> watches = analyzer.findWatches(file);
        assertEquals(List.of(new ContextualAnalyzer.Watch("src/main/A.java", 1),
                new ContextualAnalyzer.Watch("B.java", 3)), watches);
        assertEquals(List.of(), analyzer.findWatches(repo.resolve("absent.java")));
    }

    /**
     * Caret context for a class body is the four member generators in the fixed order
     * builder/getters/setters/constructor; a caret 500 lines below the only declaration is "near"
     * nothing, so the honest answer is the empty list, not a guess.
     */
    @Test
    void suggestToolsInsideAClassOffersTheFourClassMemberToolsAndNothingFarAway() throws IOException {
        var file = written("Person.java", "public class Person {\n    private String name;\n}\n");
        var analyzer = new ContextualAnalyzer();

        assertEquals(List.of("builder", "getters", "setters", "constructor"),
                analyzer.suggestTools(file, 2));
        assertEquals(List.of(), analyzer.suggestTools(file, 500));
    }

    /**
     * A record nested in a class is the specificity rule made visible: the caret sits inside BOTH
     * declarations, and the record must win (only {@code record_builder}) because that is what the
     * record_builder tool means. Also guards that an unreadable path yields no suggestions.
     */
    @Test
    void suggestToolsInsideANestedRecordOffersOnlyTheRecordBuilder() throws IOException {
        var file = written("Outer.java",
                "public class Outer {\n    record Point(int x, int y) {\n    }\n}\n");
        var analyzer = new ContextualAnalyzer();

        assertEquals(List.of("record_builder"), analyzer.suggestTools(file, 2));
        assertEquals(List.of(), analyzer.suggestTools(repo.resolve("absent.java"), 1));
    }
}
