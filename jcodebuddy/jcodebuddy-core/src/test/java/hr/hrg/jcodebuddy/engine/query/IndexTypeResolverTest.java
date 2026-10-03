package hr.hrg.jcodebuddy.engine.query;

import hr.hrg.jcodebuddy.engine.codegen.CodeContext;
import hr.hrg.jcodebuddy.engine.codegen.CodeContextImpl;
import hr.hrg.jcodebuddy.engine.codegen.CodeGenerator;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.MemberParameter;
import hr.hrg.jcodebuddy.engine.index.MemberRecord;
import hr.hrg.jcodebuddy.engine.index.TypeFacts;
import hr.hrg.jcodebuddy.engine.index.TypeRelation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * The engine's resolver, and the property plan step 3.0d is gated on: <strong>a generator runs against the
 * model without being handed a file at all</strong>.
 *
 * <p>The fixture is deliberately built from facts rather than from source. No {@code .java} file is written
 * anywhere, and the directory the {@link CodeContext} names is never created — so a generator that quietly read
 * the file it was pointed at would fail here rather than pass by accident. What it may use is the context's
 * {@link TypeResolver}, and the assertions below are about exactly that: the answer comes from the index, an
 * unknown name answers {@code null} instead of an empty shape, and a package a generator might write into is a
 * package the index actually contains.</p>
 */
class IndexTypeResolverTest {

    /** One row per type, built by hand — the index's own contract, without a parse (step 3.0r). */
    private static ClassIndex indexOf(Path tree) {
        ClassIndex index = ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        index.addTypes("a/b/Person.java", List.of(new TypeFacts("a.b.Person", "record",
                List.of("public"), null, 3, 0,
                List.of(TypeRelation.implementsType("java.io.Serializable")), List.of(),
                List.of(MemberRecord.field("id", "long", List.of("private", "final")),
                        MemberRecord.field("name", "String", List.of("private", "final")),
                        new MemberRecord("Person", MemberRecord.Kind.CONSTRUCTOR, "",
                                List.of(MemberParameter.of("long", "id"), MemberParameter.of("String", "name")),
                                List.of("public"), List.of())))), false);
        index.addTypes("a/b/Outer.java", List.of(new TypeFacts("a.b.Outer", "class", List.of("public"), null,
                3, 0, List.of(), List.of(), List.of(new MemberRecord("Inner", MemberRecord.Kind.NESTED,
                        "Inner", List.of(), List.of("static"), List.of())))), false);
        index.addTypes("a/b/Outer.java#Inner", List.of(new TypeFacts("a.b.Outer.Inner", "class",
                List.of("public", "static"), "a.b.Outer", 5, 1, List.of(), List.of(),
                List.of(MemberRecord.field("label", "String", List.of("private"))))), false);
        return index;
    }

    /**
     * A generator that emits a copy constructor for one type, using nothing but its context.
     *
     * <p>It is the smallest generator that could not exist before this step: it needs the type's fields and
     * their names, and it has no file to read them from. In the second test it is the caller that must fail
     * loudly — the generator refuses to emit a shape for a type it cannot resolve, because the alternative
     * (an empty constructor) compiles and is wrong.</p>
     */
    private static final class CopyConstructorGenerator implements CodeGenerator<String> {

        private final String targetType;

        CopyConstructorGenerator(String targetType) {
            this.targetType = targetType;
        }

        @Override
        public String name() {
            return "copy-constructor";
        }

        @Override
        public boolean isApplicable(CodeContext context) {
            return context.getTypeResolver().resolve(targetType) != null;
        }

        @Override
        public String generate(CodeContext context) {
            TypeDefinition definition = context.getTypeResolver().resolve(targetType);
            if (definition == null) {
                throw new IllegalStateException("asked to generate for " + targetType
                        + ", which the model does not resolve — refusing to invent a definition");
            }
            StringBuilder out = new StringBuilder();
            out.append("public ").append(definition.simpleName()).append('(')
                    .append(definition.simpleName()).append(" other) {\n");
            for (String field : definition.fields()) {
                out.append(context.getIndent()).append("this.").append(field).append(" = other.")
                        .append(field).append(";\n");
            }
            return out.append("}\n").toString();
        }
    }

