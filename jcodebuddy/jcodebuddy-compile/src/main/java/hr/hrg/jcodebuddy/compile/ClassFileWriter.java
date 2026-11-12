// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.compile;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link ICompilerRequestor} that receives compiled class files from the ECJ
 * {@code Compiler}, records the problems it saw and writes the class files to the
 * output directory.
 *
 * <p>One writer is reused across compiles: {@link #reset()} clears the problems of
 * the previous round, then {@link #hasErrors()} and {@link #errors()} describe the
 * round that just finished. This is the other half of the incremental design — a
 * fresh ECJ {@code Compiler} per call, but no new writer.
 *
 * <p>A unit with errors gets no class files written, which is why a failed compile
 * cannot leave a half-written class behind for the next round to resolve.
 */
final class ClassFileWriter implements ICompilerRequestor {

    private static final Logger log = System.getLogger(ClassFileWriter.class.getName());

    private final Path binDir;
    private final List<String> errors = new ArrayList<>();
    private boolean hasErrors;
    private boolean writeIdentical = true;

    ClassFileWriter(Path binDir) {
        this.binDir = Objects.requireNonNull(binDir, "binDir").toAbsolutePath().normalize();
    }

    /**
     * When false, a class whose bytes equal the file already on disk is left untouched. Incremental
     * consumers use this so an unchanged output does not wake an external file watcher; a forced rebuild
     * leaves it true.
     */
    void setWriteIdentical(boolean writeIdentical) {
        this.writeIdentical = writeIdentical;
    }

    /** Clears the problems of the previous compile round. */
    void reset() {
        errors.clear();
        hasErrors = false;
    }

    /** Whether the last round reported an error or failed to write a class file. */
    boolean hasErrors() {
        return hasErrors;
    }

    /**
     * The diagnostics of the last round, in the order ECJ reported them. Each is
     * {@code "&lt;file&gt;:&lt;line&gt;  &lt;message&gt;"}, or a write-failure message.
     * The returned list is a snapshot and never null.
     */
    List<String> errors() {
        return List.copyOf(errors);
    }

    @Override
    public void acceptResult(CompilationResult result) {
        if (result.hasProblems()) {
            for (IProblem problem : result.getProblems()) {
                if (problem.isError()) {
                    hasErrors = true;
                    errors.add(diagnostic(problem));
                } else if (problem.isWarning() && log.isLoggable(Level.DEBUG)) {
                    log.log(Level.DEBUG, diagnostic(problem));
                }
            }
        }

        // A unit with errors must not leave class files behind: the next compile would
        // otherwise resolve a type whose source no longer compiles.
        if (!result.hasErrors()) {
            ClassFile[] classFiles = result.getClassFiles();
            // ECJ 3.46 can report null here for a unit that produced no code (for
            // example a package-info or an empty source); older releases returned an
            // empty array. Both mean "nothing to write".
            if (classFiles != null) {
                for (ClassFile classFile : classFiles) {
                    writeClassFile(classFile);
                }
            }
        }
    }

    private static String diagnostic(IProblem problem) {
        char[] originatingFile = problem.getOriginatingFileName();
        String file = originatingFile == null ? "<unknown>" : new String(originatingFile);
        String message = problem.getMessage() == null ? "<no message>" : problem.getMessage();
        return file + ":" + problem.getSourceLineNumber() + "  " + message;
    }

    /**
     * Writes one class file under {@code binDir}.
     *
     * <p>The {@linkplain ClassFile#getCompoundName() compound name} already encodes
     * inner-class separators in its last segment, e.g.
     * {@code ["hr","hrg","sample","Foo$Bar"]}, so no extra splitting is needed.
     */
    private void writeClassFile(ClassFile classFile) {
        char[][] compoundName = classFile.getCompoundName();
        Path out = binDir;
        for (int i = 0; i < compoundName.length - 1; i++) {
            out = out.resolve(new String(compoundName[i]));
        }
        out = out.resolve(new String(compoundName[compoundName.length - 1]) + ".class");

        try {
            Files.createDirectories(out.getParent());
            byte[] bytes = classFile.getBytes();
            if (!writeIdentical && Files.isRegularFile(out) && Arrays.equals(Files.readAllBytes(out), bytes)) {
                log.log(Level.DEBUG, "unchanged " + binDir.relativize(out));
                return;
            }
            Files.write(out, bytes);
            log.log(Level.DEBUG, "wrote " + binDir.relativize(out));
        } catch (IOException e) {
            hasErrors = true;
            errors.add("I/O error writing " + out + ": " + e.getMessage());
        }
    }
}
