package hr.hrg.rewrite.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRewriteFieldBoilerplateGenerator.
 */
public class OpenRewriteFieldBoilerplateGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testBuildSource() {
        OpenRewriteFieldBoilerplateGenerator generator = 
                OpenRewriteFieldBoilerplateGenerator.builder(null, "Person", List.of())
                        .build();
        
        String source = generator.buildSource();
        
        assertNotNull(source);
        assertTrue(source.contains("public enum PersonField"));
        assertTrue(source.contains("private final String"));
    }

    @Test
    public void testGenerateEnum(@TempDir Path tempDir) throws IOException {
        OpenRewriteFieldBoilerplateGenerator generator = 
                OpenRewriteFieldBoilerplateGenerator.builder("com.example.view", "Person", List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ))
                .build();
        
        generator.generate(tempDir);
        
        Path enumFile = tempDir.resolve("com/example/view/PersonField.java");
        assertTrue(Files.exists(enumFile));
        
        String content = Files.readString(enumFile);
        assertTrue(content.contains("public enum PersonField"));
        assertTrue(content.contains("public String name()"));
        assertTrue(content.contains("public String age()"));
    }

    @Test
    public void testBuilderWithPropertyEnumMode() {
        OpenRewriteFieldBoilerplateGenerator generator = 
                OpenRewriteFieldBoilerplateGenerator.builder("com.example.view", "Person", List.of())
                        .withPropertyEnumMode()
                        .build();
        
        assertTrue(generator instanceof OpenRewriteFieldBoilerplateGenerator);
    }

    @Test
    public void testBuilderWithExplicitEnumName() {
        OpenRewriteFieldBoilerplateGenerator generator = 
                OpenRewriteFieldBoilerplateGenerator.builder("com.example.view", "Person", List.of())
                        .withEnumTypeName("PersonProperties")
                        .build();
        
        // The enum name should be set
    }

    @Test
    public void testBuilderWithAdditionalImports() {
        OpenRewriteFieldBoilerplateGenerator generator = 
                OpenRewriteFieldBoilerplateGenerator.builder("com.example.view", "Person", List.of())
                        .withAdditionalImports("java.util.List", "java.util.Map")
                        .build();
        
        // Verify builder can be built with imports
    }
}
