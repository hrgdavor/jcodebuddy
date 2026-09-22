// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * The class-member generators, stated against their source-text API.
 *
 * <p>Phase 6: these three generators used to live in {@code java-watch-agent} as JavaParser AST
 * mutations, and <strong>that module has no tests at all</strong> — so they were the one part of the
 * migration with no behavioural gate, only a compile. Moving them here is what makes them checkable, and
 * this class is the checked statement: what each generator adds, what it refuses to add twice, and that
 * nothing outside the class body moves.</p>
 */
public class ClassMemberProcessorTest {

    private final ClassMemberProcessor processor = new ClassMemberProcessor("    ");

    private static final String CLASS = """
            package a.b;

            public class Person {
                private String name;
                private final int age;
                private boolean active;

                public String nick() { return name; }
            }
            """;

    @Test
    public void targetPrefersTheClassNearestTheCaretAndFallsBackToTheFirst() {
        ClassMemberProcessor.Target near = processor.target(CLASS, 3);
        assertNotNull(near);
        assertEquals("Person", near.className());
        assertEquals(3, near.nameLine());

        // Far from every declaration: the first class in the file, which is the old fallback.
        ClassMemberProcessor.Target fallback = processor.target(CLASS, 900);
        assertNotNull(fallback);
        assertEquals("Person", fallback.className());

        assertNull("text with no class has no target", processor.target("package a.b;\n", 1));
        assertNull("text that cannot be read has no target", processor.target("public class {", 1));
    }

    @Test
    public void accessorsAreGeneratedOnlyWhereTheyAreMissing() {
        ClassMemberProcessor.Target target = processor.target(CLASS, 3);
        String generated = processor.withAccessors(CLASS, target, true, true);

        assertTrue(generated.contains("public String getName() {"));
        assertTrue(generated.contains("public boolean isActive() {"));
        // A final field gets no setter: the JavaParser version checked the same flag.
        assertFalse("a final field must not gain a setter: " + generated,
                generated.contains("public void setAge("));
        assertTrue(generated.contains("public void setName(String name) {"));

        // Applying it again changes nothing: every generated accessor is now an existing member, which is
        // the property that makes the tool safe to run twice from an editor.
        ClassMemberProcessor.Target second = processor.target(generated, 3);
        assertEquals(generated, processor.withAccessors(generated, second, true, true));
    }

    @Test
    public void theBuilderIsGeneratedOnceAndThenLeftAlone() {
        ClassMemberProcessor.Target target = processor.target(CLASS, 3);
        String generated = processor.withBuilder(CLASS, target);

        assertTrue(generated.contains("public static PersonBuilder builder() { return new PersonBuilder(); }"));
        assertTrue(generated.contains("public static class PersonBuilder {"));
        assertTrue(generated.contains("private String name;"));
        assertTrue(generated.contains("public PersonBuilder name(String name) {"));
        assertTrue(generated.contains("public Person build() { return new Person(); }"));

        ClassMemberProcessor.Target second = processor.target(generated, 3);
        assertEquals("a class that already has a builder is returned unchanged",
                generated, processor.withBuilder(generated, second));
    }

    @Test
    public void constructorsAreGeneratedOnlyWhereTheyAreMissing() {
        ClassMemberProcessor.Target target = processor.target(CLASS, 3);
        String generated = processor.withConstructors(CLASS, target);

        assertTrue(generated.contains("public Person(String name, int age, boolean active) {"));
        assertTrue(generated.contains("public Person() {}"));

        ClassMemberProcessor.Target second = processor.target(generated, 3);
        assertEquals(generated, processor.withConstructors(generated, second));

        // A class with no fields would gain two identical constructors, so the all-args form is skipped.
        String empty = "public class Empty {\n}\n";
        String onlyNoArgs = processor.withConstructors(empty, processor.target(empty, 1));
        assertTrue(onlyNoArgs.contains("public Empty() {}"));
        assertEquals("exactly one constructor, and it is the no-argument one: " + onlyNoArgs,
                1, countOf(onlyNoArgs, "public Empty("));
    }

    /** Occurrences of {@code needle} in {@code text}. */
    private static int countOf(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    /** The whole point of splicing rather than re-printing: nothing outside the class body moves. */
    @Test
    public void everythingOutsideTheClassBodyIsUntouched() {
        String generated = processor.withAccessors(CLASS, processor.target(CLASS, 3), true, true);

        assertTrue("the package and imports are byte-identical",
                generated.startsWith("package a.b;\n\npublic class Person {"));
        assertTrue("the class's own members keep their text",
                generated.contains("public String nick() { return name; }"));
        assertTrue("the class closes as it did", generated.endsWith("}\n"));
    }

    /** The caret-context question: which declarations exist, of which kind, on which lines. */
    @Test
    public void typesInReportsKindAndLines() {
        String source = """
                public class Outer {
                    public record Inner(int id) {
                    }
                }
                """;
        List<ClassMemberProcessor.TypeAt> types = ClassMemberProcessor.typesIn(source);

        assertEquals(2, types.size());
        assertEquals("Outer", types.get(0).simpleName());
        assertFalse(types.get(0).isRecord());
        assertEquals(1, types.get(0).startLine());
        assertEquals(4, types.get(0).endLine());

        assertEquals("Inner", types.get(1).simpleName());
        assertTrue(types.get(1).isRecord());
        assertEquals(2, types.get(1).startLine());
        assertEquals(3, types.get(1).endLine());

        assertTrue("unreadable text reports no types rather than a guess",
                ClassMemberProcessor.typesIn("public class {").isEmpty());
    }
}
