package hr.hrg.watch2.server.metadata.rpc;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class MetadataRpcService {
    private static final Logger log = LoggerFactory.getLogger(MetadataRpcService.class);

    private final MetadataProvider provider;

    public MetadataRpcService(MetadataProvider provider) {
        this.provider = provider;
    }

    @RpcMethod("getEntry")
    public Object getEntry(Map<String, Object> params) {
        String hash = (String) params.get("hash");
        if (hash == null) throw new IllegalArgumentException("Missing 'hash' parameter");
        return provider.get(hash);
    }

    @RpcMethod("listEntries")
    public List<MetadataProvider.CacheEntry> listEntries(Map<String, Object> params) {
        return provider.listEntries();
    }

    @RpcMethod("getMetadata")
    public Object getMetadata(Map<String, Object> params) {
        String hash = (String) params.get("hash");
        if (hash == null) throw new IllegalArgumentException("Missing 'hash' parameter");
        MetadataProvider.CacheEntry entry = provider.get(hash);
        if (entry == null) return null;
        return entry.metadata();
    }

    @RpcMethod("hasChanged")
    public Boolean hasChanged(Map<String, Object> params) {
        String relPath = (String) params.get("relPath");
        String checksum = (String) params.get("checksum");
        if (relPath == null || checksum == null) throw new IllegalArgumentException("Missing 'relPath' or 'checksum'");
        return provider.hasChanged(relPath, checksum);
    }

    @RpcMethod("listClasses")
    public List<String> listClasses(Map<String, Object> params) {
        return provider.listClasses();
    }
}
