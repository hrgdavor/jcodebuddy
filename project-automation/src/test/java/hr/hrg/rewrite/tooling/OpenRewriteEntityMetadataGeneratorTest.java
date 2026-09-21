package hr.hrg.rewrite.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRewriteEntityMetadataGenerator.
 */
public class OpenRewriteEntityMetadataGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testGenerateEntityMetadata() throws IOException {
        String metadata = OpenRewriteEntityMetadataGenerator.generateEntityMetadata(
                tempDir,
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                )
        );
        
        assertNotNull(metadata);
        assertTrue(metadata.contains("public record PersonMetadata"));
        assertTrue(metadata.contains("String name"));
        assertTrue(metadata.contains("String age"));
    }

    @Test
    public void testGenerateEntityMetadataWithPackage() throws IOException {
        String metadata = OpenRewriteEntityMetadataGenerator.generateEntityMetadata(
                tempDir,
                "com.example.entity",
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String")
                )
        );
        
        assertNotNull(metadata);
        assertTrue(metadata.contains("package com.example.entity;"));
    }

    @Test
    public void testGenerateEntityMetadataWritesToFile() throws IOException {
        OpenRewriteEntityMetadataGenerator.Result result = 
                OpenRewriteEntityMetadataGenerator.generate(
                        tempDir,
                        null,
                        "Person",
                        List.of(
                            new hr.hrg.hipster.entity.tooling.meta.Property("name", "String")
                        )
                );
        
        assertNotNull(result);
        assertNotNull(result.getMetadataFile());
        assertNotNull(result.getMetadataClass());
        assertEquals("PersonMetadata", result.getMetadataClass());
    }

    @Test
    public void testGenerateEntityMetadataContainsRecordDeclaration() {
        String metadata = OpenRewriteEntityMetadataGenerator.generateEntityMetadata(
                tempDir,
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String")
                )
        );
        
        assertTrue(metadata.contains("public record PersonMetadata(String name)"));
    }

    @Test
    public void testGenerateEntityMetadataWithMultipleProperties() throws IOException {
        String metadata = OpenRewriteEntityMetadataGenerator.generateEntityMetadata(
                tempDir,
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("email", "String")
                )
        );
        
        assertNotNull(metadata);
        assertTrue(metadata.contains("String name"));
        assertTrue(metadata.contains("String age"));
        assertTrue(metadata.contains("String email"));
    }

    @Test
    public void testGenerateEntityMetadataWithEmptyProperties() throws IOException {
        String metadata = OpenRewriteEntityMetadataGenerator.generateEntityMetadata(
                tempDir,
                null,
                "Person",
                List.of()
        );
        
        assertNotNull(metadata);
        assertTrue(metadata.contains("public record PersonMetadata"));
    }
}
