package hr.hrg.hipster.entity.tooling;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits the two builder entry points as {@code default} methods on the <strong>view interface
 * itself</strong> (plan.dsflash § 8.2/G2, § 8.6/3.17a, DEC-020).
 *
 * <h3>Why this is not just another whole-file emitter</h3>
 *
 * <p>Every other emission target is a file the generator owns. The view interface is the one file
 * the <em>developer</em> owns, and {@code toBuilder()}/{@code toBuilderTracking()} belong on it —
 * the hand-written example declares them exactly there:</p>
 *
 * <pre>{@code
 * public default PersonSummaryBuilder toBuilder(){ return new PersonSummaryBuilder(this); }
 * public default PersonSummaryBuilderTracking toBuilderTracking(){ return new PersonSummaryBuilderTracking(this); }
 * }</pre>
 *
 * <p>They carry no per-field logic, and leaving them out forces every consumer to write the same
 * three lines, so the generator supplies them. The consequences drive the whole design:</p>
 * <ul>
 *   <li><strong>The file is only written when a method is actually missing.</strong> If both are
 *       already present the method returns without touching the file, so the example — which
 *       declares both by hand — stays byte-identical across regeneration. That is not an
 *       optimisation; it is what keeps "regenerate the example" a no-op while the capability
 *       exists.</li>
 *   <li><strong>Recognition is by shape, never by marker comment.</strong> A {@code default} no-arg
 *       method with the right name means the developer has one, whether or not its body is the one
 *       the generator would write. A user-edited body is therefore preserved verbatim; deleting the
 *       method is the opt-in to get a fresh one back (DEC-020's three-state model).</li>
 *   <li><strong>When a method must be added, the rest of the file is preserved lexically.</strong>
 *       {@link LexicalPreservingPrinter} keeps the developer's own formatting everywhere except the
 *       new member, so adding one method does not reformat a hand-written interface.</li>
 * </ul>
 *
 * <p>Deliberately absent: any check that the method's <em>return type</em> matches the builder the
 * current level emits. A developer who narrowed the return type by hand owns that decision; a
 * generator that "corrected" it would be editing code it was told to leave alone.</p>
 */
public final class ViewInterfaceGenerator {

    private ViewInterfaceGenerator() {
    }

    /**
     * @param viewFile {@code null} when the view's source file was not located, in which case nothing
     *                 is emitted and the caller has no file to report
     * @param added    the method names actually added, in emission order; empty on a pure no-op
     */
    public record Result(Path viewFile, List<String> added, boolean alreadyPresent) {
    }

    /** The builder type the {@code toBuilder()} family returns, in emission order. */
    public record EntryPoint(String methodName, String builderType) {
    }

    /**
     * The entry points the given level must expose.
     *
     * <p>{@code BUILDER_ALL} is a superset of {@code BUILDER_TRACKED}, which is a superset of
     * {@code BUILDER}, so each level's list contains the lower level's (§ 8.1's cumulative ladder).
     * Levels below {@code BUILDER} emit none: there is no builder type for them to return.</p>
     */
    public static List<EntryPoint> entryPointsFor(hr.hrg.hipster.entity.api.GenLevel level, String viewName) {
        List<EntryPoint> entryPoints = new ArrayList<>();
        switch (level) {
            case BUILDER -> entryPoints.add(new EntryPoint("toBuilder", viewName + "Builder"));
            case BUILDER_TRACKED -> {
                entryPoints.add(new EntryPoint("toBuilder", viewName + "Builder"));
                entryPoints.add(new EntryPoint("toBuilderTracking", viewName + "BuilderTracking"));
            }
            case BUILDER_ALL -> {
                entryPoints.add(new EntryPoint("toBuilder", viewName + "Builder"));
                entryPoints.add(new EntryPoint("toBuilderTracking", viewName + "BuilderTracking"));
            }
            default -> {
                // META / RECORD / WRITABLE / DEFAULT: no builder exists at this level.
            }
        }
        return entryPoints;
    }

    /**
     * Adds any missing entry point to the view interface and rewrites the file only if something was
     * added.
     *
     * @param sourceRoot the java source root the view's own file lives under
     */
    public static Result generate(Path sourceRoot, String packageName, String viewName, List<EntryPoint> entryPoints)
            throws IOException {
        return generate(sourceRoot, packageName, viewName, entryPoints, null);
    }

    /**
     * As {@link #generate(Path, String, String, List)}, reporting a view file this pass could not read.
     *
     * <p>The file is the developer's own, so an unreadable previous revision is left untouched and
     * named in the report ({@link SourceReader}'s fail-safe direction). Reporting matters more here
     * than anywhere else: leaving the file alone is the correct action, but silence would make it
     * indistinguishable from "both methods were already present", which is the normal case.</p>
     */
    public static Result generate(Path sourceRoot, String packageName, String viewName,
                                  List<EntryPoint> entryPoints, DivergenceReporter divergences)
            throws IOException {
        if (entryPoints.isEmpty()) {
            return new Result(null, List.of(), true);
        }
        Path packageDir = packageName == null || packageName.isBlank()
                ? sourceRoot
                : sourceRoot.resolve(packageName.replace('.', '/'));
        Path viewFile = packageDir.resolve(viewName + ".java");
        if (!Files.exists(viewFile)) {
            return new Result(null, List.of(), true);
        }

        String text = Files.readString(viewFile);
        // Fail safe (SourceReader): this emitter writes to the developer's OWN file, so a file that
        // cannot be read must not be rewritten. It used to accept a partial parse, which meant a
        // syntax error could make an already-present `default` method invisible — and the generator
        // would then add a second copy of it to a file it could not correctly read.
        SourceReader.Read read = SourceReader.readText(text);
        if (!read.readable()) {
            SourceReader.reportUnparseable(divergences, "source_not_parsed",
                    packageName + "." + viewName,
                    "the view interface could not be parsed, so its entry points are unknown",
                    "add the builder entry points: the file was left exactly as it is");
            return new Result(viewFile, List.of(), false);
        }
        CompilationUnit cu = read.unit();
        ClassOrInterfaceDeclaration declaration = null;
        for (TypeDeclaration<?> type : cu.getTypes()) {
            if (type.getNameAsString().equals(viewName) && type instanceof ClassOrInterfaceDeclaration cid) {
                declaration = cid;
                break;
            }
        }
        if (declaration == null) {
            return new Result(viewFile, List.of(), true);
        }

        List<EntryPoint> missing = new ArrayList<>();
        for (EntryPoint entryPoint : entryPoints) {
            if (!hasDefaultMethod(declaration, entryPoint.methodName())) {
                missing.add(entryPoint);
            }
        }
        if (missing.isEmpty()) {
            // The hand-written-shape case: the developer (or a previous pass) already declared both,
            // so the file is left alone. This is what makes example regeneration a no-op.
            return new Result(viewFile, List.of(), true);
        }

        boolean lexical;
        try {
            LexicalPreservingPrinter.setup(cu);
            lexical = true;
        } catch (RuntimeException ignored) {
            lexical = false;
        }
        for (EntryPoint entryPoint : missing) {
            declaration.addMember(entryPointMethod(entryPoint));
        }
        String out;
        if (lexical) {
            try {
                out = LexicalPreservingPrinter.print(cu);
            } catch (RuntimeException e) {
                // The lexical printer refuses a few constructs it cannot place text for — an added
                // `default` modifier is one of them ("Not supported keywordDEFAULT"). Falling back to
                // the standard printer reformats the file, which is a cosmetic loss; refusing to add
                // the method would be a functional one, because the developer's opt-in is precisely
                // "I deleted it, please put it back".
                out = cu.toString();
            }
        } else {
            out = cu.toString();
        }
        Files.writeString(viewFile, out);
        return new Result(viewFile, missing.stream().map(EntryPoint::methodName).toList(), false);
    }

    /** Whether the interface already declares the entry point as a {@code default}, no-arg method. */
    private static boolean hasDefaultMethod(ClassOrInterfaceDeclaration declaration, String methodName) {
        for (MethodDeclaration method : declaration.getMethods()) {
            if (method.getNameAsString().equals(methodName) && method.isDefault() && method.getParameters().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code public default <Builder> toBuilder() { return new <Builder>(this); }}
     *
     * <p>Built through the AST rather than as text so the declaration is a real {@code MethodDeclaration}
     * that the lexical printer can splice into the surrounding formatting.</p>
     */
    private static MethodDeclaration entryPointMethod(EntryPoint entryPoint) {
        MethodDeclaration method = new MethodDeclaration();
        method.setPublic(true);
        method.setDefault(true);
        method.setType(new ClassOrInterfaceType(null, entryPoint.builderType()));
        method.setName(entryPoint.methodName());
        ObjectCreationExpr creation = new ObjectCreationExpr(null,
                new ClassOrInterfaceType(null, entryPoint.builderType()),
                com.github.javaparser.ast.NodeList.nodeList(new com.github.javaparser.ast.expr.ThisExpr()));
        method.setBody(new BlockStmt(com.github.javaparser.ast.NodeList.nodeList(new ReturnStmt(creation))));
        return method;
    }
}
