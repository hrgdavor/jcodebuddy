package hr.hrg.eclipse.webview.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import hr.hrg.webview.core.Clock;
import hr.hrg.webview.core.EditorHost;
import hr.hrg.webview.core.RateLimiter;
import hr.hrg.webview.core.SourceDigest;
import hr.hrg.webview.core.TextEdit;

/**
 * The write contract over a live socket (Phase 3): the plan's § 11 gates that need no human — a
 * stale digest is refused with no bytes changed, an applied edit is undone byte-for-byte, an edit
 * outside the project is refused, and the checkpoint that makes the undo survive is <b>persistent</b>:
 * the undo still works after the bridge closed and a fresh one started, which is the part the
 * JetBrains host's in-memory store does not claim.
 *
 * <p>The editor half is a recording host, so what is asserted here is the routing core's
 * {@code WriteSurface} performs — buffer preferred, the documented disk fallback when the seam
 * declines, the frozen statuses. The real {@code DocumentBufferEditor} against a real workbench
 * (unsaved change, one {@code Ctrl+Z} restores) is the observed half of the gate and lives in
 * {@code ide-observation-checklist.md}, not here.
 */
class EclipseWriteApiTest {

    /** The file every test starts from, and the edit every test proposes: {@code two} → {@code TWO}. */
    private static final String ORIGINAL = "one\ntwo\n";
    private static final String EDITED = "one\nTWO\n";

    /**
     * The editor-host stand-in for the write routes: it declares {@code edit} or not, and it accepts
     * a buffer edit or declines it — the two dials that decide which of {@code WriteSurface}'s three
     * answers a request gets.
     */
    private static final class EditableHost implements EditorHost {
        final List<String> applied = new CopyOnWriteArrayList<>();
        private final boolean canEdit;
        private final boolean accepts;

        EditableHost(boolean canEdit, boolean accepts) {
            this.canEdit = canEdit;
            this.accepts = accepts;
        }

        @Override
        public String name() {
            return "fake";
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
            if (!accepts) {
                return false;
            }
            applied.add(absolutePath);
            return true;
        }
    }

    /** One running bridge plus the project it serves, so a test can look at the bytes behind it. */
    private record Fixture(EclipseHttpBridge bridge, EditableHost host, Path project, Path file) {
    }

    private final List<EclipseHttpBridge> started = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void stopEverything() {
        for (EclipseHttpBridge bridge : started) {
            bridge.close();
        }
        started.clear();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Fixture start(boolean canEdit, boolean accepts) throws IOException {
        Path project = Files.createTempDirectory("jcb-eclipse-write");
        Files.createDirectories(project.resolve("src"));
        Path file = project.resolve("src").resolve("A.java");
        Files.writeString(file, ORIGINAL, StandardCharsets.UTF_8);
        EditableHost host = new EditableHost(canEdit, accepts);
        EclipseHttpBridge.Outcome outcome = EclipseHttpBridge.start(project, freePort(), host,
                new RateLimiter(20, 20_000L, Clock.SYSTEM), "", "Eclipse-Test");
        assertTrue(outcome.serving(), outcome.note());
        started.add(outcome.bridge());
        return new Fixture(outcome.bridge(), host, project, file);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private HttpResponse<String> post(EclipseHttpBridge bridge, String pathAndQuery, String body,
                                      String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(bridge.baseUrl() + pathAndQuery))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            request.header("X-WebView-Token", token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(EclipseHttpBridge bridge, String pathAndQuery) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(bridge.baseUrl() + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The proposal/apply body for {@code two} → {@code TWO} on {@code src/A.java}. */
    private static String applyBody(String filePath, String digest, Boolean dryRun, String target) {
        JsonObject body = new JsonObject();
        body.addProperty("filePath", filePath);
        if (digest != null) {
            body.addProperty("expectedDigest", digest);
        }
        if (dryRun != null) {
            body.addProperty("dryRun", dryRun);
        }
        if (target != null) {
            body.addProperty("target", target);
        }
        JsonObject edit = new JsonObject();
        edit.addProperty("startLine", 2);
        edit.addProperty("startColumn", 1);
        edit.addProperty("endLine", 2);
        edit.addProperty("endColumn", 4);
        edit.addProperty("newText", "TWO");
        JsonArray edits = new JsonArray();
        edits.add(edit);
        body.add("edits", edits);
        return body.toString();
    }

    private static String undoBody(String filePath) {
        JsonObject body = new JsonObject();
        body.addProperty("filePath", filePath);
        return body.toString();
    }

    private static String digestOf(Fixture f) throws IOException {
        return SourceDigest.of(Files.readAllBytes(f.file()));
    }

    // --- the disk half ----------------------------------------------------------------------------

    @Test
    void theProposalFlowProposesThenAppliesThenUndoesThenRedoes() throws Exception {
        Fixture f = start(false, true);
        String digest = digestOf(f);
        String token = f.bridge().token();

        HttpResponse<String> diff = post(f.bridge(), "/api/v1/diff",
                applyBody("src/A.java", digest, null, null), token);
        assertEquals(200, diff.statusCode(), diff.body());
        assertTrue(diff.body().contains("\"applied\": false"), diff.body());
        assertTrue(diff.body().contains("-two"), diff.body());
        assertTrue(diff.body().contains("+TWO"), diff.body());
        assertEquals(ORIGINAL, Files.readString(f.file()), "a proposal writes nothing");

        HttpResponse<String> apply = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("src/A.java", digest, false, "disk"), token);
        assertEquals(200, apply.statusCode(), apply.body());
        assertTrue(apply.body().contains("\"applied\": true"), apply.body());
        assertEquals(EDITED, Files.readString(f.file()));

        HttpResponse<String> undo = post(f.bridge(), "/api/v1/undo", undoBody("src/A.java"), token);
        assertEquals(200, undo.statusCode(), undo.body());
        assertEquals(ORIGINAL, Files.readString(f.file()), "undo restores the bytes exactly");

        HttpResponse<String> redo = post(f.bridge(), "/api/v1/redo", undoBody("src/A.java"), token);
        assertEquals(200, redo.statusCode(), redo.body());
        assertEquals(EDITED, Files.readString(f.file()));
    }

    @Test
    void aStaleDigestIsRefusedWith409AndNoByteChanges() throws Exception {
        Fixture f = start(false, true);

        // A well-formed digest that is not the file's: the format guard must pass for the staleness
        // guard to be what refuses.
        HttpResponse<String> stale = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("src/A.java", "sha256:" + "0".repeat(64), false, null), f.bridge().token());

        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("\"reason\": \"stale\""), stale.body());
        assertEquals(ORIGINAL, Files.readString(f.file()), "a stale proposal changes nothing, ever");
    }

    @Test
    void anOutsideProjectPathIsRefusedWith403AndChangesNothing() throws Exception {
        Fixture f = start(false, true);

        HttpResponse<String> outside = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("../outside.java", "sha256:" + "0".repeat(64), false, null),
                f.bridge().token());

        assertEquals(403, outside.statusCode(), outside.body());
        assertTrue(outside.body().contains("\"reason\": \"outside-project\""), outside.body());
        assertFalse(Files.exists(f.project().getParent().resolve("outside.java")),
                "the path jail is the point: nothing may be written beside the project either");
    }

