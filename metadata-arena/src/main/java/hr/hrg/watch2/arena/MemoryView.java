package hr.hrg.watch2.arena;

public interface MemoryView extends AutoCloseable {
    long getLong(long offset);

    void putLong(long offset, long value);

    void getLongs(long offset, long[] dst, int off, int len);

    void putLongs(long offset, long[] src, int off, int len);

    int getInt(long offset);

    void putInt(long offset, int value);

    default void getBytes(long offset, byte[] dst, int off, int len) {
        for (int i = 0; i < len; i++) {
            long l = getLong(offset + (long) i / 8);
            int shift = (7 - (i % 8)) * 8;
            dst[off + i] = (byte) ((l >> shift) & 0xFF);
        }
    }

    default void putBytes(long offset, byte[] src, int off, int len) {
        for (int i = 0; i < len; i++) {
            long l = getLong(offset + (long) i / 8);
            int shift = (7 - (i % 8)) * 8;
            l = (l & ~(0xFFL << shift)) | (((long) (src[off + i] & 0xFF)) << shift);
            putLong(offset + (long) i / 8, l);
        }
    }

    void close();
}
