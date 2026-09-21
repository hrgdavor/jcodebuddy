// {@link BatchProcessor} Batch processor for transforming multiple files.
// {enabled:true, blockMarker: "implicit"}
package doc.brainstorm.rewrite_migration.phase5_automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Batch processor for applying transformations to multiple files.
 * 
 * Provides both parallel and sequential processing modes.
 * 
 * @see AutomationEngine
 */
public class BatchProcessor {
    private final AutomationEngine engine;
    private final ExecutorService executor;
    
    /**
     * Creates a new BatchProcessor with default executor.
     * 
     * @param engine the automation engine to use
     */
    public BatchProcessor(AutomationEngine engine) {
        this.engine = engine;
        this.executor = Executors.newFixedThreadPool(8);
    }
    
    /**
     * Processes all Java files in a source root with the given transformation in parallel.
     * 
     * @param sourceRoot the source root directory
     * @param transformationName the transformation name
     */
    public void process(Path sourceRoot, String transformationName) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
        
        processParallel(sourceRoot, transformationName);
    }
    
    /**
     * Processes all Java files in a source root with the given transformation sequentially.
     * 
     * @param sourceRoot the source root directory
     * @param transformationName the transformation name
     */
    public void processSequential(Path sourceRoot, String transformationName) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        if (transformationName == null || transformationName.isEmpty()) {
            throw new IllegalArgumentException("transformationName cannot be null or empty");
        }
        
        List<TransformationResult> results = engine.applyAll(sourceRoot, transformationName);
        reportResults(results, sourceRoot, transformationName);
    }
    
    /**
     * Processes all Java files in a source root with the given transformation in parallel.
     * 
     * @param sourceRoot the source root directory
     * @param transformationName the transformation name
     */
    private void processParallel(Path sourceRoot, String transformationName) {
        List<Path> javaFiles = findJavaFiles(sourceRoot);
        
        if (javaFiles.isEmpty()) {
            reportResults(List.of(), 0, 0);
            return;
        }
        
        List<CompletableFuture<TransformationResult>> futures = new ArrayList<>();
        
        for (Path file : javaFiles) {
            CompletableFuture<TransformationResult> future = CompletableFuture.supplyAsync(
                () -> engine.apply(file, transformationName),
                executor
            );
            futures.add(future);
        }
        
        List<TransformationResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
        
        reportResults(results, sourceRoot, transformationName);
    }
    
    /**
     * Finds all Java files in a source root.
     * 
     * @param sourceRoot the source root directory
     * @return list of Java file paths
     */
    public List<Path> findJavaFiles(Path sourceRoot) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        
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
     * Finds all Java files matching a glob pattern.
     * 
     * @param sourceRoot the source root directory
     * @param globPattern the glob pattern (e.g., "*Controller.java")
     * @return list of matching Java file paths
     */
    public List<Path> findJavaFilesMatching(Path sourceRoot, String globPattern) {
        if (sourceRoot == null) {
            throw new IllegalArgumentException("sourceRoot cannot be null");
        }
        if (globPattern == null || globPattern.isEmpty()) {
            throw new IllegalArgumentException("globPattern cannot be null or empty");
        }
        
        List<Path> allJavaFiles = findJavaFiles(sourceRoot);
        
        return allJavaFiles.stream()
                .filter(path -> path.toString().matches(globPattern))
                .collect(Collectors.toList());
    }
    
    /**
     * Reports the results of processing.
     * 
     * @param processedFiles the files that were processed
     * @param succeeded number of successful transformations
     * @param failed number of failed transformations
     */
    public void reportResults(List<Path> processedFiles, int succeeded, int failed) {
        System.out.println("Batch processing complete:");
        System.out.println("  Total files: " + (succeeded + failed));
        System.out.println("  Succeeded: " + succeeded);
        System.out.println("  Failed: " + failed);
        
        if (failed > 0) {
            System.out.println("  Warning: " + failed + " file(s) failed processing");
        }
    }
    
    /**
     * Reports detailed results from a list of transformation results.
     * 
     * @param results the transformation results
     * @param sourceRoot the source root
     * @param transformationName the transformation name
     */
    private void reportResults(List<TransformationResult> results, Path sourceRoot, String transformationName) {
        int succeeded = (int) results.stream()
                .filter(TransformationResult::succeeded)
                .count();
        
        int failed = (int) results.stream()
                .filter(r -> !r.succeeded())
                .count();
        
        reportResults(List.of(), succeeded, failed);
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
