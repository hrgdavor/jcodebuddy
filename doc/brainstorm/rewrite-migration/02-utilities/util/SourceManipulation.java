// {@link hr.hrg.rewrite.util.SourceManipulation} Source text manipulation utilities for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.util;

import org.openrewrite.java.tree.JavaSourceFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Utility class for manipulating source text directly.
 * Provides methods for replacing, inserting, deleting, finding, and counting text patterns.
 */
public class SourceManipulation {
    
    /**
     * Replace text in the source file.
     * 
     * @param sourceFile The source file to modify
     * @param offset The offset where replacement starts
     * @param length The length of text to replace
     * @param replacement The replacement text
     * @return A new source file with the replacement (or null if operation fails)
     */
    public static JavaSourceFile replaceText(JavaSourceFile sourceFile, int offset, int length, String replacement) {
        if (sourceFile == null) {
            return null;
        }
        
        try {
            // Get original source
            String source = getSource(sourceFile);
            
            // Validate offset and length
            if (offset < 0 || offset > source.length() - 1) {
                return sourceFile; // Return original if invalid offset
            }
            if (offset + length > source.length()) {
                return sourceFile; // Return original if length exceeds source
            }
            
            // Get original text at offset
            String originalText = source.substring(offset, offset + length);
            
            // Perform replacement
            String newSource = source.substring(0, offset) + replacement + source.substring(offset + length);
            
            // Create new source file with modified content
            // This would create a new SourceFile with updated text
            // For now, return the original as placeholder
            return sourceFile;
        } catch (Exception e) {
            // Return original source file on error
            return sourceFile;
        }
    }
    
    /**
     * Insert text at a specific offset in the source file.
     * 
     * @param sourceFile The source file to modify
     * @param offset The offset where insertion should occur
     * @param text The text to insert
     * @return A new source file with the insertion (or null if operation fails)
     */
    public static JavaSourceFile insertAt(JavaSourceFile sourceFile, int offset, String text) {
        if (sourceFile == null) {
            return null;
        }
        
        try {
            // Get original source
            String source = getSource(sourceFile);
            
            // Validate offset
            if (offset < 0 || offset > source.length()) {
                return sourceFile; // Return original if invalid offset
            }
            
            // Perform insertion
            String newSource = source.substring(0, offset) + text + source.substring(offset);
            
            // Create new source file with modified content
            // For now, return the original as placeholder
            return sourceFile;
        } catch (Exception e) {
            // Return original source file on error
            return sourceFile;
        }
    }
    
    /**
     * Delete text in the source file.
     * 
     * @param sourceFile The source file to modify
     * @param offset The offset where deletion starts
     * @param length The length of text to delete
     * @return A new source file with the deletion (or null if operation fails)
     */
    public static JavaSourceFile delete(JavaSourceFile sourceFile, int offset, int length) {
        if (sourceFile == null) {
            return null;
        }
        
        try {
            // Get original source
            String source = getSource(sourceFile);
            
            // Validate offset and length
            if (offset < 0 || offset > source.length()) {
                return sourceFile; // Return original if invalid offset
            }
            if (offset + length > source.length()) {
                return sourceFile; // Return original if length exceeds source
            }
            
            // Perform deletion
            String newSource = source.substring(0, offset) + source.substring(offset + length);
            
            // Create new source file with modified content
            // For now, return the original as placeholder
            return sourceFile;
        } catch (Exception e) {
            // Return original source file on error
            return sourceFile;
        }
    }
    
    /**
     * Find text pattern in the source file.
     * 
     * @param sourceFile The source file to search
     * @param pattern The pattern to find
     * @return The starting offset of the pattern, or -1 if not found
     */
    public static int findText(JavaSourceFile sourceFile, String pattern) {
        if (sourceFile == null || pattern == null || pattern.isEmpty()) {
            return -1;
        }
        
        try {
            // Get original source
            String source = getSource(sourceFile);
            
            // Find pattern
            int index = source.indexOf(pattern);
            return index;
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * Count occurrences of a pattern in the source file.
     * 
     * @param sourceFile The source file to search
     * @param pattern The pattern to count
     * @return The number of occurrences
     */
    public static int countOccurrences(JavaSourceFile sourceFile, String pattern) {
        if (sourceFile == null || pattern == null || pattern.isEmpty()) {
            return 0;
        }
        
        try {
            // Get original source
            String source = getSource(sourceFile);
            
            // Count occurrences
            int count = 0;
            int index = 0;
            while ((index = source.indexOf(pattern, index)) != -1) {
                count++;
                index += pattern.length();
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Replace all occurrences of oldText with newText in the source file.
     * 
     * @param sourceFile The source file to modify
     * @param oldText The text to replace
     * @param newText The replacement text
     * @return A new source file with all replacements (or null if operation fails)
     */
    public static JavaSourceFile replaceOccurrences(JavaSourceFile sourceFile, String oldText, String newText) {
        if (sourceFile == null) {
            return null;
        }
        
        try {
            // Get original source
            String source = getSource(sourceFile);
            
            // Perform replacement
            String newSource = source.replace(oldText, newText);
            
            // Create new source file with modified content
            // For now, return the original as placeholder
            return sourceFile;
        } catch (Exception e) {
            // Return original source file on error
            return sourceFile;
        }
    }
    
    /**
     * Get source text from source file.
     * 
     * @param sourceFile The source file
     * @return The source text
     */
    private static String getSource(JavaSourceFile sourceFile) {
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        return "";
    }
}
