package hr.hrg.hipster.entity.tooling.validation;

import hr.hrg.hipster.entity.tooling.SourceReader;
import hr.hrg.hipster.entity.tooling.TreeQueries;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.List;

/**
 * The one lookup that finds a file's first interface.
 *
 * <h3>Renamed in Phase 6, deliberately</h3>
 * <p>This class was {@code JavaParserTool}. It is no longer a JavaParser tool — the whole point of the
 * port — and a class named after the library it no longer uses is a trap for the next reader, who
 * greps for {@code JavaParser} to find what is left and lands here. It is the only class in this
 * module whose <em>name</em> was part of the migration surface, which is why the rename is in the
 * port's diff rather than deferred to a tidy-up.</p>
 *
 * <p>It is kept rather than deleted even though nothing in this repository calls it: it is a public
 * entry point of the tooling module, so a caller outside this tree may. Deleting it is a separate
 * decision about the module's API, not part of moving the parser.</p>
 */
public final class SourceQuery {

    private SourceQuery() {
    }

    /**
     * The first interface declared in {@code javaFile}, or an exception explaining why there is none.
     *
     * <p>Two JavaParser behaviours are gone with the port, and both change the contract:</p>
     * <ul>
     *   <li>The old parse called {@code result.getResult().orElseThrow(...)}, which accepted a
     *       <em>partial</em> tree — the F-34 hazard this module spent a note fixing elsewhere. The
     *       read now goes through {@link SourceReader}, so an unreadable file is an explicit failure
     *       rather than a possibly-empty tree.</li>
     *   <li>{@code cu.findFirst(ClassOrInterfaceDeclaration.class, isInterface)} searched every
     *       depth. {@link TreeQueries#interfaces} does the same, and the kind test is what makes it
     *       an interface search at all: {@link J.ClassDeclaration} alone would also match the file's
     *       records and enums.</li>
     * </ul>
     *
     * @throws IllegalStateException when the file cannot be read or declares no interface
     */
    public static J.ClassDeclaration findFirstInterface(Path javaFile) throws Exception {
        SourceReader.Read read = SourceReader.read(javaFile);
        if (!read.readable()) {
            throw new IllegalStateException("No compilation unit parsed for " + javaFile
                    + ": the file is missing or could not be read as Java at the configured language level");
        }
        List<J.ClassDeclaration> interfaces = TreeQueries.interfaces(read.unit());
        if (interfaces.isEmpty()) {
            throw new IllegalStateException("No interface found in " + javaFile);
        }
        return interfaces.get(0);
    }
}
