package hr.hrg.hipster.sql.tooling.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * A minimal classpath name oracle: simple type name to fully qualified candidates.
 *
 * <p>It answers the one question a textual source model cannot: when an entity writes {@code Thing}
 * without an import, which {@code a.b.Thing} is meant? The index is built from {@code .class} entry
 * names only; it does not load classes, so it works for a JDK 25 tool reading Java 8 jars.</p>
 */
public final class TypeOracle {

    public record Resolution(String fqn, List<String> candidates) {
        public Resolution {
            candidates = List.copyOf(candidates);
        }

        public boolean resolved() {
            return fqn != null;
        }
    }

    private final Map<String, List<String>> bySimpleName;

    private TypeOracle(Map<String, List<String>> bySimpleName) {
        this.bySimpleName = bySimpleName;
    }

    public static TypeOracle empty() {
        return new TypeOracle(Map.of());
    }

    /** Scans directories and jars; a missing entry is ignored (the caller reports it). */
    public static TypeOracle of(List<Path> entries) throws IOException {
        Map<String, List<String>> names = new HashMap<>();
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                try (var walk = Files.walk(entry)) {
                    for (Path file : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                        add(names, entry.relativize(file).toString());
                    }
                }
            } else if (Files.isRegularFile(entry)) {
                try (JarFile jar = new JarFile(entry.toFile())) {
                    jar.stream().filter(item -> item.getName().endsWith(".class"))
                            .forEach(item -> add(names, item.getName()));
                }
            }
        }
        return new TypeOracle(Map.copyOf(names));
    }

    public Resolution resolve(String simpleName) {
        List<String> candidates = bySimpleName.getOrDefault(simpleName, List.of());
        if (candidates.size() == 1) {
            return new Resolution(candidates.get(0), candidates);
        }
        return new Resolution(null, candidates);
    }

    public boolean isEmpty() {
        return bySimpleName.isEmpty();
    }

    private static void add(Map<String, List<String>> names, String classEntry) {
        String path = classEntry.replace('\\', '/');
        if (!path.endsWith(".class") || path.contains("$")) {
            return;
        }
        String fqn = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        int dot = fqn.lastIndexOf('.');
        String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
        names.computeIfAbsent(simple, key -> new ArrayList<>()).add(fqn);
    }
}
