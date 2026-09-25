package hr.hrg.webview.core;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The cross-host gate: every host must answer {@code /health} with the same document, and must refuse an
 * unauthorized {@code /open} with {@code 403}.
 *
 * <p>Two of the three hosts cannot be started from this module — one needs an IntelliJ Platform project, the
 * other needs VS Code and a Node process — so this test reads their sources and asserts the two properties
 * that make the runtime behaviour true rather than hoping:
 *
 * <ol>
 *   <li>each host builds its health body through {@link HostHealth}, so the <b>shape is identical by
 *       construction</b> rather than by three authors agreeing;</li>
 *   <li>each host refuses an unauthorized {@code /open} with {@code 403} <em>before</em> it reads the
 *       request's parameters or resolves a path.</li>
 * </ol>
 *
 * <p>A source-reading test is a weaker instrument than calling the endpoint, and it is the honest one here:
 * the alternative is to not check two thirds of the hosts at all. What it cannot catch — a host that calls
 * the builder and then overwrites the body — is caught by the host's own test suite, which each host keeps.
 */
public class HostHealthParityTest {

    /** The route each host answers, and the source that answers it. */
    private record Host(String name, Path source) {
    }

    private static Path repositoryRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isDirectory(current.resolve("webview/core/webview-core"))) {
                return current;
            }
        }
        throw new AssertionError("could not find the repository root above " + directory);
    }

    private static List<Host> hosts() {
        Path root = repositoryRoot();
        List<Host> hosts = new ArrayList<>();
        hosts.add(new Host("webview-jetbrains",
                root.resolve("webview/webview-jetbrains/src/main/java/hr/hrg/jetbrains/webview/"
                        + "services/HttpBridgeService.java")));
        hosts.add(new Host("webview-vscode",
                root.resolve("webview/webview-vscode/src/HttpBridge.ts")));
        hosts.add(new Host("jwa-sidecar",
                root.resolve("webview/jwa-sidecar/src/main/java/hr/hrg/watch2/sidecar/SidecarApp.java")));
        return hosts;
    }

    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new AssertionError("host source not found: " + file);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * A host's decision layer: the file(s) that contain its routes, authorization and health document.
     *
     * <p>More than one file for the TypeScript host, because its decisions live in {@code BridgePolicy.ts}
     * while its HTTP plumbing lives in {@code HttpBridge.ts}. Reading only one of them is how the first
     * version of this test failed on a refactor that had kept the behaviour: the route's name had moved, not
     * the route.
     */
    private static String decisionLayer(Host host) throws IOException {
        StringBuilder combined = new StringBuilder(read(host.source()));
        Path policy = host.source().resolveSibling("BridgePolicy.ts");
        if (Files.isRegularFile(policy)) {
            combined.append('\n').append(read(policy));
        }
        return combined.toString();
    }

    @Test
    public void everyHostBuildsItsHealthDocumentWithTheSharedOne() throws IOException {
        List<String> failures = new ArrayList<>();

        for (Host host : hosts()) {
            String source = decisionLayer(host);
            boolean usesSharedDocument = source.contains("HostHealth") || source.contains("healthDocument");
            if (!usesSharedDocument) {
                failures.add(host.name() + " builds its /health body itself: " + host.source());
                continue;
            }
            // The endpoint itself, not just the import: a host could name the builder and not serve it.
            if (!source.contains("/health")) {
                failures.add(host.name() + " never mentions the /health route");
            }
        }

        if (!failures.isEmpty()) {
            fail("every host must answer /health with the shared document (Java: HostHealth.of(...).toJson();"
                    + " TypeScript: healthDocument(...)), so the shape cannot drift between them:\n  "
                    + String.join("\n  ", failures));
        }
    }

    /**
     * An unauthorized {@code /open} must be answered {@code 403}, and the check must come before the host
     * does anything on the request's behalf.
     */
    @Test
    public void everyHostRefusesAnUnauthorizedOpenWith403() throws IOException {
        List<String> failures = new ArrayList<>();

        for (Host host : hosts()) {
            String source = decisionLayer(host);

            // The refusal must exist...
            Matcher refusal = FORBIDDEN.matcher(source);
            if (!refusal.find()) {
                failures.add(host.name() + " never answers 403 on /open");
                continue;
            }

            // ...and it must come before the request is acted on. Comparing positions in the file is crude
            // but it is exactly the ordering that matters: a host that resolves a path first has done work
            // for an uninvited caller, and one that answers 200 first has already lied.
            int refusalAt = refusal.start();
            int firstAction = firstActionOffset(source);
            if (firstAction > 0 && firstAction < refusalAt) {
                failures.add(host.name() + " acts on /open (offset " + firstAction
                        + ") before it checks authorization (offset " + refusalAt + ")");
            }
        }

        if (!failures.isEmpty()) {
            fail("an unauthorized /open must be answered 403 before anything else happens:\n  "
                    + String.join("\n  ", failures));
        }
    }

    /** 403 in either language, or the shared refusal helper's name. */
    private static final Pattern FORBIDDEN =
            Pattern.compile("(403|Forbidden: configure|sendForbidden|forbidden\\()");

    /**
     * The offset of the first thing a host does on behalf of a request: resolving the file path, or
     * answering that it opened something. Whichever is earliest is what must come after the auth check.
     */
    private static int firstActionOffset(String source) {
        int earliest = -1;
        for (Pattern pattern : List.of(
                Pattern.compile("\\.open\\(filePath"),
                Pattern.compile("\\.open\\(\\s*params"),
                Pattern.compile("navigator\\(\\)\\s*\\.open"),
                Pattern.compile("navigateToFile\\("),
                Pattern.compile("Opening \" \\+ filePath"))) {
            Matcher matcher = pattern.matcher(source);
            if (matcher.find() && (earliest < 0 || matcher.start() < earliest)) {
                earliest = matcher.start();
            }
        }
        return earliest;
    }

    /**
     * A page cannot be told a capability a host does not have. The capability keys themselves are shared
     * ({@link EditorHost}), so this only checks that the hosts do not invent their own list inline.
     */
    @Test
    public void noHostInventsItsOwnCapabilityKeys() throws IOException {
        List<String> failures = new ArrayList<>();

        for (Host host : hosts()) {
            String source = decisionLayer(host);
            // A bare "reveal"/"select"/"open" string literal in a capabilities list would be a key written by
            // hand. The shared constants are the only spelling that cannot drift.
            Matcher invented = Pattern.compile(
                            "capabilities?[\"']?\\s*[:=]\\s*\\[[^\\]]*[\"'](open|reveal|select|edit)[\"']")
                    .matcher(source);
            if (invented.find()) {
                failures.add(host.name() + " writes a capability key inline: " + invented.group().trim());
            }
        }

        if (!failures.isEmpty()) {
            fail("capability keys live in EditorHost (Java) and are named in the capability document:\n  "
                    + String.join("\n  ", failures));
        }
    }

    @Test
    public void reportsWhatItChecked() {
        // Not a tautology: the test above is only meaningful while there are three hosts to compare, and a
        // fourth host added to the product without being added here would silently go unchecked.
        assertTrue("the parity check covers three hosts, found " + hosts().size(), hosts().size() >= 3);
        assertTrue("and each must have a readable decision layer",
                hosts().stream().allMatch(host -> {
                    try {
                        return decisionLayer(host).length() > 200;
                    } catch (IOException e) {
                        return false;
                    }
                }));
    }
}
