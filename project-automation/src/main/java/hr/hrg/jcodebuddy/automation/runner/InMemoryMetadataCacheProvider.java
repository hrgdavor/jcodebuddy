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

    @Override
    public List<CacheEntry> listEntries() { return new ArrayList<>(entries.values()); }

    @Override
    public boolean hasChanged(String relPath, String checksum) {
        CacheEntry e = entries.get(relPath);
        if (e == null) return true;
        Object stored = e.metadata().get("checksum");
        return stored == null || !stored.equals(checksum);
    }

    @Override
    public List<String> listClasses() { return List.of("com.example.Foo", "com.example.Bar"); }

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
}
