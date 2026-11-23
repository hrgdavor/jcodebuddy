package hr.hrg.jcodebuddy.tool.builder;

import hr.hrg.jcodebuddy.builder.ClassMemberProcessor;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The generic Java builder as a tool. It reads the class at the requested line through
 * {@link ClassMemberProcessor} (OpenRewrite LST) and returns a proposal; it does not touch the file.
 */
public final class BuilderTool implements Tool {

    public static final String ID = "builder";

    private final String indent;

    public BuilderTool() {
        this("    ");
    }

    public BuilderTool(String indent) {
        this.indent = indent == null || indent.isEmpty() ? "    " : indent;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Builder";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public String standalonePage() {
        return "builder.html";
    }

    @Override
    public String caseKey(ToolRequest request) {
        return "builder:" + relative(request);
    }

    @Override
    public boolean supportsPermanentRefusal() {
        return true;
    }

    private static String relative(ToolRequest request) {
        Path file = request.file().toAbsolutePath().normalize();
        return request.root().relativize(file).toString().replace('\\', '/');
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        if (request.file() == null) {
            throw new ToolRefusal("the builder tool needs a file");
        }
        try {
            String source = Files.readString(request.file());
            ClassMemberProcessor processor = new ClassMemberProcessor(indent);
            ClassMemberProcessor.Target target = processor.target(source, Math.max(1, request.line()));
            if (target == null) {
                throw new ToolRefusal("no class at line " + request.line() + " in " + request.file());
            }
            String after = processor.withBuilder(source, target);
            if (after.equals(source)) {
                return new ToolProposal(ID, title(), version(), List.of(), Map.of("message", "no change"));
            }
            String key = WriteCoordinator.hash(ID + ":" + request.file().toAbsolutePath().normalize()
                    + ":" + request.line() + ":" + WriteCoordinator.hash(source));
            FileProposal file = FileProposal.change(request.file(), WriteCoordinator.hash(source), source,
                    after, key);
            return new ToolProposal(ID, title(), version(), List.of(file), Map.of(),
                    "Add a fluent builder", List.of("Generate builder fields, setters and build()"));
        } catch (IOException failed) {
            throw new ToolRefusal("could not read " + request.file() + ": " + failed.getMessage(), failed);
        }
    }
}
