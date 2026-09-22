// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The processor's behaviour, stated against its source-text API.
 *
 * <h3>Phase 6: why this test no longer parses anything itself</h3>
 * <p>The old version built a JavaParser {@code JavaParser}, parsed a snippet, called
 * {@code processor.process(cu, 1)} to <em>mutate</em> the tree, and asserted on {@code cu.toString()}.
 * Every one of those steps is gone: the processor reads a tree but never mutates one, and the output is
 * generated text rather than a printed tree.</p>
 *
 * <p>The assertions are unchanged in intent, and deliberately so — they are the feature's contract, not
 * the old API's. What each now asserts is the same statement about the <em>text a developer's file is
 * given</em>, which is the thing that actually matters and is the only thing either implementation can
 * be judged by.</p>
 */
public class RecordBuilderProcessorTest {

    private final RecordBuilderProcessor processor = new RecordBuilderProcessor("  ");

    @Test
    public void testIndentStored() {
        assertEquals("  ", processor.getIndent());
    }

    /**
     * A builder carried over from a previous run is rebuilt from the record's <em>current</em>
     * components, so anything for a component that no longer exists disappears.
     *
     * <p>This is the "remove obsolete fields and methods" behaviour. The old implementation achieved it
     * by finding the stale members and removing them from the tree; generating the builder from the
     * component list cannot produce them in the first place, which is why the assertions below are
     * exact rather than approximate.</p>
     */
    @Test
    public void testRemoveObsoleteFieldsAndMethods() {
        String code = """
                public record User(String name) {
                    public static Builder builder() { return new Builder(); }
                    public Builder toBuilder() { return new Builder().name(this.name()).age(this.age()); }
                    public static class Builder {
                        private String name;
                        public Builder name(String name) { this.name = name; return this; }

                        private int age;
                        public Builder age(int age) { this.age = age; return this; }

                        public User build() { return new User(name, age); }
                    }
                }
                """;

        RecordBuilderProcessor.Target target = processor.target(code, 1);
        assertNotNull("The record must be located", target);
        String resultCode = processor.complete(
                code.substring(target.startOffset(), target.endOffset()),
                target.recordName(), target.components());

        // The still-declared component is present.
        assertTrue("Expected 'private String name;' to be kept", resultCode.contains("private String name;"));
        assertFalse("Expected 'private int age;' to be removed", resultCode.contains("private int age;"));
        // The dropped component leaves nothing behind.
        assertFalse("Expected 'private int age;' to be removed", resultCode.contains("private int age;"));
        assertFalse("Expected 'public Builder age(int age)' to be removed",
                resultCode.contains("public Builder age("));
        // And the two methods that consume the components use only the survivors.
        assertTrue("Expected 'return new User(name);' in build()",
                resultCode.contains("return new User(name);"));
        assertTrue("Expected 'return new Builder().name(this.name());' in toBuilder()",
                resultCode.contains("return new Builder().name(this.name());"));
    }

    /** The builder's members are grouped: fields, then {@code build()}, then setters in order. */
    @Test
    public void testGroupingOrder() {
        String code = """
                public record Grouped(String name, int age) {
                }
                """;

        RecordBuilderProcessor.Target target = processor.target(code, 1);
        assertNotNull(target);
        String res = processor.complete(code.substring(target.startOffset(), target.endOffset()),
                target.recordName(), target.components());

        int firstField = res.indexOf("private String name;");
        int secondField = res.indexOf("private int age;");
        int buildMethodIdx = res.indexOf("public Grouped build()");
        int firstSetter = res.indexOf("public Builder name(String name)");
        int secondSetter = res.indexOf("public Builder age(int age)");

        assertTrue("Both fields must be present", firstField >= 0 && secondField >= 0);
        assertTrue("Fields should be grouped first", firstField < secondField);
        assertTrue("Build method should be after fields", secondField < buildMethodIdx);
        assertTrue("Setters should be after build method", buildMethodIdx < firstSetter);
        assertTrue("Setters should maintain relative order", firstSetter < secondSetter);
    }

    /** The three generated parts and one field and the build method, for a two-component record. */
    @Test
    public void testBasicCreation() {
        String code = "public record Point(int x, int y) {}";

        RecordBuilderProcessor.Target target = processor.target(code, 1);
        assertNotNull(target);
        String resultCode = processor.complete(code.substring(target.startOffset(), target.endOffset()),
                target.recordName(), target.components());

        assertTrue(resultCode.contains("public static Builder builder()"));
        assertTrue(resultCode.contains("public Builder toBuilder()"));
        assertTrue(resultCode.contains("public static class Builder"));
        assertTrue(resultCode.contains("private int x;"));
        assertTrue(resultCode.contains("private int y;"));
        assertTrue(resultCode.contains("public Point build()"));
    }

