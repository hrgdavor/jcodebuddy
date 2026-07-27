package hr.hrg.watch2.server.metadata.mcp;

import hr.hrg.watch2.server.metadata.MetadataProvider;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.json.McpJsonDefaults;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.function.BiFunction;

public class MetadataMcpToolProvider {
    private final MetadataProvider provider;
    private final JsonMapper mapper;

    public MetadataMcpToolProvider(MetadataProvider provider, JsonMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    public List<Tool> tools() {
        return List.of(
            tool("get_entry", "Return the full cache entry by wayhash"),
            tool("list_entries", "List all entries in the metadata cache"),
            tool("get_metadata", "Return parsed metadata tree for a hash"),
            tool("has_changed", "Whether the file has changed since last cache update"),
            tool("list_classes", "List all fully-qualified class names in cache")
        );
    }

    public void register(McpServer.SingleSessionSyncSpecification builder) {
        builder.toolCall(tool("get_entry", "Return the full cache entry by wayhash"), this::getEntry);
        builder.toolCall(tool("list_entries", "List all entries in the metadata cache"), this::listEntries);
        builder.toolCall(tool("get_metadata", "Return parsed metadata tree for a hash"), this::getMetadata);
        builder.toolCall(tool("has_changed", "Whether the file has changed since last cache update"), this::hasChanged);
        builder.toolCall(tool("list_classes", "List all fully-qualified class names in cache"), this::listClasses);
    }

    private Tool tool(String name, String desc) {
        return Tool.builder(name)
            .description(desc)
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "hash", Map.of("type", "string", "description", "File wayhash"),
                    "relPath", Map.of("type", "string", "description", "Relative file path"),
                    "checksum", Map.of("type", "string", "description", "File checksum")
                )
            ))
            .build();
    }

    private CallToolResult getEntry(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String hash = (String) args.get("hash");
        if (hash == null) return err("Missing 'hash' parameter");
        Object result = provider.get(hash);
        return ok(result);
    }

    private CallToolResult listEntries(McpSyncServerExchange exchange, CallToolRequest request) {
        return ok(provider.listEntries());
    }

    private CallToolResult getMetadata(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String hash = (String) args.get("hash");
        if (hash == null) return err("Missing 'hash' parameter");
        MetadataProvider.CacheEntry entry = provider.get(hash);
        if (entry == null) return ok(null);
        return ok(entry.metadata());
    }

    private CallToolResult hasChanged(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String relPath = (String) args.get("relPath");
        String checksum = (String) args.get("checksum");
        if (relPath == null || checksum == null) return err("Missing 'relPath' or 'checksum'");
        return ok(provider.hasChanged(relPath, checksum));
    }

    private CallToolResult listClasses(McpSyncServerExchange exchange, CallToolRequest request) {
        return ok(provider.listClasses());
    }

    private CallToolResult ok(Object content) {
        return CallToolResult.builder()
            .content(List.of(new TextContent(mapper.valueToTree(content).toString())))
            .build();
    }

    private CallToolResult err(String msg) {
        return CallToolResult.builder()
            .content(List.of(new TextContent(msg)))
            .isError(true)
            .build();
    }
}
