package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.jcodebuddy.meta.MetadataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataAnalysisRunnerTest {
    @Test
    void analysisPopulatesProvider(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Foo.java"), "package com.example;\npublic class Foo {}\n");
        Files.writeString(tempDir.resolve("Bar.java"), "package com.example;\npublic class Bar {}\n");

        InMemoryMetadataCacheProvider provider = new InMemoryMetadataCacheProvider();
        MetadataAnalysis analysis = new MetadataAnalysis(tempDir, provider);
        analysis.scan();

        List<MetadataProvider.CacheEntry> entries = provider.listEntries();
        // Two files, one entry each. This used to be four, because the provider stores every entry under two keys
        // (its content hash and its path) and `listEntries` handed back the raw map values — so one file was
        // reported twice, with the same identity. A list of entries is a list of entries (plan step 3.0j).
        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.relativePath().endsWith("Foo.java")));
        assertTrue(entries.stream().anyMatch(e -> e.relativePath().endsWith("Bar.java")));
        // And the class names a caller asks for are the ones the engine read, not a stub's invention.
        assertEquals(List.of("com.example.Bar", "com.example.Foo"), provider.listClasses());
    }
}
