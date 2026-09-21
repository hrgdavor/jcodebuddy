package hr.hrg.rewrite.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRewriteViewInterfaceGenerator.
 */
public class OpenRewriteViewInterfaceGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testEntryPointsForBuilderLevel() {
        List<OpenRewriteViewInterfaceGenerator.EntryPoint> entryPoints =
                OpenRewriteViewInterfaceGenerator.entryPointsFor(
                        hr.hrg.hipster.entity.api.GenLevel.BUILDER,
                        "Person"
                );
        
        assertEquals(1, entryPoints.size());
        assertEquals("toBuilder", entryPoints.get(0).methodName());
        assertEquals("PersonBuilder", entryPoints.get(0).builderType());
    }

    @Test
    public void testEntryPointsForBuilderTrackedLevel() {
        List<OpenRewriteViewInterfaceGenerator.EntryPoint> entryPoints =
                OpenRewriteViewInterfaceGenerator.entryPointsFor(
                        hr.hrg.hipster.entity.api.GenLevel.BUILDER_TRACKED,
                        "Person"
                );
        
        assertEquals(2, entryPoints.size());
        assertEquals("toBuilder", entryPoints.get(0).methodName());
        assertEquals("PersonBuilder", entryPoints.get(0).builderType());
        assertEquals("toBuilderTracking", entryPoints.get(1).methodName());
        assertEquals("PersonBuilderTracking", entryPoints.get(1).builderType());
    }

    @Test
    public void testEntryPointsForBuilderAllLevel() {
        List<OpenRewriteViewInterfaceGenerator.EntryPoint> entryPoints =
                OpenRewriteViewInterfaceGenerator.entryPointsFor(
                        hr.hrg.hipster.entity.api.GenLevel.BUILDER_ALL,
                        "Person"
                );
        
        assertEquals(2, entryPoints.size());
        assertEquals("toBuilder", entryPoints.get(0).methodName());
        assertEquals("toBuilderTracking", entryPoints.get(1).methodName());
    }

    @Test
    public void testGenerateWithMissingEntryPoints(@TempDir Path tempDir) throws IOException {
        // Create a simple view interface without builder methods
        String viewName = "Person";
        String packageDir = "com/example/view";
        Path packagePath = tempDir.resolve(packageDir.replace('.', '/'));
        Files.createDirectories(packagePath);
        
        String viewContent = "package com.example.view;\n\n" +
                "public interface Person {\n" +
                "    String getName();\n" +
                "    int getAge();\n" +
                "}\n";
        
        Path viewFile = packagePath.resolve(viewName + ".java");
        Files.writeString(viewFile, viewContent);
        
        List<OpenRewriteViewInterfaceGenerator.EntryPoint> entryPoints =
                OpenRewriteViewInterfaceGenerator.entryPointsFor(
                        hr.hrg.hipster.entity.api.GenLevel.BUILDER,
                        viewName
                );
        
        OpenRewriteViewInterfaceGenerator.Result result =
                OpenRewriteViewInterfaceGenerator.generate(tempDir, 
                        "com.example.view", 
                        viewName, 
                        entryPoints);
        
        assertNotNull(result);
        assertEquals(viewName + ".java", result.viewFile().getFileName().toString());
        assertFalse(result.alreadyPresent());
        assertEquals(1, result.added().size());
    }

    @Test
    public void testGenerateWithAlreadyPresentEntryPoints(@TempDir Path tempDir) throws IOException {
        // Create a view interface with builder methods already present
        String viewName = "Person";
        String packageDir = "com/example/view";
        Path packagePath = tempDir.resolve(packageDir.replace('.', '/'));
        Files.createDirectories(packagePath);
        
        String viewContent = "package com.example.view;\n\n" +
                "import com.example.view.PersonBuilder;\n\n" +
                "public interface Person {\n" +
                "    String getName();\n" +
                "    int getAge();\n" +
                "\n" +
                "    default PersonBuilder toBuilder() {\n" +
                "        return new PersonBuilder();\n" +
                "    }\n" +
                "}\n";
        
        Path viewFile = packagePath.resolve(viewName + ".java");
        Files.writeString(viewFile, viewContent);
        
        List<OpenRewriteViewInterfaceGenerator.EntryPoint> entryPoints =
                OpenRewriteViewInterfaceGenerator.entryPointsFor(
                        hr.hrg.hipster.entity.api.GenLevel.BUILDER,
                        viewName
                );
        
        OpenRewriteViewInterfaceGenerator.Result result =
                OpenRewriteViewInterfaceGenerator.generate(tempDir, 
                        "com.example.view", 
                        viewName, 
                        entryPoints);
        
        assertNotNull(result);
        assertEquals(viewName + ".java", result.viewFile().getFileName().toString());
        assertTrue(result.alreadyPresent());
        assertTrue(result.added().isEmpty());
    }

    @Test
    public void testGenerateWithEmptyEntryPoints() {
        List<OpenRewriteViewInterfaceGenerator.EntryPoint> entryPoints = List.of();
        OpenRewriteViewInterfaceGenerator.Result result =
                OpenRewriteViewInterfaceGenerator.generate(tempDir, 
                        null, 
                        "Person", 
                        entryPoints);
        
        assertNotNull(result);
        assertTrue(result.alreadyPresent());
        assertTrue(result.added().isEmpty());
    }
}
