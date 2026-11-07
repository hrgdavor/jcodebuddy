// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.compile;

import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One source file to compile, expressed as an {@link ICompilationUnit} for the
 * ECJ internal {@code Compiler} API.
 *
 * <p>The contents are read eagerly at construction time, so the file is handed to
 * the compiler without further I/O. The package and type name are derived from the
 * file's location under the source root, once, and cached.
 *
 * <h2>Why {@link Path#toRealPath()} and not just {@code normalize()}</h2>
 * On a case-insensitive filesystem (Windows) a caller may hand in
 * {@code src/DEMO/Hello.java} for {@code src/demo/Hello.java}. {@code Path.relativize} is
 * case-insensitive there, but it keeps the <em>caller's</em> spelling of the
 * directory, which would make the expected package {@code DEMO} and fail against the
 * {@code package demo;} declaration ECJ finds in the file. {@link Path#toRealPath()}
 * answers with the on-disk spelling, so the package and main-type names the compiler
 * compares are the real ones. It also resolves symlinks, which keeps a source root
 * reached through a link consistent with the files under it.
 */
final class SourceUnit implements ICompilationUnit {

    /** Absolute, normalized path as the caller gave it; used for diagnostics. */
    private final Path file;
    private final char[] contents;
    private final char[] mainTypeName;
    private final char[][] packageName;
    private final String internalTypeName;

    /**
     * @param file       the {@code .java} file to compile
     * @param sourceRoot the source root the file's package is relative to
     * @throws IOException              if the file or the source root cannot be read
     * @throws IllegalArgumentException if the file is not a {@code .java} file under the source root
     */
    SourceUnit(Path file, Path sourceRoot) throws IOException {
        Path absoluteFile = file.toAbsolutePath().normalize();
        Path absoluteRoot = sourceRoot.toAbsolutePath().normalize();
        this.file = absoluteFile;

        Path realFile = absoluteFile.toRealPath();
        Path realRoot = absoluteRoot.toRealPath();
        Path relative = relativize(realRoot, realFile, absoluteFile, absoluteRoot);

        String relativeName = relative.toString().replace('\\', '/');
        if (!relativeName.endsWith(".java")) {
            throw new IllegalArgumentException("not a .java source file: " + absoluteFile);
        }
        String typeKey = relativeName.substring(0, relativeName.length() - 5);
        if (typeKey.isEmpty()) {
            throw new IllegalArgumentException("source file has no type name: " + absoluteFile);
        }
        this.internalTypeName = typeKey;

        int lastSlash = typeKey.lastIndexOf('/');
        String simpleName = lastSlash < 0 ? typeKey : typeKey.substring(lastSlash + 1);
        this.mainTypeName = simpleName.toCharArray();

        String packagePath = lastSlash < 0 ? "" : typeKey.substring(0, lastSlash);
        this.packageName = packagePath.isEmpty() ? new char[0][] : split(packagePath);

        this.contents = Files.readString(absoluteFile, StandardCharsets.UTF_8).toCharArray();
    }

    /** Full source text (read at construction time). */
    @Override
    public char[] getContents() {
        return contents;
    }

    /**
     * Simple class name without the package prefix, e.g. {@code "SampleMain"}.
     * For a source file with inner classes this is still the outer type, because the
     * name comes from the file name.
     */
    @Override
    public char[] getMainTypeName() {
        return mainTypeName;
    }

    /**
     * Package components derived from the file path relative to the source root.
     * e.g. {@code src/hr/hrg/sample/SampleMain.java} → {@code ["hr","hrg","sample"]},
     * and a file directly in the source root gets an empty array.
     */
    @Override
    public char[][] getPackageName() {
        return packageName;
    }

    /** Absolute path as a char array (used in ECJ diagnostics). */
    @Override
    public char[] getFileName() {
        return file.toString().toCharArray();
    }

    /** {@code false} — problems in the caller's own sources are always reported. */
    @Override
    public boolean ignoreOptionalProblems() {
        return false;
    }

    /**
     * ECJ 3.46 asks every unit for its module when the environment is not module-aware. These are
     * classpath sources, not module-path sources, so they belong to the unnamed module. Without this
     * override ECJ treats the absent module answer as a missing named module and reports a problem for
     * every file.
     */
    @Override
    public ModuleBinding module(LookupEnvironment environment) {
        return environment.getModule(ModuleBinding.UNNAMED);
    }

    /**
     * Internal type name in slash form, e.g. {@code "hr/hrg/sample/SampleMain"}.
     * Used as the key in {@link CachingNameEnvironment} for cross-unit resolution.
     */
    String getInternalTypeName() {
        return internalTypeName;
    }

    private static Path relativize(Path realRoot, Path realFile, Path file, Path root) {
        if (realFile.startsWith(realRoot)) {
            return realRoot.relativize(realFile);
        }
        throw new IllegalArgumentException(
                "source file is not under the source root: " + file + " (root: " + root + ")");
    }

    private static char[][] split(String packagePath) {
        String[] parts = packagePath.split("/");
        char[][] result = new char[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            result[i] = parts[i].toCharArray();
        }
        return result;
    }
}
