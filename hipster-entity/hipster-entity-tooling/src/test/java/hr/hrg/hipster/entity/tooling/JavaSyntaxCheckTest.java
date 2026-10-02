package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Direct unit tests for {@link JavaSyntaxCheck} - the javac half of the migration, which answers the two
 * questions the LST cannot: <em>is this text parseable Java</em>, and <em>where is each declaration</em>.
 *
 * <h3>Why this file exists</h3>
 * <p>This class owns both the F-34 fail-safe (a file the parser silently <em>recovered</em> from must not
 * be read as an empty one) and every line and character span the DEC-028 location payload and the DEC-029
 * class index record. Before Phase 7 it had <strong>one</strong> test file touching it
 * ({@code SourceReaderTest}, four assertions), and {@link JavaSyntaxCheck#isSyntacticallyValid} had no
 * direct caller in any test at all - the guard every read passes through was asserted only through the
 * reads that happened to trip it. Each test below drives the class directly, and every assertion is the
 * class's own documented rule, measured rather than assumed.</p>
 *
 * <p>Two of those measurements found the implementation not doing what its javadoc said it does, and both
 * are fixed in {@link JavaSyntaxCheck}: a type whose simple name repeats one of its own annotations
 * reported the annotation's line as its name line, and the enclosing chain an earlier Phase 7 test found
 * was built innermost-first. The tests here are what keeps them fixed.</p>
 *
 * <p>Package-private like the class - it is not visible outside {@code hr.hrg.hipster.entity.tooling},
 * which is itself worth writing down where a reader will look.</p>
 */
class JavaSyntaxCheckTest {

    private static JavaSyntaxCheck.FileCheck inspect(String source) {
        JavaSyntaxCheck.FileCheck check = JavaSyntaxCheck.inspect(source);
        Assertions.assertTrue(check.syntacticallyValid(),
                "the fixture must be syntactically valid: " + source);
        return check;
    }

    private static JavaSyntaxCheck.TypePosition type(JavaSyntaxCheck.FileCheck check, String name) {
        return check.types().stream().filter(t -> t.simpleName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no type " + name + " in " + check.types()));
    }

    private static JavaSyntaxCheck.MemberSpan span(JavaSyntaxCheck.FileCheck check, String kind,
                                                   String name) {
        return check.spans().stream()
                .filter(s -> s.kind().equals(kind) && s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " span for " + name + " in "
                        + check.spans()));
    }

    private static List<String> role(JavaSyntaxCheck.FileCheck check, String role) {
        return check.members().stream()
                .filter(m -> m.role().equals(role))
                .map(JavaSyntaxCheck.MemberPosition::simpleName).toList();
    }

    // ------------------------------------------------------------- validity ---

    @Nested
    @DisplayName("isSyntacticallyValid")
    class Validity {

        @Test
        void ordinarySourceIsValidAndTheRecoveredParseIsNot() {
            Assertions.assertTrue(JavaSyntaxCheck.isSyntacticallyValid(
                    "package p;\npublic interface V {\n    String name();\n}\n"));
            // F-34's own fixture. OpenRewrite hands back a well-formed enum for this, with no throw and no
            // ParseExceptionResult marker, so this call is the only witness that the file is broken.
            Assertions.assertFalse(JavaSyntaxCheck.isSyntacticallyValid(
                    "package p;\npublic enum Broken_ {\n    id(java.lang.Long.class;\n}\n"),
                    "a missing parenthesis inside an enum's constant list is the case the whole fail-safe "
                            + "exists for");
        }

        @Test
        void everySyntaxErrorShapeIsCaught() {
            List<String> broken = List.of(
                    "package p;\npublic class C {\n    int x = ;\n}\n",
                    "package p;\npublic class C {\n    void m() { x( }\n}\n",
                    "package p;\npublic class C {",
                    "package p;\npublic class C {\n    void m() {\n        return return;\n    }\n}\n",
                    // One the type-error prefix list does not name, so the fail-safe direction decides:
                    // an error this class cannot classify is treated as syntax, and the file is unreadable.
                    "package p;\npublic class C {\n    int x = 1 + * 2;\n}\n");
            for (String source : broken) {
                Assertions.assertFalse(JavaSyntaxCheck.isSyntacticallyValid(source),
                        "expected a syntax error in: " + source);
            }
        }

        /**
         * The scope rule the class documents: <em>syntax only</em>. Type errors are ignored, because the
         * generator reads fragments and single files whose dependencies are on no classpath, so treating
         * "cannot find symbol" as "unreadable" would reject most of the tree.
         */
        @Test
        void typeErrorsDoNotMakeSourceUnreadable() {
            String source = """
                    package p;
                    public class C extends MissingBase implements AlsoMissing {
                        private NotAType field = new NotAType();

                        @UnheardOf
                        public Missing returned(Mystery argument) {
                            return argument.undeclaredMethod(NoSuchConstant.VALUE);
                        }
                    }
                    """;

            Assertions.assertTrue(JavaSyntaxCheck.isSyntacticallyValid(source),
                    "every name here is unresolved and the text is still well-formed Java");
            // And the positions still come out, which is what the location payload needs.
            JavaSyntaxCheck.FileCheck check = inspect(source);
            Assertions.assertEquals("C", type(check, "C").simpleName());
            Assertions.assertEquals(6, check.methods().get(0).nameLine(),
                    "the name is on the line its declaration starts on, not on the body below it");
        }

        @Test
        void nullBlankAndEmptySourceAreAllInvalidWithNothingRecorded() {
            for (String source : List.of("", "   ", "\n\n\t ")) {
                JavaSyntaxCheck.FileCheck check = JavaSyntaxCheck.inspect(source);
                Assertions.assertFalse(check.syntacticallyValid(), "blank source is not a read");
                Assertions.assertEquals(List.of(), check.types());
                Assertions.assertEquals(List.of(), check.methods());
                Assertions.assertEquals(List.of(), check.members());
                Assertions.assertEquals(List.of(), check.spans());
                Assertions.assertEquals(List.of(), check.annotations());
                Assertions.assertEquals(List.of(), check.caseLines());
                Assertions.assertEquals(List.of(), check.caseSpans());
            }
            Assertions.assertFalse(JavaSyntaxCheck.isSyntacticallyValid(null));
            Assertions.assertEquals(List.of(), JavaSyntaxCheck.typeNameLines(null));
            Assertions.assertFalse(JavaSyntaxCheck.inspect(null).syntacticallyValid());
        }

        @Test
        void aFileWithNoTypeDeclarationAtAllIsStillValidJava() {
            // An almost-empty file parses; `types()` is simply empty. The fail-safe still turns on the
            // OpenRewrite side, where SourceReader insists on a real J.CompilationUnit.
            Assertions.assertEquals(List.of(), inspect("// just a comment\n").types());
        }
    }

    // ----------------------------------------------------------------- types ---

    @Nested
    @DisplayName("type positions")
    class Types {

        @Test
        void anAnnotatedDeclarationCarriesTwoLines() {
            String source = """
                    package p;

                    @Deprecated
                    @Marker
                    public class Marked {
                    }
                    """;
            JavaSyntaxCheck.TypePosition marked = type(inspect(source), "Marked");

            Assertions.assertEquals(3, marked.declarationLine(),
                    "javac's start position for a class is its first annotation");
            Assertions.assertEquals(5, marked.nameLine());
        }

        /**
         * The shape the position layer exists to survive. Measured before the fix: a type whose simple
         * name repeats one of its own annotations reported the <em>annotation's</em> line as its name
         * line, because javac's start position for a declaration <em>includes</em> its annotations and the
         * token-boundary scan is bounded by that same span - so the annotation occurrence was inside the
         * region and matched first. The qualified spelling is the same hazard wearing a dot:
         * {@code Outer.Shape} in an annotation argument is a use of a type, never a declaration of one.
         */
        @Test
        void anAnnotationNamedLikeItsTypeDoesNotStealTheNameLine() {
            String annotatedOnItsOwnLine = """
                    package p;

                    @GenerateBuilder
                    public record GenerateBuilder(Long id) {
                    }
                    """;
            JavaSyntaxCheck.TypePosition generated = type(inspect(annotatedOnItsOwnLine), "GenerateBuilder");

            Assertions.assertEquals(4, generated.nameLine(),
                    "the record's name is on line 4; the annotation on line 3 spells the same word");
            Assertions.assertEquals(3, generated.declarationLine(),
                    "and the declaration line really is the annotation's - the two answers differ on "
                            + "purpose");

            String sameLine = """
                    package p;
                    @GenerateBuilder record GenerateBuilder(Long id) { }
                    """;
            Assertions.assertEquals(2, type(inspect(sameLine), "GenerateBuilder").nameLine(),
                    "the one-line spelling the javadoc names was already answered correctly");

            String qualified = """
                    package p;
                    public class Host {
                        @Level(value = Host.Shape.class)
                        interface Shape {
                        }
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(qualified);

            Assertions.assertEquals(4, type(check, "Shape").nameLine(),
                    "the `Host.Shape` above the declaration is a use, not the declaration");
            Assertions.assertEquals(3, type(check, "Shape").declarationLine());
        }

        @Test
        void everyKindOfNamedTypeIsATypePositionWithItsEnclosingChain() {
            String source = """
                    package p;
                    public class Outer {
                        interface I { }
                        enum E { A }
                        @interface M { }
                        record R(long x) { }
                        class Deep { static class Deeper { } }
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);

            Assertions.assertEquals(List.of("Outer", "I", "E", "M", "R", "Deep", "Deeper"),
                    check.types().stream().map(JavaSyntaxCheck.TypePosition::simpleName).toList(),
                    "source order, at every depth");
            Assertions.assertEquals(List.of(), type(check, "Outer").enclosingNames());
            Assertions.assertEquals(List.of("Outer"), type(check, "I").enclosingNames());
            Assertions.assertEquals(List.of("Outer", "Deep"), type(check, "Deeper").enclosingNames(),
                    "outermost first, and never the declaration itself");
        }

        @Test
        void anAnonymousClassContributesNoNameAndItsMembersAreNotOrphaned() {
            // Documented rule: the LST has no J.ClassDeclaration for an anonymous class either, so a name
            // added to the chain here would make every lookup for a type inside it miss.
            String source = """
                    package p;
                    public class Host {
                        Runnable r = new Runnable() {
                            @Override public void run() { }
                        };
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);

            Assertions.assertEquals(List.of("Host"),
                    check.types().stream().map(JavaSyntaxCheck.TypePosition::simpleName).toList(),
                    "the anonymous body is not a named type");
            Assertions.assertEquals(List.of("run"),
                    check.methods().stream()
                            .filter(m -> !m.simpleName().equals("<init>"))
                            .map(JavaSyntaxCheck.MethodPosition::simpleName).toList(),
                    "and its method is still recorded, so a search for the method does not lose it");
        }
    }

    // --------------------------------------------------------------- members ---

    @Nested
    @DisplayName("members, roles and spans")
    class Members {

        @Test
        void enumConstantsRecordComponentsAndFieldsShareOneShapeAndDifferByRole() {
            String source = """
                    package p;
                    public enum Ledger {
                        FIRST,
                        SECOND(2) {
                            @Override public String toString() { return "second"; }
                        };

                        static final int CEILING = 9;
                        int mutable = 1;

                        Ledger() { }
                        Ledger(int ordinal) { }

                        public int size() { return 0; }

                        interface Nested { }
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);

            Assertions.assertEquals(List.of("FIRST", "SECOND"), role(check, "enum-constant"),
                    "both spellings are constants, the bodied one included; measured, javac gives a bare "
                            + "constant its synthetic `new Ledger()` initializer at parse time");
            Assertions.assertEquals(List.of("CEILING", "mutable"), role(check, "field"));
            Assertions.assertEquals(List.of(), role(check, "record-component"),
                    "an enum declares no components, whatever else it declares");
            Assertions.assertEquals(List.of("toString"),
                    check.methods().stream()
                            .filter(m -> m.simpleName().equals("toString"))
                            .map(JavaSyntaxCheck.MethodPosition::simpleName).toList(),
                    "a method inside a constant's body is still a method position");
            Assertions.assertEquals(2, check.spans().stream()
                            .filter(s -> s.kind().equals("constructor")).count(),
                    "both constructors are recorded, so an overload is told apart by parameter count");
            Assertions.assertEquals(List.of("size"), check.spans().stream()
                    .filter(s -> s.kind().equals("method"))
                    .map(JavaSyntaxCheck.MemberSpan::name).toList());
            Assertions.assertEquals(List.of("Nested"), check.spans().stream()
                    .filter(s -> s.kind().equals("type"))
                    .map(JavaSyntaxCheck.MemberSpan::name).toList());
        }

        @Test
        void recordComponentsAreTheNonStaticVariablesAndAStaticFieldStaysAField() {
            String source = """
                    package p;
                    public record Row(Long id, String name) {
                        static final Row EMPTY = new Row(null, null);
                        Long derived() { return id; }
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);

            Assertions.assertEquals(List.of("id", "name"), role(check, "record-component"),
                    "the header components, in order");
            Assertions.assertEquals(List.of("EMPTY"), role(check, "field"),
                    "a record's only legal explicit field is a static one");
            Assertions.assertEquals(List.of(2, 2, 3),
                    check.members().stream().map(JavaSyntaxCheck.MemberPosition::nameLine).toList(),
                    "both header components share the record's own line and the field is on the next: "
                            + "three members, two distinct lines, which is why a lookup keys on the name "
                            + "and never on the line");
        }

        /**
         * The case a hand-written one-line fixture cannot show, found by the 2 000-member scale test.
         *
         * <p>An annotation argument is part of the declaration's own span, and an argument is free to
         * repeat the name it annotates — {@code @FieldSource(name = "birthDate") LocalDate birthDate();}
         * is the ordinary shape of a view accessor in this project. Before the fix the name scan matched
         * the literal and reported the annotation's line, so every such accessor was one to three lines
         * early, at a line that mentions the field name and so still reads as plausible to anyone
         * reviewing the report. A wrong line that looks right is the failure mode this whole class is
         * built to avoid.</p>
         */
        @Test
        void anAnnotationArgumentRepeatingTheMemberNameDoesNotStealIt() {
            String source = """
                    package p;
                    public interface View {
                        @FieldSource(name = "birthDate")
                        LocalDate birthDate();
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);
            JavaSyntaxCheck.MethodPosition accessor = check.methods().stream()
                    .filter(m -> m.simpleName().equals("birthDate")).findFirst().orElseThrow();

            Assertions.assertEquals(4, accessor.nameLine(),
                    "the accessor is declared on line 4; the annotation on line 3 only quotes its name");
            JavaSyntaxCheck.AnnotationPosition fieldSource = check.annotations().stream()
                    .filter(a -> a.simpleName().equals("FieldSource")).findFirst().orElseThrow();
            Assertions.assertEquals(3, fieldSource.line(),
                    "and DEC-028 records the annotation as a location of its own, one line above");
            Assertions.assertEquals(List.of("View"), List.of(fieldSource.declaringType()));

            // A name that appears only inside the literal is not a declaration at all, so the scan falls
            // back to the span's start rather than inventing a match.
            String quoted = """
                    package p;
                    public interface V2 {
                        @Marker(text = "birthDate")
                        int count();
                    }
                    """;
            Assertions.assertEquals(4, inspect(quoted).methods().stream()
                            .filter(m -> m.simpleName().equals("count")).findFirst().orElseThrow().nameLine(),
                    "an unrelated literal does not disturb a normally-named member");
        }

        @Test
        void aLocalVariableIsNeverAMember() {
            String source = """
                    package p;
                    public class C {
                        int field = 1;
                        void m() {
                            int local = 2;
                            for (int i = 0; i < 3; i++) { }
                        }
                    }
                    """;

            Assertions.assertEquals(List.of("field"), role(inspect(source), "field"));
        }

        /**
         * A field's span is the whole <em>declaration statement</em>, because that is the unit cooperative
         * codegen carries verbatim. javac's own end position cannot answer that, so the terminator comes
         * from a scan - and the scan has to survive everything that can hide a semicolon.
         */
        @Test
        void fieldSpansEndAtTheRealTerminatorWhateverHidesASemicolon() {
            String source = """
                    package p;
                    public class Hides {
                        String quoted = "a ; b ; c";
                        String[] array = { "x;", "y;" };
                        char semis = ';';
                        java.util.function.Supplier<String> block = () -> {
                            String inner = "not ; the terminator";
                            return inner;
                        };
                        Runnable anonymous = new Runnable() {
                            public void run() {
                                System.out.println(";");
                            }
                        };
                        String textBlock = \"""
                                a ;
                                b ;
                                \""";
                        int commentTrick = 1; /* ; */ int afterComment = 2;
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);

            Map<String, String> nextDeclaration = Map.of(
                    "quoted", "array", "array", "semis", "semis", "block",
                    "block", "anonymous", "anonymous", "textBlock", "textBlock", "commentTrick");
            for (String name : List.of("quoted", "array", "semis", "block", "anonymous", "textBlock")) {
                JavaSyntaxCheck.MemberSpan field = span(check, "field", name);
                Assertions.assertTrue(field.startOffset() >= 0 && field.endOffset() > field.startOffset(),
                        name + " must have a span at all");
                String text = source.substring(field.startOffset(), field.endOffset());
                Assertions.assertTrue(text.contains(name + " ="),
                        name + "'s span must start at its own declarator: " + text);
                Assertions.assertTrue(text.trim().endsWith(";"),
                        name + "'s span must end at a semicolon, got: " + text);
                Assertions.assertFalse(text.contains(nextDeclaration.get(name) + " ="),
                        name + "'s span must stop before the next declaration - that is where a missed "
                                + "quote or comment skip shows up: " + text);
            }

            Assertions.assertEquals("int commentTrick = 1;",
                    source.substring(span(check, "field", "commentTrick").startOffset(),
                            span(check, "field", "commentTrick").endOffset()),
                    "the scan stops at the first semicolon at depth zero, which precedes the block comment");
            Assertions.assertTrue(source
                            .substring(span(check, "field", "afterComment").startOffset(),
                                    span(check, "field", "afterComment").endOffset())
                            .contains("int afterComment = 2;"),
                    "and the next declarator keeps its own statement");
        }

        @Test
        void aSpanIsNeverLongerThanTheSourceAllows() {
            // `fieldDeclarationEnd` gives up when the nesting goes negative - the enclosing body's closing
            // brace - and returns the start, so a malformed declaration yields a span that is merely short
            // instead of one that swallows the rest of the type.
            String source = "package p;\npublic class C {\n    int unterminated = 1\n}\n";
            JavaSyntaxCheck.FileCheck check = JavaSyntaxCheck.inspect(source);

            Assertions.assertFalse(check.syntacticallyValid());
            JavaSyntaxCheck.MemberSpan field = check.spans().stream()
                    .filter(s -> s.kind().equals("field")).findFirst().orElseThrow();
            Assertions.assertTrue(field.endOffset() <= source.length());
            Assertions.assertTrue(field.endOffset() - field.startOffset() < source.length(),
                    "the slice covers the declaration, not the file");
        }

        @Test
        void overloadsKeepTheirArityAndOwnerInEverySpanRecord() {
            String source = """
                    package p;
                    public class O {
                        void pick() { }
                        void pick(String a) { }
                        void pick(String a, int b) { }
                        O() { }
                    }
                    """;
            JavaSyntaxCheck.FileCheck check = inspect(source);

            Assertions.assertEquals(List.of(0, 1, 2), check.spans().stream()
                    .filter(s -> s.kind().equals("method") && s.name().equals("pick"))
                    .map(JavaSyntaxCheck.MemberSpan::parameterCount).toList(),
                    "three overloads of one name are three identities, told apart by arity");
            Assertions.assertEquals(List.of(0), check.spans().stream()
                    .filter(s -> s.kind().equals("constructor"))
                    .map(JavaSyntaxCheck.MemberSpan::parameterCount).toList());
            Assertions.assertEquals(List.of("pick", "pick", "pick", "<init>"),
                    check.methods().stream().map(JavaSyntaxCheck.MethodPosition::simpleName).toList(),
                    "declaration order, and the constructor carries javac's own spelling");
            Assertions.assertEquals(List.of("O"), check.methods().stream()
                    .filter(m -> m.simpleName().equals("<init>"))
                    .map(JavaSyntaxCheck.MethodPosition::declaringType).toList(),
                    "a constructor's declaring type is its own class");
        }
    }

    // ----------------------------------------------------------- annotations ---

    @Test
    void anAnnotationIsRecordedAgainstItsInnermostOwner() {
        String source = """
                package p;
                @Level(1)
                public class Host {
                    @Level(2)
                    String field = "x";

                    @Level(3)
                    void method() { }

                    class Nested {
                        @Level(4)
                        void method() { }
                    }
                }
                """;
        JavaSyntaxCheck.FileCheck check = inspect(source);

        // Measured: annotations are collected on types and methods, so a field's `@Level(2)` is not among
        // these positions. That is the limitation TreeQueriesTest records with its -1.
        Assertions.assertEquals(List.of("Level", "Level", "Level"),
                check.annotations().stream().map(JavaSyntaxCheck.AnnotationPosition::simpleName).toList());
        Assertions.assertEquals(List.of(2, 7, 11),
                check.annotations().stream().map(JavaSyntaxCheck.AnnotationPosition::line).toList());
        Assertions.assertEquals(List.of("Host", "method", "method"),
                check.annotations().stream().map(JavaSyntaxCheck.AnnotationPosition::owner).toList(),
                "a type-level annotation is owned by the type's own name");
        Assertions.assertEquals(List.of("Host", "Host", "Nested"),
                check.annotations().stream().map(JavaSyntaxCheck.AnnotationPosition::declaringType).toList(),
                "the declaring type is the innermost one, which is what tells the two same-named members "
                        + "of this file apart");
    }

    @Test
    void aFullyQualifiedAnnotationIsRecordedByItsSimpleName() {
        String source = """
                package p;
                @java.lang.Deprecated
                public class C {
                }
                """;
        JavaSyntaxCheck.FileCheck check = inspect(source);

        Assertions.assertEquals(1, check.annotations().size());
        Assertions.assertEquals("Deprecated", check.annotations().get(0).simpleName(),
                "the package prefix is what TreeQueries strips for a lookup, so this side must agree");
    }

    // ----------------------------------------------------------------- cases ---

    @Test
    void caseLinesAndCaseSpansCoverEveryArmInSourceOrderAcrossTheWholeFile() {
        String source = """
                package p;
                public class Switches {
                    int first(String name) {
                        return switch (name) {
                            case "a" -> 1;
                            default -> 0;
                        };
                    }

                    int second(int n) {
                        switch (n) {
                            case 1:
                            case 2:
                                return 2;
                            default:
                                return 0;
                        }
                    }
                }
                """;
        JavaSyntaxCheck.FileCheck check = inspect(source);

        Assertions.assertEquals(List.of(5, 6, 12, 13, 15), check.caseLines(),
                "every arm of every switch in the file, in source order, including a fall-through arm that "
                        + "shares the body of the next");
        Assertions.assertEquals(check.caseLines().size(), check.caseSpans().size(),
                "TreeQueries pairs both lists against the LST's arms positionally, so they must agree with "
                        + "each other before anything else can");
        for (JavaSyntaxCheck.CaseSpan arm : check.caseSpans()) {
            Assertions.assertTrue(arm.startOffset() >= 0 && arm.endOffset() >= arm.startOffset());
            String text = source.substring(arm.startOffset(), arm.endOffset());
            Assertions.assertTrue(text.startsWith("case") || text.startsWith("default"),
                    "the span starts at the arm's own keyword: " + text);
        }
    }

    @Test
    void aFileWithNoSwitchHasNoArms() {
        JavaSyntaxCheck.FileCheck check = inspect("package p;\npublic class C {\n    void m() { }\n}\n");

        Assertions.assertEquals(List.of(), check.caseLines());
        Assertions.assertEquals(List.of(), check.caseSpans());
    }

    // ----------------------------------------------------------------- cache ---

    @Test
    void theSameTextAnswersFromTheSameResultAndDifferentTextsFromDifferentOnes() {
        String source = "package p;\npublic class C {\n    void m() { }\n}\n";
        JavaSyntaxCheck.FileCheck first = JavaSyntaxCheck.inspect(source);

        Assertions.assertSame(first, JavaSyntaxCheck.inspect(source), "one parse per text");
        Assertions.assertNotSame(first, JavaSyntaxCheck.inspect(source + "\n"),
                "the cache is keyed on the exact text, so a changed byte is a changed answer");
    }

    @Test
    void positionsSurviveACRLFFile() {
        // Every fixture in this module is written with `\n` and the committed files are CRLF; the line map
        // comes from javac over the exact text, so a suite that only ever saw LF could hide a line-ending
        // assumption.
        String crlf = "package p;\r\npublic class C {\r\n    @Deprecated\r\n    void m() { }\r\n}\r\n";
        JavaSyntaxCheck.FileCheck check = inspect(crlf);

        Assertions.assertEquals(3, check.annotations().get(0).line());
        Assertions.assertEquals(4, check.methods().get(0).nameLine());
        Assertions.assertEquals("m", check.methods().get(0).simpleName());
    }
}
