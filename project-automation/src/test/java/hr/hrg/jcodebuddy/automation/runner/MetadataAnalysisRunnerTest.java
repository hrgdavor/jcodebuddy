package hr.hrg.jcodebuddy.automation.runner;

import hr.hrg.watch2.server.metadata.MetadataProvider;
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
        assertEquals(4, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.relativePath().endsWith("Foo.java")));
        assertTrue(entries.stream().anyMatch(e -> e.relativePath().endsWith("Bar.java")));
    }
}
