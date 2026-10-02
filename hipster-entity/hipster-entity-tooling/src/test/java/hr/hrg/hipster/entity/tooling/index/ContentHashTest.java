package hr.hrg.hipster.entity.tooling.index;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * The content hash, pinned to <strong>library-produced goldens</strong> (DEC-029 § "the content hash").
 *
 * <h3>Why these particular numbers</h3>
 * <p>{@link ContentHash} uses a vendored copy of {@code hr.hrg.wyhash:wyhash:1.0.0}, because the watch
 * agent's own tables ({@code java-watch-core}'s {@code ChecksumDatabase}, {@code java-watch-agent}'s
 * {@code MetadataCache}) hash text files with that algorithm and DEC-028 reserved <em>one</em> content
 * identity for the repository. A copy can silently diverge from the library, and then two tables disagree
 * about what "the same content" means — the exact failure the single-hash reservation existed to
 * prevent.</p>
 *
 * <p>So the vectors below were produced by the LIBRARY, not by this copy:</p>
 *
 * <pre>
 *   hr.hrg.wyhash.Wyhash64.hash(0, bytes)  formatted as %016x, JDK 25
 * </pre>
 *
 * <p>If {@link Wyhash64} is ever regenerated from a newer upstream release, these vectors must be
 * regenerated with the same release — and the algorithm name in the table's {@code hash} header, and
 * DEC-029, must be revisited with it. A self-derived vector would prove nothing, which is why this test
 * does not compute its expectations.</p>
 */
class ContentHashTest {

    /** The fixture text of the large vector: 900 repetitions of {@code 0123456789ab}, i.e. 10 800 bytes. */
    private static String bigFixture() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            sb.append("0123456789ab");
        }
        return sb.toString();
    }

    /** Library-produced vectors, by fixture name. */
    @Test
    void theVendoredAlgorithmMatchesTheLibraryVectorForVector() {
        assertVector("empty", ContentHash.of(new byte[0]), "0409638ee2bde459");
        assertVector("a", ContentHash.of(ContentHash.utf8("a")), "28d2053309d28531");
        assertVector("lf (12 bytes)", ContentHash.of(ContentHash.utf8("line1\nline2\n")),
                "775e9cc0de7360a1");
        assertVector("crlf (14 bytes, normalised to the lf fixture)",
                ContentHash.of(ContentHash.normalizeLineEndings(ContentHash.utf8("line1\r\nline2\r\n"))),
                "775e9cc0de7360a1");
        assertVector("cr only (12 bytes, normalised to the lf fixture)",
                ContentHash.of(ContentHash.normalizeLineEndings(ContentHash.utf8("line1\rline2\r"))),
                "775e9cc0de7360a1");
        assertVector("exactly 16 bytes (the library's short-input path)",
                ContentHash.of(ContentHash.utf8("0123456789abcdef")), "c304e72c387cd229");
        assertVector("17 bytes (the first block path)",
                ContentHash.of(ContentHash.utf8("0123456789abcdefg")), "b496f8f306600195");
        assertVector("exactly 48 bytes (one full block)",
                ContentHash.of(ContentHash.utf8("0123456789abcdef0123456789abcdef0123456789abcdef")),
                "f838bb251f427d71");
        assertVector("49 bytes (one block plus one)",
                ContentHash.of(ContentHash.utf8("0123456789abcdef0123456789abcdef0123456789abcdef0")),
                "3fbbd2637f1bb7e0");
        assertVector("10 800 bytes (the multi-block path)",
                ContentHash.of(ContentHash.utf8(bigFixture())), "ed30b1305711a8dd");
    }

    private static void assertVector(String fixture, String actual, String libraryValue) {
        Assertions.assertEquals(libraryValue, actual,
                "the vendored Wyhash64 must reproduce the library's own hash of " + fixture
                        + ". If this fails, the copy has diverged from hr.hrg.wyhash:wyhash:1.0.0 and the "
                        + "class index's checksums are no longer comparable with the watch agent's tables.");
    }

    /**
     * The normalisation is what makes a CRLF checkout agree with an LF one.
     *
     * <p>This is the property the whole field rests on: hash the bytes as they are on disk and the table
     * becomes a property of the developer's git configuration rather than of the code.</p>
     */
    @Test
    void crlfAndLfFormsOfTheSameTextHashEqual() {
        String lf = "package a;\n\nclass A {\n}\n";
        String crlf = "package a;\r\n\r\nclass A {\r\n}\r\n";
        Assertions.assertEquals(ContentHash.of(ContentHash.utf8(lf)),
                ContentHash.of(ContentHash.normalizeLineEndings(ContentHash.utf8(crlf))),
                "a CRLF checkout and an LF checkout of the same content must hash the same, or the table "
                        + "is only valid on the machine that wrote it");
    }

    /** Lone {@code \r} becomes {@code \n} too — the same behaviour the watch agent's normaliser has. */
    @Test
    void aLoneCarriageReturnIsNormalised() {
        Assertions.assertEquals(ContentHash.of(ContentHash.utf8("a\nb\n")),
                ContentHash.of(ContentHash.normalizeLineEndings(ContentHash.utf8("a\rb\r"))),
                "the normaliser must match ChecksumDatabase.normalizeLineEndings, not merely handle CRLF");
    }

    /** Bytes that are not part of a line ending are untouched. */
    @Test
    void otherBytesAreUntouched() {
        byte[] utf8 = "héllo — ünïcode\n".getBytes(StandardCharsets.UTF_8);
        assertVector("no line endings to change", ContentHash.of(ContentHash.normalizeLineEndings(utf8)),
                ContentHash.of(utf8));
    }

    /** Two different contents do not hash the same — the field's whole purpose. */
    @Test
    void differentContentHashesDifferently() {
        Assertions.assertNotEquals(ContentHash.of(ContentHash.utf8("class A {}")),
                ContentHash.of(ContentHash.utf8("class B {}")),
                "a checksum that cannot tell two files apart is not a content identity");
    }

    /** The header the table publishes names the algorithm and the normalisation this build writes. */
    @Test
    void theHeaderNamesTheAlgorithmAndTheNormalisation() {
        Assertions.assertEquals("wyhash64", ContentHash.ALGO,
                "the table's `hash.algo` is a contract with the watch agent's tables; changing it "
                        + "invalidates every table and belongs in a decision");
        Assertions.assertEquals("lf", ContentHash.NORMALIZE,
                "and `hash.normalize` must say the content is normalised before hashing");
    }
}
