package com.example.indexer;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Paths.get(args[0]) : Paths.get(".");
        System.out.println("Indexing Java project at: " + root.toAbsolutePath());

        ProjectIndexer indexer = new ProjectIndexer();
        try {
            indexer.index(root);
        } catch (CollisionException e) {
            System.err.println(e.getMessage());
            System.exit(42);
        }

        indexer.printStats();

        System.out.println("\n=== Example queries (exact, because no collision) ===");
        String[] queries = {"java.lang.Long.valueOf", "java.util.List.add", "java.util.List"};
        for (String q : queries) {
            var files = indexer.filesUsing(q);
            System.out.printf("'%s' -> %d files: %s%n", q, files.size(), files.stream().limit(5).toList());
        }

        System.out.println("\nGuarantee: if symbol is in dictionary and no CollisionException was thrown,");
        System.out.println("filesUsing() is EXACT. Hash presence == symbol presence.");
    }
}
