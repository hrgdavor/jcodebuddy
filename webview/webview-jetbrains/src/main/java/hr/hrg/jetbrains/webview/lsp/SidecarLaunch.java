package hr.hrg.jetbrains.webview.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where the JWA sidecar's JAR and the Java that runs it come from — the rules, without the IntelliJ Platform.
 *
 * <p>Pure static methods over an injected {@link Exists}, so {@code SidecarLaunchTest} asserts every fallback
 * chain on a plain JVM. The descriptor keeps only what needs the platform: reading the two configured values
 * and building the {@code GeneralCommandLine}.</p>
 *
 * <p><strong>This is step 3.0q's IntelliJ merge.</strong> The capability lived in the earlier
 * {@code webview/intellij-jwa} attempt, whose descriptor discovered the same things; the search order below is
 * that one's, kept because it is right — a per-project override first, then the IDE-wide one, then the copy
 * bundled in the plugin, then the development build under the project's base path. Two things changed with the
 * move: the configured values live under this plugin's own {@code webview.explorer.*} namespace instead of
 * {@code hr.hrg.watch2.*}, and the rules are testable, which the old private methods were not.</p>
 *
 * <p>What the old attempt got wrong was in its <em>build</em> rather than this logic: its Gradle copy task
 * looked for {@code ../webview/jwa-sidecar/...}, which the 2026-10-02 move turned into
 * {@code webview/webview/jwa-sidecar/...}. The descriptor's own development path was already correct, and it is
 * what this class reproduces.</p>
 */
public final class SidecarLaunch {

    /** The sidecar's main class, and an external contract: renaming it here without the Java class breaks the launch. */
    public static final String SIDECAR_MAIN_CLASS = "hr.hrg.watch2.sidecar.SidecarApp";

    /** The name the sidecar's `pom.xml` sets with `finalName`. */
    public static final String SIDECAR_JAR_NAME = "jwa-sidecar.jar";

    /** Whether a candidate exists. Injected so the rules do not touch the filesystem in a test. */
    public interface Exists {
        boolean test(String candidate);
    }

    private SidecarLaunch() {
    }

    /**
     * The Java executable to run the sidecar with: the configured home, then {@code JAVA_HOME}, then the bare
     * name so the operating system searches {@code PATH}.
     */
    public static String javaExecutable(String configuredJavaHome, String envJavaHome, String osName, Exists exists) {
        String executable = osName != null && osName.toLowerCase().contains("win") ? "java.exe" : "java";
        for (String home : new String[]{configuredJavaHome, envJavaHome}) {
            if (home != null && !home.isEmpty()) {
                String candidate = withSeparator(home) + "bin/" + executable;
                if (exists.test(candidate)) {
                    return candidate;
                }
            }
        }
        return executable;
    }

    /**
     * The JAR candidates in the order they are tried, so a failure can name every path it looked in.
     *
     * @param configuredProjectJar the per-project override, or null
     * @param configuredGlobalJar  the IDE-wide override, or null
     * @param bundledJar           the copy inside the installed plugin, or null when the plugin path is unknown
     * @param projectBasePath      the project's base directory, or null
     */
    public static List<String> jarCandidates(String configuredProjectJar, String configuredGlobalJar,
                                             String bundledJar, String projectBasePath) {
        List<String> candidates = new ArrayList<>();
        addIfPresent(candidates, configuredProjectJar);
        addIfPresent(candidates, configuredGlobalJar);
        addIfPresent(candidates, bundledJar);
        if (projectBasePath != null && !projectBasePath.isEmpty()) {
            candidates.add(withSeparator(projectBasePath) + "webview/jwa-sidecar/target/" + SIDECAR_JAR_NAME);
        }
        return candidates;
    }

    /** The first candidate that exists, or empty when none does. */
    public static Optional<String> firstExisting(List<String> candidates, Exists exists) {
        for (String candidate : candidates) {
            if (exists.test(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void addIfPresent(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isEmpty()) {
            candidates.add(candidate);
        }
    }

    private static String withSeparator(String directory) {
        return directory.endsWith("/") || directory.endsWith("\\") ? directory : directory + "/";
    }
}
