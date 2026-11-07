package com.example.indexer;

import org.openrewrite.java.JavaParser;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class ProjectIndexer {
    private final SymbolDictionary dictionary = new SymbolDictionary();
    private final Map<String, FileIndex> forward = new HashMap<>();
    private final Map<Long, Set<String>> inverted = new HashMap<>();

    public void index(Path root) throws IOException {
        ExecutionContext ctx = new InMemoryExecutionContext(t -> t.printStackTrace());
        JavaParser parser = JavaParser.fromJavaVersion()
                .classpath(JavaParser.runtimeClasspath())
                .build();

        List<Path> javaFiles = Files.walk(root)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());

        System.out.println("Found " + javaFiles.size() + " java files under " + root);

        for (Path file : javaFiles) {
            try {
                String source = Files.readString(file);
                var units = parser.parse(ctx, source).toList();
                for (J.CompilationUnit cu : units.stream()
                        .filter(j -> j instanceof J.CompilationUnit)
                        .map(j -> (J.CompilationUnit) j).toList()) {

                    FileIndex fi = new FileIndex(root.relativize(file).toString());
                    new SymbolCollector().visit(cu, fi);
                    fi.finalizeHashes(dictionary);
                    forward.put(fi.filePath, fi);
                    for (long h : fi.sortedHashes) {
                        inverted.computeIfAbsent(h, k -> new HashSet<>()).add(fi.filePath);
                    }
                }
            } catch (CollisionException ce) {
                System.err.println("Collision while indexing file: " + file);
                throw ce;
            } catch (Exception e) {
                System.err.println("Failed to parse " + file + ": " + e.getMessage());
            }
        }
        System.out.println("Dictionary size: " + dictionary.size() + " unique symbols");
    }

    public Set<String> filesUsing(String symbol) {
        long h = WyHash.hash(symbol.replace('/', '.'));
        String known = dictionary.getSymbol(h);
        if (known == null) {
            // Symbol never indexed -> no file contains it, and we KNOW no collision for this hash
            return Set.of();
        }
        if (!known.equals(symbol.replace('/', '.'))) {
            throw new CollisionException(known, symbol, h);
        }
        return inverted.getOrDefault(h, Set.of());
    }

    public void printStats() {
        System.out.println("\n=== Top 20 most used symbols ===");
        inverted.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(20)
                .forEach(e -> {
                    String sym = dictionary.getSymbol(e.getKey());
                    System.out.printf("%5d files : %s [0x%016x]%n", e.getValue().size(), sym, e.getKey());
                });
    }

    public SymbolDictionary getDictionary() { return dictionary; }
}
