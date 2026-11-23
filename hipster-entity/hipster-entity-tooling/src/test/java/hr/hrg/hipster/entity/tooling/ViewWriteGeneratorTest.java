package hr.hrg.hipster.entity.tooling;

import hr.hrg.hipster.entity.tooling.meta.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewWriteGeneratorTest {

    @Test
    void addsAWriteInterfaceOnceAndLeavesAnExistingOneAlone(@TempDir Path root) throws Exception {
        Path source = root.resolve("V.java");
        Files.writeString(source, "package p;\n\npublic interface V {\n    Long id();\n}\n");
        assertTrue(ViewWriteGenerator.generate(source, "V", List.of(new Property("id", "Long"))));
        String first = Files.readString(source);
        assertTrue(first.contains("interface Write"), first);
        assertTrue(first.contains("Write id(Long value);"), first);
        assertFalse(ViewWriteGenerator.generate(source, "V", List.of(new Property("id", "Long"))));
        assertTrue(Files.readString(source).equals(first), "a second pass must be a no-op");
    }
}
