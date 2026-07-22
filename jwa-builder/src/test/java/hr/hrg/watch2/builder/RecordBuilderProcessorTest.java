// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class RecordBuilderProcessorTest {
    private JavaParser parser;
    private RecordBuilderProcessor processor;

    @Before
    public void setup() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        parser = new JavaParser(config);
        processor = new RecordBuilderProcessor(parser, "  ");
    }

    @Test
    public void testIndentStored() {
        assertEquals("  ", processor.getIndent());
    }

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
        var cuRes = parser.parse(code);
        assertTrue("Parsing should be successful", cuRes.isSuccessful());
        CompilationUnit cu = cuRes.getResult().get();

        boolean processed = processor.process(cu, 1);
        assertTrue("Processing should succeed", processed);

        String resultCode = cu.toString();

        // Field name should be kept
        assertTrue("Expected 'private String name;' to be kept", resultCode.contains("private String name;"));
        // Field age should be removed
        assertFalse("Expected 'private int age;' to be removed", resultCode.contains("private int age;"));
        // Method age() should be removed
        assertFalse("Expected 'public Builder age(int age)' to be removed", resultCode.contains("public Builder age("));
        // Build method should be updated to only use 'name'
        assertTrue("Expected 'return new User(name);' in build()", resultCode.contains("return new User(name);"));
        // toBuilder should be updated to only use 'name'
        assertTrue("Expected 'return new Builder().name(this.name());' in toBuilder()",
                resultCode.contains("return new Builder().name(this.name());"));
    }

    @Test
    public void testGroupingOrder() {
        String code = """
                public record Grouped(String name, int age) {
                }
                """;
        var cuRes = parser.parse(code);
        assertTrue(cuRes.isSuccessful());
        CompilationUnit cu = cuRes.getResult().get();

        processor.process(cu, 1);

        String res = cu.toString();

        // Find indices of members in Builder class
        int firstField = res.indexOf("private String name;");
        int secondField = res.indexOf("private int age;");
        int buildMethodIdx = res.indexOf("public Grouped build()");
        int firstSetter = res.indexOf("public Builder name(String name)");
        int secondSetter = res.indexOf("public Builder age(int age)");

        assertTrue("Fields should be grouped first", firstField < secondField);
        assertTrue("Build method should be after fields", secondField < buildMethodIdx);
        assertTrue("Setters should be after build method", buildMethodIdx < firstSetter);
        assertTrue("Setters should maintain relative order", firstSetter < secondSetter);
    }

    @Test
    public void testBasicCreation() {
        String code = "public record Point(int x, int y) {}";
        var cuRes = parser.parse(code);
        assertTrue(cuRes.isSuccessful());
        CompilationUnit cu = cuRes.getResult().get();

        processor.process(cu, 1);

        String resultCode = cu.toString();

        assertTrue(resultCode.contains("public static Builder builder()"));
        assertTrue(resultCode.contains("public Builder toBuilder()"));
        assertTrue(resultCode.contains("public static class Builder"));
        assertTrue(resultCode.contains("private int x;"));
        assertTrue(resultCode.contains("private int y;"));
        assertTrue(resultCode.contains("public Point build()"));
    }

    @Test
    public void testIndentationWithLexicalPreservation() {
        String code = "public record Indented(String name) {}";
        var cuRes = parser.parse(code);
        CompilationUnit cu = cuRes.getResult().get();
        LexicalPreservingPrinter.setup(cu);

        processor.process(cu, 1);

        String res = cu.toString();
        System.out.println("--- GENERATED CODE (toString) ---");
        System.out.println(res);
        System.out.println("---------------------------------");

        int builderIdx = res.indexOf("public static class Builder");
        int nameIdx = res.indexOf("private String name;");

        int builderLineStart = res.lastIndexOf('\n', builderIdx) + 1;
        String builderIndent = res.substring(builderLineStart, builderIdx);

        int nameLineStart = res.lastIndexOf('\n', nameIdx) + 1;
        String nameIndent = res.substring(nameLineStart, nameIdx);

        System.out.println("Builder indent: '" + builderIndent + "'");
        System.out.println("Name indent:    '" + nameIndent + "'");

        assertTrue("Builder members should be indented more than Builder class itself",
                nameIndent.length() > builderIndent.length());
    }
}
