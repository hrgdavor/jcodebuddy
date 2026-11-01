package hr.hrg.jcodebuddy.watch;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A Java client for the fs-meta daemon's Unix-socket protocol (`FORMAT.socket.md`).
 *
 * <p>The daemon is the watcher; this class is the client side of the live path. It sends the simultaneous
 * {@code HELLO}, then {@code SUBSCRIBE}, and turns the {@code START_BATCH} / {@code CHANGE_ENTRY} /
 * {@code END_BATCH} chain into one {@link Batch} per generation. It is deliberately a transport, not a policy: no
 * filtering, no diff, no regeneration decision.</p>
 *
 * <p>Only the frames a consumer needs are interpreted. {@code STATE} and {@code GIT_EVENT} are consumed without
 * changing the batch state, {@code PING} is answered, and an {@code ERROR} raises. An unknown kind raises rather
 * than being skipped — the protocol's own rule, because a skipped change entry is a silently wrong index.</p>
 */
public final class FsMetaClient implements AutoCloseable {

    /** The fixed protocol major/minor the daemon speaks. */
    public static final int PROTOCOL = 0x0102;
    /** Ask {@link #connect(Path, long)} to subscribe after the daemon's current generation. */
    public static final long LATEST = -1;

    private static final int KIND_HELLO = 0x01;
    private static final int KIND_START_BATCH = 0x02;
    private static final int KIND_CHANGE_ENTRY = 0x03;
    private static final int KIND_END_BATCH = 0x04;
    private static final int KIND_ERROR = 0x05;
    private static final int KIND_SUBSCRIBE = 0x06;
    private static final int KIND_PING = 0x07;
    private static final int KIND_PONG = 0x08;
    private static final int KIND_STATE = 0x09;
    private static final int KIND_GIT_EVENT = 0x0a;

    private static final int ENTRY_SIZE = 64;
    private static final int MAX_FRAME = 1 << 20;

    private static final int CHANGE_ADDED = 0x20;
    private static final int CHANGE_CHANGED = 0x40;
    private static final int CHANGE_DELETED = 0x80;

    private final SocketChannel channel;
    private final long serverGeneration;

    private FsMetaClient(SocketChannel channel, long serverGeneration) {
        this.channel = channel;
        this.serverGeneration = serverGeneration;
    }

