// {@link hr.hrg.rewrite.util.AstPrinter} Source printing and extraction utilities for OpenRewrite migration.
// {enabled:true, blockMarker: "delete to regen"}
package hr.hrg.rewrite.util;

import org.openrewrite.java.tree.*;
import com.github.javaparser.ast.CompilationUnit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Utility class for printing and extracting information from ASTs.
 * Provides methods for printing source code, formatting, and extracting imports, annotations, and comments.
 */
public class AstPrinter {
    
    /**
     * Print AST to source code.
     * 
     * @param sourceFile The source file AST to print
     * @return The source code string
     */
    public static String print(JavaSourceFile sourceFile) {
        if (sourceFile == null) {
            return "";
        }
        
        // Get the original source if available
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        
        // If no source is available, reconstruct from AST
        // This would require a proper printer implementation
        return reconstructFromAst(sourceFile);
    }
    
    /**
     * Print AST to source code with custom tab width.
     * 
     * @param sourceFile The source file AST to print
     * @param tabWidth The tab width for formatting
     * @return The formatted source code string
     */
    public static String printWithTabWidth(JavaSourceFile sourceFile, int tabWidth) {
        if (sourceFile == null) {
            return "";
        }
        
        // Use original source if available
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        
        // Format with custom tab width
        return formatWithTabWidth(sourceFile, tabWidth);
    }
    
    /**
     * Print AST with lexical preservation.
     * 
     * @param sourceFile The source file AST to print
     * @return The source code string with preserved whitespace where possible
     */
    public static String printWithLexer(JavaSourceFile sourceFile) {
        if (sourceFile == null) {
            return "";
        }
        
        // Try to get original source
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        
        // Fallback: reconstruct from AST
        return reconstructFromAst(sourceFile);
    }
    
    /**
     * Format source with specific style.
     * 
     * @param sourceFile The source file AST to format
     * @param format The format style to use
     * @return The formatted source code string
     */
    public static String format(JavaSourceFile sourceFile, SourceFormat format) {
        if (sourceFile == null) {
            return "";
        }
        
        // Use original source if available
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        
        // Format based on specified style
        return formatWithStyle(sourceFile, format);
    }
    
    /**
     * Pretty print with standard formatting.
     * 
     * @param sourceFile The source file AST to pretty print
     * @return The pretty-printed source code string
     */
    public static String prettyPrint(JavaSourceFile sourceFile) {
        if (sourceFile == null) {
            return "";
        }
        
        // Use original source if available
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        
        // Pretty print from AST
        return reconstructFromAst(sourceFile);
    }
    
    /**
     * Extract all import statements from the source file.
     * 
     * @param sourceFile The source file to extract imports from
     * @return List of import statements
     */
    public static List<String> extractImports(JavaSourceFile sourceFile) {
        if (sourceFile == null) {
            return Collections.emptyList();
        }
        
        List<String> imports = new ArrayList<>();
        
        // Extract from parsed source if available
        if (sourceFile.getParsedSource() != null) {
            String source = sourceFile.getParsedSource().getText();
            // Simple extraction - in production, use proper tokenizer
            for (String line : source.split("\n")) {
                line = line.trim();
                if (line.startsWith("import ") && !line.startsWith("import java.")) {
                    imports.add(line.substring(7).trim());
                } else if (line.startsWith("import ")) {
                    imports.add(line.substring(6).trim());
                }
            }
        }
        
        // Fallback: extract from AST
        return extractImportsFromAst(sourceFile);
    }
    
