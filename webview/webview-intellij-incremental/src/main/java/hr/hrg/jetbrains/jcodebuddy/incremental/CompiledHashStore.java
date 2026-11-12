package hr.hrg.jetbrains.jcodebuddy.incremental;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content hashes of the source files that were successfully compiled in the previous generation.
 *
 * <p>This is the authoritative startup check. The fs-meta batch marker only says where the daemon
 * subscription should resume; if a batch was missed, failed, or the plugin was stopped, the hash map still
 * identifies exactly which source files differ from the last successful compile.</p>
 */
public final class CompiledHashStore {

    private static final String FILE = "compiled-hashes.tsv";

    private final Path file;

    public CompiledHashStore(Path projectRoot) {
        this.file = projectRoot.resolve(".jcodebuddy").resolve("cache")
                .resolve("incremental-build").resolve(FILE);
    }

    public Path file() {
        return file;
    }

    public State load() {
        if (!Files.isRegularFile(file)) {
            return new State("", Map.of());
        }
        String signature = "";
        Map<Path, String> hashes = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("# signature ")) {
                    signature = decode(line.substring("# signature ".length()));
                    continue;
                }
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t", 2);
                if (fields.length != 2) {
                    continue;
                }
                try {
                    hashes.put(Path.of(decode(fields[0])), fields[1]);
                } catch (RuntimeException malformed) {
                    // Ignore one corrupt entry; the missing hash will cause a recompile.
                }
            }
        } catch (IOException unreadable) {
            return new State("", Map.of());
        }
        return new State(signature, Map.copyOf(hashes));
    }

    public void save(String signature, Map<Path, String> hashes) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder content = new StringBuilder("# signature ").append(encode(signature)).append('\n');
        for (Map.Entry<Path, String> entry : hashes.entrySet()) {
            content.append(encode(entry.getKey().toString())).append('\t')
                    .append(entry.getValue()).append('\n');
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

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /** SHA-256 of the source content, hex encoded. */
    public static String hash(Path source) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(Files.readAllBytes(source)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            value.append(Character.forDigit((b >>> 4) & 0xf, 16));
            value.append(Character.forDigit(b & 0xf, 16));
        }
        return value.toString();
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public record State(String signature, Map<Path, String> hashes) {
    }
}
