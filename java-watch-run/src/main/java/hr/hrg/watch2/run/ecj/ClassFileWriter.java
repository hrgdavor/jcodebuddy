// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.run.ecj;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ICompilerRequestor} that receives compiled class files from the ECJ
 * {@code Compiler} and writes them to the {@code bin/} output directory.
 *
 * <p>Call {@link #reset()} before each compile, then check {@link #hasErrors()}
 * and {@link #getErrors()} after.
 */
public final class ClassFileWriter implements ICompilerRequestor {
    private static final Logger log = LoggerFactory.getLogger(ClassFileWriter.class);

    private final Path   binDir;
    private final List<String> errors = new ArrayList<>();
    private boolean hasErrors;

    public ClassFileWriter(Path binDir) { this.binDir = binDir; }

    /** Clear error state before a new compile round. */
    public void reset() { errors.clear(); hasErrors = false; }

    public boolean hasErrors()       { return hasErrors; }
    public List<String> getErrors()  { return errors; }

    @Override
    public void acceptResult(CompilationResult result) {
        // ── Collect problems ──────────────────────────────────────────────────
        if (result.hasProblems()) {
            for (IProblem p : result.getProblems()) {
                if (p.isError()) {
                    hasErrors = true;
                    errors.add(new String(p.getOriginatingFileName())
                            + ":" + p.getSourceLineNumber()
                            + "  " + p.getMessage());
                } else if (log.isDebugEnabled() && p.isWarning()) {
                    log.debug("ECJ warn {}:{} {}", new String(p.getOriginatingFileName()),
                            p.getSourceLineNumber(), p.getMessage());
                }
            }
        }

        // ── Write .class files only if this unit compiled cleanly ─────────────
        if (!result.hasErrors()) {
            for (ClassFile cf : result.getClassFiles()) {
                writeClassFile(cf);
            }
        }
    }

    /**
     * Writes a single class file to {@code bin/}.
     *
     * <p>The {@linkplain ClassFile#getCompoundName() compound name} already
     * encodes inner-class separators in the last segment, e.g.
     * {@code ["hr","hrg","sample","Foo$Bar"]}, so no extra splitting is needed.
     */
    private void writeClassFile(ClassFile cf) {
        char[][] compoundName = cf.getCompoundName();
        Path out = binDir;
        for (int i = 0; i < compoundName.length - 1; i++) {
            out = out.resolve(new String(compoundName[i]));
        }
        out = out.resolve(new String(compoundName[compoundName.length - 1]) + ".class");

        try {
            Files.createDirectories(out.getParent());
            Files.write(out, cf.getBytes());
            log.debug("Written: {}", binDir.relativize(out));
        } catch (IOException e) {
            log.error("Failed to write class file {}: {}", out, e.getMessage());
            hasErrors = true;
            errors.add("I/O error writing " + out + ": " + e.getMessage());
        }
    }
}