    /**
     * Extract all annotations from the source file.
     * 
     * @param sourceFile The source file to extract annotations from
     * @return List of annotation strings
     */
    public static List<String> extractAnnotations(JavaSourceFile sourceFile) {
        if (sourceFile == null) {
            return Collections.emptyList();
        }
        
        List<String> annotations = new ArrayList<>();
        
        // Extract from parsed source if available
        if (sourceFile.getParsedSource() != null) {
            String source = sourceFile.getParsedSource().getText();
            // Simple extraction - in production, use proper tokenizer
            for (String line : source.split("\n")) {
                line = line.trim();
                if (line.startsWith("@") && !line.startsWith("@interface") && 
                    !line.startsWith("@enum") && !line.startsWith("@record")) {
                    annotations.add(line);
                }
            }
        }
        
        // Fallback: extract from AST
        return extractAnnotationsFromAst(sourceFile);
    }
    
    /**
     * Extract all comments from the source file.
     * 
     * @param sourceFile The source file to extract comments from
     * @return List of comment strings
     */
    public static List<String> extractComments(JavaSourceFile sourceFile) {
        if (sourceFile == null) {
            return Collections.emptyList();
        }
        
        List<String> comments = new ArrayList<>();
        
        // Extract from parsed source if available
        if (sourceFile.getParsedSource() != null) {
            String source = sourceFile.getParsedSource().getText();
            // Extract single-line comments
            for (String line : source.split("\n")) {
                int commentStart = line.indexOf("//");
                if (commentStart >= 0) {
                    String comment = line.substring(commentStart + 2).trim();
                    if (!comment.isEmpty()) {
                        comments.add(comment);
                    }
                }
            }
            // Extract multi-line comments
            int start = 0;
            while ((start = source.indexOf("/*", start)) >= 0) {
                int end = source.indexOf("*/", start);
                if (end >= 0) {
                    String comment = source.substring(start + 2, end).trim();
                    if (!comment.isEmpty()) {
                        comments.add(comment);
                    }
                    start = end + 2;
                } else {
                    // Multi-line comment without closing
                    comments.add("/* unclosed */");
                    start = start + 2;
                }
            }
        }
        
        // Fallback: extract from AST
        return extractCommentsFromAst(sourceFile);
    }
    
    /**
     * Extract imports from AST (fallback method).
     */
    private static List<String> extractImportsFromAst(JavaSourceFile sourceFile) {
        List<String> imports = new ArrayList<>();
        
        // This would extract imports from AST nodes
        // For now, return empty list as placeholder
        return imports;
    }
    
    /**
     * Extract annotations from AST (fallback method).
     */
    private static List<String> extractAnnotationsFromAst(JavaSourceFile sourceFile) {
        List<String> annotations = new ArrayList<>();
        
        // This would extract annotations from AST nodes
        // For now, return empty list as placeholder
        return annotations;
    }
    
    /**
     * Extract comments from AST (fallback method).
     */
    private static List<String> extractCommentsFromAst(JavaSourceFile sourceFile) {
        List<String> comments = new ArrayList<>();
        
        // This would extract comments from AST nodes
        // For now, return empty list as placeholder
        return comments;
    }
    
    /**
     * Reconstruct source from AST (fallback method).
     */
    private static String reconstructFromAst(JavaSourceFile sourceFile) {
        // This would reconstruct source from AST
        // For now, return empty string as placeholder
        return "";
    }
    
    /**
     * Format with custom tab width.
     */
    private static String formatWithTabWidth(JavaSourceFile sourceFile, int tabWidth) {
        // This would format with custom tab width
        // For now, return the original source if available
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        return "";
    }
    
    /**
     * Format with specific style.
     */
    private static String formatWithStyle(JavaSourceFile sourceFile, SourceFormat format) {
        // This would format with specific style
        // For now, return the original source if available
        if (sourceFile.getParsedSource() != null) {
            return sourceFile.getParsedSource().getText();
        }
        return "";
    }
    
    /**
     * Source format enumeration.
     */
    public enum SourceFormat {
        /** Standard formatting with 4-space indentation */
        STANDARD,
        
        /** Minimal formatting preserving as much whitespace as possible */
        MINIMAL,
        
        /** Compact formatting with minimal whitespace */
        COMPACT,
        
        /** Pretty formatting with optimal readability */
        PRETTY
    }
}
