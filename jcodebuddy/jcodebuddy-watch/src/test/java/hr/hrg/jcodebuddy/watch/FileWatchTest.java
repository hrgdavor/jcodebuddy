package hr.hrg.jcodebuddy.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FileWatchTest {

    @Test
    void reportsCreateModifyAndDeleteAfterTheDebounce(@TempDir Path root) throws Exception {
        Path target = root.resolve("target");
        Files.createDirectories(target);
        Files.writeString(target.resolve("ignored.java"), "ignored\n");

        List<WatchBatch> batches = new CopyOnWriteArrayList<>();
        FileWatch watch = new FileWatch(root, PathFilter.of(List.of("**/*.java"), List.of("**/target/**")),
                60, batches::add);
        watch.start();
        try {
            Path file = root.resolve("A.java");
            Files.writeString(file, "class A {}\n");
            await(() -> changed(batches, "A.java"), "create");

            batches.clear();
            Files.writeString(file, "class A { int x; }\n");
            await(() -> changed(batches, "A.java"), "modify");

            batches.clear();
            Files.delete(file);
            await(() -> deleted(batches, "A.java"), "delete");
        } finally {
            watch.stop();
        }
    }

    @Test
    void startAndStopAreSafeToRepeat(@TempDir Path root) throws Exception {
        FileWatch watch = new FileWatch(root, PathFilter.of(List.of("**/*.java"), List.of()), 50, batch -> { });
        watch.start();
        watch.start();
        watch.stop();
        watch.stop();
    }

    private static boolean changed(List<WatchBatch> batches, String name) {
        return batches.stream().anyMatch(batch -> batch.changed().contains(name));
    }

    private static boolean deleted(List<WatchBatch> batches, String name) {
        return batches.stream().anyMatch(batch -> batch.deleted().contains(name));
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "the watcher did not report " + what + " within 8 s");
    }
}
