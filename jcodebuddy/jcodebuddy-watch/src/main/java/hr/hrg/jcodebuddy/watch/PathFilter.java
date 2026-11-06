package hr.hrg.jcodebuddy.watch;

import hr.hrg.gitignore.GitIgnoreFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The include/exclude decision for a watched tree, using the {@code gitignore} project's wildmatch.
 *
 * <p>This is the filter the old {@code FileFilter} supplied: the includes decide what is interesting (empty means
 * everything), and an exclude always wins. The patterns are matched with git's wildmatch semantics, never the JDK's
 * glob engine (DEC-051).</p>
 *
 * <p>{@link FsMetaDaemonWatch} applies it to every daemon batch, and a consumer that must filter again — the agent's
 * {@code ToolSetAgent} does, per toolset — asks the same class rather than carrying a matcher of its own.</p>
 */
public final class PathFilter {

    private final List<GitIgnoreFile> includes;
    private final List<GitIgnoreFile> excludes;

    private PathFilter(List<GitIgnoreFile> includes, List<GitIgnoreFile> excludes) {
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
    }

    /** Build a filter from wildmatch patterns. An empty include list means "every file". */
    public static PathFilter of(List<String> includes, List<String> excludes) {
        return new PathFilter(parse(includes), parse(excludes));
    }

    private static List<GitIgnoreFile> parse(List<String> patterns) {
        List<GitIgnoreFile> parsed = new ArrayList<>();
        if (patterns == null) {
            return parsed;
        }
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                parsed.add(GitIgnoreFile.parse("", pattern));
            }
        }
        return parsed;
    }

    /** Whether {@code relative} (POSIX, root-relative) is a file this watcher should report. */
    public boolean shouldInclude(String relative) {
        boolean included = includes.isEmpty() || includes.stream()
                .anyMatch(file -> file.decide(relative, false).orElse(false));
        if (!included) {
            return false;
        }
        return excludes.stream().noneMatch(file -> file.decide(relative, false).orElse(false));
    }

    /** Whether {@code relative} is a file this watcher should report. */
    public boolean shouldInclude(Path root, Path file) {
        return shouldInclude(root.relativize(file).toString().replace('\\', '/'));
    }

    /** Whether a directory should be entered. Excludes apply; includes do not, because they name files. */
    public boolean shouldEnter(String relativeDirectory) {
        return excludes.stream().noneMatch(file -> file.decide(relativeDirectory, true).orElse(false)
                || file.decide(relativeDirectory + "/", true).orElse(false));
    }
}
