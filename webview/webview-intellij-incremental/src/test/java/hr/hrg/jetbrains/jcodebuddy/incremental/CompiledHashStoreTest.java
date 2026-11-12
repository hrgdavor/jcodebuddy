package hr.hrg.jetbrains.jcodebuddy.incremental;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CompiledHashStoreTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void hashesChangeWithContentAndRoundTrip() throws Exception {
        Path project = temporary.getRoot().toPath();
        Path source = project.resolve("A.java");
        Files.writeString(source, "class A {}");
        String first = CompiledHashStore.hash(source);
        Files.writeString(source, "class A { int x; }");
        String second = CompiledHashStore.hash(source);
        assertNotEquals(first, second);

        CompiledHashStore store = new CompiledHashStore(project);
        store.save("signature", Map.of(source, second));
        CompiledHashStore.State loaded = store.load();
        assertEquals("signature", loaded.signature());
        assertEquals(second, loaded.hashes().get(source));
        assertTrue(Files.isRegularFile(store.file()));
    }
}
