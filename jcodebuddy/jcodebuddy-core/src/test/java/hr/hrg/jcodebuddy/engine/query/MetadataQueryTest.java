package hr.hrg.jcodebuddy.engine.query;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.TypeFacts;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * The query surface (plan step 3.0h). Two things are being pinned here, and neither is a feature:
 *
 * <ul>
 *   <li><strong>A per-module index cannot answer a question that crosses a module boundary.</strong> The
 *       fixture is two modules, with the relation between them, so every relation query here is one a single
 *       {@link ClassIndex} would fail.</li>
 *   <li><strong>"Cannot answer" must stay distinguishable from "there are none".</strong> A declared type with
 *       no implementors is a fact; a name declared nowhere is a question without an answer — and the engine says
 *       which is which, because collapsing them is what produces confident wrong answers.</li>
 * </ul>
 */
class MetadataQueryTest {

    /** Module {@code web} declares the interface; module {@code app} implements it, by simple name. */
    private static MetadataQuery twoModules(Path tree) {
        ClassIndex web = ClassIndex.forPass(tree.resolve("web-report"), tree.resolve("web"),
                tree.resolve("web/src/main/java"));
        web.addTypes("a/b/CtxModule.java", List.of(new TypeFacts("a.b.CtxModule", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of())), false);
        web.addTypes("a/b/Unused.java", List.of(new TypeFacts("a.b.Unused", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of())), false);

        ClassIndex app = ClassIndex.forPass(tree.resolve("app-report"), tree.resolve("app"),
                tree.resolve("app/src/main/java"));
        // Written the way developers write it: a simple name, in another module.
        app.addTypes("c/d/AppModule.java", List.of(new TypeFacts("c.d.AppModule", "class",
                List.of("public"), null, 3, 0, List.of(TypeRelation.implementsType("CtxModule"),
                        TypeRelation.implementsType("java.io.Serializable")),
                // The annotation type lives nowhere in these modules, which is the normal case for one from a
                // dependency or the JDK: the query must still answer it by name.
                List.of(new hr.hrg.jcodebuddy.engine.index.TypeAnnotation("Component",
                        List.of("name = \"app\""))),
                // Step 3.0r's member field, on the same row: the query answers "what does this type declare"
                // from the index alone (the members test asserts the extraction).
                List.of(MemberRecord.field("ctx", "CtxModule", List.of("private", "final")),
                        MemberRecord.field("count", "int", List.of("private")),
                        new MemberRecord("AppModule", MemberRecord.Kind.CONSTRUCTOR, "", List.of(),
                                List.of("public"), List.of())))), false);
        return MetadataQuery.over(List.of(web, app));
    }

    @Test
    void oneQuestionIsAnsweredAcrossModuleBoundaries(@TempDir Path tree) {
        MetadataQuery query = twoModules(tree);

        Assertions.assertEquals(List.of("c.d.AppModule"),
                query.implementorsOf("a.b.CtxModule").related().stream().map(ClassRecord::fqn).toList(),
                "the implementor lives in another module and wrote the simple name; a per-module index answers"
                        + " nothing here, and 3.0b recorded exactly that as 3.0h's first item");
        Assertions.assertTrue(query.answer("c.d.AppModule").isFound(), "and each module's rows are searchable");
        Assertions.assertTrue(query.coverage().contains("web") && query.coverage().contains("app"),
                "the answer says which modules it covers: " + query.coverage());
    }

    @Test
    void aDeclaredTypeWithNoImplementorsIsAFactAndAnUnknownNameIsNot(@TempDir Path tree) {
        MetadataQuery query = twoModules(tree);

        MetadataQuery.RelationAnswer none = query.implementorsOf("a.b.Unused");
        Assertions.assertTrue(none.isAnswered(), "the name is declared, so this question has an answer");
        Assertions.assertTrue(none.related().isEmpty(), "and the answer is: nobody implements it");

        MetadataQuery.RelationAnswer unknown = query.implementorsOf("a.b.Ghost");
        Assertions.assertFalse(unknown.isAnswered(), "the name is declared nowhere in these modules");
        Assertions.assertTrue(unknown.describe().contains("cannot answer"),
                "so the answer says it cannot answer rather than 'none': " + unknown.describe());
        Assertions.assertTrue(unknown.subject().describe().contains("not declared in these modules"),
                "and the cause names the scope, so a JDK type is not read as a typo: "
                        + unknown.subject().describe());
    }

    @Test
    void aRelationToATypeOutsideTheModulesIsReportedAsUnresolved(@TempDir Path tree) {
        MetadataQuery query = twoModules(tree);

        MetadataQuery.RelationAnswer answer = query.supertypesOf("c.d.AppModule");

        Assertions.assertTrue(answer.isAnswered());
        Assertions.assertEquals(List.of("a.b.CtxModule"),
                answer.related().stream().map(ClassRecord::fqn).toList(),
                "the resolvable supertype is resolved, even from another module and from a simple name");
        Assertions.assertEquals(List.of("java.io.Serializable"), answer.unresolvedNames(),
                "and the one that names nothing in these modules is reported, not dropped: an answer with"
                        + " invisible gaps is worse than no answer");
        Assertions.assertTrue(answer.describe().contains("1 relation name(s) unresolved"), answer.describe());
    }

