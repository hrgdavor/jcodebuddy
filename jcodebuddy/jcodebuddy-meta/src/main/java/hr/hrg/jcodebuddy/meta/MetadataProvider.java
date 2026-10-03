package hr.hrg.jcodebuddy.meta;

import java.util.List;
import java.util.Map;

/**
 * The cache-access contract the metadata server, the MCP tool surface and the automation runner all
 * read through.
 *
 * <h3>Two paths to the same entry shape</h3>
 *
 * <p>Four methods answer from a <em>cache</em>: {@link #get}, {@link #listEntries},
 * {@link #hasChanged} and {@link #listClasses}. {@link #parse(String, byte[])} answers from the
 * <em>source bytes themselves</em>, with no cache anywhere in the picture — the manual-mode and
 * single-file path that DEC-W008 fixes. Both produce the same {@link CacheEntry}, so a consumer never
 * has to know which one served it.</p>
 */
public interface MetadataProvider {

    /**
     * The entry stored under {@code hash}, or {@code null}.
     *
     * @param hash the wayhash of the file's content (see {@link CacheEntry#hash()})
     */
    CacheEntry get(String hash);

    /** Every entry the provider holds, in no particular order. */
    List<CacheEntry> listEntries();

    /**
     * Whether {@code relPath}'s content differs from the recorded {@code checksum}.
     *
     * @param relPath  the project-relative path, forward slashes
     * @param checksum the checksum to test against the recorded one
     */
    boolean hasChanged(String relPath, String checksum);

    /** Every fully qualified class name the provider knows about. */
    List<String> listClasses();

    /**
     * DEC-W008's no-cache path: a fully populated {@link CacheEntry} derived from the file's own bytes.
     *
     * <p>The returned entry carries the wayhash of {@code sourceBytes} (CRLF-normalised first, the
     * repository's one content-identity rule — DEC-029 § 4), the input {@code relativePath}, the primary
     * type's fully qualified name, and a metadata map of facts derived from <strong>this file
     * alone</strong>. Cross-file facts — resolved references, annotation inventories, anything needing a
     * second source file — are deliberately absent: DEC-W008 puts them outside {@code parse}, in the
     * relation store DEC-W009 describes.</p>
     *
     * <p><strong>The call is pure.</strong> It must not read or write a cache, an index or any other
     * backing store, so the same bytes always produce the same entry and a caller may use it in a fresh
     * checkout with no daemon and no prior scan.</p>
     *
     * <h3>The default, and the amendment it records</h3>
     *
     * <p>The default implementation throws {@link MetadataParseUnsupportedException}. DEC-W008
     * originally required the default to "work correctly regardless of whether overriding exists",
     * which assumed every provider could reach a Java parser; the module graph does not allow that —
     * {@code metadata-server} deliberately has no source reader (DEC-030 puts the LST in
     * {@code hipster-entity-tooling}), so a default that parses cannot exist in this module. The
     * decision's own "Implementation boundaries" section anticipated the split ("whether {@code parse}
     * lives in {@code metadata-server} or {@code project-automation} depends on module dependency
     * resolution") and named {@code InMemoryMetadataCacheProvider} as the override; that is what
     * happened, and DEC-W008 carries the amendment.</p>
     *
     * @param relativePath the project-relative path, forward slashes, used as the entry's identity in
     *                     reports — it is passed through, never resolved against a filesystem
     * @param sourceBytes  the file's bytes, exactly as read (line endings included; normalisation is
     *                     part of the checksum, not of the caller's duty)
     * @return the entry for those bytes; never {@code null}
     * @throws MetadataParseUnsupportedException when this provider has no source parser
     */
    default CacheEntry parse(String relativePath, byte[] sourceBytes) {
        throw new MetadataParseUnsupportedException(getClass().getName());
    }

    /**
     * One cache entry: the file's identity, its primary type, and the metadata parsed from its bytes.
     *
     * @param hash         16 lowercase hex characters, the wayhash of the LF-normalised content
     *                     ({@code ContentHash.ALGO}; DEC-029 § 4)
     * @param fullClassName the primary type's fully qualified name, or an empty string when the source
     *                     declares no type or could not be read
     * @param relativePath the project-relative path, forward slashes — the input, never a resolved
     *                     absolute path
     * @param metadata     the file-scoped facts; never {@code null} for a valid source
     */
    record CacheEntry(String hash, String fullClassName, String relativePath, Map<String, Object> metadata) {
    }
}
