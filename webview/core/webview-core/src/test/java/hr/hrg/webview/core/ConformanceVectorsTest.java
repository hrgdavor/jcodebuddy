package hr.hrg.webview.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Asserts this module's authorization and rate-limit behaviour against the vectors in
 * {@code webview/conformance/bridge-decisions.json}, which the VS Code host's Node test reads as well.
 *
 * <p>The point of the file is that the three hosts disagreed about the same policy, twice in a way that let
 * an uninvited page drive the editor. A rule that lives only in one implementation's unit tests is a rule the
 * next host can get wrong quietly; a rule with a shared table is one a host has to either implement or
 * contradict in writing.
 *
 * <p>The vectors are <b>not</b> generated from this code. Generating them would only prove this code agrees
 * with itself.
 */
public class ConformanceVectorsTest {

    /** Walks up from the working directory until it finds the vector file. */
    private static Path vectorsFile() {
        Path directory = Paths.get("").toAbsolutePath();
        List<Path> candidates = new ArrayList<>();
        for (Path current = directory; current != null; current = current.getParent()) {
            candidates.add(current.resolve("webview/conformance/bridge-decisions.json"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("webview/conformance/bridge-decisions.json not found above " + directory
                + "; this test must run from inside the repository");
    }

    private static JsonObject vectors() throws IOException {
        String json = Files.readString(vectorsFile(), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String textOrNull(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    @Test
    public void theVectorFileExistsAndIsShapedAsExpected() throws IOException {
        JsonObject vectors = vectors();

        assertTrue("the vectors must carry a note explaining what they are", vectors.has("note"));
        assertTrue("the /health key list is what stops the hosts' documents from drifting",
                vectors.has("healthKeys"));
        assertFalse("an empty vector list would make every host trivially conform",
                vectors.getAsJsonArray("allowedOrigins").isEmpty());
        assertFalse(vectors.getAsJsonArray("cors").isEmpty());
        assertFalse(vectors.getAsJsonArray("rateLimit").isEmpty());
    }

    /**
     * {@link HostHealth} must produce exactly the keys the shared list names, in that order. This is the
     * cross-language half of the parity check: the TypeScript host asserts the same list against its own
     * {@code healthDocument()}, so neither language can add a key alone.
     */
    @Test
    public void hostHealthAgreesWithTheSharedKeyList() throws IOException {
        JsonObject healthKeys = vectors().getAsJsonObject("healthKeys");
        List<String> expected = new ArrayList<>();
        for (JsonElement value : healthKeys.getAsJsonArray("keys")) {
            expected.add(value.getAsString());
        }

        String document = HostHealth.of(HostHealth.PLUGIN_JETBRAINS, 18881, 1, false,
                java.util.Set.of("open", "select")).toJson();

        assertEquals("the /health keys must match conformance/bridge-decisions.json in order",
                expected, HostHealth.keysOf(document));
        assertEquals("HostHealth's own contract list must be that list",
                expected, HostHealth.REQUIRED_KEYS);
    }

    @Test
    public void allowedOriginsAgreesWithEveryVector() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonElement element : vectors().getAsJsonArray("allowedOrigins")) {
            JsonObject vector = element.getAsJsonObject();
            String allowed = textOrNull(vector, "allowed");
            String origin = textOrNull(vector, "origin");
            boolean expected = vector.get("expected").getAsBoolean();

            boolean actual = AllowedOrigins.of(allowed).allows(origin);
            if (actual != expected) {
                failures.add("allowed='" + allowed + "' origin=" + origin
                        + " expected=" + expected + " actual=" + actual);
            }
        }
        if (!failures.isEmpty()) {
            fail("AllowedOrigins disagrees with the shared vectors:\n  " + String.join("\n  ", failures));
        }
    }

    /**
     * CORS is emitted for an allowed origin only. The rule is asserted here as "would we send a grant", which
     * is the decision both HTTP hosts make; the header spelling is each host's business.
     */
    @Test
    public void corsEmissionAgreesWithEveryVector() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonElement element : vectors().getAsJsonArray("cors")) {
            JsonObject vector = element.getAsJsonObject();
            String allowed = textOrNull(vector, "allowed");
            String origin = textOrNull(vector, "origin");
            boolean shouldSend = vector.get("sends").getAsBoolean();
            String echoes = textOrNull(vector, "echoes");

            AllowedOrigins origins = AllowedOrigins.of(allowed);
            boolean sends = origin != null && origins.allows(origin);
            if (sends != shouldSend) {
                failures.add("allowed='" + allowed + "' origin=" + origin
                        + " expected sends=" + shouldSend + " actual=" + sends);
                continue;
            }
            // A host echoes the caller's own origin; echoing a constant, or "*", is the bug this guards.
            if (sends && !origin.equals(echoes)) {
                failures.add("an allowed origin must be echoed verbatim, not replaced: "
                        + origin + " vs " + echoes);
            }
        }
        if (!failures.isEmpty()) {
            fail("CORS decisions disagree with the shared vectors:\n  " + String.join("\n  ", failures));
        }
    }

    @Test
    public void rateLimitAgreesWithEveryVector() throws IOException {
        List<String> failures = new ArrayList<>();
        for (JsonElement element : vectors().getAsJsonArray("rateLimit")) {
            JsonObject vector = element.getAsJsonObject();
            int limit = vector.get("limit").getAsInt();
            long window = vector.get("windowMillis").getAsLong();

            List<Boolean> expected = new ArrayList<>();
            for (JsonElement value : vector.getAsJsonArray("expected")) {
                expected.add(value.getAsBoolean());
            }

            TestClock clock = new TestClock();
            RateLimiter limiter = new RateLimiter(limit, window, clock);

            // tryAt[i] is the absolute offset the clock is moved to before call i, so a vector reads as a
            // timeline rather than as a series of deltas.
            List<Boolean> actual = new ArrayList<>();
            long previous = 0;
            boolean first = true;
            for (JsonElement value : vector.getAsJsonArray("tryAt")) {
                long at = value.getAsLong();
                if (!first) {
                    clock.advance(at - previous);
                }
                previous = at;
                first = false;
                actual.add(limiter.tryAcquire());
            }

            if (!expected.equals(actual)) {
                failures.add("limit=" + limit + " window=" + window
                        + " tryAt=" + vector.getAsJsonArray("tryAt")
                        + " expected=" + expected + " actual=" + actual);
            }
        }
        if (!failures.isEmpty()) {
            fail("RateLimiter disagrees with the shared vectors:\n  " + String.join("\n  ", failures));
        }
    }
}
