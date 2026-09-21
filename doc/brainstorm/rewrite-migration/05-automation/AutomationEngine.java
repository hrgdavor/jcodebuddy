// {@link AutomationEngine} Main orchestration engine for OpenRewrite transformations.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Main orchestration engine for running OpenRewrite transformations in batch mode.
 * 
 * Provides methods to:
 * - Apply single transformations to files
 * - Apply transformations to all files in a source root
 * - Apply multiple transformations sequentially
 * - Validate source files
 * - Analyze source files
 * 
 * @see TransformationRegistry
 * @see BatchProcessor
 */
public class AutomationEngine {
    
    private final TransformationRegistry registry;
    private final ExecutorService executor;
    
    /**
     * Creates a new AutomationEngine with a default thread pool.
     */
    public AutomationEngine() {
        this.registry = new TransformationRegistry();
        this.executor = Executors.newFixedThreadPool(4);
    }
    
    /**
     * Creates a new AutomationEngine with a custom executor.
     * 
     * @param executor the executor service to use
     */
    public AutomationEngine(ExecutorService executor) {
        this.registry = new TransformationRegistry();
        this.executor = executor;
    }
    
    /**
     * Applies a single transformation to a source file.
     * 
     * @param sourceFile the source file path
     * @param transformationName the transformation name
     * @return the transformation result
     * @throws TransformationException if the transformation fails
     */
    public TransformationResult apply(Path sourceFile, String transformationName) {
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile cannot be null");
        }
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
        
