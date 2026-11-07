package com.example.indexer;

import java.util.*;

public class FileIndex {
    public final String filePath;
    public final Set<String> rawSymbols = new TreeSet<>();
    public long[] sortedHashes = new long[0];
    public final Set<String> fineSymbols = new TreeSet<>();

    public FileIndex(String filePath) { this.filePath = filePath; }

    public void addSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        // ignore java.lang.* auto-imports noise? keep it, it's useful for narrowing
        rawSymbols.add(symbol);
    }

    public void addFine(String detail) { if (detail != null) fineSymbols.add(detail); }

    public void finalizeHashes(SymbolDictionary dict) {
        Set<Long> hashes = new TreeSet<>();
        for (String s : rawSymbols) {
            long h = dict.intern(s);
            if (h != 0) hashes.add(h);
        }
        sortedHashes = hashes.stream().mapToLong(l -> l).sorted().toArray();
    }

    public boolean mayContain(long hash) {
        return Arrays.binarySearch(sortedHashes, hash) >= 0;
    }
}