    /**
     * A nested record's generated members are indented relative to the record, not to the file.
     *
     * <p>The JavaParser version needed {@code LexicalPreservingPrinter} plus a line-by-line re-indent
     * pass to reach this property. It is asserted the same way — by comparing the two indents rather
     * than by pinning exact columns — so the test still states the property rather than an
     * implementation's spacing.</p>
     */
    @Test
    public void testIndentationIsRelativeToTheRecord() {
        String code = "public class Outer {\n    public record Indented(String name) {}\n}";

        RecordBuilderProcessor.Target target = processor.target(code, 2);
        assertNotNull(target);
        String res = processor.complete(code.substring(target.startOffset(), target.endOffset()),
                target.recordName(), target.components());

        int builderIdx = res.indexOf("public static class Builder");
        int nameIdx = res.indexOf("private String name;");
        assertTrue("The builder must be generated", builderIdx > 0 && nameIdx > 0);

        int builderLineStart = res.lastIndexOf('\n', builderIdx) + 1;
        String builderIndent = res.substring(builderLineStart, builderIdx);

        int nameLineStart = res.lastIndexOf('\n', nameIdx) + 1;
        String nameIndent = res.substring(nameLineStart, nameIdx);

        assertTrue("Builder members should be indented more than Builder class itself",
                nameIndent.length() > builderIndent.length());
        // The record is at one level and its members one step further in, so the builder class must sit
        // exactly one indent step past the record's own line — the property the old re-indent pass
        // existed to provide. Asserted relative to the record's indentation rather than at a fixed
        // column, because the engine's indent step is a constructor argument, not a constant.
        int recordLineStart = res.lastIndexOf('\n', res.indexOf("public record Indented")) + 1;
        int recordIndent = res.indexOf("public record Indented") - recordLineStart;
        assertEquals("A nested record's builder sits one indent step past the record",
                recordIndent + processor.getIndent().length(), builderIndent.length());
    }

    /**
     * The two discovery questions the language server asks, answered from the same text.
     *
     * <p>These replaced a JavaParser walk in {@code JwaTextDocumentService}: which records carry the
     * builder annotation, and which record's <em>name</em> is on a given line. Both are asserted here
     * rather than in the sidecar because this class owns the tree and the javac-backed line lookup, and
     * the interesting cases are the ones a naive implementation gets wrong: the annotation's line is not
     * the name's line, and only one spelling of the annotation may be treated as a match.</p>
     */
    @Test
    public void annotationDiscoveryReportsTheNameLineNotTheAnnotationLine() {
        String code = """
                import hr.hrg.watch2.builder.api.GenerateBuilder;

                @GenerateBuilder
                public record User(String name) {}

                public record Plain(int id) {}
                """;

        var annotated = processor.annotatedRecords(code);
        assertEquals("only the annotated record is reported: " + annotated, 1, annotated.size());
        assertEquals("User", annotated.get(0).recordName());
        // A Java text block drops the line terminator after `"""`, so `@GenerateBuilder` is line 3 and
        // `public record User` line 4. The declaration's own start is the annotation's, and an editor
        // command sent to that line addresses the wrong place — the distinction F-46 records for
        // annotated accessors, and the same trap for a type.
        assertEquals(4, annotated.get(0).nameLine());

        assertNotNull("the annotated record is on its own name's line",
                processor.recordOnLine(code, 4));
        assertEquals("User", processor.recordOnLine(code, 4).recordName());
        assertNull("the annotation's line is not the record's line",
                processor.recordOnLine(code, 3));
        assertNotNull("an un-annotated record is still a valid code-action target",
                processor.recordOnLine(code, 6));
    }

    /** The fully qualified spelling is the same annotation, and text that does not parse yields none. */
    @Test
    public void annotationDiscoveryAcceptsTheQualifiedSpellingAndRefusesBrokenText() {
        String qualified = """
                @hr.hrg.watch2.builder.api.GenerateBuilder
                public record User(String name) {}
                """;
        assertEquals(1, processor.annotatedRecords(qualified).size());
        assertEquals(2, processor.annotatedRecords(qualified).get(0).nameLine());

        assertTrue("a record without the annotation is not reported",
                processor.annotatedRecords("public record User(String name) {}").isEmpty());
        assertTrue("text that cannot be read yields no records rather than a guess",
                processor.annotatedRecords("public record {").isEmpty());
    }
}
