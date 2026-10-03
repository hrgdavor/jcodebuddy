package hr.hrg.jcodebuddy.meta.rpc;

import hr.hrg.jcodebuddy.meta.MetadataProvider;
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

    /**
     * DEC-W008's no-cache path over RPC: parse the given source text and return the entry.
     *
     * <p>Additive, as DEC-W008 requires: the cache-backed methods above are untouched, and a client that
     * never calls this cannot tell it exists. It is the route a manual-mode caller takes — send the file's
     * text, get metadata back — so it works with no daemon, no cache folder and no prior scan.</p>
     *
     * <p>The source arrives as text rather than bytes because both transports carry JSON/JSON-RPC
     * parameters; the service encodes it as UTF-8, which is the encoding the checksum is defined over
     * (DEC-029 § 4). A provider with no parser answers with
     * {@link hr.hrg.jcodebuddy.meta.MetadataParseUnsupportedException}, which the dispatcher turns
     * into a JSON-RPC error whose message names the provider — not a silent {@code null}.</p>
     */
    @RpcMethod("parseFile")
    public MetadataProvider.CacheEntry parseFile(Map<String, Object> params) {
        String relPath = (String) params.get("relPath");
        Object source = params.get("source");
        if (relPath == null) {
            throw new IllegalArgumentException("Missing 'relPath' parameter");
        }
        if (!(source instanceof String text)) {
            throw new IllegalArgumentException("Missing 'source' parameter: the file's text");
        }
        return provider.parse(relPath, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
