package hr.hrg.hipster.entity.tooling;

import hr.hrg.jcodebuddy.engine.source.SourceSplicer;
import hr.hrg.hipster.entity.tooling.meta.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Adds the nested {@code Write} interface a marked entity view asks for.
 *
 * <p>This is the one emitter that writes into the developer-owned view interface. It is deliberately
 * idempotent and additive: an existing {@code Write} is left byte-for-byte alone, and only a missing one is
 * spliced in before the interface's closing brace. The builder emitter then reads that interface from the
 * same file, so "add {@code Write} now" and "add it in a later edit" take the same code path.</p>
 */
final class ViewWriteGenerator {

    private ViewWriteGenerator() {
    }

    /** Whether the view source already declares a nested interface named {@code Write}. */
    static boolean hasWrite(String source) {
        return java.util.regex.Pattern.compile("\\binterface\\s+Write\\b").matcher(source).find();
    }

    /**
     * Ensure {@code viewSource} declares {@code Write} with one setter per writable field.
     *
     * @return {@code true} when the file was changed, {@code false} when a Write already existed
     */
    static boolean generate(Path viewSource, String viewName, List<Property> properties) throws IOException {
        if (viewSource == null || !Files.isRegularFile(viewSource)) {
            return false;
        }
        String source = Files.readString(viewSource);
        if (hasWrite(source)) {
            return false;
        }
        int close = SourceSplicer.bodyCloseOffset(source, viewName);
        if (close < 0) {
            return false;
        }
        String eol = source.contains("\r\n") ? "\r\n" : "\n";
        String memberIndent = SourceSplicer.lineIndentBefore(source, close) + "    ";
        String bodyIndent = memberIndent + "    ";
        StringBuilder write = new StringBuilder();
        write.append(memberIndent).append("public interface Write {").append(eol);
        for (Property property : properties) {
            if (ViewAdapterGenerator.isRetired(property)) {
                continue;
            }
            write.append(bodyIndent).append("Write ").append(property.name())
                    .append('(').append(ViewTrackingBuilderGenerator.boxedType(property.type()))
                    .append(" value);").append(eol);
        }
        write.append(memberIndent).append('}').append(eol);
        String prefix = source.substring(0, close);
        if (!prefix.endsWith("\n")) prefix += eol;
        Files.writeString(viewSource, prefix + eol + write + source.substring(close));
        return true;
    }
}
