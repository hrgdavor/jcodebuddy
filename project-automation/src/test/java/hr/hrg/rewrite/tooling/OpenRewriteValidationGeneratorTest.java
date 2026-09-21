package hr.hrg.rewrite.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRewriteValidationGenerator.
 */
public class OpenRewriteValidationGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testGenerateValidationRules() {
        String validationRules = OpenRewriteValidationGenerator.generateValidationRules(
                tempDir,
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                )
        );
        
        assertNotNull(validationRules);
        assertTrue(validationRules.contains("public static void validate(Person entity)"));
    }

    @Test
    public void testValidationRulesContainNullChecks() {
        String validationRules = OpenRewriteValidationGenerator.generateValidationRules(
                tempDir,
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                )
        );
        
        assertTrue(validationRules.contains("entity.name == null"));
        assertTrue(validationRules.contains("entity.age == null"));
    }

    @Test
    public void testImportsFor() {
        List<String> imports = OpenRewriteValidationGenerator.importsFor(
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                )
        );
        
        assertNotNull(imports);
    }

    @Test
    public void testConstraintInterface() {
        OpenRewriteValidationGenerator.Constraint constraint = 
                new OpenRewriteValidationGenerator.Constraint() {
                    @Override
                    public String getField() {
                        return "name";
                    }
                    
                    @Override
                    public String getMessage() {
                        return "Name cannot be null";
                    }
                };
        
        assertEquals("name", constraint.getField());
        assertEquals("Name cannot be null", constraint.getMessage());
    }

    @Test
    public void testGenerateValidationRulesWithPackage() {
        String validationRules = OpenRewriteValidationGenerator.generateValidationRules(
                tempDir,
                "com.example.view",
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String")
                )
        );
        
        assertNotNull(validationRules);
        assertTrue(validationRules.contains("package com.example.view;"));
    }
}
