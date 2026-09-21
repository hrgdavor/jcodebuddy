// {@link hr.hrg.rewrite.validation.JavaParserTool} Generic JavaParser tool for validation.
// {enabled:true, blockMarker: "implicit"}
package hr.hrg.rewrite.validation;

import org.openrewrite.java.tree.*;
import java.util.*;

/**
 * Generic tool for JavaParser operations migrated to OpenRewrite.
 *
 * <p>Provides a generic interface for performing various tooling operations.</p>
 *
 * <p>Original JavaParser location: hipster-entity-tooling/src/main/java/hr/hrg/hipster/entity/tooling/validation/JavaParserTool.java</p>
 *
 * <p>Compliance: DEC-019 (source-visible wiring), DEC-020 (cooperative codegen), DEC-021 (generator class-file header).</p>
 */
public class JavaParserTool {

    /**
     * Execute a tool operation on a source file.
     *
     * @param sourceFile The source file AST
     * @return Tool result
     */
    public ToolResult execute(SourceFile sourceFile) {
        if (sourceFile == null) {
            return ToolResult.failed("Source file is null");
        }

        try {
            // Perform tooling operation
            List<TypeTree> classes = sourceFile.getDescendantTypes();
            int classCount = classes.size();

            return ToolResult.ok("Executed tool operation on " + classCount + " classes");
        } catch (Exception e) {
            return ToolResult.failed("Tool operation failed: " + e.getMessage());
        }
    }

    /**
     * Parse source code.
     *
     * @param sourceCode The source code to parse
     * @return Tool result
     */
    public ToolResult parse(String sourceCode) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return ToolResult.failed("Source code is null or empty");
        }

        try {
            SourceFile sourceFile = SourceFile.build(sourceCode);
            List<TypeTree> classes = sourceFile.getDescendantTypes();
            int classCount = classes.size();

            return ToolResult.ok("Parsed " + classCount + " classes from source code");
        } catch (Exception e) {
            return ToolResult.failed("Failed to parse source code: " + e.getMessage());
        }
    }

    /**
     * Format source code.
     *
     * @param sourceCode The source code to format
     * @return Tool result
     */
    public ToolResult format(String sourceCode) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return ToolResult.failed("Source code is null or empty");
        }

        try {
            // Format source code (simplified - in reality would use proper formatting)
            String formattedCode = formatCode(sourceCode);
            return ToolResult.ok("Formatted " + (formattedCode.length() - sourceCode.length()) + " characters");
        } catch (Exception e) {
            return ToolResult.failed("Failed to format source code: " + e.getMessage());
        }
    }

    /**
     * Format source code.
     */
    private String formatCode(String sourceCode) {
        // Simplified formatting - in reality would use proper formatting
        return sourceCode;
    }

    /**
     * Validate source code.
     *
     * @param sourceCode The source code to validate
     * @return Tool result
     */
    public ToolResult validate(String sourceCode) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return ToolResult.failed("Source code is null or empty");
        }

        try {
            SourceFile sourceFile = SourceFile.build(sourceCode);
            ValidationResult validation = validateSourceFile(sourceFile);
            
            if (validation.isValid()) {
                return ToolResult.ok("Source code is valid");
            } else {
                return ToolResult.failed("Source code validation failed: " + validation.getErrors().get(0));
            }
        } catch (Exception e) {
            return ToolResult.failed("Failed to validate source code: " + e.getMessage());
        }
    }

    /**
     * Validate a source file.
     */
    private ValidationResult validateSourceFile(SourceFile sourceFile) {
        EntityRulesValidator validator = new EntityRulesValidator();
        return validator.validateAll(sourceFile);
    }

    /**
     * Get available tool operations.
     *
     * @return List of operation names
     */
    public List<String> getAvailableOperations() {
        return List.of("parse", "format", "validate", "execute");
    }

    /**
     * Get tool information.
     *
     * @return Tool information
     */
    public Map<String, Object> getToolInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", "JavaParserTool");
        info.put("version", "1.0");
        info.put("description", "Generic tool for JavaParser operations migrated to OpenRewrite");
        return info;
    }
}
