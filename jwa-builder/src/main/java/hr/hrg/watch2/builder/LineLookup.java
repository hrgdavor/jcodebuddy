// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LineMap;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The line a type declaration's name sits on, asked of javac.
 *
 * <h3>Why this is not read from the tree</h3>
 * <p>This module selects a record by proximity to the cursor line, which the JavaParser version did
 * with {@code Range}. The OpenRewrite LST exposes <strong>no positions at all</strong> — a node does
 * not know where it came from — so the line has to come from something that still models positions.
 * javac is that thing, and it costs no new dependency: OpenRewrite's Java parser <em>is</em> a javac
 * front end, which is why `jdk.compiler` is already on the module path.</p>
 *
 * <p>The same recipe lives in the tooling module ({@code JavaSyntaxCheck.typeNameLines}), with the
 * matching rule recorded in `MIGRATION-CAVEATS.md` § 4.1. It is duplicated here rather than shared
 * because {@code hipster-entity-tooling} is not a dependency of this module and pulling a
 * code-generation toolchain in for twenty lines would be the worse trade.</p>
 */
final class LineLookup {

    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    private LineLookup() {
    }

    /**
     * The 1-based line of the first type declaration named {@code simpleName}, or {@code -1}.
     *
     * <p>The line is the <strong>name's</strong>, not the declaration's: a record under
     * {@code @GenerateBuilder} starts on the annotation's line, and selecting a record by proximity to
     * the cursor wants the line a reader would call its own.</p>
     */
    static int lineOf(String source, String simpleName) {
        Span span = spanOf(source, simpleName);
        return span == null ? -1 : span.nameLine();
    }

    /**
     * Where a type declaration named {@code simpleName} lives in {@code source}.
     *
     * @param startOffset the declaration's first character (its first annotation or modifier)
     * @param endOffset   one past its last character
     * @param nameLine    the 1-based line its <em>name</em> is on
     * @param startLine   the 1-based line the declaration starts on
     * @param endLine     the 1-based line it ends on
     */
    record Span(int startOffset, int endOffset, int nameLine, int startLine, int endLine) {
    }

    /**
     * Resolve a declaration's byte span and line numbers, or {@code null} when it is not found.
     *
     * <p>One javac pass answers everything the engine needs: the span to replace, the line to select
     * by, and the coordinates the {@link hr.hrg.watch2.core.CodeEdit} contract is expressed in.</p>
     */
    static Span spanOf(String source, String simpleName) {
        if (source == null || source.isBlank() || simpleName == null || COMPILER == null) {
            return null;
        }
        try (StandardJavaFileManager manager =
                     COMPILER.getStandardFileManager(null, Locale.ROOT, null)) {
            JavacTask task = (JavacTask) COMPILER.getTask(
                    null, manager, null, List.of("-proc:none", "-nowarn"),
                    null, List.of(new SourceFileObject(source)));
            var units = task.parse();
            var unit = units.iterator().hasNext() ? units.iterator().next() : null;
            if (unit == null) {
                return null;
            }
            LineMap lineMap = unit.getLineMap();
            var positions = Trees.instance(task).getSourcePositions();
            List<Span> found = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void unused) {
                    if (tree.getSimpleName().contentEquals(simpleName)) {
                        long start = positions.getStartPosition(unit, tree);
                        long end = positions.getEndPosition(unit, tree);
                        long name = namePositionIn(source, simpleName, start, end);
                        found.add(new Span(
                                (int) start,
                                (int) end,
                                (int) lineMap.getLineNumber(name),
                                (int) lineMap.getLineNumber(start),
                                (int) lineMap.getLineNumber(end)));
                    }
                    return super.visitClass(tree, unused);
                }
            }.scan(units, null);
            return found.isEmpty() ? null : found.get(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The 1-based column of {@code offset} within its line. */
    static int columnOf(String source, int offset) {
        if (source == null || offset < 0 || offset > source.length()) {
            return 1;
        }
        int lineStart = source.lastIndexOf('\n', Math.max(offset - 1, 0)) + 1;
        return offset - lineStart + 1;
    }

    /**
     * The offset of a declaration's name within its own declaration text.
     *
     * <p>A token-boundary match inside {@code [start, end)}, not {@code indexOf} on the whole file:
     * {@code @GenerateBuilder record GenerateBuilder {}} contains its own name inside the annotation,
     * and an unbounded search would return the annotation's occurrence — reporting the annotation's
     * line while looking authoritative.</p>
     */
    private static long namePositionIn(String source, String simpleName, long start, long end) {
        if (start < 0 || end > source.length()) {
            return Math.max(start, 0);
        }
        String region = source.substring((int) start, (int) end);
        for (int index = region.indexOf(simpleName); index >= 0; index = region.indexOf(simpleName, index + 1)) {
            boolean leftFree = index == 0 || !Character.isJavaIdentifierPart(region.charAt(index - 1));
            int after = index + simpleName.length();
            boolean rightFree = after >= region.length()
                    || !Character.isJavaIdentifierPart(region.charAt(after));
            if (leftFree && rightFree) {
                return start + index;
            }
        }
        return start;
    }

    /** An in-memory source file, so nothing touches the disk. */
    private static final class SourceFileObject extends SimpleJavaFileObject {

        private final String content;

        SourceFileObject(String content) {
            super(URI.create("string:///LineLookup.java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