    /**
     * Connects, greets, and subscribes.
     *
     * @param socket      the root's {@code .fs-meta/socket}
     * @param fromBatch   the last batch already applied, or {@link #LATEST} to start at the daemon's current head
     */
    public static FsMetaClient connect(Path socket, long fromBatch) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socket));
            writeFrame(channel, KIND_HELLO, hello(1));
            long generation = readHello(channel);
            FsMetaClient client = new FsMetaClient(channel, generation);
            long subscribeFrom = fromBatch == LATEST ? generation : fromBatch;
            writeFrame(channel, KIND_SUBSCRIBE, littleEndian(8).putLong(subscribeFrom).array());
            return client;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    /** The newest batch the daemon named in its greeting. */
    public long serverGeneration() {
        return serverGeneration;
    }

    /**
     * Reads until one batch is complete. A batch is only returned at {@code END_BATCH}; a connection that closes
     * mid-batch raises, leaving the caller free to reconnect from its last applied batch.
     */
    public Batch nextBatch() throws IOException {
        long number = -1;
        long parent = -1;
        List<Change> changes = new ArrayList<>();
        while (true) {
            Frame frame = readFrame(channel);
            switch (frame.kind) {
                case KIND_HELLO -> {
                    // The server's greeting is consumed in connect(); a second one would be a protocol novelty.
                }
                case KIND_STATE, KIND_GIT_EVENT -> {
                    // Live metadata, not part of the change set; a consumer that needs it can add accessors.
                }
                case KIND_PING -> writeFrame(channel, KIND_PONG, new byte[0]);
                case KIND_PONG -> {
                }
                case KIND_START_BATCH -> {
                    ByteBuffer payload = littleEndian(frame.payload);
                    number = payload.getLong();
                    parent = payload.getLong();
                    changes.clear();
                }
                case KIND_CHANGE_ENTRY -> changes.add(change(frame.payload));
                case KIND_END_BATCH -> {
                    ByteBuffer payload = littleEndian(frame.payload);
                    long ended = payload.getLong();
                    long added = payload.getLong();
                    long changed = payload.getLong();
                    long deleted = payload.getLong();
                    if (number < 0 || ended != number) {
                        throw new IOException("fs-meta: END_BATCH " + ended + " does not close START_BATCH " + number);
                    }
                    long actualAdded = changes.stream().filter(change -> change.kind() == Kind.ADDED).count();
                    long actualChanged = changes.stream().filter(change -> change.kind() == Kind.CHANGED).count();
                    long actualDeleted = changes.stream().filter(change -> change.kind() == Kind.DELETED).count();
                    if (actualAdded != added || actualChanged != changed || actualDeleted != deleted) {
                        throw new IOException("fs-meta: batch " + number + " counts " + added + "/" + changed + "/"
                                + deleted + " do not match entries " + actualAdded + "/" + actualChanged + "/"
                                + actualDeleted);
                    }
                    if (payload.hasRemaining()) {
                        payload.getLong(); // changes_hash, currently 0 (capability bit clear)
                    }
                    return new Batch(number, parent, changes);
                }
                case KIND_ERROR -> throw error(frame.payload);
                default -> throw new IOException("fs-meta: unknown frame kind 0x" + Integer.toHexString(frame.kind));
            }
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private static Change change(byte[] payload) throws IOException {
        if (payload.length < ENTRY_SIZE) {
            throw new IOException("fs-meta: CHANGE_ENTRY shorter than " + ENTRY_SIZE);
        }
        ByteBuffer entry = littleEndian(payload);
        int nameLength = entry.getInt(40);
        if (nameLength < 0 || payload.length != ENTRY_SIZE + nameLength) {
            throw new IOException("fs-meta: CHANGE_ENTRY length " + payload.length + " does not match name length " + nameLength);
        }
        int flags = entry.getInt(44);
        int changeBits = flags & (CHANGE_ADDED | CHANGE_CHANGED | CHANGE_DELETED);
        if (Integer.bitCount(changeBits) != 1) {
            throw new IOException("fs-meta: CHANGE_ENTRY must carry exactly one change bit (flags 0x"
                    + Integer.toHexString(flags) + ")");
        }
        Kind kind;
        if ((changeBits & CHANGE_ADDED) != 0) {
            kind = Kind.ADDED;
        } else if ((changeBits & CHANGE_CHANGED) != 0) {
            kind = Kind.CHANGED;
        } else {
            kind = Kind.DELETED;
        }
        String name = new String(payload, ENTRY_SIZE, nameLength, StandardCharsets.UTF_8);
        return new Change(name, kind);
    }

    private static IOException error(byte[] payload) {
        if (payload.length < 2) {
            return new IOException("fs-meta: ERROR frame without a code");
        }
        int code = littleEndian(payload).getShort() & 0xffff;
        String message = payload.length > 2
                ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
                : "";
        return new IOException("fs-meta: ERROR " + code + (message.isEmpty() ? "" : ": " + message));
    }

    private static byte[] hello(int role) {
        return littleEndian(24)
                .putShort((short) PROTOCOL)
                .putShort((short) ENTRY_SIZE)
                .put((byte) 0) // wyhash64
                .put((byte) role)
                .put((byte) 0) // capabilities
                .put((byte) 0)
                .putLong(0) // our generation
                .putLong(MAX_FRAME)
                .array();
    }

    private static long readHello(SocketChannel channel) throws IOException {
        Frame frame = readFrame(channel);
        if (frame.kind != KIND_HELLO || frame.payload.length != 24) {
            throw new IOException("fs-meta: expected a 24-byte HELLO, got kind 0x"
                    + Integer.toHexString(frame.kind) + " length " + frame.payload.length);
        }
        ByteBuffer payload = littleEndian(frame.payload);
        int protocol = payload.getShort() & 0xffff;
        int entrySize = payload.getShort() & 0xffff;
        int hash = payload.get() & 0xff;
        int role = payload.get() & 0xff;
        if ((protocol >>> 8) != (PROTOCOL >>> 8)) {
            throw new IOException("fs-meta: unsupported protocol 0x" + Integer.toHexString(protocol));
        }
        if (entrySize != ENTRY_SIZE || hash != 0) {
            throw new IOException("fs-meta: unsupported entry/hash " + entrySize + "/" + hash);
        }
        if (role != 0) {
            throw new IOException("fs-meta: peer role is not server: " + role);
        }
        payload.get(); // capabilities
        payload.get(); // reserved
        return payload.getLong();
    }

    private static Frame readFrame(SocketChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, header);
        int kind = header.getShort(0) & 0xffff;
        int flags = header.getShort(2) & 0xffff;
        int length = header.getInt(4);
        if (flags != 0) {
            throw new IOException("fs-meta: unsupported frame flags 0x" + Integer.toHexString(flags));
        }
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("fs-meta: frame length " + length + " outside 0.." + MAX_FRAME);
        }
        byte[] payload = new byte[length];
        readFully(channel, ByteBuffer.wrap(payload));
        return new Frame(kind, payload);
    }

    private static void writeFrame(SocketChannel channel, int kind, byte[] payload) throws IOException {
        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putShort((short) kind);
        frame.putShort((short) 0);
        frame.putInt(payload.length);
        frame.put(payload);
        frame.flip();
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target);
            if (read < 0) {
                throw new EOFException("fs-meta: socket closed");
            }
        }
    }

    private static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer littleEndian(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    private record Frame(int kind, byte[] payload) {
    }

    /** One committed generation. */
    public record Batch(long number, long parent, List<Change> changes) {
        public Batch {
            changes = List.copyOf(changes);
        }
    }

    /** One name's change; the entry itself is not exposed until a consumer needs a fingerprint field. */
    public record Change(String name, Kind kind) {
    }

    /** The change bit, not a guess from mtime. */
    public enum Kind {
        ADDED,
        CHANGED,
        DELETED
    }
}
