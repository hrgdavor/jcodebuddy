package hr.hrg.jcodebuddy.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteCoordinatorTest {

    @Test
    void appliesOnceAndRefusesAStaleSecondProposal(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {}\n");
        WriteCoordinator coordinator = new WriteCoordinator(root);
        FileProposal proposal = FileProposal.change(file, coordinator.currentHash(file),
                Files.readString(file), "class A { int x; }\n", "A.java:field");
        assertTrue(coordinator.apply(proposal).ok());
        assertEquals("class A { int x; }\n", Files.readString(file));
        assertFalse(coordinator.apply(proposal).ok());
        assertEquals(ApplyResult.Status.STALE, coordinator.apply(proposal).status());
    }

    @Test
    void rejectsAChangeItDidNotWrite(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        WriteCoordinator coordinator = new WriteCoordinator(root);
        FileProposal proposal = FileProposal.change(file, coordinator.currentHash(file),
                Files.readString(file), "class A { int x; }\n", "A.java:field");
        coordinator.apply(proposal);
        Files.writeString(file, "class A { int y; }\n");
        assertEquals(ApplyResult.Status.REFUSED, coordinator.revert(proposal).status());
        assertEquals("class A { int y; }\n", Files.readString(file));
    }

    @Test
    void refusesAConflictingGeneratorOwner(@TempDir Path root) throws Exception {
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        WriteCoordinator coordinator = new WriteCoordinator(root);
        String hash = coordinator.currentHash(file);
        FileProposal first = FileProposal.change(file, hash, "class A {}\n",
                "class A { int x; }\n", "first").owned("generic-tool:builder");
        assertTrue(coordinator.apply(first).ok());
        FileProposal second = FileProposal.change(file, coordinator.currentHash(file),
                "class A { int x; }\n", "class A { int y; }\n", "second").owned("other");
        assertEquals(ApplyResult.Status.REFUSED, coordinator.apply(second).status());
    }

    @Test
    void rejectsAPathOutsideTheProject(@TempDir Path root) {
        WriteCoordinator coordinator = new WriteCoordinator(root);
        FileProposal proposal = FileProposal.change(root.resolve("..").resolve("outside.java"),
                "", "", "x", "outside");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> coordinator.apply(proposal));
    }
}
