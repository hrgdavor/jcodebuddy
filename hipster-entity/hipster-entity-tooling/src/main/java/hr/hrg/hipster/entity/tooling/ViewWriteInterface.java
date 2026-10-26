// SPDX-License-Identifier: GPL-3.0-only
package hr.hrg.hipster.entity.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.openrewrite.java.tree.J;

import hr.hrg.jcodebuddy.engine.source.SourceReader;
import hr.hrg.jcodebuddy.engine.source.TreeQueries;

/**
 * A view's nested {@code Write} interface, read from the view's own source (DEC-048's 2026-10-09 amendment).
 *
 * <p>The amendment makes a generated builder implement its view's {@code Write}, because that is the route
 * from a view instance to a writer ({@code view.toBuilder()}). The builder emitter and the patch-applier
 * emitter must agree about the interface's exact setters, so the read lives here rather than in either
 * emitter: a builder that implements a method whose parameter type it does not match does not compile, and
 * an applier that calls a setter the interface does not declare does not compile either.</p>
 *
 * <p>Read through {@link SourceReader}, the one place this repository decides whether a file is readable
 * (F-34). {@code null} means "not known" — no source path, or the file could not be parsed — and every
 * caller must then behave conservatively (no {@code implements} clause, no emitted call) rather than assume
 * an empty interface.</p>
 */
record ViewWriteInterface(List<Setter> setters) {

    /** One one-parameter method of the nested {@code Write}, in declaration order. */
    record Setter(String name, String parameterType) {
    }

    /** The setters a {@code Write} interface declares, or {@code null} when the source cannot be read. */
    static ViewWriteInterface readOrNull(Path viewSource) throws IOException {
        if (viewSource == null || !Files.isRegularFile(viewSource)) {
            return null;
        }
        SourceReader.Read read = SourceReader.read(viewSource);
        if (!read.readable()) {
            return null;
        }
        for (J.ClassDeclaration declaration : TreeQueries.interfaces(read.unit())) {
            if (!"Write".equals(declaration.getSimpleName())) {
                continue;
            }
            List<Setter> setters = new ArrayList<>();
            for (J.MethodDeclaration method : TreeQueries.methodsOf(declaration)) {
                if (!TreeQueries.hasOneParameter(method)) {
                    continue;
                }
                String parameterType = parameterType(method);
                if (parameterType != null) {
                    setters.add(new Setter(method.getSimpleName(), parameterType));
                }
            }
            return new ViewWriteInterface(List.copyOf(setters));
        }
        return null;
    }

    /** The setter's single parameter as source text, or {@code null} when it cannot be read. */
    private static String parameterType(J.MethodDeclaration method) {
        List<J.VariableDeclarations> parameters = new ArrayList<>();
        for (org.openrewrite.java.tree.Statement parameter : method.getParameters()) {
            if (parameter instanceof J.VariableDeclarations declarations) {
                parameters.add(declarations);
            }
        }
        if (parameters.size() != 1 || parameters.get(0).getTypeExpression() == null) {
            return null;
        }
        return TreeQueries.typeText(parameters.get(0).getTypeExpression());
    }
}
