package hr.hrg.hipster.ioc.tooling;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ClassRecord;
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
     * One file the generator wants to exist, and its whole content.
     *
     * <p>Produced by {@link #render} and written by {@link #write} — the split the plan's charter § 2.8 draws:
     * a generator's kind is what it reads and it returns; a pass's kind is what it writes.</p>
     */
    public record GeneratedFile(Path file, String source) {
    }

    /**
     * A run that has produced its output and written nothing: metadata in, code out.
     *
     * @param files        every implementation to write, in the order the contexts were read
     * @param graphJson    the dependency graph as JSON, for the caller to place
     * @param graphFile    where that graph belongs ({@code moduleRoot/.jcodebuddy/metadata/hipster-ioc/contexts.json})
     * @param contextsRead how many context interfaces were found
     * @param refused      how many contexts were declined, each with a diagnostic
     * @param divergences  every diagnostic the run produced, in DEC-022's format
     */
    public record Rendered(List<GeneratedFile> files, String graphJson, Path graphFile,
                           int contextsRead, int refused, List<String> divergences) {

        public Rendered {
            files = List.copyOf(files);
            divergences = List.copyOf(divergences);
        }
    }

    /**
     * Produce everything and write nothing — the project-scoped generator's half.
     *
     * <p>It is separate from {@link #write} because the two answer different questions: this one is "what should
     * exist", which a watch agent applies through an editor and a pass applies to disk, and the other is "make it
     * so". Keeping them apart is what lets the dev-time pass drive this generator (step 3.9) without either side
     * pretending the other's job is its own.</p>
     */
    public static Rendered render(Path sourceRoot, Path moduleRoot, String indent) throws IOException {
        return render(index(sourceRoot, moduleRoot, new ArrayList<>()), sourceRoot, moduleRoot, indent);
    }

    /**
     * Render against a model the caller already built — what the dev-time pass does (step 3.9).
     *
     * <p>The index is the pass's to produce: it is the one step that reads sources, and a generator that builds its
     * own model would be reading the tree again (DEC-036 § 11). So the pass hands the model over and this method
     * answers only the generation question.</p>
     */
    public static Rendered render(ClassIndex index, Path sourceRoot, Path moduleRoot, String indent)
            throws IOException {
        IocContextGenerator generator = new IocContextGenerator();
        List<String> divergences = new ArrayList<>();
        List<GeneratedFile> files = new ArrayList<>();
        List<Map<String, Object>> graph = new ArrayList<>();
        int contextsRead = 0;
        int refused = 0;

        for (ClassRecord contextRow : ContextReader.contextsIn(index)) {
            contextsRead++;
            IocContextGenerator.GeneratedContext generated =
                    generator.generate(contextRow, index, moduleRoot, indent);
            divergences.addAll(generated.divergences());
            if (generated.refused()) {
                refused++;
                continue;
            }
            files.add(new GeneratedFile(generated.implFile(), generated.source()));
            graph.add(graphEntry(contextRow, index));
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("generator", IocContextGenerator.NAME);
        document.put("contexts", graph);
        return new Rendered(files, new ObjectMapper().writeValueAsString(document) + "\n",
                moduleRoot.resolve(GRAPH_PATH), contextsRead, refused, divergences);
    }

    /** Write what {@link #render} produced, and say how much of it was new. The pass's half. */
    public static Result write(Rendered rendered) throws IOException {
        int filesWritten = 0;
        for (GeneratedFile file : rendered.files()) {
            if (writeIfChanged(file.file(), file.source())) {
                filesWritten++;
            }
        }
        Path graphFile = rendered.graphFile();
        Files.createDirectories(graphFile.getParent());
        Files.writeString(graphFile, rendered.graphJson(), StandardCharsets.UTF_8);
        return new Result(rendered.contextsRead(), filesWritten, rendered.refused(),
                rendered.divergences(), graphFile);
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
        return write(render(sourceRoot, moduleRoot, indent));
    }

    /**
     * The facts, once: every {@code .java} file under {@code sourceRoot} indexed by the engine.
     *
     * <p>This is the step that reads sources, and it is deliberately not the generator's: a generator that reads
     * the tree is what step 3.0e removes, while producing the model is what the metadata pass is for (DEC-036
     * § 11). A file that cannot be read is reported and skipped, exactly as the pass reports it (F-34).</p>
     *
     * <p>Public because the dev-time pass must build the model <em>before</em> it can hand one to a
     * {@code ProjectGenerator} (step 3.9): the pass produces the facts, the generator renders from them.</p>
     */
    public static ClassIndex index(Path sourceRoot, Path moduleRoot, List<String> divergences)
            throws IOException {
        ClassIndex index = ClassIndex.forPass(moduleRoot.resolve(hr.hrg.jcodebuddy.engine.JcodebuddyDirectory.DIR),
                moduleRoot, sourceRoot);
        for (Path file : javaFiles(sourceRoot)) {
            String relative = moduleRoot.relativize(file).toString().replace('\\', '/');
            String source = Files.readString(file, StandardCharsets.UTF_8);
            hr.hrg.jcodebuddy.engine.source.SourceReader.Read read =
                    hr.hrg.jcodebuddy.engine.source.SourceReader.readText(source);
            if (!read.readable() || read.unit() == null) {
                divergences.add("kind=source_not_parsed, location=" + relative
                        + ", cause=the file could not be parsed, current=unparseable, canonical=a readable Java"
                        + " file, action=fix the syntax error; its context was not generated");
                continue;
            }
            index.addTypes(relative, read.unit(), source, false);
        }
        // Written before anything reads it, and that order is not incidental: writing stamps each row' WITH FILE FACTS, and a relation's range is only sliceable when the row carries the checksum of the file it points into (DEC-040 D2). A caller that reads a context's parent type from an unwritten index gets the bare name and a reported reason instead.
        index.write();
        return index;
    }

    /**
     * What the graph says about one context, from the model: its beans, their declared types, and the order they
     * are created in. Deliberately not the generated source — a report describes the model, and the source is one
     * rendering of it.
     */
    private static Map<String, Object> graphEntry(ClassRecord contextRow, ClassIndex index) {
        Map<String, Object> entry = new LinkedHashMap<>();
        hr.hrg.hipster.entity.tooling.DivergenceReporter quiet =
                new hr.hrg.hipster.entity.tooling.DivergenceReporter();
        java.util.Optional<IocModel.Context> read = ContextReader.read(contextRow, index, quiet);
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
