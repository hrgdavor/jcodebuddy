package hr.hrg.jetbrains.jcodebuddy.incremental;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Persists the fs-meta generation last successfully applied to this IntelliJ project.
 *
 * <p>The file is project output, not source: <code>.jcodebuddy/cache/incremental-build/batch.properties</code>.
 * It is written atomically only after a compile succeeds, so a crash or a failed compile leaves the previous
 * batch as the replay point.</p>
 */
public final class BatchStateStore {

    private static final String FILE = "batch.properties";
    private static final String BATCH = "batch";
    private static final String ROOT = "daemonRoot";
    private static final String SIGNATURE = "sourceSignature";

    private final Path file;

    public BatchStateStore(Path projectRoot) {
        this.file = projectRoot.resolve(".jcodebuddy").resolve("cache")
                .resolve("incremental-build").resolve(FILE);
    }

    public Path file() {
        return file;
    }

    /** Loads the saved marker, or {@code null} when absent/unreadable. */
    public State load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            long batch = Long.parseLong(properties.getProperty(BATCH, "-1"));
            String root = properties.getProperty(ROOT, "");
            String signature = properties.getProperty(SIGNATURE, "");
            return batch < 0 ? null : new State(batch, root, signature);
        } catch (IOException | NumberFormatException malformed) {
            return null;
        }
    }

    /** Atomically replaces the marker. */
    public void save(long batch, String daemonRoot, String sourceSignature) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Properties properties = new Properties();
        properties.setProperty(BATCH, Long.toString(batch));
        properties.setProperty(ROOT, daemonRoot == null ? "" : daemonRoot);
        properties.setProperty(SIGNATURE, sourceSignature == null ? "" : sourceSignature);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            properties.store(out, "JCodeBuddy incremental build marker");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record State(long batch, String daemonRoot, String sourceSignature) {
    }
}
