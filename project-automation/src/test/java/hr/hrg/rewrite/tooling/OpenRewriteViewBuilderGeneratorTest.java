package hr.hrg.rewrite.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRewriteViewBuilderGenerator.
 */
public class OpenRewriteViewBuilderGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    public void testBuilderClassName() {
        String builderClass = OpenRewriteViewBuilderGenerator.builderClassName("Person");
        assertEquals("PersonBuilder", builderClass);
    }

    @Test
    public void testGenerateBuilder(@TempDir Path tempDir) throws IOException {
        List<OpenRewriteViewBuilderGenerator.Result> results = new java.util.ArrayList<>();
        
        // Generate builder source
        String source = OpenRewriteViewBuilderGenerator.source(
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ),
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ),
                "PersonBuilder",
                false
        );
        
        assertNotNull(source);
        assertTrue(source.contains("public final class PersonBuilder"));
        assertTrue(source.contains("public String name()"));
        assertTrue(source.contains("public Integer age()"));
        assertTrue(source.contains("public Person build()"));
    }

    @Test
    public void testGenerateBuilderWithWriters(@TempDir Path tempDir) throws IOException {
        List<OpenRewriteViewBuilderGenerator.Result> results = new java.util.ArrayList<>();
        
        // Generate builder source with writable fields
        String source = OpenRewriteViewBuilderGenerator.source(
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ),
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ),
                "PersonBuilder",
                false
        );
        
        assertNotNull(source);
        assertTrue(source.contains("public PersonBuilder name(Integer value)"));
        assertTrue(source.contains("public PersonBuilder age(Integer value)"));
    }

    @Test
    public void testBuilderSourceContainsRequiredMethods() {
        String source = OpenRewriteViewBuilderGenerator.source(
                null,
                "Person",
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ),
                List.of(
                    new hr.hrg.hipster.entity.tooling.meta.Property("name", "String"),
                    new hr.hrg.hipster.entity.tooling.meta.Property("age", "int")
                ),
                "PersonBuilder",
                false
        );
        
        // Check for required methods
        assertTrue(source.contains("public final class PersonBuilder"));
        assertTrue(source.contains("public PersonBuilder()"));
        assertTrue(source.contains("public PersonBuilder(Person source)"));
        assertTrue(source.contains("public String name()"));
        assertTrue(source.contains("public Integer age()"));
        assertTrue(source.contains("public Object get(int fieldOrdinal)"));
        assertTrue(source.contains("public Person build()"));
    }

    @Test
    public void testGetBoxedType() {
        assertEquals("Integer", OpenRewriteViewBuilderGenerator.getBoxedType("int"));
        assertEquals("Long", OpenRewriteViewBuilderGenerator.getBoxedType("long"));
        assertEquals("Float", OpenRewriteViewBuilderGenerator.getBoxedType("float"));
        assertEquals("Double", OpenRewriteViewBuilderGenerator.getBoxedType("double"));
        assertEquals("Boolean", OpenRewriteViewBuilderGenerator.getBoxedType("boolean"));
        assertEquals("Character", OpenRewriteViewBuilderGenerator.getBoxedType("char"));
        assertEquals("Byte", OpenRewriteViewBuilderGenerator.getBoxedType("byte"));
        assertEquals("Short", OpenRewriteViewBuilderGenerator.getBoxedType("short"));
        assertEquals("Object", OpenRewriteViewBuilderGenerator.getBoxedType("String"));
    }
}
