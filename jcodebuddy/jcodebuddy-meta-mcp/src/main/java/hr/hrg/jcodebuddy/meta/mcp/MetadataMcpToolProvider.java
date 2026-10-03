package hr.hrg.jcodebuddy.meta.mcp;

import hr.hrg.jcodebuddy.meta.MetadataProvider;
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
            tool("list_classes", "List all fully-qualified class names in cache"),
            tool("parse_file", "Parse one file's source text with no cache involved (DEC-W008)")
        );
    }

    public void register(McpServer.SingleSessionSyncSpecification builder) {
        builder.toolCall(tool("get_entry", "Return the full cache entry by wayhash"), this::getEntry);
        builder.toolCall(tool("list_entries", "List all entries in the metadata cache"), this::listEntries);
        builder.toolCall(tool("get_metadata", "Return parsed metadata tree for a hash"), this::getMetadata);
        builder.toolCall(tool("has_changed", "Whether the file has changed since last cache update"), this::hasChanged);
        builder.toolCall(tool("list_classes", "List all fully-qualified class names in cache"), this::listClasses);
        builder.toolCall(tool("parse_file", "Parse one file's source text with no cache involved (DEC-W008)"),
                this::parseFile);
    }

    private Tool tool(String name, String desc) {
        return Tool.builder(name)
            .description(desc)
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "hash", Map.of("type", "string", "description", "File wayhash"),
                    "relPath", Map.of("type", "string", "description", "Relative file path"),
                    "checksum", Map.of("type", "string", "description", "File checksum"),
                    // Only `parse_file` reads this, and it is in the shared schema for the same reason the
                    // other three are: one schema for the surface keeps a tool's arguments discoverable,
                    // and an argument no tool reads is inert rather than wrong.
                    "source", Map.of("type", "string",
                            "description", "The file's source text, for parse_file (no cache is consulted)")
                )
            ))
            .build();
    }

    // The handlers are package-private rather than private on purpose. The wiring is register() above:
    // one explicit toolCall per tool, each naming its handler method, so the mapping is navigable and
    // there is no name-to-handler registry to keep in step. This module's test then calls each handler
    // directly; a test forced to assemble an McpSyncServer to reach them would be testing the SDK.

    CallToolResult getEntry(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String hash = (String) args.get("hash");
        if (hash == null) return err("Missing 'hash' parameter");
        Object result = provider.get(hash);
        return ok(result);
    }

    CallToolResult listEntries(McpSyncServerExchange exchange, CallToolRequest request) {
        return ok(provider.listEntries());
    }

    CallToolResult getMetadata(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String hash = (String) args.get("hash");
        if (hash == null) return err("Missing 'hash' parameter");
        MetadataProvider.CacheEntry entry = provider.get(hash);
        if (entry == null) return ok(null);
        return ok(entry.metadata());
    }

    CallToolResult hasChanged(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String relPath = (String) args.get("relPath");
        String checksum = (String) args.get("checksum");
        if (relPath == null || checksum == null) return err("Missing 'relPath' or 'checksum'");
        return ok(provider.hasChanged(relPath, checksum));
    }

    CallToolResult listClasses(McpSyncServerExchange exchange, CallToolRequest request) {
        return ok(provider.listClasses());
    }

    /**
     * DEC-W008's no-cache path over MCP, additive like the RPC method of the same name.
     *
     * <p>A provider with no parser reports
     * {@link hr.hrg.jcodebuddy.meta.MetadataParseUnsupportedException}; it is caught here and
     * returned as a tool <em>error</em> rather than an exception, because an MCP tool that throws gives
     * the caller a broken session instead of an answer it can act on.</p>
     */
    CallToolResult parseFile(McpSyncServerExchange exchange, CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String relPath = (String) args.get("relPath");
        Object source = args.get("source");
        if (relPath == null) return err("Missing 'relPath' parameter");
        if (!(source instanceof String text)) return err("Missing 'source' parameter: the file's text");
        try {
            return ok(provider.parse(relPath,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (hr.hrg.jcodebuddy.meta.MetadataParseUnsupportedException e) {
            return err(e.getMessage());
        }
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
