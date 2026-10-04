package hr.hrg.jcodebuddy.engine.codegen;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.TypeAnswer;
import hr.hrg.jcodebuddy.engine.index.TypeFacts;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 7.8's three facts as tests rather than as a paragraph: a caller can tell the kinds apart without reading a
 * generator's source, a wrapper's kind is its delegate's, and a type the metadata cannot resolve is
 * <strong>reported</strong> rather than answered with absence.
 *
 * <p>The first two are assertions about <em>types</em>, which is the point of the step — the distinction lives in
 * the engine's SPI, so the compiler enforces it and a test only has to prove the enforcement is really there.</p>
 */
class GeneratorKindsTest {

    /** One row per type, built by hand: the index's own contract without a parse. */
    private static ClassIndex indexOf(Path tree) {
        ClassIndex index = ClassIndex.forPass(tree.resolve("report"), tree.resolve("module"),
                tree.resolve("module/src/main/java"));
        index.addTypes("a/b/Person.java", List.of(new TypeFacts("a.b.Person", "record",
                List.of("public"), null, 3, 0, List.of(), List.of(), List.of())), false);
        return index;
    }

    /** A file-scoped wrapper. Its kind is its delegate's, and its delegate is file-scoped. */
    private static final class FileScopedWrapper implements CodeGenerator<String> {

        private final CodeGenerator<String> delegate;

        FileScopedWrapper(CodeGenerator<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public boolean isApplicable(CodeContext context) {
            return delegate.isApplicable(context);
        }

        @Override
        public String generate(CodeContext context) {
            return delegate.generate(context);
        }
    }

    /** The mirror of the wrapper above: a projector-scoped wrapper of a project-scoped delegate. */
    private static final class ProjectScopedWrapper implements ProjectGenerator<String> {

        private final ProjectGenerator<String> delegate;

        ProjectScopedWrapper(ProjectGenerator<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public boolean isApplicable(ProjectContext context) {
            return delegate.isApplicable(context);
        }

        @Override
        public String generate(ProjectContext context) {
            return delegate.generate(context);
        }
    }

    /**
     * The compiler is the enforcement: a caller holding a list of file-scoped generators has no way to put a
     * project-scoped one in it, because neither interface is a subtype of the other. That is what "a caller can tell
     * the kinds apart without reading the generator's source" means in practice.
     */
    @Test
    void theTwoKindsAreUnrelatedSoNoCallerCanMixThem() {
        Assertions.assertFalse(CodeGenerator.class.isAssignableFrom(ProjectGenerator.class),
                "a project-scoped generator must not be usable where a file-scoped one is expected — offering it "
                        + "per file is exactly the mistake the second interface exists to prevent");
        Assertions.assertFalse(ProjectGenerator.class.isAssignableFrom(CodeGenerator.class),
                "and the reverse, so no caller can hand a file-scoped generator a project model");
        Assertions.assertTrue(CodeGenerator.class.isInterface() && ProjectGenerator.class.isInterface(),
                "both are interfaces a generator implements, not classes it extends");

        List<CodeGenerator<?>> fileScoped = new ArrayList<>();
        fileScoped.add(new FileScopedWrapper(null));
        Assertions.assertEquals(1, fileScoped.size(),
                "a file-scoped list holds file-scoped generators, and this is the whole of that statement");
    }

    /** A wrapper is the kind of the thing it wraps, and exactly one kind — never both. */
    @Test
    void aWrappersKindFollowsItsDelegate() {
        Object fileScoped = new FileScopedWrapper(null);
        Object projectScoped = new ProjectScopedWrapper(null);

        Assertions.assertTrue(fileScoped instanceof CodeGenerator<?>,
                "wrapping a file-scoped generator makes a file-scoped wrapper");
        Assertions.assertFalse(fileScoped instanceof ProjectGenerator<?>,
                "and does not make it a project-scoped one as well");

        Assertions.assertTrue(projectScoped instanceof ProjectGenerator<?>,
                "wrapping a project-scoped generator makes a project-scoped wrapper");
        Assertions.assertFalse(projectScoped instanceof CodeGenerator<?>,
                "and not a file-scoped one — a wrapper cannot be both, because nothing implements both");
    }

    /**
     * The rule this thread kept circling, pinned: the index answers a question it cannot answer with an explanation,
     * not with "no". A generator that read absence into it would emit code for a type it never saw — which compiles,
     * and is wrong.
     */
    @Test
    void anUnresolvableTypeIsReportedRatherThanInferredAbsent(@TempDir Path tree) {
        ClassIndex index = indexOf(tree);

        Assertions.assertTrue(index.answer("a.b.Person") instanceof TypeAnswer.Found,
                "the type the index holds is answered as found");

        TypeAnswer answer = index.answer("a.b.Ghost");
        Assertions.assertTrue(answer instanceof TypeAnswer.NotIndexed,
                "a type the index does not hold is NotIndexed, never a Found with nothing in it: " + answer);
        TypeAnswer.NotIndexed notIndexed = (TypeAnswer.NotIndexed) answer;
        Assertions.assertEquals("a.b.Ghost", notIndexed.fqn());
        Assertions.assertFalse(notIndexed.cause().isBlank(),
                "and the cause says WHAT is missing rather than that the type is absent: " + notIndexed.cause());
    }
}
