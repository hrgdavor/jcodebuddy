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
 * {@code #add/++} means, one of them navigates somewhere wrong. A rule that lives in two languages is only one
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
            case "line" -> {
                assertEquals(where + " line", expected.get("line").getAsInt(), actual.line());
                assertColumn(where, expected, actual);
            }
            case "range" -> {
                assertEquals(where + " from", expected.get("from").getAsInt(), actual.from());
                assertEquals(where + " to", expected.get("to").getAsInt(), actual.to());
                assertColumn(where, expected, actual);
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

    /**
     * A position carries the column it starts at, and a vector that does not name one means the contract's default —
     * which is also why the JavaScript reader omits the key unless a page wrote a column, and the two readers still
     * agree.
     */
    private static void assertColumn(String where, JsonObject expected, LocationFragment actual) {
        int column = expected.has("column") ? expected.get("column").getAsInt() : 1;
        assertEquals(where + " column", column, actual.column());
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
        // A modifier is a whole trailing segment in the reference grammar (2026-10-13): 'add-', 'add+' and 'add++'
        // are names, and the leading forms are a name ('-add') or a refusal ('+add') - so these are the spellings.
        assertEquals("region add (body only)", LocationFragment.parse(path, "add/-").summary());
        assertEquals("region add (with annotations)", LocationFragment.parse(path, "add/+").summary());
        assertEquals("region add (with annotations and doc comment)",
            LocationFragment.parse(path, "add/++").summary());
        // A name with no modifier is a member: a declaration, or a heading anchor in a document.
        assertEquals("member add", LocationFragment.parse(path, "add").summary());
        assertEquals("member add-", LocationFragment.parse(path, "add-").summary());
        assertEquals("member -add", LocationFragment.parse(path, "-add").summary());
        assertEquals("lines 42\u201358", LocationFragment.parse(path, "L42-L58").summary());
        assertEquals("keys name, scripts.test", LocationFragment.parse("package.json", "name,scripts.test")
            .summary());
    }

    @Test
    public void aPositionTheReferenceRefusesIsNotALocation() {
        // The reference implementation reads lines and columns from 1 and refuses a range that ends before it starts,
        // so these are a page's mistake rather than something to clamp (2026-10-13). A link carrying one falls back
        // to the file, which is the contract's rule for a place that does not resolve.
        String path = "src/main/java/com/hrg/bla/SomeFile.java";
        assertNull(LocationFragment.parse(path, "L0"));
        assertNull(LocationFragment.parse(path, "L58-L42"));
        assertNull(LocationFragment.parse(path, "L42-"));
        // What the reference reads as more than one place is not one location here either.
        assertNull(LocationFragment.parse(path, "add,regionName"));
        assertNull(LocationFragment.parse(path, "Cart/^,Cart/$"));
    }

    @Test
    public void aPositionCarriesTheColumnItStartsAt() {
        // A position is a line and a column, and the page reads both by splitting on ':' - the same spelling the
        // reference implementation reads, so the host and the page cannot disagree about where a caret goes.
        String path = "src/main/java/com/hrg/bla/SomeFile.java";
        LocationFragment atColumn = LocationFragment.parse(path, "L42:12");
        assertNotNull(atColumn);
        assertEquals(LocationFragment.Kind.LINE, atColumn.kind());
        assertEquals(42, atColumn.line());
        assertEquals(12, atColumn.column());
        assertEquals("line 42, column 12", atColumn.summary());

        LocationFragment rangeAtColumn = LocationFragment.parse(path, "L42:12-L44:3");
        assertNotNull(rangeAtColumn);
        assertEquals(LocationFragment.Kind.RANGE, rangeAtColumn.kind());
        assertEquals(42, rangeAtColumn.from());
        assertEquals(44, rangeAtColumn.to());
        assertEquals("a range starts at its column; its end column is not carried",
            12, rangeAtColumn.column());

        // A position with no column keeps the column the contract defaults to, and says nothing extra.
        assertEquals(1, LocationFragment.parse(path, "L42").column());
        assertEquals("line 42", LocationFragment.parse(path, "L42").summary());

        // A colon that is NOT a position is still scheme-like, and still not a location.
        assertNull(LocationFragment.parse(path, "mailto:dev@example.com"));
    }

    @Test
    public void anExplicitPositionNeedsNoFilePathButANameDoes() {
        assertEquals(42, LocationFragment.parse(null, "L42").line());
        assertNull("a name needs a file type to be told from a heading anchor", LocationFragment.parse(null, "add"));
        assertNull(LocationFragment.parse(null, null));
        assertNull(LocationFragment.parse("A.java", ""));
        assertNull("a scheme-like fragment is not a location",
            LocationFragment.parse("A.java", "mailto:dev@example.com"));
    }
}
