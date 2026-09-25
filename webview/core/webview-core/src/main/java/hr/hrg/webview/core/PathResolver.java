package hr.hrg.webview.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The path jail: one place that decides whether a path a page named is inside the project.
 *
 * <p>The rule the frozen contract states (§ 7, item 3) is "resolve a path against the project and
 * refuse anything outside it". Written down as prose it is easy to implement almost-correctly, which is
 * how the three hosts ended up with three different answers. The cases that a naive
 * {@code absolutePath.startsWith(root)} gets wrong, and that this class handles:
 *
 * <ul>
 *   <li><b>Prefix tricks.</b> {@code C:/work/proj-evil/x} starts with {@code C:/work/proj} but is a
 *       different directory. Comparison is therefore by path segment, not by string prefix.</li>
 *   <li><b>{@code ..} after normalisation.</b> {@code ../../etc/passwd} must not become
 *       {@code C:/etc/passwd} and be accepted because the string still looks relative.</li>
 *   <li><b>Symlinks.</b> A symlink inside the project pointing out of it is the same escape as
 *       {@code ..}, and the only honest answer is to resolve it. A link to a file that does not exist
 *       yet is still resolved through its parent, because a write target is exactly the case that
 *       matters.</li>
 *   <li><b>A path that is not a path.</b> A NUL byte or a Windows-illegal character is refused as
 *       "outside the project" rather than thrown at the caller, because it comes from a web page.</li>
 * </ul>
 *
 * <p>Deliberately permissive about the two things a host cannot know: whether the file exists (the
 * host probes that, with {@code VirtualFile} in an IDE and {@link Files} in the sidecar) and which
 * case the file system uses. On a case-insensitive file system a difference in case between the URL a
 * page produced and the on-disk name is normal and is not treated as an escape.
 */
public final class PathResolver {

    private final Path root;

    private PathResolver(Path root) {
        this.root = root;
    }

    /**
     * @param projectRoot the project directory, or {@code null}/blank for a host that has no project
     *                    (a headless host serving a single file). With no root every path is
     *                    considered confined, because there is nothing to escape from.
     */
    public static PathResolver forProject(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return new PathResolver(null);
        }
        try {
            return new PathResolver(Paths.get(projectRoot).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            // A root that is not a path is a configuration error, and the safe reading of "a jail with
            // no walls" is to have no jail: the host that asked for confinement will refuse every path
            // below, which is the closed default rather than the open one.
            return new PathResolver(null);
        }
    }

    /** The project root in force, or {@code null} when this resolver has none. */
    public Path root() {
        return root;
    }

    public boolean hasRoot() {
        return root != null;
    }

    /**
     * Resolves {@code filePath} — absolute or relative to the project, either slash style — and reports
     * whether it stayed inside the project.
     *
     * @return the resolution, or {@code null} when the text is not a usable path at all
     */
    public PathResolution resolve(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path candidate;
        try {
            // Normalising drops "." and applies "..", so an escape attempt is visible in the result
            // rather than hidden in the text.
            candidate = Paths.get(filePath.replace('\\', '/')).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (candidate.isAbsolute()) {
            return resolution(candidate);
        }
        if (root == null) {
            return new PathResolution(slashes(candidate), true);
        }
        return resolution(root.resolve(candidate));
    }

    private PathResolution resolution(Path candidate) {
        if (root == null) {
            return new PathResolution(slashes(candidate), true);
        }
        Path real = realPath(candidate);
        boolean confined = isInside(real, root);
        return new PathResolution(slashes(candidate), confined);
    }

    /**
     * The path with every symbolic link resolved, falling back to the resolved parent plus the file
     * name when the file itself does not exist yet — the case a write of a new file hits.
     */
    private static Path realPath(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return path.toRealPath();
            }
            Path parent = path.getParent();
            if (parent == null) {
                return path.toAbsolutePath().normalize();
            }
            return parent.toRealPath().resolve(path.getFileName());
        } catch (IOException | SecurityException e) {
            // Unreadable means unverifiable. Returning the path unchanged makes the containment test
            // fail for anything that is not literally under the root, which is the closed default.
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Segment-wise containment. A string-prefix test would accept {@code <root>-evil}; walking the
     * segments cannot. A root of "no root" contains everything.
     */
    private static boolean isInside(Path candidate, Path root) {
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException | SecurityException e) {
            realRoot = root.toAbsolutePath().normalize();
        }
        Path current = candidate.isAbsolute() ? candidate : candidate.toAbsolutePath().normalize();
        if (current.getNameCount() < realRoot.getNameCount()) {
            return false;
        }
        for (int i = 0; i < realRoot.getNameCount(); i++) {
            Path rootSegment = realRoot.getName(i);
            Path candidateSegment = current.getName(i);
            if (!segmentEquals(rootSegment, candidateSegment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares one segment. Case-insensitively on the file systems that are case-insensitive, because
     * a URL may spell a directory differently from the on-disk name and that is not an escape.
     */
    private static boolean segmentEquals(Path left, Path right) {
        String a = left.toString();
        String b = right.toString();
        if (a.equals(b)) {
            return true;
        }
        return fileSystemIsCaseInsensitive() && a.equalsIgnoreCase(b);
    }

    private static boolean fileSystemIsCaseInsensitive() {
        String os = System.getProperty("os.name", "");
        return os.startsWith("Windows") || os.startsWith("Mac");
    }

    /**
     * Forward-slashed text. Every editor API and every URL in this product accepts either style on
     * Windows and only forward slashes make a usable URL, so normalising once here removes the
     * {@code replace('\\', '/')} that used to appear in each host.
     */
    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }
}
