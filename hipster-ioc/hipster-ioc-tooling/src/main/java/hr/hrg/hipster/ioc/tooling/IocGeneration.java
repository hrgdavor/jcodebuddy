package hr.hrg.hipster.ioc.tooling;

import hr.hrg.jcodebuddy.engine.codegen.CodeContextImpl;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the context generator over a source tree: read every context, write every implementation, and write
 * the dependency graph (DEC-036 §§ 10–11).
 *
 * <p>This is the piece a command and a watch pass share, and the only place that touches the filesystem.
 * The generator itself returns text ({@link IocContextGenerator}), which is what keeps it testable without a
 * tree; this class decides that a text becomes a file, and it writes only when the text differs from what is
 * already there — regenerating an unchanged file would rewrite identical bytes and produce a diff-free but
 * touched file, which is noise in every tool that watches mtimes (the containment is the point of a
 * dev-time pass).</p>
 *
 * <p>The graph is JSON under {@code <root>/.jcodebuddy/metadata/hipster-ioc/contexts.json}: derived,
 * ignorable output in the location DEC-026 reserves for it, and the model a report renders from — never HTML
 * produced by a generator (DEC-027).</p>
 */
public final class IocGeneration {

    /** Where the graph goes, relative to the root it describes. */
    public static final String GRAPH_PATH = ".jcodebuddy/metadata/hipster-ioc/contexts.json";

    private IocGeneration() {
    }

    /**
     * @param contextsRead how many context interfaces were found
     * @param filesWritten how many implementation files were written (an unchanged file is not one)
     * @param refused      how many contexts were declined, each with a diagnostic
     * @param divergences  every diagnostic the run produced, in DEC-022's format
     * @param graphFile    the graph that was written
     */
    public record Result(int contextsRead, int filesWritten, int refused, List<String> divergences,
                         Path graphFile) {

        public Result {
            divergences = List.copyOf(divergences);
        }
    }

    /**
     * @param sourceRoot the tree to read and to write implementations into
     * @param moduleRoot the module the graph describes; its {@code .jcodebuddy/} is where the graph goes,
     *                   which is why this is not derived from {@code sourceRoot} — a Maven module's source
     *                   root is {@code src/main/java}, and a {@code .jcodebuddy/} inside it would be a
     *                   derived directory committed as source (DEC-026)
     * @param indent     one indentation step for the generated code
     */
    public static Result generate(Path sourceRoot, Path moduleRoot, String indent) throws IOException {
        IocContextGenerator generator = new IocContextGenerator();
        List<String> divergences = new ArrayList<>();
        List<Map<String, Object>> graph = new ArrayList<>();
        int contextsRead = 0;
        int filesWritten = 0;
        int refused = 0;

        for (Path file : javaFiles(sourceRoot)) {
            if (!generator.isApplicable(new CodeContextImpl(sourceRoot, file, 0, indent))) {
                continue;
            }
            contextsRead++;
            IocContextGenerator.GeneratedContext generated =
                    generator.generate(new CodeContextImpl(sourceRoot, file, 0, indent));
            divergences.addAll(generated.divergences());
            if (generated.refused()) {
                refused++;
                continue;
            }
            if (writeIfChanged(generated.implFile(), generated.source())) {
                filesWritten++;
            }
            graph.add(graphEntry(file, indent));
        }

        Path graphFile = moduleRoot.resolve(GRAPH_PATH);
        Files.createDirectories(graphFile.getParent());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("generator", IocContextGenerator.NAME);
        document.put("contexts", graph);
        Files.writeString(graphFile, new ObjectMapper().writeValueAsString(document) + "\n",
                StandardCharsets.UTF_8);

        return new Result(contextsRead, filesWritten, refused, divergences, graphFile);
    }

    /**
     * What the graph says about one context: its beans, their declared types, and the order they are created
     * in. Deliberately not the generated source — a report describes the model, and the source is one
     * rendering of it.
     */
    private static Map<String, Object> graphEntry(Path file, String indent) {
        Map<String, Object> entry = new LinkedHashMap<>();
        try {
            hr.hrg.hipster.entity.tooling.DivergenceReporter quiet =
                    new hr.hrg.hipster.entity.tooling.DivergenceReporter();
            java.util.Optional<IocModel.Context> read = ContextReader.read(file, quiet);
            if (read.isEmpty()) {
                return entry;
            }
            IocModel.Context model = read.get();
            entry.put("context", model.qualifiedName());
            entry.put("implementation", model.qualifiedName().replace(model.simpleName(),
                    model.implSimpleName()));
            entry.put("dependencies", model.dependencyTypes());
            if (model.hasParent()) {
                entry.put("parent", model.parentType());
            }
            List<Map<String, Object>> beans = new ArrayList<>();
            for (IocModel.Bean bean : model.beans()) {
                Map<String, Object> beanEntry = new LinkedHashMap<>();
                beanEntry.put("name", bean.name());
                beanEntry.put("type", bean.typeText());
                IocModel.Factory factory = model.factories().get(bean.name());
                beanEntry.put("createdBy", factory == null ? "new " + bean.typeText() + "()" : factory.methodName());
                beans.add(beanEntry);
            }
            entry.put("beans", beans);
        } catch (IOException e) {
            entry.put("error", "unreadable: " + e.getMessage());
        }
        return entry;
    }

    /** Sorted, so two runs over the same tree produce the same graph byte for byte. */
    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains(".jcodebuddy"))
                    .sorted()
                    .toList();
        }
    }

    /** @return whether the file was written, i.e. whether the text differed from what was there */
    private static boolean writeIfChanged(Path file, String text) throws IOException {
        if (file == null) {
            return false;
        }
        if (Files.exists(file) && Files.readString(file).equals(text)) {
            return false;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return true;
    }
}
