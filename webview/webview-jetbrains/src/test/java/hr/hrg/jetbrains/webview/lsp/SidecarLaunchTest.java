package hr.hrg.jetbrains.webview.lsp;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The sidecar discovery rules, on a plain JVM.
 *
 * <p>No IntelliJ Platform, no filesystem: {@link SidecarLaunch} takes an injected {@link SidecarLaunch.Exists},
 * so each fallback chain is walked candidate by candidate — including the one this merge exists to preserve, the
 * development JAR under the project's base path.</p>
 *
 * <p>JUnit 4, like this plugin's other tests: the version catalog pins `junit 4.13.2`.</p>
 */
public class SidecarLaunchTest {

    /** Answers true for exactly the given paths, so a chain can be walked step by step. */
    private static SidecarLaunch.Exists existing(String... present) {
        List<String> paths = List.of(present);
        return paths::contains;
    }

    private static SidecarLaunch.Exists nothing() {
        return candidate -> false;
    }

    @Test
    public void theConfiguredJavaHomeWinsWhenItHasAnExecutable() {
        assertEquals("the configured home is tried first", "C:/jdk-configured/bin/java.exe",
                SidecarLaunch.javaExecutable("C:/jdk-configured", "C:/jdk-env", "Windows 11",
                        existing("C:/jdk-configured/bin/java.exe")));
    }

    @Test
    public void javaHomeIsUsedWhenTheConfiguredHomeHasNoExecutable() {
        assertEquals("JAVA_HOME is the second candidate, not the first", "C:/jdk-env/bin/java.exe",
                SidecarLaunch.javaExecutable("C:/jdk-configured", "C:/jdk-env", "Windows 11",
                        existing("C:/jdk-env/bin/java.exe")));
    }

    @Test
    public void withNothingResolvedTheBareNameIsReturnedSoTheOperatingSystemSearchesPath() {
        assertEquals("on Windows the bare name keeps its .exe suffix", "java.exe",
                SidecarLaunch.javaExecutable("C:/jdk-configured", "C:/jdk-env", "Windows 11", nothing()));
        assertEquals("and elsewhere it is java", "java",
                SidecarLaunch.javaExecutable(null, null, "Linux", nothing()));
    }

    @Test
    public void theJarCandidatesAreOrderedProjectThenGlobalThenBundledThenDevelopment() {
        List<String> candidates = SidecarLaunch.jarCandidates(
                "D:/project/sidecar.jar", "D:/ide/sidecar.jar", "C:/plugins/webview/sidecar/jwa-sidecar.jar",
                "C:/work/jcodebuddy");

        assertEquals("the per-project override is tried before the IDE-wide one, and the development build last",
                List.of(
                        "D:/project/sidecar.jar",
                        "D:/ide/sidecar.jar",
                        "C:/plugins/webview/sidecar/jwa-sidecar.jar",
                        "C:/work/jcodebuddy/webview/jwa-sidecar/target/jwa-sidecar.jar"),
                candidates);
    }

    @Test
    public void theDevelopmentCandidateIsTheModuleTargetUnderTheProjectBasePath() {
        List<String> candidates = SidecarLaunch.jarCandidates(null, null, null, "C:/work/jcodebuddy");

        assertEquals("not webview/webview/..., which is what the earlier attempt's Gradle copy task resolved to "
                        + "after the directories moved: the descriptor's own path was always built from the "
                        + "project base path, and that is what is kept",
                List.of("C:/work/jcodebuddy/webview/jwa-sidecar/target/jwa-sidecar.jar"), candidates);
    }

    @Test
    public void candidatesThatAreAbsentAreNotListedButTheOnesThatAreRemain() {
        List<String> candidates = SidecarLaunch.jarCandidates(null, "", "C:/plugins/sidecar.jar", null);

        assertEquals("an empty or absent configured value adds nothing; the bundled copy still does",
                List.of("C:/plugins/sidecar.jar"), candidates);
    }

    @Test
    public void theFirstCandidateThatExistsIsTheOneUsed() {
        List<String> candidates = List.of("D:/project/sidecar.jar", "D:/ide/sidecar.jar");

        assertEquals("a per-project path that does not exist falls through to the next candidate",
                Optional.of("D:/ide/sidecar.jar"), SidecarLaunch.firstExisting(candidates, existing("D:/ide/sidecar.jar")));
    }

    @Test
    public void whenNothingExistsTheResultIsEmptySoTheCallerCanNameEveryPathItLookedIn() {
        assertTrue("no candidate means no answer, never the last one silently",
                SidecarLaunch.firstExisting(List.of("a.jar", "b.jar"), nothing()).isEmpty());
        assertFalse("with nothing configured and no base path there is nothing to try",
                SidecarLaunch.jarCandidates(null, null, null, null).size() > 0);
    }

    @Test
    public void theMainClassAndJarNameAreTheSidecarsOwnCoordinates() {
        assertEquals("the sidecar artifact's entry point — an external contract, not a name to refactor",
                "hr.hrg.watch2.sidecar.SidecarApp", SidecarLaunch.SIDECAR_MAIN_CLASS);
        assertEquals("the artifact's finalName, which is what both earlier IDE clients looked for too",
                "jwa-sidecar.jar", SidecarLaunch.SIDECAR_JAR_NAME);
    }
}
