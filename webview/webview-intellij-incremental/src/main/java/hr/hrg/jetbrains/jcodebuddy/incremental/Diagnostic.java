package hr.hrg.jetbrains.jcodebuddy.incremental;

import java.nio.file.Path;

/**
 * One compiler diagnostic, kept in memory by {@link IncrementalDiagnosticsService} and handed directly to
 * IntelliJ's {@code ExternalAnnotator}. There is no report file and no JSON serialization in this path.
 *
 * @param file    absolute source path
 * @param line    1-based line; {@code 0} means no line information
 * @param column  1-based column; {@code 0} means the whole line
 * @param message compiler message
 */
public record Diagnostic(Path file, int line, int column, String message) {
}
