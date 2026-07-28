package hr.hrg.watch2.arena;

import java.nio.ByteOrder;

public interface Arena extends AutoCloseable {
    long allocate(long bytes);

    long size();

    MemoryView view();

    void reset();

    void close();

    ByteOrder byteOrder();
}
