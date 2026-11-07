package com.example.indexer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SymbolDictionary {
    private final Map<Long, String> hashToSymbol = new ConcurrentHashMap<>();

    public synchronized long intern(String symbol) {
        String normalized = symbol.replace('/', '.').trim();
        if (normalized.isEmpty()) return 0;
        long h = WyHash.hash(normalized);
        String existing = hashToSymbol.get(h);
        if (existing == null) {
            hashToSymbol.put(h, normalized);
            return h;
        }
        if (!existing.equals(normalized)) {
            throw new CollisionException(existing, normalized, h);
        }
        return h;
    }

    public String getSymbol(long hash) { return hashToSymbol.get(hash); }
    public Map<Long, String> snapshot() { return Map.copyOf(hashToSymbol); }
    public int size() { return hashToSymbol.size(); }
}
