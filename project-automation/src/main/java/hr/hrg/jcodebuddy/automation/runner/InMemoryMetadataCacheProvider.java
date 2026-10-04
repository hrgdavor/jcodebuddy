package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.jcodebuddy.meta.MetadataProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryMetadataCacheProvider implements MetadataProvider {
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @Override
    public CacheEntry get(String hash) { return entries.get(hash); }

    /**
     * Every entry the provider holds, once each.
     *
     * <p>Deduplicated because the provider keeps each entry under two keys — its content hash and its path — so a
     * raw iteration of the map reports one file twice, with the same identity, to a caller listing a project's
     * types. The distinct set is what "the entries" means.</p>
     */
    @Override
    public List<CacheEntry> listEntries() {
        return List.copyOf(new java.util.LinkedHashSet<>(entries.values()));
    }

    /**
     * Whether {@code relPath}'s content differs from the recorded {@code checksum}.
     *
     * <p>Compared against the entry's own {@link CacheEntry#hash()} — the identity the parser computed over the
     * LF-normalised bytes. It used to look for a {@code "checksum"} key inside the metadata map, which the parser
     * never wrote, so the answer was "changed" for every file on every scan: a cache that always misses, and
     * silently. The map carries the key now, but the entry's own field is what this reads, because that is where
     * the identity lives.</p>
     */
    @Override
    public boolean hasChanged(String relPath, String checksum) {
        CacheEntry entry = entries.get(relPath);
        return entry == null || entry.hash() == null || !entry.hash().equals(checksum);
    }

    /**
     * Every class name the entries know, from the facts the engine read (plan step 3.0j).
     *
     * <p>It used to answer with two invented names — {@code com.example.Foo} and {@code com.example.Bar} — which
     * is a stub that reads like data: a caller asking the server "which classes are there" got a plausible answer
     * about a project that does not exist. The names now come from the entries
     * {@link SourceMetadataParser} produced, so the answer is what this scan actually saw.</p>
     */
    @Override
    public List<String> listClasses() {
        java.util.TreeSet<String> classes = new java.util.TreeSet<>();
        for (CacheEntry entry : entries.values()) {
            Object recorded = entry.metadata() == null ? null : entry.metadata().get("classes");
            if (recorded instanceof java.util.Collection<?> names) {
                for (Object name : names) {
                    if (name != null && !name.toString().isEmpty()) {
                        classes.add(name.toString());
                    }
                }
            }
        }
        return List.copyOf(classes);
    }

    /**
     * DEC-W008's no-cache path, and the reference implementation the provider interface's javadoc points
     * at: this module is where the source reader (OpenRewrite's LST, DEC-030) is on the classpath, so
     * this is the provider that can answer {@code parse} for real.
     *
     * <p>It consults no cache — not even its own in-memory map. An implementation that returned a
     * previously cached entry for the same bytes would look identical to a caller while quietly breaking
     * the property DEC-W008 cares about: that the answer is a function of the bytes handed in, so it
     * works in a fresh checkout with no prior scan.</p>
     */
    @Override
    public CacheEntry parse(String relativePath, byte[] sourceBytes) {
        return SourceMetadataParser.parse(relativePath, sourceBytes);
    }

    public void put(String key, CacheEntry entry) { entries.put(key, entry); }

    /**
     * Records one entry under both keys it is asked by — its content hash and its path — dropping whatever the
     * path held before.
     *
     * <p>Without the removal a rescan leaves the superseded content's entry behind under its old hash, so
     * {@link #listEntries()} reports a growing set of entries for one file and a caller sees the same path several
     * times with different identities. Keeping only the current entry per path is what makes "one entry per file"
     * true of this provider; an old content hash is not worth answering for, because nothing asks about content
     * that is no longer on disk.</p>
     */
    public void putForPath(CacheEntry entry) {
        CacheEntry previous = entries.get(entry.relativePath());
        if (previous != null && !previous.hash().equals(entry.hash())) {
            entries.remove(previous.hash(), previous);
        }
        entries.put(entry.relativePath(), entry);
        entries.put(entry.hash(), entry);
    }
}
