package hr.hrg.jcodebuddy.tool;

import java.nio.file.Path;

/**
 * One proposed file change. A tool returns this; it never writes the file.
 *
 * <p>{@code beforeHash} is the hash the tool saw when it produced the proposal. The coordinator refuses
 * the proposal when the file has moved on, and {@code afterHash} lets a reject verify that it is
 * reversing its own write rather than somebody else's.</p>
 */
public record FileProposal(Path path, String beforeHash, String beforeContent, String afterContent,
                           ChangeType changeType, String idempotencyKey, String blockId, String owner) {

    public enum ChangeType { ADD, CHANGE, DELETE }

    public FileProposal {
        if (path == null) {
            throw new IllegalArgumentException("a proposal path is required");
        }
        if (changeType == null) {
            throw new IllegalArgumentException("a proposal change type is required");
        }
    }

    /** A proposal that replaces text in an existing file. */
    public static FileProposal change(Path path, String beforeHash, String beforeContent, String afterContent,
                                      String idempotencyKey) {
        return new FileProposal(path, beforeHash, beforeContent, afterContent, ChangeType.CHANGE,
                idempotencyKey, null, null);
    }

    /** Attach the generator that owns this file; the coordinator refuses a conflicting owner. */
    public FileProposal owned(String owner) {
        return new FileProposal(path, beforeHash, beforeContent, afterContent, changeType, idempotencyKey,
                blockId, owner);
    }

    /** The SHA-256 the file must have after this proposal is applied. */
    public String afterHash() {
        return WriteCoordinator.hash(afterContent == null ? "" : afterContent);
    }
}
