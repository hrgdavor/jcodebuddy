package hr.hrg.jcodebuddy.tool.java;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import hr.hrg.jcodebuddy.tool.FileProposal;
import hr.hrg.jcodebuddy.tool.Tool;
import hr.hrg.jcodebuddy.tool.ToolProposal;
import hr.hrg.jcodebuddy.tool.ToolRefusal;
import hr.hrg.jcodebuddy.tool.ToolRequest;
import hr.hrg.jcodebuddy.tool.WriteCoordinator;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rename as a proposal tool, using OpenRewrite for the authority on how many identifiers carry the old
 * name and text splicing for the edit. A text count that differs from the tree count is refused rather
 * than guessed (the same rule as the legacy {@code RenameMemberTool}).
 */
public final class RenameTool implements Tool {

    public static final String ID = "rename";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Rename member";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public String caseKey(ToolRequest request) {
        return "rename:" + request.root().relativize(request.file().toAbsolutePath().normalize())
                .toString().replace('\\', '/') + ":" + request.string("from", "") + "->"
                + request.string("to", "");
    }

    @Override
    public boolean supportsPermanentRefusal() {
        return true;
    }

    @Override
    public ToolProposal preview(ToolRequest request) throws ToolRefusal {
        if (request.file() == null) throw new ToolRefusal("the rename tool needs a file");
        String from = request.string("from", "").trim();
        String to = request.string("to", "").trim();
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
            throw new ToolRefusal("rename needs different non-empty 'from' and 'to' parameters");
        }
        try {
            SourceReader.Read read = SourceReader.read(request.file());
            if (read.unparseable() || read.unit() == null) {
                throw new ToolRefusal("refusing to rename in " + request.file()
                        + ": the source does not parse, so the tree cannot say how many uses there are");
            }
            String source = Files.readString(request.file());
            int namedTo = 0;
            int identifiers = 0;
            for (J.Identifier identifier : TreeQueries.findAll(read.unit(), J.Identifier.class)) {
                if (from.equals(identifier.getSimpleName())) identifiers++;
                else if (to.equals(identifier.getSimpleName())) namedTo++;
            }
            if (identifiers == 0) {
                throw new ToolRefusal("no identifier named '" + from + "' in " + request.file());
            }
            if (namedTo > 0) {
                throw new ToolRefusal("refusing to rename '" + from + "' to '" + to + "': the file already uses "
                        + "that name " + namedTo + " time(s)");
            }
            Matcher matcher = pattern(from).matcher(source);
            int textOccurrences = 0;
            while (matcher.find()) textOccurrences++;
            if (textOccurrences != identifiers) {
                throw new ToolRefusal("refusing to rename '" + from + "': the tree sees " + identifiers
                        + " identifier(s) and the text has " + textOccurrences + " occurrence(s); a plain text "
                        + "rename would change a literal, comment or longer identifier");
            }
            String after = pattern(from).matcher(source).replaceAll(Matcher.quoteReplacement(to));
            String key = WriteCoordinator.hash(ID + ":" + request.file().toAbsolutePath().normalize()
                    + ":" + from + ":" + to + ":" + WriteCoordinator.hash(source));
            FileProposal file = FileProposal.change(request.file(), WriteCoordinator.hash(source), source,
                    after, key).owned("generic-tool:" + ID);
            return new ToolProposal(ID, title(), version(), List.of(file),
                    Map.of("from", from, "to", to, "identifiers", identifiers));
        } catch (IOException failed) {
            throw new ToolRefusal("could not read " + request.file() + ": " + failed.getMessage(), failed);
        }
    }

    private static Pattern pattern(String identifier) {
        return Pattern.compile("(?<![\\p{javaJavaIdentifierPart}])"
                + Pattern.quote(identifier) + "(?![\\p{javaJavaIdentifierPart}])");
    }
}
