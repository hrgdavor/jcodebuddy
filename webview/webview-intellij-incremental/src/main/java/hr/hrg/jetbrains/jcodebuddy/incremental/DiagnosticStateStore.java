package hr.hrg.jetbrains.jcodebuddy.incremental;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Persists the last compiler diagnostics so IntelliJ can restore the editor error annotations after a
 * project/IDE restart even when the incremental startup finds no new fs-meta batch to compile.
 *
 * <p>IntelliJ does not persist {@code ExternalAnnotator} output itself. The file is project output:
 * <code>.jcodebuddy/cache/incremental-build/diagnostics.tsv</code>. Each field is Base64-encoded, so tabs
 * and newlines in compiler messages are safe. The write is atomic.</p>
 */
public final class DiagnosticStateStore {

    private static final String FILE = "diagnostics.tsv";

    private final Path file;

    public DiagnosticStateStore(Path projectRoot) {
        this.file = projectRoot.resolve(".jcodebuddy").resolve("cache")
                .resolve("incremental-build").resolve(FILE);
    }

    public Path file() {
        return file;
    }

    public List<Diagnostic> load() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", 4);
                if (fields.length != 4) {
                    continue;
                }
                try {
                    diagnostics.add(new Diagnostic(
                            Path.of(decode(fields[0])),
                            Integer.parseInt(fields[1]),
                            Integer.parseInt(fields[2]),
                            decode(fields[3])));
                } catch (RuntimeException malformed) {
                    // One malformed line must not hide all other restored diagnostics.
                }
            }
        } catch (IOException unreadable) {
            return List.of();
        }
        return List.copyOf(diagnostics);
    }

    public void save(Collection<Diagnostic> diagnostics) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder content = new StringBuilder("# JCodeBuddy diagnostics\n");
        for (Diagnostic diagnostic : diagnostics) {
            content.append(encode(diagnostic.file().toString())).append('\t')
                    .append(diagnostic.line()).append('\t')
                    .append(diagnostic.column()).append('\t')
                    .append(encode(diagnostic.message())).append('\n');
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, content.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Clears the saved diagnostics when the project setting is disabled. */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort: the in-memory diagnostics are cleared by the service anyway.
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