    @Test
    void anAmbiguousSimpleNameIsRefusedRatherThanGuessed(@TempDir Path tree) {
        ClassIndex one = ClassIndex.forPass(tree.resolve("r1"), tree.resolve("m1"), tree.resolve("m1/src"));
        one.addTypes("a/b/Person.java", List.of(new TypeFacts("a.b.Person", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of())), false);
        ClassIndex two = ClassIndex.forPass(tree.resolve("r2"), tree.resolve("m2"), tree.resolve("m2/src"));
        two.addTypes("c/d/Person.java", List.of(new TypeFacts("c.d.Person", "interface",
                List.of("public", "abstract"), null, 3, 0, List.of())), false);
        // In package c.d, so the declaring package settles it exactly — no ambiguity to resolve.
        two.addTypes("c/d/Employee.java", List.of(new TypeFacts("c.d.Employee", "class",
                List.of("public"), null, 3, 0, List.of(TypeRelation.implementsType("Person")))), false);
        // In a package where neither Person lives: now only the simple-name index can answer, and it has two.
        two.addTypes("e/f/Orphan.java", List.of(new TypeFacts("e.f.Orphan", "class",
                List.of("public"), null, 3, 0, List.of(TypeRelation.implementsType("Person")))), false);
        two.addTypes("g/h/Exact.java", List.of(new TypeFacts("g.h.Exact", "class",
                List.of("public"), null, 3, 0, List.of(TypeRelation.implementsType("c.d.Person")))), false);

        MetadataQuery query = MetadataQuery.over(List.of(one, two));

        Assertions.assertEquals(List.of("c.d.Employee", "g.h.Exact"),
                query.implementorsOf("c.d.Person").related().stream().map(ClassRecord::fqn).toList(),
                "the declaring package resolves 'Person' exactly, and an FQN needs no resolution at all");
        Assertions.assertTrue(query.implementorsOf("a.b.Person").related().isEmpty(),
                "Orphan's 'Person' cannot be attributed: two types are called Person and neither is in e.f");
        Assertions.assertEquals(List.of("Person"), query.implementorsOf("a.b.Person").unresolvedNames(),
                "it is reported instead — picking one would be the confident wrong answer this engine keeps"
                        + " having to unlearn");
    }

    @Test
    void kindModifierPathAndPackageQueriesWorkOverTheWholeProject(@TempDir Path tree) {
        MetadataQuery query = twoModules(tree);

        Assertions.assertEquals(List.of("a.b.CtxModule", "a.b.Unused"),
                query.byKind("interface").stream().map(ClassRecord::fqn).sorted().toList());
        Assertions.assertEquals(List.of("c.d.AppModule"),
                query.byModifier("public").stream().map(ClassRecord::fqn)
                        .filter(fqn -> fqn.startsWith("c.")).toList(),
                "a modifier query spans the modules searched");
        Assertions.assertEquals(List.of("a.b.CtxModule"),
                query.byPath("a/b/CtxModule.java").stream().map(ClassRecord::fqn).toList());
        Assertions.assertEquals(List.of("a.b.CtxModule", "a.b.Unused"),
                query.inPackage("a.b").stream().map(ClassRecord::fqn).sorted().toList());
    }

    @Test
    void annotationsAndMembersAreAnsweredFromTheIndex(@TempDir Path tree) {
        MetadataQuery query = twoModules(tree);

        MetadataQuery.AnnotatedAnswer annotated = query.annotatedWith("Component");
        Assertions.assertEquals(List.of("c.d.AppModule"),
                annotated.annotated().stream().map(ClassRecord::fqn).toList(),
                "the annotation is recorded as written and found by name, without parsing a file");
        Assertions.assertTrue(annotated.isAnswered(), "and the question is answerable by name");
        Assertions.assertFalse(annotated.annotation().isFound(),
                "even though the annotation type itself is not declared in these modules — a dependency"
                        + " annotation is the normal case and must not be a refusal");

        MetadataQuery.AnnotationAnswer onOneType = query.annotationsOf("c.d.AppModule");
        Assertions.assertTrue(onOneType.isAnswered());
        Assertions.assertEquals(List.of(new hr.hrg.jcodebuddy.engine.index.TypeAnnotation("Component",
                        List.of("name = \"app\""))),
                onOneType.annotations(), "and a caller can read the arguments a consumer will project from");

        // Step 3.0r replaced the "not covered" answer that used to be here. The gap cannot reopen silently now:
        // there is no such vocabulary left, and this asserts the members are answered from the model.
        MetadataQuery.MemberAnswer members = query.membersOf("c.d.AppModule");
        Assertions.assertTrue(members.isAnswered(), "the type is declared, so its members are answerable");
        Assertions.assertEquals(List.of("ctx", "count", "AppModule"),
                members.members().stream().map(MemberRecord::signature).toList(),
                "fields and the constructor, in source order, from the index alone: " + members.describe());
        Assertions.assertEquals(List.of("CtxModule", "int"),
                query.membersOf("c.d.AppModule", MemberRecord.Kind.FIELD).members().stream()
                        .map(MemberRecord::type).toList(),
                "and each field's type is the spelling the source used, which is what a generator given a"
                        + " TypeDefinition needs (step 3.0d)");

        MetadataQuery.MemberAnswer unknown = query.membersOf("c.d.Ghost");
        Assertions.assertFalse(unknown.isAnswered(),
                "a type declared nowhere is still a question without an answer rather than an empty member list");
        Assertions.assertTrue(unknown.describe().contains("cannot answer"), unknown.describe());
    }
}
