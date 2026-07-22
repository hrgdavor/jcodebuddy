// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;

/**
 * Analyzer to detect markers and suggest tools based on Java source context.
 */
public class ContextualAnalyzer {

    public record Trigger(String toolName, int line) {
        public boolean isDiscovery() {
            return toolName == null || toolName.isEmpty();
        }
    }

    public record Watch(String target, int line) {
    }

    public List<Watch> findWatches(Path path) {
        List<Watch> watches = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                int idx = line.indexOf("// " + "@watch:");
                if (idx != -1) {
                    String target = line.substring(idx + ("// " + "@watch:").length()).trim().split("\\s+")[0];
                    watches.add(new Watch(target, lineNum));
                }
            }
        } catch (IOException e) {
        }
        return watches;
    }

    /**
     * Scans a file for markers.
     * Handles markers like "@gen tool" or "@gen" in comments.
     */
    public Optional<Trigger> findTrigger(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                int idx = line.indexOf("// " + "@gen");
                if (idx != -1) {
                    String remaining = line.substring(idx + ("// " + "@gen").length()).trim();
                    String toolName = remaining.isEmpty() ? null : remaining.split("\\s+")[0];
                    return Optional.of(new Trigger(toolName, lineNum));
                }
            }
        } catch (IOException e) {
            // Log or handle
        }
        return Optional.empty();
    }

    /**
     * Suggests tools based on the code structure at the given line.
     */
    public List<String> suggestTools(Path path, int lineNum) {
        List<String> suggestions = new ArrayList<>();
        JavaParser parser = JavaParserFactory.getParser();
        try {
            ParseResult<CompilationUnit> result = parser.parse(path);
            if (!result.isSuccessful()) {
                System.err.println("Parse failed for " + path + ": " + result.getProblems());
                return suggestions;
            }
            CompilationUnit cu = result.getResult().get();

            // Find all candidates and pick the one with smallest range that contains the
            // line
            ClassOrInterfaceDeclaration classCandidate = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(cid -> isNear(cid, lineNum) || isIn(cid, lineNum))
                    .min((a, b) -> Integer.compare(getLineCount(a), getLineCount(b)))
                    .orElse(null);

            RecordDeclaration recordCandidate = cu.findAll(RecordDeclaration.class).stream()
                    .filter(rd -> isNear(rd, lineNum) || isIn(rd, lineNum))
                    .min((a, b) -> Integer.compare(getLineCount(a), getLineCount(b)))
                    .orElse(null);

            // If we are inside both a class and a record (highly unlikely in standard Java,
            // but nested classes),
            // we should pick the most specific one.
            boolean isRecordMoreSpecific = false;
            if (classCandidate != null && recordCandidate != null) {
                isRecordMoreSpecific = getLineCount(recordCandidate) < getLineCount(classCandidate);
            }

            if (recordCandidate != null && (classCandidate == null || isRecordMoreSpecific)) {
                suggestions.add("record_builder");
            } else if (classCandidate != null) {
                suggestions.add("builder");
                suggestions.add("getters");
                suggestions.add("setters");
                suggestions.add("constructor");
            }

            if (suggestions.isEmpty()) {
                System.out.println("[DEBUG] No suggestions found for " + path.getFileName() + " at line " + lineNum);
                System.out.println("[DEBUG] ClassCandidate: "
                        + (classCandidate == null ? "null" : classCandidate.getNameAsString())
                        + " (isIn: " + (classCandidate != null && isIn(classCandidate, lineNum))
                        + ", isNear: " + (classCandidate != null && isNear(classCandidate, lineNum)) + ")");
                System.out.println("[DEBUG] RecordCandidate: "
                        + (recordCandidate == null ? "null" : recordCandidate.getNameAsString())
                        + " (isIn: " + (recordCandidate != null && isIn(recordCandidate, lineNum))
                        + ", isNear: " + (recordCandidate != null && isNear(recordCandidate, lineNum)) + ")");
            } else {
                System.out.println(
                        "[DEBUG] Suggestions for " + path.getFileName() + " at line " + lineNum + ": " + suggestions);
            }

        } catch (Exception e) {
            // Source might be invalid during edit
        }
        return suggestions;
    }

    private int getLineCount(Node node) {
        return node.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(Integer.MAX_VALUE);
    }

    private boolean isNear(Node node, int line) {
        return node.getRange().map(r -> Math.abs(r.begin.line - line) <= 5).orElse(false);
    }

    private boolean isIn(Node node, int line) {
        return node.getRange().map(r -> line >= r.begin.line && line <= r.end.line).orElse(false);
    }
}
