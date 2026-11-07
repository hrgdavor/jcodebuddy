package com.example.indexer;

public class CollisionException extends RuntimeException {
    public CollisionException(String existing, String incoming, long hash) {
        super(build(existing, incoming, hash));
    }

    private static String build(String existing, String incoming, long hash) {
        String hex = String.format("%016x", hash);
        return "\n" +
            "============================================================================\n" +
            "WYHASH COLLISION DETECTED - ACTION REQUIRED - INDEX ABORTED\n" +
            "============================================================================\n" +
            "You configured this indexer to FAIL-FAST on collision to guarantee:\n" +
            "  hash present => symbol present (no false positives).\n" +
            "You asked it to complain loudly so you will remember what to do.\n" +
            "\n" +
            "Collision hash : 0x" + hex + " (" + hash + ")\n" +
            "  Existing     : '" + existing + "'\n" +
            "  Incoming     : '" + incoming + "'\n" +
            "\n" +
            "Both different strings produced the same 64-bit WyHash.\n" +
            "Probability of this happening is ~1 in 10 million for 1M symbols. You hit it.\n" +
            "\n" +
            "WHAT IT MEANS:\n" +
            "  Inverted index key 0x" + hex + " now maps to TWO different symbols.\n" +
            "  Query for '" + existing + "' would also return files using '" + incoming + "'.\n" +
            "  The guarantee 'all these files import this class' is BROKEN for this hash.\n" +
            "\n" +
            "WHAT YOU PROMISED TO DO:\n" +
            "  You said: 'I am willing to accept rename if WyHash discovers a collision'.\n" +
            "  So you must RENAME one of the two symbols by at least 1 char.\n" +
            "\n" +
            "OPTIONS:\n" +
            "  1) RENAME (your preferred): Change '" + incoming + "' to '" + incoming + "_v2'\n" +
            "     or similar. Any char change changes the WyHash completely.\n" +
            "  2) Change WyHash secret: Edit WyHash.java SECRET array to other random longs\n" +
            "     and re-index ENTIRE project from scratch.\n" +
            "  3) Allow collisions: Modify SymbolDictionary to store List<String> per hash\n" +
            "     and change filesUsing() to do exact string filtering after hash lookup.\n" +
            "     This removes the exact guarantee but tolerates collision.\n" +
            "\n" +
            "This error is intentional and descriptive as requested.\n" +
            "============================================================================\n";
    }
}
