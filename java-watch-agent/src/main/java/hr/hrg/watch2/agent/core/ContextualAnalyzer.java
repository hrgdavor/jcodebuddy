// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.agent.core;

import hr.hrg.watch2.builder.ClassMemberProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
     *
     * <p>Phase 6: the JavaParser walk is gone. The type declarations and their line spans come from
     * {@link ClassMemberProcessor#typesIn}, which reads the structure from the LST and the positions from
     * javac — an LST node has no positions at all — and the selection rule is unchanged: prefer the
     * <em>most specific</em> declaration containing or near the line, and let a record win over a class
     * because a record's builder is what {@code record_builder} means. A file that cannot be read yields
     * no suggestions, which is the same answer the old {@code catch} produced.</p>
     */
    public List<String> suggestTools(Path path, int lineNum) {
        List<String> suggestions = new ArrayList<>();
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException unreadable) {
            // Source might be invalid during edit.
            return suggestions;
        }

        ClassMemberProcessor.TypeAt classCandidate = null;
        ClassMemberProcessor.TypeAt recordCandidate = null;
        for (ClassMemberProcessor.TypeAt type : ClassMemberProcessor.typesIn(source)) {
            if (!isNear(type, lineNum) && !isIn(type, lineNum)) {
                continue;
            }
            if (type.isRecord()) {
                if (recordCandidate == null || type.lineCount() < recordCandidate.lineCount()) {
                    recordCandidate = type;
                }
            } else if (classCandidate == null || type.lineCount() < classCandidate.lineCount()) {
                classCandidate = type;
            }
        }

        // If the caret is inside both a class and a record (a record nested in a class, which the tools
        // offer on), the most specific one wins.
        boolean isRecordMoreSpecific = classCandidate != null && recordCandidate != null
                && recordCandidate.lineCount() < classCandidate.lineCount();

        if (recordCandidate != null && (classCandidate == null || isRecordMoreSpecific)) {
            suggestions.add("record_builder");
        } else if (classCandidate != null) {
            suggestions.add("builder");
            suggestions.add("getters");
            suggestions.add("setters");
            suggestions.add("constructor");
        }

        return suggestions;
    }

    private static boolean isNear(ClassMemberProcessor.TypeAt type, int line) {
        return type.startLine() > 0 && Math.abs(type.startLine() - line) <= 5;
    }

    private static boolean isIn(ClassMemberProcessor.TypeAt type, int line) {
        return type.startLine() > 0 && line >= type.startLine() && line <= type.endLine();
    }
}
