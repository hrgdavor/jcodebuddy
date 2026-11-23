package hr.hrg.jcodebuddy.tool.java;

import hr.hrg.jcodebuddy.builder.BuilderTransformationEngine;
import hr.hrg.jcodebuddy.builder.RecordBuilderProcessor;
import hr.hrg.jcodebuddy.builder.api.edit.TransformationResult;
import hr.hrg.jcodebuddy.builder.edit.CodeEditApplier;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.ProjectMarker;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Fluent builder generation for a Java record, as a proposal tool. */
public final class RecordBuilderTool implements Tool {

    public static final String ID = "record-builder";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Record builder";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public String caseKey(ToolRequest request) {
        return "record-builder:" + request.root().relativize(request.file().toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    @Override
    public boolean supportsPermanentRefusal() {
        return true;
    }

    @Override
    public String markerAnnotation() {
        return "GenerateBuilder";
    }

    @Override
    public List<ProjectMarker> projectMarkers() {
        return List.of(new ProjectMarker("GenerateBuilder", "Record builder opt-in marker",
                "@Retention(RetentionPolicy.SOURCE)\n"
                        + "@Target(ElementType.TYPE)\n"
                        + "public @interface GenerateBuilder {\n"
                        + "}\n"));
    }

    /**
     * Automatic fs-meta proposals are opt-in for records: only a record carrying {@code @GenerateBuilder}
     * is completed without a manual request. The explicit tool invocation still works on any record.
     */
    public boolean autoEligible(String source) {
        return source != null && !new RecordBuilderProcessor("    ").annotatedRecords(source).isEmpty();
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        if (request.file() == null) throw new ToolRefusal("the record builder needs a file");
        try {
            String source = Files.readString(request.file());
            String indent = request.string("indent", "    ");
            BuilderTransformationEngine engine = new BuilderTransformationEngine(indent);
            TransformationResult result = engine.generate(request.file().toUri().toString(), source,
                    Math.max(1, request.line()));
            if (result.edits().isEmpty()) {
                return new ToolProposal(ID, title(), version(), List.of(), Map.of());
            }
            String after = CodeEditApplier.applyStyles(source, result.edits());
            after = matchLineEndings(after, source);
            if (after.equals(source)) {
                return new ToolProposal(ID, title(), version(), List.of(), Map.of());
            }
            String key = WriteCoordinator.hash(ID + ":" + request.file().toAbsolutePath().normalize()
                    + ":" + request.line() + ":" + WriteCoordinator.hash(source));
            FileProposal file = FileProposal.change(request.file(), WriteCoordinator.hash(source), source,
                    after, key);
            RecordBuilderProcessor.Target target = new RecordBuilderProcessor(indent)
                    .target(source, Math.max(1, request.line()));
            String summary = target == null
                    ? "Add a fluent builder"
                    : "Add a fluent builder for " + target.recordName()
                            + " (" + target.components().size() + " components)";
            return new ToolProposal(ID, title(), version(), List.of(file), Map.of(), summary,
                    List.of("builder() and toBuilder() entry points",
                            "one fluent setter per record component"));
        } catch (IOException failed) {
            throw new ToolRefusal("could not read " + request.file() + ": " + failed.getMessage(), failed);
        } catch (RuntimeException failed) {
            throw new ToolRefusal("record builder failed: " + failed.getMessage(), failed);
        }
    }

    private static String matchLineEndings(String output, String source) {
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        return output.replace("\r\n", "\n").replace('\r', '\n').replace("\n", eol);
    }
}
