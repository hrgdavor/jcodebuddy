package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.J;

import java.util.List;
import java.util.Map;

/**
 * Scale as an edge case: source big enough that an offset, a traversal order or a scan inside a
 * declaration's own span stops being a detail.
 *
 * <p>The plan's {@code LargeFileTests} named a Phase 2 sketch, so it had no subject. The subject is the
 * pair of classes the port actually put in the read path - {@link SourceReader} for the tree and
 * {@link JavaSyntaxCheck} for every position - and the properties worth owning at scale are:</p>
 *
 * <ul>
 *   <li><strong>An annotation argument that quotes the name it annotates.</strong> A view accessor is
 *       {@code @FieldSource(name = "birthDate") LocalDate birthDate();} in this project, and the
 *       annotation is part of the declaration's own span, so a name lookup scanning that span meets the
 *       literal first. At this size the mistake is not one wrong line, it is two thousand, each landing on
 *       a line that mentions the field and therefore reads as plausible. Phase 7 found the defect here, in
 *       {@link JavaSyntaxCheck#namePositionIn}.</li>
 *   <li><strong>Offsets past 32 767.</strong> javac's positions are {@code int} character offsets and every
 *       span in this module is a character range; an unnoticed narrowing anywhere in the chain shows up
 *       only in a file big enough to cross that line. The fixtures here are deliberately past it, and the
 *       assertion is that the slice still comes back verbatim, in order.</li>
 *   <li><strong>One parse per file, not one per question.</strong> {@code JavaSyntaxCheck} memoises its
 *       javac parse on the source text, and the generator asks that question once per declaration of a
 *       file. At this size a missing cache is not slow, it is quadratic - so these tests ask every member
 *       line of a 2 000-member type under a ceiling no honest implementation misses.</li>
 *   <li><strong>Order survives.</strong> Source order is the contract of five queries here, and a traversal
 *       that reorders under load is a wrong answer that still compiles.</li>
 * </ul>
 *
 * <p>Nothing in this file asserts a <em>performance claim</em>: the ceilings below are hang detectors.
 * Measured timings live in
 * {@code doc/brainstorm/rewrite-migration/07-testing/benchmarks/BenchmarkReport.md}, produced by the JMH
 * benchmarks under the {@code jmh} profile, where a number means something.</p>
 */
class LargeSourceEdgeCaseTest {

    /** Enough members that the file passes 32 767 characters twice over. */
    private static final int ACCESSOR_PAIRS = 2_000;

    private static String buildWideInterface() {
        StringBuilder source = new StringBuilder(1_000_000);
        source.append("package p;\n\nimport java.util.List;\n\npublic interface Wide {\n");
        for (int i = 0; i < ACCESSOR_PAIRS; i++) {
            source.append("    /**\n     * Property ").append(i).append(".\n     */\n");
            source.append("    @FieldSource(name = \"prop").append(i).append("\")\n");
            source.append("    Long prop").append(i).append("();\n");
            source.append("    void setProp").append(i).append("(Long value);\n");
        }
        source.append("}\n");
        return source.toString();
    }

