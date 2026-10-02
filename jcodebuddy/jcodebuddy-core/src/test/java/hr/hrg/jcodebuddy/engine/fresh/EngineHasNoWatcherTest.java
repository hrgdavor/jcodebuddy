package hr.hrg.jcodebuddy.engine.fresh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * DEC-038's structural half, made mechanical: <strong>the engine takes no watcher.</strong>
 *
 * <p>Freshness (3.0g) is the step where this could quietly stop being true. The engine publishes what a change
 * means; the <em>host</em> observes the file. Adding `java-watch-core` here, or reaching for the JDK's
 * {@code WatchService} and growing a loop inside the engine, would look like a convenience and would make the
 * engine the thing every consumer's watch loop has to agree with — which is the duplication DEC-038 removed by
 * keeping `java-watch*` an independent library.</p>
 *
 * <p>A note on how this test reads the module: it runs from the module's own directory (`jcodebuddy-core`),
 * which is where Surefire starts it, so `pom.xml` and `src/main/java` are the engine's own. If that stops being
 * true the test fails loudly rather than passing vacuously.</p>
 */
class EngineHasNoWatcherTest {

    @Test
    void theEnginesOwnPomDeclaresNoWatcherDependency() throws IOException {
        Path pom = Path.of("pom.xml");
        Assertions.assertTrue(Files.isRegularFile(pom), "this test must read the engine's own pom.xml; it is"
                + " running from " + Path.of("").toAbsolutePath());

        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        List<String> artifacts = new ArrayList<>();
        Matcher matcher = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(xml);
        while (matcher.find()) {
            artifacts.add(matcher.group(1).trim());
        }

        List<String> watchers = artifacts.stream().filter(artifact -> artifact.contains("watch")).toList();
        Assertions.assertTrue(watchers.isEmpty(),
                "the engine must not depend on a watcher (DEC-038): found " + watchers + " among " + artifacts);
    }

    @Test
    void theEnginesSourcesNameNoWatcherPackageOrWatchService() throws IOException {
        Path sources = Path.of("src", "main", "java");
        Assertions.assertTrue(Files.isDirectory(sources), "the engine's sources must be where this test looks: "
                + sources.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains("hr.hrg.watch2")) {
                    offenders.add(file + " names hr.hrg.watch2");
                }
                if (text.contains("WatchService")) {
                    offenders.add(file + " reaches for the JDK's WatchService");
                }
            }
        }

        Assertions.assertTrue(offenders.isEmpty(),
                "watching is the host's job, and the engine publishes only what a change means (DEC-038, step"
                        + " 3.0g): " + offenders);
    }
}
