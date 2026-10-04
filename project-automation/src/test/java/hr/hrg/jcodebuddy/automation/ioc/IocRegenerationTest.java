package hr.hrg.jcodebuddy.automation.ioc;

import hr.hrg.jcodebuddy.engine.codegen.ProjectContext;
import hr.hrg.jcodebuddy.engine.codegen.ProjectGenerator;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.query.MetadataQuery;
import hr.hrg.hipster.entity.tooling.DivergenceReporter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The dev-time pass and the generator seam (plan step 3.9, part one).
 *
 * <p>Three facts, and the third is the one the plan named as the hazard: the pass produces the model, the generator
 * renders from it and writes nothing, and a second pass over an unchanged tree <strong>writes nothing at all</strong>
 * — which is what makes running it on every save in watch mode safe rather than noisy.</p>
 */
class IocRegenerationTest {

    /**
     * A minimal but real context: a bean declared on the context, its factory on the module interface.
     *
     * <p>Deliberately the same shape {@code IocContextGeneratorTest} uses, because a fixture that is easier than the
     * real thing tests a generator nobody has.</p>
     */
    private static Path tree(Path dir) throws Exception {
        Path root = dir.resolve("src");
        Path pkg = Files.createDirectories(root.resolve("ioc/fixture"));
        Files.writeString(pkg.resolve("AppModule.java"), """
                package ioc.fixture;

                interface AppModule {
                    default Greeting buildGreeting() {
                        return new Greeting("hello");
                    }
                }
                """);
        Files.writeString(pkg.resolve("Greeting.java"), """
                package ioc.fixture;

                class Greeting {
                    private final String text;

                    Greeting(String text) {
                        this.text = text;
                    }

                    String text() {
                        return text;
                    }
                }
                """);
        Files.writeString(pkg.resolve("AppContext.java"), """
                package ioc.fixture;

                import hr.hrg.hipster.ioc.HipsterContext;

                @HipsterContext
                public interface AppContext extends AppModule {
                    Greeting greeting();
                }
                """);
        return root;
    }

    @Test
    void thePassBuildsTheModelGeneratesAndWritesTheGraph(@TempDir Path dir) throws Exception {
        Path sourceRoot = tree(dir);
        Path moduleRoot = dir;

        IocRegeneration.Pass pass = IocRegeneration.of(sourceRoot, moduleRoot);

        Assertions.assertEquals(1, pass.contextsRead(), "one context in the model: " + pass.divergences());
        Assertions.assertEquals(1, pass.filesWritten(), "and one implementation written: " + pass.divergences());
        Assertions.assertEquals(0, pass.refused(), pass.divergences().toString());
        Assertions.assertTrue(pass.wrote());
        Assertions.assertTrue(Files.exists(sourceRoot.resolve("ioc/fixture/AppContextImpl.java")),
                "the implementation lands in the source tree, which is where committed generated Java lives");
        Assertions.assertTrue(pass.graphFile().endsWith(IocRegenerationGraphPath()),
                "and the graph lands in the module's .jcodebuddy/, not in the sources (DEC-026): " + pass.graphFile());
        Assertions.assertTrue(pass.graphFile().startsWith(moduleRoot),
                "under the module root it was handed: " + pass.graphFile());
    }

    /** The watch hazard the plan named: a pass over an unchanged tree must be a no-op, or watch mode never settles. */
    @Test
    void aSecondPassOverAnUnchangedTreeWritesNothing(@TempDir Path dir) throws Exception {
        Path sourceRoot = tree(dir);
        Path moduleRoot = dir;

        IocRegeneration.Pass first = IocRegeneration.of(sourceRoot, moduleRoot);
        Assertions.assertEquals(1, first.filesWritten());

        IocRegeneration.Pass second = IocRegeneration.of(sourceRoot, moduleRoot);

        Assertions.assertEquals(1, second.contextsRead(), "the model still holds the context");
        Assertions.assertEquals(0, second.filesWritten(),
                "but nothing is written: generation is idempotent, which is what makes a pass on every save safe");
        Assertions.assertFalse(second.wrote());
    }

    /** The generator is the engine's project-scoped kind, and it decides applicability from the model, not the tree. */
    @Test
    void theGeneratorIsProjectScopedAndDecidesFromTheModel(@TempDir Path dir) throws Exception {
        Path sourceRoot = tree(dir);
        Path moduleRoot = dir;

        List<ProjectGenerator<?>> generators = new IocRegeneration().generators();
        Assertions.assertEquals(1, generators.size());
        ProjectGenerator<?> generator = generators.get(0);

        ClassIndex index = hr.hrg.hipster.ioc.tooling.IocGeneration.index(sourceRoot, moduleRoot,
                new DivergenceReporter().entries());
        ProjectContext context = ProjectContext.of(sourceRoot, moduleRoot, index, MetadataQuery.over(index),
                new DivergenceReporter());

        Assertions.assertTrue(generator.isApplicable(context),
                "a model holding a context is what this generator applies to");

        Path empty = Files.createDirectories(dir.resolve("empty"));
        ClassIndex emptyIndex = ClassIndex.forPass(moduleRoot.resolve(".jcodebuddy"), moduleRoot, empty);
        ProjectContext emptyContext = ProjectContext.of(empty, moduleRoot, emptyIndex,
                MetadataQuery.over(emptyIndex), new DivergenceReporter());
        Assertions.assertFalse(generator.isApplicable(emptyContext),
                "and an empty model is not — the predicate is asked of the model the pass already built");
        Assertions.assertFalse(generator instanceof hr.hrg.jcodebuddy.engine.codegen.CodeGenerator<?>,
                "it is not, and cannot be, the file-scoped kind (step 7.8)");
    }

    private static Path IocRegenerationGraphPath() {
        return Path.of(".jcodebuddy/metadata/hipster-ioc/contexts.json");
    }
}