        try {
            String sourceContent = Files.readString(sourceFile, StandardCharsets.UTF_8);
            String transformedContent = applyTransformation(sourceContent, transformationName);
            long duration = Duration.ofMillis(System.currentTimeMillis() - 0).toMillis();
            return TransformationResult.success(sourceFile, transformationName, transformedContent, duration);
        } catch (IOException e) {
            return TransformationResult.failure(sourceFile, transformationName, 
                "IO error: " + e.getMessage(), System.currentTimeMillis());
        } catch (TransformationException e) {
            return TransformationResult.failure(sourceFile, transformationName, e.getMessage(), System.currentTimeMillis());
        } catch (Exception e) {
            return TransformationResult.failure(sourceFile, transformationName, 
                "Unexpected error: " + e.getMessage(), System.currentTimeMillis());
        }
    }
    
    /**
     * Applies a transformation to all Java files in a source root.
     * 
     * @param sourceRoot the source root directory
     * @param transformationName the transformation name
     * @return list of transformation results
     */
    public List<TransformationResult> applyAll(Path sourceRoot, String transformationName) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
        
        List<Path> javaFiles = findJavaFiles(sourceRoot);
        List<TransformationResult> results = new ArrayList<>();
        
        for (Path file : javaFiles) {
            results.add(apply(file, transformationName));
        }
        
        return results;
    }
    
    /**
     * Applies multiple transformations sequentially to all Java files in a source root.
     * 
     * @param sourceRoot the source root directory
     * @param transformationNames list of transformation names to apply
     * @return map of transformation name to result list
     */
    public Map<String, List<TransformationResult>> applyAllSequential(Path sourceRoot, 
            List<String> transformationNames) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        if (transformationNames == null) {
            throw new IllegalArgumentException("transformationNames cannot be null");
        }
        
        Map<String, List<TransformationResult>> results = new ConcurrentHashMap<>();
        List<Path> javaFiles = findJavaFiles(sourceRoot);
        
        for (Path file : javaFiles) {
            for (String transformationName : transformationNames) {
                results.computeIfAbsent(transformationName, k -> new ArrayList<>())
                    .add(apply(file, transformationName));
            }
        }
        
        return results;
    }
    
    /**
     * Validates a source file.
     * 
     * @param sourceFile the source file to validate
     * @return the validation result
     */
    public ValidationResult validate(Path sourceFile) {
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile cannot be null");
        }
        
        try {
            String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
            long startTime = System.currentTimeMillis();
            
            // Check for common issues
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            // Basic validation checks
            if (content.isEmpty()) {
                errors.add("File is empty");
            }
            
            // Check for common Java issues
            if (content.contains("public class ") && !content.contains("package ")) {
                warnings.add("Class is public but no package declaration");
            }
            
            boolean isValid = errors.isEmpty();
            
            long duration = Duration.ofMillis(System.currentTimeMillis() - startTime).toMillis();
            
            return new ValidationResult(sourceFile, isValid, errors, warnings, duration);
        } catch (IOException e) {
            return new ValidationResult(sourceFile, false, 
                List.of("IO error: " + e.getMessage()), List.of(), System.currentTimeMillis());
        }
    }
    
    /**
     * Analyzes a source file.
     * 
     * @param sourceFile the source file to analyze
     * @return the analysis result
     */
    public AnalysisResult analyze(Path sourceFile) {
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile cannot be null");
        }
        
        try {
            String content = Files.readString(sourceFile, StandardCharsets.UTF_8);
            long startTime = System.currentTimeMillis();
            
            // Basic analysis
            Map<String, Object> analysis = new ConcurrentHashMap<>();
            
            int lineCount = countLines(content);
            int codeLines = countCodeLines(content);
            int commentLines = countCommentLines(content);
            int methodCount = countMethods(content);
            int classCount = countClasses(content);
            
            analysis.put("lines", lineCount);
            analysis.put("codeLines", codeLines);
            analysis.put("commentLines", commentLines);
            analysis.put("methodCount", methodCount);
            analysis.put("classCount", classCount);
            
            List<String> issues = new ArrayList<>();
            
            // Simple issue detection
            if (codeLines < 10 && lineCount > 0) {
                issues.add("File is very short (less than 10 lines of code)");
            }
            
            long duration = Duration.ofMillis(System.currentTimeMillis() - startTime).toMillis();
            
            return new AnalysisResult(sourceFile, analysis, issues, duration);
        } catch (IOException e) {
            return new AnalysisResult(sourceFile, Map.of("error", e.getMessage()),
                List.of("IO error: " + e.getMessage()), System.currentTimeMillis());
        }
    }
    
    /**
     * Applies a transformation to content.
     * 
     * @param source the source content
     * @param transformationName the transformation name
     * @return the transformed content
     * @throws TransformationException if transformation fails
     */
    private String applyTransformation(String source, String transformationName) {
        Transformation transformation = registry.get(transformationName);
        return transformation.apply(source);
    }
    
    /**
     * Finds all Java files in a source root.
     * 
     * @param sourceRoot the source root directory
     * @return list of Java file paths
     */
    private List<Path> findJavaFiles(Path sourceRoot) {
        try {
            java.nio.file.Path root = sourceRoot.toAbsolutePath().normalize();
            return Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read source root: " + sourceRoot, e);
        }
    }
    
    /**
     * Counts lines in content.
     * 
     * @param content the content
     * @return line count
     */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.split("\n").length;
    }
    
    /**
     * Counts lines of code (non-empty, non-comment lines).
     * 
     * @param content the content
     * @return code line count
     */
    private int countCodeLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Counts comment lines.
     * 
     * @param content the content
     * @return comment line count
     */
    private int countCommentLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Counts methods in content.
     * 
     * @param content the content
     * @return method count
     */
    private int countMethods(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        java.util.regex.Pattern methodPattern = java.util.regex.Pattern.compile(
            "\\b(public\\s+)?(static\\s+)?(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        java.util.regex.Matcher matcher = methodPattern.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
    
    /**
     * Counts classes in content.
     * 
     * @param content the content
     * @return class count
     */
    private int countClasses(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        
        int count = 0;
        java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
            "\\b(public\\s+)?class\\s+(\\w+)");
        java.util.regex.Matcher matcher = classPattern.matcher(content);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
    
    /**
     * Gets the transformation registry.
     * 
     * @return the registry
     */
    public TransformationRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
