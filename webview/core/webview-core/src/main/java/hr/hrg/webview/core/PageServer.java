package hr.hrg.webview.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serves a file from the project to a page, and decides which files a page may ask for.
 *
 * <p>This was the VS Code host's {@code /file/} route, which was the only one of the three hosts that had
 * one. Two things were wrong with keeping it there: the JetBrains host needs the same route (a report served
 * over {@code file://} cannot be loaded into a webview's iframe on every platform), and that route was the
 * one that answered {@code Access-Control-Allow-Origin: *} to any caller — so the code that decides what a
 * page may read is exactly the code that should not be written three times.
 *
 * <p>Pure decision and bytes: an instance is given the project root and whether confinement is required, and
 * {@link #serve(String)} returns a status, a content type and a body. Binding a socket, setting CORS headers
 * and writing the response belong to the host, which is why the JetBrains host can use this with
 * {@code com.sun.net.httpserver} and the VS Code host can use it with {@code node:http}.
 *
 * <p>{@code injectBridge} is the one host-specific difference in the body: a page served to an iframe needs
 * the bridge script appended, and only the host knows which transport that script should use. It is a
 * parameter rather than a method so the decision logic stays testable without a browser.
 */
public final class PageServer {

    /** Every route below is under this prefix. */
    public static final String ROUTE_PREFIX = "/file/";

    private final PathResolver resolver;
    private final boolean requireConfinedPaths;
    private final Map<String, String> mimeTypes;

    /**
     * @param projectRoot         the directory pages may read from; {@code null} means any file, which is
     *                            only correct for a host that has no project at all
     * @param requireConfinedPaths true for a route reachable from a browser — the caller can be any page the
     *                            user has open — and false only for a host that has already established the
     *                            caller is trusted
     */
    public PageServer(String projectRoot, boolean requireConfinedPaths) {
        this.resolver = PathResolver.forProject(projectRoot);
        this.requireConfinedPaths = requireConfinedPaths;
        this.mimeTypes = defaultMimeTypes();
    }

    /** The statuses this route can answer with, so a host maps them to its own HTTP layer by name. */
    public enum Status {
        /** The body is the file. */
        OK,
        /** The path was empty or not a path. */
        BAD_REQUEST,
        /** The path resolves outside the project. */
        FORBIDDEN,
        /** Nothing is there. */
        NOT_FOUND,
        /** A directory was asked for; this route serves files. */
        IS_A_DIRECTORY,
        /** The file exists but could not be read. */
        UNREADABLE
    }

    /**
     * One answer: what to send, and what to say about it.
     *
     * @param fileDigest the {@link SourceDigest} of the <b>file's</b> bytes, which for {@link #OK} is what a page
     *                   must send back as {@code expectedDigest} when it proposes an edit. It is the file's
     *                   digest even on the page route, where the served body has a bridge appended — the page
     *                   read the file's content, and the script tag is not part of what it is editing. Empty on
     *                   every status that has no file behind it.
     */
    public record Response(Status status, String contentType, byte[] body, String fileDigest) {

        /** An answer with no file behind it: an error, or a refusal. */
        public Response(Status status, String contentType, byte[] body) {
            this(status, contentType, body, "");
        }

        public boolean ok() {
            return status == Status.OK;
        }

        /** The status code a host should use, given the frozen meanings in the link contract. */
        public int httpStatus() {
            return switch (status) {
                case OK -> 200;
                case BAD_REQUEST -> 400;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case IS_A_DIRECTORY -> 403;
                case UNREADABLE -> 500;
            };
        }
    }

    /**
     * Serves whatever a page asked for.
     *
     * @param requestPath the full request path, including {@link #ROUTE_PREFIX}
     * @param injectBridge an optional string appended to an HTML body — the host's bridge script, or null
     */
    public Response serve(String requestPath, String injectBridge) {
        if (requestPath == null || requestPath.isBlank()) {
            return new Response(Status.BAD_REQUEST, "text/plain", bytes("Invalid file path"));
        }
        // A query string is not part of the path; a host that hands over the raw request target gets it
        // stripped here rather than being trusted to have done so.
        String withoutQuery = requestPath;
        int query = withoutQuery.indexOf('?');
        if (query >= 0) {
            withoutQuery = withoutQuery.substring(0, query);
        }
        int fragment = withoutQuery.indexOf('#');
        if (fragment >= 0) {
            withoutQuery = withoutQuery.substring(0, fragment);
        }
        if (!withoutQuery.startsWith(ROUTE_PREFIX)) {
            return new Response(Status.BAD_REQUEST, "text/plain", bytes("Invalid file path"));
        }

        // Decoded as a whole path rather than per segment: encodeURIComponent leaves "/" alone, so a page
        // that encodes each segment produces the same string as one that encodes the path, and a "%2F"
        // written by hand is rejected below because the decoded path is then jailed like any other.
        String decoded = percentDecode(withoutQuery.substring(ROUTE_PREFIX.length()));
        if (decoded.isBlank()) {
            return new Response(Status.BAD_REQUEST, "text/plain", bytes("Invalid file path"));
        }

        PathResolution resolution = resolver.resolve(decoded);
        if (resolution == null) {
            return new Response(Status.BAD_REQUEST, "text/plain", bytes("Invalid file path"));
        }
        if (requireConfinedPaths && resolution.escaped()) {
            return new Response(Status.FORBIDDEN, "text/plain",
                    bytes("Forbidden: '" + decoded + "' is outside the project"));
        }

        Path file = Path.of(resolution.absolute());
        if (!Files.exists(file)) {
            return new Response(Status.NOT_FOUND, "text/plain", bytes("File not found: " + decoded));
        }
        if (Files.isDirectory(file)) {
            return new Response(Status.IS_A_DIRECTORY, "text/plain", bytes("Directories not supported"));
        }

        String contentType = contentTypeOf(file.getFileName().toString());
        try {
            byte[] bytes = Files.readAllBytes(file);
            // Computed from the file, before any bridge is appended: the page edits the file, not the page.
            String digest = SourceDigest.of(bytes);
            if (injectBridge != null && !injectBridge.isEmpty() && contentType.startsWith("text/html")) {
                bytes = bytes(new String(bytes, StandardCharsets.UTF_8) + injectBridge);
            }
            return new Response(Status.OK, contentType, bytes, digest);
        } catch (IOException e) {
            return new Response(Status.UNREADABLE, "text/plain",
                    bytes("Error serving file: " + e.getMessage()));
        }
    }

    /** The content type for a file name, from the same table every host used, now in one place. */
    public String contentTypeOf(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return mimeTypes.getOrDefault(fileName.substring(dot).toLowerCase(Locale.ROOT),
                "application/octet-stream");
    }

    /** True when this server confines what a page may read to the project. */
    public boolean requireConfinedPaths() {
        return requireConfinedPaths;
    }

    private static Map<String, String> defaultMimeTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put(".html", "text/html");
        types.put(".htm", "text/html");
        types.put(".js", "text/javascript");
        types.put(".mjs", "text/javascript");
        types.put(".css", "text/css");
        types.put(".json", "application/json");
        types.put(".png", "image/png");
        types.put(".jpg", "image/jpeg");
        types.put(".jpeg", "image/jpeg");
        types.put(".gif", "image/gif");
        types.put(".svg", "image/svg+xml");
        types.put(".ico", "image/x-icon");
        types.put(".txt", "text/plain");
        types.put(".md", "text/plain");
        types.put(".xml", "text/xml");
        types.put(".pdf", "application/pdf");
        types.put(".woff", "font/woff");
        types.put(".woff2", "font/woff2");
        return Map.copyOf(types);
    }

    /**
     * Percent-decodes a URL path as UTF-8. Unlike {@code URLDecoder}, a {@code +} stays a {@code +}: in a
     * path segment it is a literal character, and turning it into a space is how a request for a file with a
     * plus in its name silently reads a different file.
     */
    static String percentDecode(String encoded) {
        if (encoded.indexOf('%') < 0) {
            return encoded;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            // Two hex digits must follow, so i + 2 has to be a valid index.
            if (c == '%' && i + 2 < encoded.length()) {
                int high = Character.digit(encoded.charAt(i + 1), 16);
                int low = Character.digit(encoded.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 3;
                    continue;
                }
            }
            byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(raw, 0, raw.length);
            i++;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The capability keys a host with a page server can add to its {@link HostHealth}. */
    public static final Set<String> CAPABILITIES = Set.of("serveFile");
}
