package hr.hrg.watch2.arena;

import java.nio.ByteOrder;

/**
 * A contiguous region of memory handed out sequentially, with a view for reading and writing it.
 *
 * <h3>What a view is, and the one thing to know about growth</h3>
 *
 * <p>{@link #view()} covers the arena's <strong>whole</strong> storage, not just the part
 * {@link #allocate} has handed out — both backends behave this way, and callers depend on it: an index can
 * write a fixed header region before it allocates anything.</p>
 *
 * <p>That storage can be <em>replaced</em> when an allocation needs more room (both implementations copy
 * into a larger region and carry on). Contents survive, but a {@link MemoryView} obtained before the growth
 * still points at the old storage, so writes through it are lost. <strong>Take the view after the last
 * allocation that may grow</strong>; {@code ArenaTest} pins both halves of this — that content survives,
 * and that only the fresh view speaks for the arena.</p>
 *
 * <p>{@link #close()} is idempotent on both implementations, because closing twice is normal when a
 * caller's resource and the arena it handed to another object are both closed. {@link #reset()} returns
 * the allocation cursor to zero; it is refused on a read-only arena, as is {@link #allocate}.</p>
 */
public interface Arena extends AutoCloseable {
    long allocate(long bytes);

    long size();

    MemoryView view();

    void reset();

    void close();

    ByteOrder byteOrder();
}
