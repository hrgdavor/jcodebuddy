package hr.hrg.hipster.entity.tooling.index;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file's content identity: {@code Wyhash64} over its bytes, with CRLF normalised to LF first
 * (DEC-029).
 *
 * <h3>Why the normalisation is not optional</h3>
 * <p>A CRLF checkout and an LF checkout of the <em>same</em> content must produce the same checksum, or
 * the table becomes a property of the developer's git configuration rather than of the code. The
 * watch agent already normalises (see {@code ChecksumDatabase.normalizeLineEndings}), so this class
 * copies that <strong>behaviour</strong>, not just the algorithm name: {@code \r\n} becomes {@code \n},
 * and a lone {@code \r} becomes {@code \n}.</p>
 *
 * <h3>Why the algorithm is not chosen here</h3>
 * <p>{@code hr.hrg.wyhash:wyhash:1.0.0} is what {@code java-watch-core}'s {@code ChecksumDatabase} and
 * {@code java-watch-agent}'s {@code MetadataCache} hash text files with, and DEC-028 reserved
 * <em>one</em> content-identity table for the whole repository (plan.metadata-locations.md § 2.3.2).
 * The tooling must not depend on {@code project-automation} (DEC-W003), so the algorithm is vendored
 * in {@link Wyhash64} and pinned to library-produced golden vectors by {@code ContentHashTest}.</p>
 *
 * <p><strong>Never a correctness input.</strong> A missing, unreadable or algorithm-mismatched table
 * forces a full pass; the {@code hash} header of the table names both the algorithm and the
 * normalisation, and a mismatch is reported rather than guessed.</p>
 */
public final class ContentHash {

    /** The {@code hash.algo} header value this build writes. Changing it invalidates every table. */
    public static final String ALGO = "wyhash64";

    /** The {@code hash.normalize} header value this build writes. */
    public static final String NORMALIZE = "lf";

    private ContentHash() {
    }

    /**
     * The checksum of {@code file}'s content: 16 lowercase hex characters.
     *
     * @throws IOException when the file cannot be read — a row with an invented checksum would be a
     *                     claim about content nobody looked at
     */
    public static String of(Path file) throws IOException {
        byte[] content = normalizeLineEndings(Files.readAllBytes(file));
        return of(content);
    }

    /** The checksum of content that is already LF-normalised: 16 lowercase hex characters. */
    public static String of(byte[] normalisedContent) {
        return String.format("%016x", Wyhash64.hash(0, normalisedContent));
    }

    /**
     * {@code \r\n} and lone {@code \r} become {@code \n}; every other byte is untouched.
     *
     * <p>The same transformation {@code ChecksumDatabase} performs, and it is applied before hashing
     * rather than to the file on disk: the index describes the checkout it is generated in and must
     * describe an identical checkout elsewhere the same way.</p>
     */
    public static byte[] normalizeLineEndings(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        int i = 0;
        while (i < content.length) {
            if (i + 1 < content.length && content[i] == '\r' && content[i + 1] == '\n') {
                out.write('\n');
                i += 2;
            } else if (content[i] == '\r') {
                out.write('\n');
                i += 1;
            } else {
                out.write(content[i]);
                i += 1;
            }
        }
        return out.toByteArray();
    }

    /** UTF-8 text as the bytes this class hashes — the test's and the reader's convenience. */
    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
