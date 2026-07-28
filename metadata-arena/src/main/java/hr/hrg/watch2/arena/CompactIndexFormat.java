package hr.hrg.watch2.arena;

public final class CompactIndexFormat {
    public static final byte[] MAGIC = new byte[]{
        (byte) 'A', (byte) 'R', (byte) 'E', (byte) 'N',
        (byte) 'A', (byte) '0', (byte) '1', 0
    };
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 64;
    public static final int SLOT_SHIFT = 3;
    public static final float LOAD_FACTOR = 0.75f;
    public static final int INT_SIZE = 4;
    public static final int LONG_SIZE = 8;

    private CompactIndexFormat() {}
}
