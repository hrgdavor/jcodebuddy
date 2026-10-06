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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Asserts the host half of the location grammar against {@code webview/conformance/location-fragments.json} — the
 * same claims the page half ({@code scripts/webview-location}, JavaScript) asserts.
 *
 * <p>That is the point of the file: a page renders a link and a host resolves it, so if the two disagree about what
 * {@code #region:++add} means, one of them navigates somewhere wrong. A rule that lives in two languages is only one
 * rule while both are checked against the same table, and — as {@code webview/conformance/README.md} says — neither
 * reader generates the table, because generating it would only prove one implementation agrees with itself.</p>
 */
public class LocationFragmentTest {

    /** Walks up from the working directory until it finds the vector file, as the neighbouring vectors test does. */
    private static Path vectorsFile() {
        Path directory = Paths.get("").toAbsolutePath();
        for (Path current = directory; current != null; current = current.getParent()) {
            Path candidate = current.resolve("webview/conformance/location-fragments.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("webview/conformance/location-fragments.json not found above " + directory
            + "; this test must run from inside the repository");
    }

    private static JsonObject vectors() throws IOException {
        return JsonParser.parseString(Files.readString(vectorsFile(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Test
    public void theVectorsAreTheShapeThisTestReads() throws IOException {
        JsonObject vectors = vectors();
        assertEquals("another file is not the location vectors", "location-fragments",
            vectors.get("shape").getAsString());
        assertTrue("the grammar is not a handful of cases", vectors.getAsJsonArray("cases").size() > 20);
    }

    @Test
    public void everyVectorCaseParsesToWhatTheFileClaims() throws IOException {
        JsonArray cases = vectors().getAsJsonArray("cases");
        List<String> wrong = new ArrayList<>();
        for (JsonElement element : cases) {
            JsonObject vector = element.getAsJsonObject();
            String path = text(vector, "path");
            String fragment = vector.get("fragment").getAsString();
            LocationFragment actual = LocationFragment.parse(path, fragment);
            try {
                assertMatches(vector.get("expect"), actual, path + "#" + fragment);
            } catch (AssertionError failure) {
                wrong.add(failure.getMessage() + " [" + text(vector, "why") + "]");
            }
        }
        assertTrue("every vector case must hold:\n  " + String.join("\n  ", wrong), wrong.isEmpty());
    }

    /**
     * The comparison the JavaScript reader gets from a deep equality: the kind, and the fields that kind carries.
     * Only the fields of the kind are compared, because that is all a location ever means.
     */
    private static void assertMatches(JsonElement expect, LocationFragment actual, String where) {
        if (expect == null || expect.isJsonNull()) {
            assertNull(where + " is not a location", actual);
            return;
        }
        assertNotNull(where + " is a location", actual);
        JsonObject expected = expect.getAsJsonObject();
        String kind = expected.get("kind").getAsString();
        // The vectors spell kinds in lower case, because the JavaScript reader has no enum; the host's enum is
        // upper case because Java's is. One mapping, here, rather than two spellings in the table.
        assertEquals(where + " kind", LocationFragment.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)),
            actual.kind());
        switch (kind) {
            case "line" -> assertEquals(where + " line", expected.get("line").getAsInt(), actual.line());
            case "range" -> {
                assertEquals(where + " from", expected.get("from").getAsInt(), actual.from());
                assertEquals(where + " to", expected.get("to").getAsInt(), actual.to());
            }
            case "region" -> {
                assertEquals(where + " name", expected.get("name").getAsString(), actual.name());
                assertEquals(where + " scope", expected.get("scope").getAsString(), actual.scope());
            }
            case "member" -> assertEquals(where + " name", expected.get("name").getAsString(), actual.name());
            case "json" -> {
                List<String> keys = new ArrayList<>();
                expected.getAsJsonArray("keys").forEach(key -> keys.add(key.getAsString()));
                assertEquals(where + " keys", keys, actual.keys());
            }
            default -> throw new AssertionError(where + " claims an undeclared kind: " + kind);
        }
    }

    @Test
    public void everyKindTheVectorsUseIsOneThisGrammarDeclares() throws IOException {
        for (JsonElement element : vectors().getAsJsonArray("cases")) {
            JsonElement expect = element.getAsJsonObject().get("expect");
            if (expect == null || expect.isJsonNull()) {
                continue;
            }
            String kind = expect.getAsJsonObject().get("kind").getAsString();
            LocationFragment.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Test
    public void everyLocationHasASummaryAndANonLocationHasNone() throws IOException {
        for (JsonElement element : vectors().getAsJsonArray("cases")) {
            JsonObject vector = element.getAsJsonObject();
            String path = text(vector, "path");
            String fragment = vector.get("fragment").getAsString();
            LocationFragment actual = LocationFragment.parse(path, fragment);
            if (actual == null) {
                continue;
            }
            assertNotNull(path + "#" + fragment + " needs a summary for a tooltip", actual.summary());
            assertTrue(path + "#" + fragment + " needs a non-empty summary", !actual.summary().isEmpty());
        }
    }

    @Test
    public void theScopeModifiersReadAsWhatTheyMean() {
        String path = "src/main/java/com/hrg/bla/SomeFile.java";
        assertEquals("region add (body only)", LocationFragment.parse(path, "region:-add").summary());
        assertEquals("region add (with annotations)", LocationFragment.parse(path, "region:+add").summary());
        assertEquals("region add (with annotations and doc comment)",
            LocationFragment.parse(path, "region:++add").summary());
        assertEquals("region add", LocationFragment.parse(path, "region:add").summary());
        // The simple spellings say the same thing, which is the point of accepting them.
        assertEquals(LocationFragment.parse(path, "region:++add"), LocationFragment.parse(path, "++add"));
        assertEquals("lines 42\u201358", LocationFragment.parse(path, "L42-L58").summary());
        assertEquals("keys name, scripts.test", LocationFragment.parse("package.json", "region:name,scripts.test")
            .summary());
    }

    @Test
    public void anExplicitPositionNeedsNoFilePathButANameDoes() {
        assertEquals(42, LocationFragment.parse(null, "L42").line());
        assertNull("a name needs a file type to be told from a heading anchor", LocationFragment.parse(null, "add"));
        assertNull(LocationFragment.parse(null, null));
        assertNull(LocationFragment.parse("A.java", ""));
        assertNull(LocationFragment.parse("A.java", "REGION:add"));
    }
}