    @Test
    void aTwoThousandMemberInterfaceReadsAndEveryMemberResolvesToItsOwnLine() {
        String source = buildWideInterface();
        Assertions.assertTrue(source.length() > 200_000,
                "the fixture must be large enough to be the case it claims: " + source.length() + " chars");

        long started = System.nanoTime();
        J.CompilationUnit cu = SourceReader.readSourceText(source);
        J.ClassDeclaration wide = TreeQueries.interfaces(cu).get(0);
        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(wide);
        Assertions.assertEquals(ACCESSOR_PAIRS * 2, methods.size(), "accessors and setters, all of them");

        // Every accessor's declared line, from the query the metadata and the reports are built on. The
        // annotation directly above each one quotes the accessor's own name, which is what makes this the
        // shape that catches a span scan matching inside a literal.
        for (int i = 0; i < ACCESSOR_PAIRS; i++) {
            J.MethodDeclaration accessor = methods.get(i * 2);
            int line = TreeQueries.methodLineOf(accessor, "Wide", source);
            Assertions.assertEquals(10 + i * 6, line,
                    "prop" + i + " resolves to its own line, not to the @FieldSource above it");
            int annotationLine = TreeQueries.annotationLineOf("Wide", accessor.getSimpleName(),
                    "FieldSource", source);
            Assertions.assertEquals(line - 1, annotationLine,
                    "and its @FieldSource is the line above, at every depth in the file");
        }

        // The setters carry no annotation, so nothing can collide with them: the control for the loop above.
        for (int i = 0; i < ACCESSOR_PAIRS; i += 37) {
            J.MethodDeclaration setter = methods.get(i * 2 + 1);
            Assertions.assertEquals(11 + i * 6, TreeQueries.methodLineOf(setter, "Wide", source),
                    "setProp" + i + " resolves too");
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        Assertions.assertTrue(elapsedMillis < 60_000,
                "6 000 line lookups over one 2 000-member type completed in " + elapsedMillis
                        + " ms; the bound is a hang detector, not a performance claim");
    }

    /**
     * The verbatim carry-through cooperative codegen promises (DEC-020), checked at the far end of a large
     * file - where an offset narrowing or a span that ran past its terminator would show up.
     */
    @Test
    void memberTextIsVerbatimAtBothEndsOfALargeFile() {
        String source = buildWideInterface();

        String first = TreeQueries.memberText("Wide", "method", "prop0", 0, source);
        String middle = TreeQueries.memberText("Wide", "method", "prop" + (ACCESSOR_PAIRS / 2), 0, source);
        String last = TreeQueries.memberText("Wide", "method", "prop" + (ACCESSOR_PAIRS - 1), 0, source);

        for (String slice : List.of(first, middle, last)) {
            Assertions.assertNotNull(slice, "a member near the end of a large file is still findable");
            Assertions.assertTrue(slice.contains("/**"), "its javadoc travels with it: " + slice);
            Assertions.assertTrue(slice.trim().endsWith("();"),
                    "and the slice ends at the declaration, not mid-file: " + slice);
        }
        Assertions.assertTrue(middle.contains("Property " + (ACCESSOR_PAIRS / 2) + "."),
                "the middle slice is the middle member: " + middle);
        Assertions.assertTrue(source.indexOf(middle.stripTrailing()) > 32_767,
                "the middle member really is past the short-offset boundary");
    }

    @Test
    void aOneThousandArmSwitchPairsWithTheLstAndStaysInSourceOrder() {
        StringBuilder source = new StringBuilder();
        source.append("package p;\npublic class Ordinals {\n    public int nameOf(int ordinal) {\n"
                + "        return switch (ordinal) {\n");
        int arms = 1_000;
        for (int i = 0; i < arms; i++) {
            source.append("            case ").append(i).append(" -> ").append(i).append(";\n");
        }
        source.append("            default -> -1;\n        };\n    }\n}\n");

        long started = System.nanoTime();
        J.CompilationUnit cu = SourceReader.readSourceText(source.toString());
        Map<J.Case, TreeQueries.SourceSpan> spans = TreeQueries.caseSpans(cu, source.toString());
        Map<J.Case, Integer> lines = TreeQueries.caseLines(cu, source.toString());
        List<J.Case> armsOf = TreeQueries.findAll(cu, J.Case.class);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        Assertions.assertEquals(arms + 1, armsOf.size());
        Assertions.assertEquals(arms + 1, spans.size(),
                "the positional zip between javac's arms and the LST's must not degrade with size");
        Assertions.assertEquals(arms + 1, lines.size());

        int previousStart = -1;
        int previousLine = -1;
        for (J.Case arm : armsOf) {
            TreeQueries.SourceSpan span = spans.get(arm);
            Assertions.assertNotNull(span);
            Assertions.assertTrue(span.start() > previousStart,
                    "the arms are paired in source order, and past the 32 767 boundary a narrowing would "
                            + "put them out of order: " + previousStart + " then " + span.start());
            previousStart = span.start();
            Assertions.assertTrue(lines.get(arm) > previousLine, "the lines are in order too");
            previousLine = lines.get(arm);
        }
        Assertions.assertTrue(elapsedMillis < 60_000, "1 001 arms resolved in " + elapsedMillis + " ms");
    }

    @Test
    void aNestingDepthNoGeneratorEmitsStillResolvesEveryLine() {
        // Six levels under a root: nothing in the generated tree nests like this, and that is the point -
        // the chain is a stack walk, and a stack that only works two deep is the bug Phase 7 found in
        // TreeQueries#typesWithEnclosing.
        StringBuilder source = new StringBuilder("package p;\npublic class Root {\n");
        StringBuilder closing = new StringBuilder();
        for (int depth = 0; depth < 6; depth++) {
            source.append("    static class L").append(depth).append(" {\n");
            closing.append("    }\n");
        }
        source.append("        record Deepest(long x) { }\n");
        source.append(closing).append("}\n");
        String text = source.toString();

        List<TreeQueries.EnclosedType> all = TreeQueries.typesWithEnclosing(SourceReader.readSourceText(text));
        Assertions.assertEquals(8, all.size(), "the root, six levels and the nested record");

        String[] lines = text.split("\\R", -1);
        for (TreeQueries.EnclosedType enclosed : all) {
            List<String> chain = enclosed.enclosing().stream()
                    .map(J.ClassDeclaration::getSimpleName).toList();
            String declared = enclosed.declaration().getSimpleName();
            int line = TreeQueries.lineOfChained(enclosed.declaration(), chain, text);
            Assertions.assertTrue(line > 0, declared + " must resolve its own line");
            Assertions.assertTrue(lines[line - 1].contains(declared),
                    "the line the query names is the line that declares " + declared + ": "
                            + lines[line - 1]);
        }
        TreeQueries.EnclosedType deepest = all.get(all.size() - 1);
        Assertions.assertEquals(List.of("Root", "L0", "L1", "L2", "L3", "L4", "L5"),
                deepest.enclosing().stream().map(J.ClassDeclaration::getSimpleName).toList(),
                "outermost first, seven names deep");
    }
}
