package hr.hrg.jcodebuddy.tool.java;

import hr.hrg.jcodebuddy.builder.RecordBuilderProcessor;
import hr.hrg.jcodebuddy.engine.source.SourceSplicer;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.ProjectMarker;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a record's components and the interface it implements in step.
 *
 * <p>A record component already provides the accessor method, so the interface only needs the matching
 * abstract declaration. Without it the record does not implement the interface and javac fails. When the
 * marked interface has no record yet, the tool creates the nested {@code Record} from its accessors and the
 * identity type inherited from {@code Identifiable<T>}. It only adds declarations; it never removes a
 * hand-written interface method, and it only works when the record and interface are in the same source file.</p>
 */
public final class RecordInterfaceSyncTool implements Tool {

    public static final String ID = "record-interface-sync";
    private static final Pattern IMPLEMENTS = Pattern.compile("\\bimplements\\s+([\\w.$]+)");

    private final String indent;

    public RecordInterfaceSyncTool() {
        this("    ");
    }

    public RecordInterfaceSyncTool(String indent) {
        this.indent = indent == null || indent.isEmpty() ? "    " : indent;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Record / interface sync";
    }

    @Override
    public String caseKey(ToolRequest request) {
        return "record-interface-sync:" + request.root()
                .relativize(request.file().toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    @Override
    public boolean supportsPermanentRefusal() {
        return true;
    }

    @Override
    public String markerAnnotation() {
        return "GenerateRecordInterfaceSync";
    }

    @Override
    public List<ProjectMarker> projectMarkers() {
        return List.of(new ProjectMarker("GenerateRecordInterfaceSync", "Record/interface sync opt-in marker",
                "@Retention(RetentionPolicy.SOURCE)\n"
                        + "@Target(ElementType.TYPE)\n"
                        + "public @interface GenerateRecordInterfaceSync {\n"
                        + "}\n"));
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        if (request.file() == null) throw new ToolRefusal("the record sync tool needs a file");
        try {
            String source = Files.readString(request.file());
            String annotation = request.string("annotation", "GenerateRecordInterfaceSync");
            int at = annotation.lastIndexOf('.');
            if (at >= 0) annotation = annotation.substring(at + 1);
            RecordBuilderProcessor.Target target = new RecordBuilderProcessor(indent)
                    .target(source, Math.max(1, request.line()));
            if (target == null) {
                return proposal(request, source, generateMissingRecord(source, annotation));
            }
            int recordBody = SourceSplicer.bodyOpenOffset(source, target.recordName());
            if (recordBody < 0) {
                return new ToolProposal(ID, title(), "1", List.of(), Map.of());
            }
            String header = source.substring(target.startOffset(), recordBody);
            Matcher matcher = IMPLEMENTS.matcher(header);
            if (!matcher.find()) {
                return new ToolProposal(ID, title(), "1", List.of(), Map.of());
            }
            String qualified = matcher.group(1);
            String interfaceName = qualified.substring(qualified.lastIndexOf('.') + 1);
            int interfaceOpen = SourceSplicer.bodyOpenOffset(source, interfaceName);
            int interfaceClose = interfaceOpen < 0 ? -1 : SourceSplicer.bodyCloseOffset(source, interfaceName);
            if (interfaceOpen < 0 || interfaceClose < 0) {
                return new ToolProposal(ID, title(), "1", List.of(), Map.of());
            }
            String interfaceHeader = source.substring(0, interfaceOpen);
            if (!header.contains("@" + annotation) && !interfaceHeader.contains("@" + annotation)) {
                return new ToolProposal(ID, title(), "1", List.of(), Map.of());
            }

            String interfaceBody = source.substring(interfaceOpen + 1, interfaceClose);
            List<RecordBuilderProcessor.Component> missing = new ArrayList<>();
            for (RecordBuilderProcessor.Component component : target.components()) {
                if (!Pattern.compile("\\b" + Pattern.quote(component.name()) + "\\s*\\(")
                        .matcher(interfaceBody).find()) {
                    missing.add(component);
                }
            }
            if (missing.isEmpty()) {
                return new ToolProposal(ID, title(), "1", List.of(), Map.of());
            }

            String eol = source.contains("\r\n") ? "\r\n" : "\n";
            String memberIndent = SourceSplicer.lineIndentBefore(source, interfaceClose) + indent;
            String prefix = source.substring(0, interfaceClose);
            if (!prefix.endsWith("\n")) prefix += eol;
            StringBuilder addition = new StringBuilder();
            for (RecordBuilderProcessor.Component component : missing) {
                addition.append(memberIndent).append(component.type()).append(' ')
                        .append(component.name()).append("();").append(eol);
            }
            String after = prefix + addition + source.substring(interfaceClose);
            return proposal(request, source, after);
        } catch (IOException failed) {
            throw new ToolRefusal("could not read " + request.file() + ": " + failed.getMessage(), failed);
        } catch (RuntimeException failed) {
            throw new ToolRefusal("record/interface sync failed: " + failed.getMessage(), failed);
        }
    }

    /**
     * A marked interface with no record yet: build the nested {@code Record} from its zero-argument
     * accessors, plus the identity accessor inherited from {@code Identifiable<T>}.
     */
    private String generateMissingRecord(String source, String annotation) {
        Pattern marked = Pattern.compile("@\\s*" + Pattern.quote(annotation)
                + "\\b(?s).*?\\binterface\\s+(\\w+)");
        Matcher marker = marked.matcher(source);
        if (!marker.find()) return source;
        String interfaceName = marker.group(1);
        int open = SourceSplicer.bodyOpenOffset(source, interfaceName);
        int close = open < 0 ? -1 : SourceSplicer.bodyCloseOffset(source, interfaceName);
        if (open < 0 || close < 0) return source;

        String header = source.substring(0, open);
        List<String> components = new ArrayList<>();
        Pattern identifiable = Pattern.compile("Identifiable\\s*<\\s*([^>]+)\\s*>");
        Matcher identity = identifiable.matcher(header);
        if (identity.find()) components.add(identity.group(1).trim() + " id");

        Pattern accessor = Pattern.compile(
                "(?m)^\\s*((?:(?:public|protected|private|default|static|abstract|final)\\s+)*)"
                        + "([\\w.$<>\\[\\], ?]+?)\\s+(\\w+)\\s*\\(\\s*\\)\\s*;");
        Matcher method = accessor.matcher(source.substring(open + 1, close));
        while (method.find()) {
            String modifiers = method.group(1);
            if (modifiers.contains("default") || modifiers.contains("static")) continue;
            String type = method.group(2).trim().replaceAll("\\s+", " ");
            String name = method.group(3);
            if (name.equals("id") || type.isBlank()) continue;
            components.add(type + " " + name);
        }
        if (components.isEmpty()) return source;

        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        String memberIndent = SourceSplicer.lineIndentBefore(source, close) + indent;
        String prefix = source.substring(0, close);
        if (!prefix.endsWith("\n")) prefix += eol;
        String record = memberIndent + "record Record(" + String.join(", ", components) + ")" + eol
                + memberIndent + indent + "implements " + interfaceName + " {" + eol
                + memberIndent + "}" + eol;
        return prefix + eol + record + source.substring(close);
    }

    private ToolProposal proposal(ToolRequest request, String source, String after) {
        if (after == null || after.equals(source)) {
            return new ToolProposal(ID, title(), "1", List.of(), Map.of());
        }
        String key = WriteCoordinator.hash(ID + ":" + request.file().toAbsolutePath().normalize()
                + ":" + request.line() + ":" + WriteCoordinator.hash(source));
        FileProposal file = FileProposal.change(request.file(), WriteCoordinator.hash(source), source,
                after, key);
        return new ToolProposal(ID, title(), "1", List.of(file), Map.of());
    }
}
