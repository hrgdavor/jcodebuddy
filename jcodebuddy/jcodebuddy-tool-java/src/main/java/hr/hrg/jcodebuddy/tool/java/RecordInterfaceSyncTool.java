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
 * <p>The interface is the source of truth. Its zero-argument accessors (and the identity type inherited from
 * {@code Identifiable<T>}) define the record components: a component missing from the record is added, and a
 * record component removed from the interface is removed from the record header. The tool never adds a
 * method to the interface. When the marked interface has no record yet, it creates the nested {@code Record}
 * from the same accessors. It only works when the record and interface are in the same source file.</p>
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
                String after = generateMissingRecord(source, annotation);
                String summary = "Create the missing Record declaration";
                List<String> details = List.of("Generated from the interface accessors");
                return proposal(request, source, after, summary, details);
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

            List<Component> components = interfaceComponents(source, interfaceName);
            SyncSummary summary = summarize(target.components(), components);
            return proposal(request, source, replaceRecordHeader(source, target, components),
                    summary.shortText(), summary.details());
        } catch (IOException failed) {
            throw new ToolRefusal("could not read " + request.file() + ": " + failed.getMessage(), failed);
        } catch (RuntimeException failed) {
            throw new ToolRefusal("record/interface sync failed: " + failed.getMessage(), failed);
        }
    }

    private record Component(String type, String name) {
    }

    private record SyncSummary(String shortText, List<String> details) {
    }

    /** The human explanation of a record-header rewrite, derived from names and declared types. */
    private SyncSummary summarize(List<RecordBuilderProcessor.Component> before, List<Component> after) {
        Map<String, String> previous = before.stream().collect(java.util.stream.Collectors.toMap(
                RecordBuilderProcessor.Component::name, RecordBuilderProcessor.Component::type,
                (left, right) -> left));
        Map<String, String> current = after.stream().collect(java.util.stream.Collectors.toMap(
                Component::name, Component::type, (left, right) -> left));
        List<String> details = new ArrayList<>();
        int removed = 0;
        int added = 0;
        int changed = 0;
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                removed++;
                details.add("Removed " + entry.getKey() + " (" + entry.getValue() + ")");
            } else if (!current.get(entry.getKey()).equals(entry.getValue())) {
                changed++;
                details.add("Changed " + entry.getKey() + ": " + entry.getValue() + " -> "
                        + current.get(entry.getKey()));
            }
        }
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (!previous.containsKey(entry.getKey())) {
                added++;
                details.add("Added " + entry.getKey() + " (" + entry.getValue() + ")");
            }
        }
        if (details.isEmpty()) {
            return new SyncSummary("Record header is already synchronized", List.of());
        }
        List<String> parts = new ArrayList<>();
        if (removed > 0) parts.add("removed " + removed);
        if (added > 0) parts.add("added " + added);
        if (changed > 0) parts.add("changed " + changed);
        return new SyncSummary("Record components: " + String.join(", ", parts), details);
    }

    /** The interface is the source of truth: identity first, then its zero-argument accessors in order. */
    private List<Component> interfaceComponents(String source, String interfaceName) {
        int open = SourceSplicer.bodyOpenOffset(source, interfaceName);
        int close = open < 0 ? -1 : SourceSplicer.bodyCloseOffset(source, interfaceName);
        if (open < 0 || close < 0) return List.of();
        List<Component> components = new ArrayList<>();
        Pattern identifiable = Pattern.compile("Identifiable\\s*<\\s*([^>]+)\\s*>");
        Matcher identity = identifiable.matcher(source.substring(0, open));
        String identityType = identity.find() ? identity.group(1).trim() : "";

        Pattern accessor = Pattern.compile(
                "(?m)^\\s*((?:(?:public|protected|private|default|static|abstract|final)\\s+)*)"
                        + "([\\w.$<>\\[\\], ?]+?)\\s+(\\w+)\\s*\\(\\s*\\)\\s*;");
        Matcher method = accessor.matcher(source.substring(open + 1, close));
        boolean hasId = false;
        while (method.find()) {
            String modifiers = method.group(1);
            if (modifiers.contains("default") || modifiers.contains("static")) continue;
            String type = method.group(2).trim().replaceAll("\\s+", " ");
            String name = method.group(3);
            if (type.isBlank()) continue;
            if (name.equals("id")) hasId = true;
            components.add(new Component(type, name));
        }
        if (!identityType.isBlank() && !hasId) components.add(0, new Component(identityType, "id"));
        return components;
    }

    private String replaceRecordHeader(String source, RecordBuilderProcessor.Target target,
                                       List<Component> components) {
        int name = source.indexOf(target.recordName(), target.startOffset());
        int open = name < 0 ? -1 : source.indexOf('(', name);
        int close = open < 0 ? -1 : matchingParen(source, open);
        if (open < 0 || close < 0) return source;
        String replacement = components.stream().map(c -> c.type() + " " + c.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
        return source.substring(0, open + 1) + replacement + source.substring(close);
    }

    private int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
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

        List<Component> components = interfaceComponents(source, interfaceName);
        if (components.isEmpty()) return source;

        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        String memberIndent = SourceSplicer.lineIndentBefore(source, close) + indent;
        String prefix = source.substring(0, close);
        if (!prefix.endsWith("\n")) prefix += eol;
        String declaration = components.stream().map(c -> c.type() + " " + c.name())
                .reduce((a, b) -> a + ", " + b).orElse("");
        String record = memberIndent + "record Record(" + declaration + ")" + eol
                + memberIndent + indent + "implements " + interfaceName + " {" + eol
                + memberIndent + "}" + eol;
        return prefix + eol + record + source.substring(close);
    }

    private ToolProposal proposal(ToolRequest request, String source, String after) {
        return proposal(request, source, after, "", List.of());
    }

    private ToolProposal proposal(ToolRequest request, String source, String after,
                                  String summary, List<String> details) {
        if (after == null || after.equals(source)) {
            return new ToolProposal(ID, title(), "1", List.of(), Map.of());
        }
        String key = WriteCoordinator.hash(ID + ":" + request.file().toAbsolutePath().normalize()
                + ":" + request.line() + ":" + WriteCoordinator.hash(source));
        FileProposal file = FileProposal.change(request.file(), WriteCoordinator.hash(source), source,
                after, key);
        return new ToolProposal(ID, title(), "1", List.of(file), Map.of(), summary, details);
    }
}
