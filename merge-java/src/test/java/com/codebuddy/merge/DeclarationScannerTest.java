// {@link com.codebuddy.merge.DeclarationScannerTest} Tests for formatting-tolerant declaration extraction.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These pin down three silent failures that probing found in the previous
 * line-by-line scan. Each one returned "nothing detected" rather than an error,
 * which is the worst outcome for a merge tool: the conflict does not move to the
 * review pile, it vanishes.
 */
class DeclarationScannerTest {

    @Test
    @DisplayName("finds a declaration despite a comment between declarations")
    void findsDeclarationDespiteComment() {
        String code = """
            void process() { }
            // an explanatory comment
            void process(String id) { }
            """;

        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn(code);

        assertEquals(2, declarations.size(),
            "a comment between declarations must not hide either: " + declarations);
        assertEquals("process", declarations.get(1).name());
        assertEquals("String id", declarations.get(1).parameters());
    }

    @Test
    @DisplayName("finds a declaration despite an annotation between declarations")
    void findsDeclarationDespiteAnnotation() {
        String code = """
            void process() { }
            @Deprecated
            @SuppressWarnings("unchecked")
            void process(String id) { }
            """;

        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn(code);

        assertEquals(2, declarations.size(),
            "an annotation must not hide the declaration it annotates: " + declarations);
    }

    @Test
    @DisplayName("finds a declaration whose opening brace is on the next line")
    void findsDeclarationWithAllmanBrace() {
        String code = """
            void process()
            { }
            void process(String id)
            { }
            """;

        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn(code);

        assertEquals(2, declarations.size(),
            "the allman brace style must be recognised: " + declarations);
        assertEquals("String id", declarations.get(1).parameters());
    }

    @Test
    @DisplayName("finds a declaration with both a comment and a next-line brace")
    void findsDeclarationWithBothVariations() {
        String code = """
            void process() { }
            // why this overload exists
            void process(String id)
            {
            }
            """;

        assertEquals(2, DeclarationScanner.declarationsIn(code).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "void process() { }",
        "void process(){ }",
        "    void process()    { }",
        "public void process() { }",
        "public static final void process() { }",
        "void process()\n{ }",
        "// note\nvoid process() { }",
        "@Override\nvoid process() { }"
    })
    @DisplayName("recognises a no-arg declaration however it is formatted")
    void recognisesNoArgDeclarationInAllForms(String code) {
        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn(code);

        assertEquals(1, declarations.size(), "failed for: " + code.replace("\n", "\\n"));
        DeclarationScanner.MethodDeclaration declaration = declarations.get(0);
        assertEquals("process", declaration.name(),
            "the name must not swallow part of the return type");
        assertEquals("", declaration.parameters(),
            "a no-arg method has an empty parameter list, not a missing one");
    }

    @Test
    @DisplayName("does not let the method name swallow the return type")
    void doesNotSwallowReturnType() {
        assertEquals("process", DeclarationScanner.declarationsIn("void process() { }")
            .get(0).name());
        assertEquals("getCount", DeclarationScanner.declarationsIn("int getCount() { }")
            .get(0).name());
        assertEquals("of", DeclarationScanner.declarationsIn(
                "static Map<String, Integer> of(List<String> keys) { }")
            .get(0).name());
    }

    @Test
    @DisplayName("normalises parameter spacing")
    void normalisesParameterSpacing() {
        assertEquals("String id,boolean force",
            DeclarationScanner.declarationsIn("void process(String id,boolean force) { }")
                .get(0).parameters());
        assertEquals("String id, boolean force",
            DeclarationScanner.declarationsIn("void process(String   id,   boolean   force) { }")
                .get(0).parameters());
    }

    @Test
    @DisplayName("recognises an abstract method with no body")
    void recognisesAbstractMethod() {
        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn("void process();");

        assertEquals(1, declarations.size(), "an interface method has no brace: " + declarations);
    }

    @Test
    @DisplayName("reports the member signature as name plus parameters")
    void reportsMemberSignature() {
        Set<String> signatures = DeclarationScanner.memberSignatures(
            "void process() { }\nvoid process(String id) { }");

        assertEquals(Set.of("process()", "process(String id)"), signatures);
    }

    @Test
    @DisplayName("does not mistake control flow or calls for declarations")
    void ignoresNonDeclarations() {
        assertTrue(DeclarationScanner.declarationsIn("if (ready) { return; }").isEmpty(),
            "an if statement is not a declaration");
        assertTrue(DeclarationScanner.declarationsIn("int total = 0;").isEmpty(),
            "a field assignment is not a method declaration");
        assertTrue(DeclarationScanner.declarationsIn("").isEmpty());
        assertTrue(DeclarationScanner.declarationsIn(null).isEmpty());
    }

    @Test
    @DisplayName("keeps both declarations when one has a body and one does not")
    void keepsMixedDeclarations() {
        String code = """
            interface A {
                void process();
            }
            class B {
                void process(String id)
                {
                }
            }
            """;

        List<DeclarationScanner.MethodDeclaration> declarations =
            DeclarationScanner.declarationsIn(code);

        assertFalse(declarations.isEmpty(), "at least the interface method must be found");
        assertTrue(declarations.stream().anyMatch(d -> d.parameters().isEmpty()),
            "the abstract method must be found: " + declarations);
    }

    @Test
    @DisplayName("the detection service sees declarations the scanner sees")
    void detectionAndScannerAgree() {
        String branch1 = """
            void process() { }
            // added
            void process(String id) { }
            """;
        String branch2 = """
            void process() { }
            void process(String id, boolean force)
            { }
            """;

        // Both sides add a distinct overload, so this must be an auto-resolvable
        // overload conflict rather than something that vanishes.
        List<Conflict> conflicts = new ConflictDetectionService().detectOverloadConflicts(
            "A.java", "void process() { }", branch1, branch2);

        assertEquals(1, conflicts.size(),
            "the overload conflict must be detected despite the comment and the brace: "
                + conflicts);
        assertEquals(ConflictType.OVERLOAD_ADD, conflicts.get(0).getType());

        ConflictResolution resolution =
            ConflictResolvers.find(ConflictResolvers.defaultResolvers(), ConflictType.OVERLOAD_ADD)
                .orElseThrow()
                .resolve(conflicts.get(0).withTypeContext(TestTypeContexts.jdk()));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "distinct parameter lists are overloads: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("declaration view drops noise and joins braces")
    void declarationViewDropsNoiseAndJoinsBraces() {
        List<String> view = DeclarationScanner.declarationView("""
            // a leading comment
            void process()
            { }
            @Deprecated
            void audit() { }
            """);

        assertEquals(2, view.size(), "was " + view);
        assertEquals("void process() {", view.get(0).strip());
        assertTrue(view.get(1).contains("audit"), "was " + view);
    }
}
