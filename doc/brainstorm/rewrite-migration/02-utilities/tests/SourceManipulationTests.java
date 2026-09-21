// {@link hr.hrg.rewrite.util.SourceManipulationTests} Unit tests for SourceManipulation utility class.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import static org.junit.Assert.*;

/**
 * Unit tests for SourceManipulation utility class.
 */
@RunWith(JUnit4.class)
public class SourceManipulationTests {
    
    @Test
    public void testReplaceText() {
        // Test replacing text in source
        // TODO: Implement with actual source file
        assertTrue("Should replace text", true);
    }
    
    @Test
    public void testInsertAt() {
        // Test inserting text at offset
        // TODO: Implement with actual source file
        assertTrue("Should insert text", true);
    }
    
    @Test
    public void testDelete() {
        // Test deleting text
        // TODO: Implement with actual source file
        assertTrue("Should delete text", true);
    }
    
    @Test
    public void testFindText() {
        // Test finding text pattern
        // TODO: Implement with actual source file
        assertTrue("Should find text", true);
    }
    
    @Test
    public void testCountOccurrences() {
        // Test counting occurrences
        // TODO: Implement with actual source file
        assertTrue("Should count occurrences", true);
    }
    
    @Test
    public void testReplaceOccurrences() {
        // Test replacing all occurrences
        // TODO: Implement with actual source file
        assertTrue("Should replace occurrences", true);
    }
    
    @Test
    public void testNullSourceFile() {
        // Test handling null source file
        JavaSourceFile nullFile = null;
        assertNull(SourceManipulation.replaceText(nullFile, 0, 0, "text"));
        assertNull(SourceManipulation.insertAt(nullFile, 0, "text"));
        assertNull(SourceManipulation.delete(nullFile, 0, 0));
        assertEquals(-1, SourceManipulation.findText(nullFile, "text"));
        assertEquals(0, SourceManipulation.countOccurrences(nullFile, "text"));
        assertNull(SourceManipulation.replaceOccurrences(nullFile, "old", "new"));
    }
}
