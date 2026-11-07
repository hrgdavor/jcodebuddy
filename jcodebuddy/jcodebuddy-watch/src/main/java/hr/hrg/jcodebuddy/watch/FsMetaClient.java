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
 * diff, no regeneration decision. Its one policy input is the protocol's own consumer filter, sent with the
 * extended {@code SUBSCRIBE} ({@code FORMAT.socket.md} § SUBSCRIBE filter patterns), so a consumer interested in one
 * subfolder of a served root asks the daemon for it instead of filtering a monorepo's whole stream itself.</p>
 *
 * <p>Only the frames a consumer needs are interpreted. {@code GIT_EVENT} is consumed without changing the batch
 * state, {@code PING} is answered, and an {@code ERROR} raises a {@link ProtocolException} that carries the numeric
 * code, so a consumer can branch on {@code batch-too-old}/{@code batch-gap} instead of parsing a message. A
 * {@code STATE} frame is kept on the client ({@link #lastState()}) because a one-shot consumer must know whether
 * the daemon was <em>waking</em>: the wake scan's batch is published after the greeting, so stopping at the
 * greeted generation would miss a change made while the root slept — {@link #replayUntilSettled(long)} is the
 * one-shot read that waits for the settled phase. An unknown kind raises rather than being skipped — the
 * protocol's own rule, because a skipped change entry is a silently wrong index.</p>
 */
public final class FsMetaClient implements AutoCloseable {

    /** The fixed protocol major/minor the daemon speaks. */
    public static final int PROTOCOL = 0x0102;
    /** Ask {@link #connect(Path, long)} to subscribe after the daemon's current generation. */
    public static final long LATEST = -1;

    /** {@code ERROR} code 1, {@code protocol}: a malformed frame or an unserviceable {@code SUBSCRIBE}. */
    public static final int ERROR_PROTOCOL = 1;
    /** {@code ERROR} code 2, {@code batch-too-old}: the replay cannot start where the client asked. */
    public static final int ERROR_BATCH_TOO_OLD = 2;
    /** {@code ERROR} code 3, {@code batch-gap}: a generation in the replay range has no batch. */
    public static final int ERROR_BATCH_GAP = 3;

    /** {@code STATE} code 0: never watch; a subscribe is answered with one scan. */
    public static final int STATE_OFF = 0;
    /** {@code STATE} code 1: no watcher and no reason to watch; the last generation is kept. */
    public static final int STATE_ASLEEP = 1;
    /** {@code STATE} code 2: a scan is running and its batch will follow. */
    public static final int STATE_WAKING = 2;
    /** {@code STATE} code 3: the watcher is armed. */
    public static final int STATE_WATCHING = 3;

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
    private volatile State lastState;

    private FsMetaClient(SocketChannel channel, long serverGeneration) {
        this.channel = channel;
        this.serverGeneration = serverGeneration;
    }

    /**
     * Connects, greets, and subscribes to every change under the root.
     *
     * @param socket      the root's {@code .fs-meta/socket}
     * @param fromBatch   the last batch already applied, or {@link #LATEST} to start at the daemon's current head
     */
    public static FsMetaClient connect(Path socket, long fromBatch) throws IOException {
        return connect(socket, fromBatch, List.of(), List.of());
    }

    /**
     * Connects, greets, and subscribes with the protocol's consumer-side filter
     * ({@code FORMAT.socket.md} § SUBSCRIBE filter patterns).
     *
     * <p>The patterns are workspace-relative and use git's {@code wildmatch}: a name must match an include to be
     * sent, a matching exclude suppresses it, and an empty pair is the original pass-all form. The filter is
     * <strong>server-side</strong>: the daemon still tracks every file and still publishes every generation; it sends
     * only the matching {@code CHANGE_ENTRY} frames. A generation that matches nothing therefore produces no batch
     * at all, which is why a consumer that needs "did anything change" compares
     * {@link #serverGeneration()} rather than waiting for a batch.</p>
     *
     * @param socket   the root's consumer socket
     * @param fromBatch the last batch already applied, or {@link #LATEST} to start at the daemon's current head
     * @param includes patterns a name must match at least one of; empty means "everything"
     * @param excludes patterns that suppress a matching name even when it is included; empty means none
     */
    public static FsMetaClient connect(Path socket, long fromBatch, List<String> includes, List<String> excludes)
            throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(socket));
            writeFrame(channel, KIND_HELLO, hello(1));
            long generation = readHello(channel);
            FsMetaClient client = new FsMetaClient(channel, generation);
            long subscribeFrom = fromBatch == LATEST ? generation : fromBatch;
            writeFrame(channel, KIND_SUBSCRIBE, subscribe(subscribeFrom, includes, excludes));
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

    /** The last {@code STATE} frame this subscription received, or {@code null} when the peer sent none. */
    public State lastState() {
        return lastState;
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
                case KIND_STATE -> lastState = state(frame.payload);
                case KIND_GIT_EVENT -> {
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
                    if (number < 0) {
                        throw new IOException("fs-meta: END_BATCH without a START_BATCH");
                    }
                    return committed(number, parent, changes, frame.payload);
                }
                case KIND_ERROR -> throw error(frame.payload);
                default -> throw new IOException("fs-meta: unknown frame kind 0x" + Integer.toHexString(frame.kind));
            }
        }
    }

    /**
     * The one-shot read: every batch after {@code fromBatch}, up to the generation the daemon is at when it has
     * <strong>settled</strong>.
     *
     * <p>This exists because the greeting's generation is not the end of the story. A root that was asleep sends
     * {@code STATE(waking)} and then runs its wake scan, publishing that scan as a batch <em>after</em> the
     * greeting: a consumer that stopped at the greeted generation would answer "nothing changed" for an edit made
     * while the daemon was asleep, which is the one wrong answer. So the read does not stop until the daemon
     * reports a settled phase ({@code watching} or {@code off}) and every batch up to that {@code STATE}'s
     * {@code head_batch} has arrived. A peer that sends no {@code STATE} at all (the replay-only
     * {@code fsmeta serve}) is read only up to the greeted generation, which is all it has.</p>
     *
     * <p>Returns the batches, their changes in wire order, and the generation they reached. A protocol refusal
     * is thrown, never turned into an empty replay.</p>
     */
    public Replay replayUntilSettled(long fromBatch) throws IOException {
        List<Batch> batches = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        long reached = fromBatch;
        long head = serverGeneration;
        boolean settled = false;
        boolean stateSeen = false;
        long number = -1;
        long parent = -1;
        List<Change> current = new ArrayList<>();
        while (true) {
            Frame frame = readFrame(channel);
            switch (frame.kind) {
                case KIND_HELLO -> {
                }
                case KIND_STATE -> {
                    State state = state(frame.payload);
                    lastState = state;
                    stateSeen = true;
                    head = Math.max(head, state.headBatch());
                    if (state.settled()) {
                        settled = true;
                    }
                }
                case KIND_GIT_EVENT -> {
                }
                case KIND_PING -> writeFrame(channel, KIND_PONG, new byte[0]);
                case KIND_PONG -> {
                }
                case KIND_START_BATCH -> {
                    ByteBuffer payload = littleEndian(frame.payload);
                    number = payload.getLong();
                    parent = payload.getLong();
                    current.clear();
                }
                case KIND_CHANGE_ENTRY -> current.add(change(frame.payload));
                case KIND_END_BATCH -> {
                    if (number < 0) {
                        throw new IOException("fs-meta: END_BATCH without a START_BATCH");
                    }
                    Batch batch = committed(number, parent, current, frame.payload);
                    batches.add(batch);
                    changes.addAll(batch.changes());
                    reached = Math.max(reached, batch.number());
                }
                case KIND_ERROR -> throw error(frame.payload);
                default -> throw new IOException("fs-meta: unknown frame kind 0x" + Integer.toHexString(frame.kind));
            }
            // Settled and caught up: the wake scan, if there was one, has been read. Without any STATE (a peer
            // that does not implement the frame) the greeted generation is all the peer has, which is the
            // pre-STATE behaviour every deployed client already had.
            if (settled && reached >= head) {
                return new Replay(reached, batches, changes);
            }
            if (!stateSeen && reached >= head) {
                return new Replay(reached, batches, changes);
            }
        }
    }

    /** Validates one {@code END_BATCH} against the entries received and builds the committed batch. */
    private static Batch committed(long number, long parent, List<Change> changes, byte[] endPayload)
            throws IOException {
        ByteBuffer payload = littleEndian(endPayload);
        long ended = payload.getLong();
        long added = payload.getLong();
        long changed = payload.getLong();
        long deleted = payload.getLong();
        if (ended != number) {
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
            return new ProtocolException(0, "");
        }
        int code = littleEndian(payload).getShort() & 0xffff;
        String message = payload.length > 2
                ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8)
                : "";
        return new ProtocolException(code, message);
    }

    /**
     * One {@code STATE} frame: {@code u8 state}, {@code u8 reason}, {@code u32 idle_timeout_ms},
     * {@code u64 head_batch} ({@code FORMAT.socket.md} § STATE), fourteen bytes.
     */
    private static State state(byte[] payload) throws IOException {
        if (payload.length < 14) {
            throw new IOException("fs-meta: STATE shorter than 14 bytes: " + payload.length);
        }
        ByteBuffer buffer = littleEndian(payload);
        int code = buffer.get() & 0xff;
        int reason = buffer.get() & 0xff;
        long idleTimeoutMs = buffer.getInt() & 0xffffffffL;
        long headBatch = buffer.getLong();
        return new State(code, reason, idleTimeoutMs, headBatch);
    }

    /**
     * The {@code SUBSCRIBE} payload: the original 8-byte form when there is no filter, else the extended form of
     * {@code FORMAT.socket.md} § SUBSCRIBE filter patterns — {@code u64 from_batch}, {@code u16 include_count},
     * the include patterns as {@code u16 length + bytes}, {@code u16 exclude_count}, the excludes the same way.
     *
     * <p>An empty pair sends the 8-byte form rather than an extended payload with two zero counts: the format says
     * both mean pass-all, and the shorter form is what every existing daemon has always read.</p>
     */
    private static byte[] subscribe(long fromBatch, List<String> includes, List<String> excludes) {
        List<byte[]> includePatterns = patterns(includes, "include");
        List<byte[]> excludePatterns = patterns(excludes, "exclude");
        if (includePatterns.isEmpty() && excludePatterns.isEmpty()) {
            return littleEndian(8).putLong(fromBatch).array();
        }
        int size = 8 + 2 + sizeOf(includePatterns) + 2 + sizeOf(excludePatterns);
        ByteBuffer payload = littleEndian(size);
        payload.putLong(fromBatch);
        payload.putShort((short) includePatterns.size());
        for (byte[] pattern : includePatterns) {
            payload.putShort((short) pattern.length);
            payload.put(pattern);
        }
        payload.putShort((short) excludePatterns.size());
        for (byte[] pattern : excludePatterns) {
            payload.putShort((short) pattern.length);
            payload.put(pattern);
        }
        return payload.array();
    }

    /** The UTF-8 bytes of each pattern, refusing one the protocol's {@code u16} length cannot carry. */
    private static List<byte[]> patterns(List<String> patterns, String kind) {
        List<byte[]> encoded = new ArrayList<>();
        if (patterns == null) {
            return encoded;
        }
        for (String pattern : patterns) {
            if (pattern == null) {
                throw new IllegalArgumentException("a null " + kind + " pattern is not a filter");
            }
            byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0xffff) {
                throw new IllegalArgumentException("the " + kind + " pattern is " + bytes.length
                        + " bytes, longer than the protocol's 65535-byte limit: " + pattern);
            }
            encoded.add(bytes);
        }
        if (encoded.size() > 0xffff) {
            throw new IllegalArgumentException("too many " + kind + " patterns: " + encoded.size());
        }
        return encoded;
    }

    private static int sizeOf(List<byte[]> patterns) {
        int size = 0;
        for (byte[] pattern : patterns) {
            size += 2 + pattern.length;
        }
        return size;
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

    /**
     * One {@code STATE} frame, as the daemon reports it.
     *
     * @param code          {@link #STATE_OFF}, {@link #STATE_ASLEEP}, {@link #STATE_WAKING} or {@link #STATE_WATCHING}
     * @param reason        why the phase changed (0 none, 1 a client subscribed, 2 idle timeout, 3 control, 4 watcher)
     * @param idleTimeoutMs the root's current idle timeout; {@code 0} = it never sleeps
     * @param headBatch     the newest batch the daemon considers published
     */
    public record State(int code, int reason, long idleTimeoutMs, long headBatch) {

        /** Whether a wake scan is running: its batch will arrive after the greeting this state came with. */
        public boolean waking() {
            return code == STATE_WAKING;
        }

        /** Whether no scan is pending, so {@link #headBatch()} is the generation the tree is at. */
        public boolean settled() {
            return code == STATE_OFF || code == STATE_ASLEEP || code == STATE_WATCHING;
        }
    }

    /**
     * One one-shot replay: its batches and changes in wire order, and the generation they reached.
     *
     * @param head    the newest generation read, at least the greeted one
     * @param batches the committed batches, in order
     * @param changes every change entry those batches carried, in order
     */
    public record Replay(long head, List<Batch> batches, List<Change> changes) {

        public Replay {
            batches = List.copyOf(batches);
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

    /**
     * An {@code ERROR} frame, with the protocol's numeric code kept so the caller branches on the number and never
     * on the message text ({@code FORMAT.socket.md} § "Values this implementation pins").
     *
     * <p>{@link #code()} is {@link #ERROR_PROTOCOL}, {@link #ERROR_BATCH_TOO_OLD} or {@link #ERROR_BATCH_GAP} for
     * the three codes the format defines; {@code 0} means the frame carried no code at all (a malformed frame, not
     * a defined refusal). The consumer's obligation is explicit: a replay that is too old or has a gap falls back
     * to a full read, never a silently skipped batch.</p>
     */
    public static final class ProtocolException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int code;

        public ProtocolException(int code, String message) {
            super("fs-meta: ERROR " + code + (message == null || message.isEmpty() ? "" : ": " + message));
            this.code = code;
        }

        /** The {@code u16} code from the payload, or {@code 0} when the frame did not carry one. */
        public int code() {
            return code;
        }

        /** Whether the replay cannot continue where the client asked, so a full read is the only safe answer. */
        public boolean needsFullRead() {
            return code == ERROR_BATCH_TOO_OLD || code == ERROR_BATCH_GAP;
        }
    }
}
