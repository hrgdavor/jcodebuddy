package hr.hrg.jcodebuddy.tool;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * The single writer for tool proposals.
 *
 * <p>Every apply takes a per-file OS lock, checks the proposal's {@code beforeHash}, writes a temp file
 * and moves it into place. A stale proposal is refused rather than overwriting an editor's change. The
 * file lock is under {@code .jcodebuddy/agent/locks/} so a separate build/tool process can coordinate
 * through the same file.</p>
 *
 * <p>The coordinator also keeps a small audit trail under {@code .jcodebuddy/agent/audit/}; it is a
 * review record of what was applied, not a second source of truth. The actual source remains the
 * committed file.</p>
 */
public final class WriteCoordinator {

    private final Path root;

    public WriteCoordinator(Path root) {
        this.root = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path resolve(FileProposal proposal) throws IOException {
        Path path = proposal.path();
        if (!path.isAbsolute()) {
            path = root.resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new IOException("proposal escapes the project: " + path);
        }
        return path;
    }

    public ApplyResult apply(FileProposal proposal) throws IOException {
        Path target = resolve(proposal);
        Path lockDir = root.resolve(".jcodebuddy").resolve("agent").resolve("locks");
        Files.createDirectories(lockDir);
        Path lockPath = lockDir.resolve(lockName(target));
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            String current = currentHash(target);
            String expected = proposal.beforeHash() == null ? "" : proposal.beforeHash();
            if (!current.equals(expected)) {
                return ApplyResult.stale("the file changed since the tool previewed it");
            }
            if (proposal.owner() != null && !proposal.owner().isBlank()
                    && !owns(target, proposal.owner())) {
                return ApplyResult.refused("another generator owns " + root.relativize(target));
            }
            String after = proposal.afterContent() == null ? "" : proposal.afterContent();
            if (proposal.changeType() == FileProposal.ChangeType.DELETE) {
                Files.deleteIfExists(target);
            } else {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temp = Files.createTempFile(parent, ".jcb-write-", ".tmp");
                Files.writeString(temp, after, StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            record(proposal, target, proposal.beforeContent(), after);
            if (proposal.owner() != null && !proposal.owner().isBlank()) {
                recordOwner(target, proposal.owner());
            }
            return ApplyResult.applied("applied to " + root.relativize(target));
        }
    }

    /**
     * Reverts one applied proposal. It refuses when the current file is not the proposal's after-state:
     * another writer owns that change now.
     */
    public ApplyResult revert(FileProposal proposal) throws IOException {
        Path target = resolve(proposal);
        Path lockDir = root.resolve(".jcodebuddy").resolve("agent").resolve("locks");
        Files.createDirectories(lockDir);
        Path lockPath = lockDir.resolve(lockName(target));
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            String current = currentHash(target);
            if (!current.equals(proposal.afterHash())) {
                return ApplyResult.refused("the file changed after the proposal was applied");
            }
            String before = proposal.beforeContent() == null ? "" : proposal.beforeContent();
            if (before.isEmpty() && proposal.changeType() == FileProposal.ChangeType.ADD) {
                Files.deleteIfExists(target);
            } else {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Path temp = Files.createTempFile(parent, ".jcb-revert-", ".tmp");
                Files.writeString(temp, before, StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return ApplyResult.reverted("reverted " + root.relativize(target));
        }
    }

    public String currentHash(Path path) throws IOException {
        Path absolute = path.isAbsolute() ? path : root.resolve(path);
        if (!Files.isRegularFile(absolute)) {
            return "";
        }
        return hash(Files.readString(absolute));
    }

    public static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((text == null ? "" : text)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void record(FileProposal proposal, Path target, String before, String after) {
        try {
            String stamp = Instant.now().toString().replace(':', '-');
            Path dir = root.resolve(".jcodebuddy").resolve("agent").resolve("audit")
                    .resolve(stamp + "_" + safe(proposal.idempotencyKey() == null
                            ? "tool" : proposal.idempotencyKey()));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("path.txt"), root.relativize(target).toString().replace('\\', '/'));
            if (before != null) Files.writeString(dir.resolve("before.txt"), before);
            if (after != null) Files.writeString(dir.resolve("after.txt"), after);
        } catch (IOException ignored) {
            // Audit is evidence, not a precondition for the atomic source write.
        }
    }

    /** Whether the recorded owner of a path permits this generator. */
    private boolean owns(Path target, String owner) {
        Path owners = root.resolve(".jcodebuddy").resolve("agent").resolve("owners.properties");
        if (!Files.isRegularFile(owners)) return true;
        String key = root.relativize(target).toString().replace('\\', '/');
        try {
            for (String line : Files.readAllLines(owners)) {
                int equals = line.indexOf('=');
                if (equals > 0 && line.substring(0, equals).equals(key)) {
                    String existing = line.substring(equals + 1);
                    return existing.equals(owner);
                }
            }
        } catch (IOException ignored) {
            // A missing/unreadable owner file is "unclaimed"; the apply remains safe by hash.
        }
        return true;
    }

    private void recordOwner(Path target, String owner) {
        Path owners = root.resolve(".jcodebuddy").resolve("agent").resolve("owners.properties");
        String key = root.relativize(target).toString().replace('\\', '/');
        try {
            Files.createDirectories(owners.getParent());
            Map<String, String> values = new java.util.LinkedHashMap<>();
            if (Files.isRegularFile(owners)) {
                for (String line : Files.readAllLines(owners)) {
                    int equals = line.indexOf('=');
                    if (equals > 0) values.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
            values.put(key, owner);
            StringBuilder out = new StringBuilder();
            values.forEach((path, value) -> out.append(path).append('=').append(value).append('\n'));
            Files.writeString(owners, out.toString());
        } catch (IOException ignored) {
            // Ownership metadata is an extra guard, not a reason to undo a successful atomic write.
        }
    }

    private String lockName(Path target) {
        return hash(root.relativize(target).toString().replace('\\', '/')) + ".lock";
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "-");
    }
}
