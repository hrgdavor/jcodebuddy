package hr.hrg.watch2.server.metadata;

import java.util.List;
import java.util.Map;

public interface MetadataProvider {
    CacheEntry get(String hash);
    List<CacheEntry> listEntries();
    boolean hasChanged(String relPath, String checksum);
    List<String> listClasses();

    public record CacheEntry(String hash, String fullClassName, String relativePath, Map<String, Object> metadata) {
    }
}
