package hr.hrg.hipster.entity.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What makes a {@code .jcodebuddy/} directory a <b>marker</b>.
 *
 * <p>DEC-026 § 2 resolves a module's output root by walking up to the nearest
 * {@code .jcodebuddy/}: the directory <em>is</em> the registry, and "this module uses JCodeBuddy"
 * is a fact about its presence. That reading stopped being complete once other tools began
 * keeping state in a directory of the same name at a level that is not a module:
 *
 * <ul>
 *   <li>a <b>page host</b> publishes the port it bound in {@code <project>/.jcodebuddy/webview/}
 *       (DEC-032, DEC-033) — and a host is pointed at a project root, which, for a repository,
 *       is a directory that has no marker and must not gain one;</li>
 *   <li>{@code codebuddy merge} keeps {@code .jcodebuddy/merge-history/} at a repository root.</li>
 * </ul>
 *
 * <p>Treating either as a module marker is not a cosmetic mistake: the entity pass would decide
 * that a module's sources belong to the directory a host happened to serve, and write
 * {@code metadata/entity} there — one level up from the module, in an output tree that module's
 * {@code .gitignore} does not cover. So the rule is:
 *
 * <blockquote>
 *   A {@code .jcodebuddy/} whose only substantive content is <b>tool state</b> is not a
 *   project-automation marker. Marker resolution skips it and keeps walking up.
 * </blockquote>
 *
 * <p>An <b>empty</b> {@code .jcodebuddy/} still counts as a marker: creating one by hand is an
 * explicit act, and a module is allowed to carry its marker before its first pass. Tool state is
 * created by a tool without anyone deciding anything, which is the distinction this class draws.
 *
 * <p>The names in {@link #TOOL_STATE} are the whole list on purpose. Adding a subtree that a
 * <em>tool</em> writes at a level that is not a module means adding it here in the same change —
 * and the test in {@code JcodebuddyDirectoryTest} exists to make that a decision rather than an
 * accident.
 */
public final class JcodebuddyDirectory {

    private JcodebuddyDirectory() {
    }

    /** The directory name, which is also the marker's name: DEC-026 § 1. */
    public static final String DIR = EntityMetadataGenerator.JCODEBUDDY_DIR;

    /**
     * Subtrees a <b>tool</b> writes, at a level that is a project rather than a module.
     *
     * <ul>
     *   <li>{@code webview/} — a page host's published port, token and checkpoints (DEC-032);</li>
     *   <li>{@code merge-history/} — {@code codebuddy merge}'s per-branch conflict state.</li>
     * </ul>
     */
    public static final Set<String> TOOL_STATE = Set.of("webview", "merge-history");

    /** The two files a converted module (or a host) may keep beside its subtrees: policy, not content. */
    public static final Set<String> POLICY_FILES = Set.of("README.md", ".gitignore");

    /**
     * True when this {@code .jcodebuddy/} means "this module applies {@code project-automation}".
     *
     * <p>False only for the case above: a directory whose whole content is tool state. A directory
     * that does not exist, is not a directory, or cannot be listed is not a marker either — a
     * resolution that cannot read a directory must fall through to the next candidate rather than
     * claim it.
     */
    public static boolean isMarker(Path jcodebuddyDirectory) {
        if (jcodebuddyDirectory == null || !Files.isDirectory(jcodebuddyDirectory)) {
            return false;
        }
        boolean sawToolState = false;
        try (Stream<Path> children = Files.list(jcodebuddyDirectory)) {
            for (Path child : children.toList()) {
                String name = child.getFileName() == null ? "" : child.getFileName().toString();
                if (TOOL_STATE.contains(name)) {
                    sawToolState = true;
                    continue;
                }
                if (POLICY_FILES.contains(name) && Files.isRegularFile(child)) {
                    continue;
                }
                // Anything else — context/, metadata/, index/, reports/, agent-state/ for a converted
                // module, or a file a person put there — is content, and content makes a marker.
                return true;
            }
        } catch (IOException e) {
            // An unreadable directory must not be mistaken for a marker: the walk continues upward and a
            // real marker above it is still found.
            return false;
        }
        return !sawToolState;
    }

    /**
     * The nearest {@code .jcodebuddy/} <b>marker</b> at or above {@code start}, or {@code null}.
     *
     * <p>Inclusive in both senses the callers need: {@code start} may itself be a
     * {@code .jcodebuddy/} directory, and it may be a directory that contains one. A candidate that
     * is only tool state is skipped, so a page host that published into a marker-less project root
     * cannot move a module's report directory.
     */
    public static Path nearestMarker(Path start) {
        if (start == null) {
            return null;
        }
        Path normalized = start.toAbsolutePath().normalize();
        // `start` itself: a caller that already holds the .jcodebuddy directory passes it directly.
        if (DIR.equals(fileName(normalized)) && isMarker(normalized)) {
            return normalized;
        }
        for (Path cursor = normalized; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(DIR);
            if (isMarker(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }
}
