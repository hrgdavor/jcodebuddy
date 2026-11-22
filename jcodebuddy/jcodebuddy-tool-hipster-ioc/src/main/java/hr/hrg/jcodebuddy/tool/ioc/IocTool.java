package hr.hrg.jcodebuddy.tool.ioc;

import hr.hrg.hipster.ioc.tooling.IocGeneration;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * hipster-ioc as a tool. It calls the generator's <b>render</b> half, which returns generated context
 * implementations and the graph without writing anything. The agent reviews/applies those files through
 * the coordinator.
 *
 * <p>The project is located from the requested file: the nearest {@code pom.xml} is the module root, and
 * {@code src/main/java} is its source root. A request may also pass {@code moduleRoot} explicitly.</p>
 */
public final class IocTool implements Tool {

    public static final String ID = "hipster-ioc";
    private final String indent;

    public IocTool() {
        this("    ");
    }

    public IocTool(String indent) {
        this.indent = indent == null || indent.isEmpty() ? "    " : indent;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "hipster-ioc contexts";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        Path moduleRoot = moduleRoot(request);
        Path sourceRoot = request.string("sourceRoot", null) == null
                ? moduleRoot.resolve("src").resolve("main").resolve("java")
                : moduleRoot.resolve(request.string("sourceRoot", "src/main/java"));
        try {
            IocGeneration.Rendered rendered = IocGeneration.render(sourceRoot, moduleRoot, indent);
            List<FileProposal> files = new ArrayList<>();
            for (IocGeneration.GeneratedFile generated : rendered.files()) {
                addProposal(files, generated.file(), generated.source());
            }
            addProposal(files, rendered.graphFile(), rendered.graphJson());
            return new ToolProposal(ID, title(), version(), files,
                    Map.of("contextsRead", rendered.contextsRead(), "refused", rendered.refused(),
                            "divergences", rendered.divergences()));
        } catch (IOException failed) {
            throw new ToolRefusal("hipster-ioc render failed: " + failed.getMessage(), failed);
        }
    }

    private void addProposal(List<FileProposal> files, Path path, String after) throws IOException {
        String before = Files.isRegularFile(path) ? Files.readString(path) : "";
        if (before.equals(after)) return;
        Path absolute = path.toAbsolutePath().normalize();
        String key = WriteCoordinator.hash(ID + ":" + absolute + ":" + WriteCoordinator.hash(before));
        files.add(FileProposal.change(absolute, WriteCoordinator.hash(before), before, after, key)
                .owned("hipster-ioc-tooling"));
    }

    private static Path moduleRoot(ToolRequest request) throws ToolRefusal {
        String explicit = request.string("moduleRoot", null);
        if (explicit != null && !explicit.isBlank()) {
            return request.root().resolve(explicit).toAbsolutePath().normalize();
        }
        Path candidate = request.file() == null ? request.root() : request.file();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate.toAbsolutePath().normalize();
            }
            candidate = candidate.getParent();
        }
        throw new ToolRefusal("no pom.xml above " + request.file() + " (pass moduleRoot explicitly)");
    }
}
