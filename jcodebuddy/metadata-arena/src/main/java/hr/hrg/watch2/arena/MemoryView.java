package hr.hrg.watch2.arena;

/**
 * Typed access to an arena's memory: 8-byte words and 4-byte ints by byte offset, plus byte-level access.
 *
 * <h3>How the byte-level defaults pack their bytes</h3>
 *
 * <p>{@link #getBytes} and {@link #putBytes} move eight bytes per word, and byte {@code i} of a group sits
 * at the word's <em>most</em> significant position when {@code i % 8 == 0} — so byte 0 of a word is its top
 * byte and byte 7 is its bottom one, at
 * <code>getLong(offset + (i / 8) * 8) &gt;&gt; ((7 - (i % 8)) * 8)</code>. Writing a single byte is a
 * read-modify-write of the word it lands in; the other seven bytes are untouched. A caller that needs a
 * different packing (a file header, a hash) has to say so — this is the one the interface chooses, and
 * {@code IndexMmapWriter} relies on it for the tail of a file whose size is not a multiple of eight.</p>
 *
 * <p>Both implementations must agree on the semantics, and the two reach the memory differently
 * ({@code ByteBuffer} versus an ordered {@code MemorySegment}); {@code MemoryViewTest} runs every case
 * against both. The FFM backend declares its layouts with <strong>alignment 1</strong>, because the
 * structures this view serves are packed — a 4-byte count immediately followed by {@code long} values, so a
 * word routinely starts at a 4-byte-aligned offset.</p>
 */
public interface MemoryView extends AutoCloseable {
    long getLong(long offset);

    void putLong(long offset, long value);

    void getLongs(long offset, long[] dst, int off, int len);

    void putLongs(long offset, long[] src, int off, int len);

    int getInt(long offset);

    void putInt(long offset, int value);

    default void getBytes(long offset, byte[] dst, int off, int len) {
        for (int i = 0; i < len; i++) {
            // Eight bytes per word, and the word's base is a *byte* offset: `offset + (i / 8) * 8`. The
            // earlier form, `offset + (long) i / 8`, advanced the base by one byte per byte instead of
            // eight per word, so any run longer than one word wrote into overlapping, misaligned words —
            // and every read of it came back scrambled. Nothing caught it because the only caller,
            // `IndexMmapWriter`'s tail, passes fewer than eight bytes.
            long l = getLong(offset + (long) (i / 8) * 8);
            int shift = (7 - (i % 8)) * 8;
            dst[off + i] = (byte) ((l >> shift) & 0xFF);
        }
    }

    default void putBytes(long offset, byte[] src, int off, int len) {
        for (int i = 0; i < len; i++) {
            long wordOffset = offset + (long) (i / 8) * 8;
            long l = getLong(wordOffset);
            int shift = (7 - (i % 8)) * 8;
            l = (l & ~(0xFFL << shift)) | (((long) (src[off + i] & 0xFF)) << shift);
            putLong(wordOffset, l);
        }
    }

    void close();
}
