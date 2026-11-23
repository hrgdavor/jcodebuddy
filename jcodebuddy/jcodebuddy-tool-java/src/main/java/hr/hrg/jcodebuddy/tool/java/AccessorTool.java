package hr.hrg.jcodebuddy.tool.java;

import hr.hrg.jcodebuddy.builder.ClassMemberProcessor;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Accessors as a proposal tool. */
public final class AccessorTool implements Tool {

    public static final String ID = "accessors";
    private final String indent;

    public AccessorTool() {
        this("    ");
    }

    public AccessorTool(String indent) {
        this.indent = indent == null || indent.isEmpty() ? "    " : indent;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Accessors";
    }

    @Override
    public String caseKey(ToolRequest request) {
        return "accessors:" + relative(request);
    }

    @Override
    public boolean supportsPermanentRefusal() {
        return true;
    }

    private static String relative(ToolRequest request) {
        return request.root().relativize(request.file().toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        if (request.file() == null) throw new ToolRefusal("the accessors tool needs a file");
        try {
            String source = Files.readString(request.file());
            ClassMemberProcessor.Target target = new ClassMemberProcessor(indent)
                    .target(source, Math.max(1, request.line()));
            if (target == null) {
                throw new ToolRefusal("no class at line " + request.line() + " in " + request.file());
            }
            String after = new ClassMemberProcessor(indent).withAccessors(source, target, true, true);
            if (after.equals(source)) return new ToolProposal(ID, title(), "1", List.of(), Map.of());
            String key = WriteCoordinator.hash(ID + ":" + request.file().toAbsolutePath().normalize()
                    + ":" + request.line() + ":" + WriteCoordinator.hash(source));
            FileProposal file = FileProposal.change(request.file(), WriteCoordinator.hash(source), source,
                    after, key);
            return new ToolProposal(ID, title(), "1", List.of(file), Map.of(),
                    "Add missing accessors", List.of("Generate getters and setters for the fields"));
        } catch (IOException failed) {
            throw new ToolRefusal("could not read " + request.file() + ": " + failed.getMessage(), failed);
        }
    }
}