    @Test
    void aGeneratorRunsAgainstTheModelWithoutBeingHandedAFile(@TempDir Path tree) {
        IndexTypeResolver resolver = IndexTypeResolver.over(MetadataQuery.over(List.of(indexOf(tree))));

        // The directory does not exist and no source file was ever written: only the model is on offer.
        Path nowhere = tree.resolve("nowhere");
        Assertions.assertFalse(java.nio.file.Files.exists(nowhere),
                "the fixture must not put a file on disk, or a generator reading one would still pass");
        CodeContext context = new CodeContextImpl(nowhere, nowhere.resolve("Person.java"), 0, "    ",
                resolver, null);

        CodeGenerator<String> generator = new CopyConstructorGenerator("a.b.Person");
        Assertions.assertTrue(generator.isApplicable(context),
                "a cheap predicate answered by the model, which is what lets a tool offer a generator to every"
                        + " file without parsing one");
        Assertions.assertEquals("public Person(Person other) {\n"
                        + "    this.id = other.id;\n"
                        + "    this.name = other.name;\n"
                        + "}\n",
                generator.generate(context),
                "the fields and their order come from the index alone (step 3.0r), and the generator never"
                        + " opened the file it was pointed at");
    }

    @Test
    void anUnknownTypeAnswersNullAndTheGeneratorRefusesToEmit(@TempDir Path tree) {
        IndexTypeResolver resolver = IndexTypeResolver.over(MetadataQuery.over(List.of(indexOf(tree))));

        Assertions.assertNull(resolver.resolve("a.b.Ghost"),
                "a name this index does not carry is a question with no answer, never an empty definition");
        Assertions.assertNotNull(resolver.resolve("a.b.Person"), "and a carried name still answers");

        CodeContext context = new CodeContextImpl(tree, tree.resolve("Ghost.java"), 0, "    ", resolver, null);
        CodeGenerator<String> generator = new CopyConstructorGenerator("a.b.Ghost");
        Assertions.assertFalse(generator.isApplicable(context), "the predicate says no rather than guessing");
        IllegalStateException refused = Assertions.assertThrows(IllegalStateException.class,
                () -> generator.generate(context),
                "and a generator asked anyway fails loudly instead of emitting a shape with no fields");
        Assertions.assertTrue(refused.getMessage().contains("a.b.Ghost"), refused.getMessage());
    }

    @Test
    void aDefinitionCarriesTheKindAndTheRelationsTheIndexRecorded(@TempDir Path tree) {
        IndexTypeResolver resolver = IndexTypeResolver.over(MetadataQuery.over(List.of(indexOf(tree))));

        TypeDefinition person = resolver.resolve("a.b.Person");
        Assertions.assertEquals("record", person.kind(),
                "a generator that cannot tell a record from a class has to read the file to find out");
        Assertions.assertEquals(List.of("java.io.Serializable"),
                person.relations().stream().map(TypeRelation::name).toList(),
                "and what a type implements is a model fact too — the two fields step 3.0d added to this seam");
        Assertions.assertEquals(List.of("id", "name"), person.fields(), "fields are in declaration order");
        Assertions.assertEquals("long", person.fieldTypes().get("id"),
                "and each field carries the type the source wrote — unresolved, like every name in this model");
        Assertions.assertEquals("Person", person.simpleName());
    }

    @Test
    void knownPackagesArePackagesAndAMemberTypesPackageIsItsEnclosings(@TempDir Path tree) {
        IndexTypeResolver resolver = IndexTypeResolver.over(MetadataQuery.over(List.of(indexOf(tree))));

        Assertions.assertEquals(List.of("a.b"), resolver.knownPackages(),
                "one package, and not `a.b.Outer` — a member type's package is its enclosing type's package,"
                        + " because a generator that wrote into a.b.Outer would be writing into a package that"
                        + " does not exist");
    }

    @Test
    void anEmptyIndexResolvesNothingAndKnowsNoPackages(@TempDir Path tree) {
        ClassIndex empty = ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        TypeResolver resolver = IndexTypeResolver.over(MetadataQuery.over(List.of(empty)));

        Assertions.assertNull(resolver.resolve("a.b.Anything"));
        Assertions.assertTrue(resolver.knownPackages().isEmpty(),
                "an empty index answers nothing rather than everything — the same distinction the query surface"
                        + " draws between 'not declared' and 'declares none'");
    }
}
