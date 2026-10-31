package hr.hrg.jcodebuddy.watch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFilterTest {

    @Test
    void includesAreWildmatchAndAnEmptyIncludeListMeansEverything() {
        PathFilter javaOnly = PathFilter.of(List.of("**/*.java"), List.of());
        assertTrue(javaOnly.shouldInclude("A.java"));
        assertTrue(javaOnly.shouldInclude("src/main/A.java"));
        assertFalse(javaOnly.shouldInclude("src/main/readme.md"));

        PathFilter all = PathFilter.of(List.of(), List.of());
        assertTrue(all.shouldInclude("A.java"));
        assertTrue(all.shouldInclude("docs/readme.md"));
    }

    @Test
    void anExcludeAlwaysWins() {
        PathFilter filter = PathFilter.of(List.of("**/*.java"),
                List.of("**/target/**", "**/.jcodebuddy/**"));
        assertTrue(filter.shouldInclude("src/A.java"));
        assertFalse(filter.shouldInclude("target/A.java"));
        assertFalse(filter.shouldInclude("module/target/generated/A.java"));
        assertFalse(filter.shouldInclude(".jcodebuddy/cache/A.java"));
    }
}
