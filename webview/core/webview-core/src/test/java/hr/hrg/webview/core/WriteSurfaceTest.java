package hr.hrg.webview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write conversation, without a socket: which destination a request takes, what a page is told, and — the
 * part that matters most — that the digest guard runs before any editor is asked.
 *
 * <p>This is the shared surface both hosts use, so these cases are the ones a second host would otherwise have to
 * re-derive (or get wrong) on its own.
 */
class WriteSurfaceTest {

    /** A host that can take a buffer edit, and records what it was asked to do. */
    private static final class EditableHost implements EditorHost {
        final List<String> applied = new ArrayList<>();
        boolean canEdit = true;
        boolean accept = true;

        @Override
        public String name() {
            return "test-editor";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Set<String> capabilities() {
            return canEdit ? Set.of(CAP_OPEN, CAP_EDIT) : Set.of(CAP_OPEN);
        }

        @Override
        public boolean openFileAt(String absolutePath, int line, int column) {
            return true;
        }

        @Override
        public boolean applyEdit(String absolutePath, List<TextEdit> edits) {
            applied.add(absolutePath + ":" + edits.size());
            return accept;
        }
    }

    @TempDir
    Path project;

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent() == null ? project : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private WriteSurface surface(EditorHost host) {
        return new WriteSurface(new EditService(project.toString()), host);
    }

    private static String body(String filePath, String digest, boolean dryRun, String target) {
        return "{\"filePath\":\"" + filePath + "\",\"expectedDigest\":\"" + digest + "\",\"dryRun\":" + dryRun
                + (target == null ? "" : ",\"target\":\"" + target + "\"")
                + ",\"edits\":[{\"startLine\":2,\"startColumn\":1,\"endLine\":2,\"endColumn\":4,"
                + "\"newText\":\"TWO\"}]}";
    }

    @Test
    void autoPrefersTheEditorsBufferAndLeavesTheFileAlone() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] onDisk = Files.readAllBytes(file);
        EditableHost host = new EditableHost();

        WriteSurface.Answer answer = surface(host).applyEdit(
                body("src/A.java", SourceDigest.of(onDisk), false, null));

        assertEquals(200, answer.status());
        assertTrue(answer.body().contains("\"target\": \"buffer\""), answer.body());
        assertTrue(answer.body().contains("\"applied\": true"), answer.body());
        assertEquals(1, host.applied.size(), "the editor was asked exactly once");
        assertArrayEqualsBytes(onDisk, Files.readAllBytes(file));
    }

    @Test
    void aHostThatRefusesTheBufferFallsBackToTheDiskWrite() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] onDisk = Files.readAllBytes(file);
        EditableHost host = new EditableHost();
        host.accept = false;

        WriteSurface.Answer answer = surface(host).applyEdit(
                body("src/A.java", SourceDigest.of(onDisk), false, null));

        assertEquals(200, answer.status());
        assertTrue(answer.body().contains("\"applied\": true"), answer.body());
        assertFalse(answer.body().contains("\"target\": \"buffer\""), answer.body());
        assertEquals("one\nTWO\n", read(file));
    }

    @Test
    void askingForABufferNoHostCanProvideIsRefusedRatherThanWritten() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] onDisk = Files.readAllBytes(file);
        EditableHost host = new EditableHost();
        host.canEdit = false;

        WriteSurface.Answer answer = surface(host).applyEdit(
                body("src/A.java", SourceDigest.of(onDisk), false, "buffer"));

        assertEquals(409, answer.status());
        assertTrue(answer.body().contains("no-buffer-edit"), answer.body());
        assertArrayEqualsBytes(onDisk, Files.readAllBytes(file));
    }

    @Test
    void askingForDiskSkipsTheEditorEntirely() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] onDisk = Files.readAllBytes(file);
        EditableHost host = new EditableHost();

        WriteSurface.Answer answer = surface(host).applyEdit(
                body("src/A.java", SourceDigest.of(onDisk), false, "disk"));

        assertEquals(200, answer.status());
        assertTrue(host.applied.isEmpty(), "an explicit disk target must not touch the editor");
        assertEquals("one\nTWO\n", read(file));
    }

    @Test
    void anAbsentDryRunMeansProposeAndNoEditorIsAsked() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] onDisk = Files.readAllBytes(file);
        EditableHost host = new EditableHost();
        String withoutFlag = body("src/A.java", SourceDigest.of(onDisk), false, null)
                .replace(",\"dryRun\":false", "");

        WriteSurface.Answer answer = surface(host).applyEdit(withoutFlag);

        assertEquals(200, answer.status());
        assertTrue(answer.body().contains("\"applied\": false"), answer.body());
        assertTrue(answer.body().contains("unifiedDiff"), answer.body());
        assertTrue(host.applied.isEmpty(), "a proposal is not a change, in the buffer or on disk either");
        assertArrayEqualsBytes(onDisk, Files.readAllBytes(file));
    }

    @Test
    void aStaleDigestIsRefusedBeforeAnyEditorSeesTheEdits() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        String stale = SourceDigest.of(Files.readAllBytes(file));
        Files.writeString(file, "one\nchanged\n", StandardCharsets.UTF_8);
        EditableHost host = new EditableHost();

        WriteSurface.Answer answer = surface(host).applyEdit(body("src/A.java", stale, false, null));

        assertEquals(409, answer.status());
        assertTrue(answer.body().contains("\"reason\": \"stale\""), answer.body());
        assertTrue(host.applied.isEmpty(),
                "the guard is the whole safety argument: an editor must never apply edits against content the "
                        + "page did not read");
    }

    @Test
    void malformedRequestsAreRejectedWithAReason() {
        WriteSurface surface = surface(new EditableHost());

        assertEquals(400, surface.applyEdit("{ not json").status());
        assertTrue(surface.applyEdit("{ not json").body().contains("invalid-edit"));
        assertEquals(400, surface.applyEdit("{\"filePath\":\"src/A.java\"}").status());
        assertEquals(400, surface.applyEdit(body("src/A.java", "sha256:" + "0".repeat(64), false, "somewhere"))
                .status());
        assertEquals(400, surface.undo("{\"nothing\":true}").status());
    }

    @Test
    void undoAndRedoGoThroughTheSameSurface() throws IOException {
        Path file = write("src/A.java", "one\ntwo\n");
        byte[] original = Files.readAllBytes(file);
        WriteSurface surface = surface(new EditableHost());
        surface.applyEdit(body("src/A.java", SourceDigest.of(original), false, "disk"));

        assertEquals(200, surface.undo("{\"filePath\":\"src/A.java\"}").status());
        assertArrayEqualsBytes(original, Files.readAllBytes(file));
        assertEquals(200, surface.redo("{\"filePath\":\"src/A.java\"}").status());
        assertEquals("one\nTWO\n", read(file));
        assertEquals(404, surface.undo("{\"filePath\":\"src/missing.java\"}").status());
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8));
    }
}
