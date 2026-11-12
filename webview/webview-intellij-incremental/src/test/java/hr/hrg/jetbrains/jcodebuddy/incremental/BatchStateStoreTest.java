package hr.hrg.jetbrains.jcodebuddy.incremental;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BatchStateStoreTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void roundTripsTheBatchRootAndSignature() throws IOException {
        BatchStateStore store = new BatchStateStore(temporary.getRoot().toPath());
        assertNull(store.load());

        store.save(42, "D:\\work\\project", "roots:abc");

        BatchStateStore.State state = store.load();
        assertEquals(42, state.batch());
        assertEquals("D:\\work\\project", state.daemonRoot());
        assertEquals("roots:abc", state.sourceSignature());
    }

    @Test
    public void newerMarkerReplacesThePreviousOne() throws IOException {
        BatchStateStore store = new BatchStateStore(temporary.getRoot().toPath());
        store.save(1, "root", "signature");
        store.save(2, "root", "signature");
        assertEquals(2, store.load().batch());
    }
}
