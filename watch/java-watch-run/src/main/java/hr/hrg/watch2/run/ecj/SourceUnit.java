// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run.ecj;

import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A source file to compile, expressed as an {@link ICompilationUnit} for the
 * ECJ internal {@code Compiler} API.
 *
 * <p>Contents are read eagerly at construction time so that the file can be
 * passed to the compiler without further I/O.
 */
public final class SourceUnit implements ICompilationUnit {

    private final Path   file;
    private final Path   srcDir;
    private final char[] contents;

    /**
     * @param file   the {@code .java} file to compile
     * @param srcDir the source root (used to derive the package name from the path)
     */
    public SourceUnit(Path file, Path srcDir) throws IOException {
        this.file    = file.toAbsolutePath().normalize();
        this.srcDir  = srcDir.toAbsolutePath().normalize();
        this.contents = Files.readString(this.file, StandardCharsets.UTF_8).toCharArray();
    }

    /** Full source text (read at construction time). */
    @Override public char[] getContents() { return contents; }

    /**
     * Simple class name without the package prefix, e.g. {@code "SampleMain"}.
     * For inner classes this will be the outer class name (the file name without {@code .java}).
     */
    @Override
    public char[] getMainTypeName() {
        String name = file.getFileName().toString();
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        return name.toCharArray();
    }

    /**
     * Package components derived from the file path relative to {@code srcDir}.
     * e.g. {@code src/hr/hrg/watch2/sample/SampleMain.java} → {@code ["hr","hrg","watch2","sample"]}.
     */
    @Override
    public char[][] getPackageName() {
        Path rel    = srcDir.relativize(file);
        Path parent = rel.getParent();
        if (parent == null) return new char[0][];
        int    n   = parent.getNameCount();
        char[][] pkg = new char[n][];
        for (int i = 0; i < n; i++) pkg[i] = parent.getName(i).toString().toCharArray();
        return pkg;
    }

    /** Absolute path as a char array (used in diagnostic messages). */
    @Override public char[] getFileName() { return file.toString().toCharArray(); }

    /** {@code false} — we always want errors reported for our source files. */
    @Override public boolean ignoreOptionalProblems() { return false; }

    /**
     * Internal type name in slash form, e.g. {@code "hr/hrg/watch2/sample/SampleMain"}.
     * Used as the key in {@link CachingNameEnvironment} for cross-unit resolution.
     */
    String getInternalTypeName() {
        Path rel = srcDir.relativize(file);
        String s = rel.toString().replace('\\', '/');
        if (s.endsWith(".java")) s = s.substring(0, s.length() - 5);
        return s;
    }
}
