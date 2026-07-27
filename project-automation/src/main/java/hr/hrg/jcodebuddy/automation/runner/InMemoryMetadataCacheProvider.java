package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.watch2.server.metadata.MetadataProvider;
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

    public void put(String key, CacheEntry entry) { entries.put(key, entry); }
}