    @Test
    void aMalformedBodyIsRefusedWith400() throws Exception {
        Fixture f = start(false, true);

        HttpResponse<String> bad = post(f.bridge(), "/api/v1/applyEdit", "not json", f.bridge().token());

        assertEquals(400, bad.statusCode(), bad.body());
        assertTrue(bad.body().contains("\"reason\": \"invalid-edit\""), bad.body());
    }

    // --- the buffer half ---------------------------------------------------------------------------

    @Test
    void aBufferEditLeavesTheDiskAlone() throws Exception {
        Fixture f = start(true, true);

        HttpResponse<String> apply = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("src/A.java", digestOf(f), false, null), f.bridge().token());

        assertEquals(200, apply.statusCode(), apply.body());
        assertTrue(apply.body().contains("\"target\": \"buffer\""), apply.body());
        assertEquals(ORIGINAL, Files.readString(f.file()),
                "the buffer edit must not touch the disk: the reader decides when the file changes (R17)");
        assertEquals(1, f.host().applied.size());
        assertTrue(f.host().applied.get(0).replace('\\', '/').endsWith("src/A.java"),
                f.host().applied.get(0));
    }

    @Test
    void aDeclinedBufferFallsBackToTheHostsOwnDiskWrite() throws Exception {
        // The seam's false — a path the platform holds no document for — is not an error: core's
        // WriteSurface takes its documented next step and writes the file itself, with the
        // persistent checkpoint that makes the write undoable. The asymmetry belongs to the shared
        // surface, not to this host (E8: "WriteSurface decides, not the plugin").
        Fixture f = start(true, false);

        HttpResponse<String> apply = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("src/A.java", digestOf(f), false, null), f.bridge().token());

        assertEquals(200, apply.statusCode(), apply.body());
        assertTrue(apply.body().contains("\"applied\": true"), apply.body());
        assertFalse(apply.body().contains("\"target\": \"buffer\""), apply.body());
        assertEquals(EDITED, Files.readString(f.file()));
        assertTrue(journalEntries(f).findAny().isPresent(),
                "a disk write leaves a checkpoint state, not only an in-memory one");
    }

    @Test
    void anExplicitBufferRequestWithoutTheCapabilityIsRefusedWith409() throws Exception {
        Fixture f = start(false, true);

        HttpResponse<String> refused = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("src/A.java", digestOf(f), false, "buffer"), f.bridge().token());

        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("\"reason\": \"no-buffer-edit\""), refused.body());
        assertEquals(ORIGINAL, Files.readString(f.file()), "a refusal writes nothing");
    }

    // --- persistence across a restart ---------------------------------------------------------------

    @Test
    void undoSurvivesARestartBecauseTheCheckpointIsPersistent() throws Exception {
        Fixture f = start(false, true);
        HttpResponse<String> apply = post(f.bridge(), "/api/v1/applyEdit",
                applyBody("src/A.java", digestOf(f), false, null), f.bridge().token());
        assertEquals(200, apply.statusCode(), apply.body());
        assertEquals(EDITED, Files.readString(f.file()));
        int firstPort = f.bridge().port();
        f.bridge().close();

        // A fresh bridge over the same project: the port comes from the descriptor the first start
        // published, the token from the file it wrote, and the undo from the checkpoint journal —
        // nothing of the first process is left but what is on disk.
        EclipseHttpBridge.Outcome restarted = EclipseHttpBridge.start(f.project(), null,
                new EditableHost(false, true),
                new RateLimiter(20, 20_000L, Clock.SYSTEM), "", "Eclipse-Test");
        assertTrue(restarted.serving(), restarted.note());
        started.add(restarted.bridge());
        assertEquals(firstPort, restarted.bridge().port(), "the descriptor's current port is what a restart asks for");

        HttpResponse<String> undo = post(restarted.bridge(), "/api/v1/undo",
                undoBody("src/A.java"), restarted.bridge().token());
        assertEquals(200, undo.statusCode(), undo.body());
        assertTrue(undo.body().contains("\"applied\": true"), undo.body());
        assertEquals(ORIGINAL, Files.readString(f.file()),
                "the Phase 3 gate: byte-for-byte undo after a restart of the host");
    }

    /** The journal's layout is {@code checkpoints/<hash16>/NNNN-<kind>.entry} — one subdirectory per file. */
    private static Stream<Path> journalEntries(Fixture f) throws IOException {
        Path checkpoints = f.project().resolve(".jcodebuddy").resolve("webview").resolve("checkpoints");
        if (!Files.isDirectory(checkpoints)) {
            return Stream.empty();
        }
        try (Stream<Path> entries = Files.walk(checkpoints)) {
            return entries.filter(path -> path.toString().endsWith(".entry")).toList().stream();
        }
    }

    // --- the route surface --------------------------------------------------------------------------

    @Test
    void theQueryTokenAuthorizesAWriteToo() throws Exception {
        Fixture f = start(false, true);

        HttpResponse<String> proposal = post(f.bridge(),
                "/api/v1/diff?token=" + f.bridge().token(),
                applyBody("src/A.java", digestOf(f), null, null), null);

        assertEquals(200, proposal.statusCode(), proposal.body());
        assertTrue(proposal.body().contains("unifiedDiff"), proposal.body());
    }

    @Test
    void anUnknownWriteRouteIs404RatherThan405() throws Exception {
        Fixture f = start(false, true);

        HttpResponse<String> unknown = post(f.bridge(), "/api/v1/notAVerb", "{}", f.bridge().token());

        assertEquals(404, unknown.statusCode(),
                "only a route that exists can be a method mistake: " + unknown.body());
    }

    @Test
    void theEventStreamIsRefusedBecauseNoWatchCapabilityIsDeclared() throws Exception {
        Fixture f = start(false, true);

        HttpResponse<String> events = get(f.bridge(), "/api/v1/events?token=" + f.bridge().token());

        assertEquals(404, events.statusCode());
        assertTrue(events.body().contains("watch"), events.body());
    }

    @Test
    void writesAndNavigationDrawOnOneBudget() throws Exception {
        // The constructor's claim, observed: the write surface was handed the SAME limiter the
        // navigator uses, so a page that spins on writes cannot outspend a page that spins on
        // clicks — the budget is one (production: 20 per 20 seconds).
        Fixture f = start(false, true);
        String token = f.bridge().token();
        String digest = digestOf(f);

        for (int i = 0; i < 10; i++) {
            assertEquals(200, post(f.bridge(), "/api/v1/diff",
                    applyBody("src/A.java", digest, null, null), token).statusCode());
            assertEquals(200, get(f.bridge(),
                    "/open?filePath=src%2FA.java&line=1&column=1&token=" + token).statusCode());
        }
        HttpResponse<String> oneTooMany = post(f.bridge(), "/api/v1/diff",
                applyBody("src/A.java", digest, null, null), token);

        assertEquals(429, oneTooMany.statusCode(), oneTooMany.body());
        assertTrue(oneTooMany.body().contains("\"reason\": \"rate-limited\""), oneTooMany.body());
    }
}
