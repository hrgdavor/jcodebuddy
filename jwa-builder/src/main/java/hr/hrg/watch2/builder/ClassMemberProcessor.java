// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Class-oriented member generation for the language-server tools: accessors, a builder and constructors,
 * generated as <strong>text</strong> and spliced into the class the caret is on.
 *
 * <h3>Why this class exists</h3>
 * <p>Phase 6 of the rewrite-migration plan. Three tools in {@code java-watch-agent} —
 * {@code AccessorGenerator}, {@code BuilderGenerator} and {@code ConstructorGenerator} — each used
 * JavaParser to parse the file, mutate the class ({@code addMember}), and return
 * {@code cu.toString()}. Two things were wrong with that, and only one of them was the parser:</p>
 * <ul>
 *   <li>the LST is immutable, so {@code addMember} has no equivalent — but mutation was never what the
 *       feature is. The feature is "this class gains these members", and the direct expression of that
 *       is to generate their text and put them in the class;</li>
 *   <li>returning {@code cu.toString()} re-printed the <strong>whole file</strong>, so an unrelated
 *       formatting difference anywhere in it became part of the edit. Splicing changes only the class
 *       body, which is the property {@link SourceSplicer} already established for records.</li>
 * </ul>
 *
 * <p>This is the same move that ported {@code JwaTextDocumentService} to
 * {@link RecordBuilderProcessor}: the tree is read here, the text is generated here, and the tools
 * become adapters. It is also what makes the behaviour testable — the agent module has no tests at all,
 * while this one does.</p>
 *
 * <h3>What is read from the tree, and what from javac</h3>
 * <p>The LST answers everything structural: which class is nearest the caret, what its fields are, and
 * which members it already declares. It cannot answer <em>where</em> anything is — an LST node has no
 * positions — so the caret arithmetic and the body braces come from javac ({@link LineLookup}) and from
 * brace matching over the text, exactly as {@link SourceSplicer} does it.</p>
 */
public final class ClassMemberProcessor {

    private final String indent;

    public ClassMemberProcessor(String indent) {
        this.indent = indent;
    }

    public String getIndent() {
        return indent;
    }

    /** One declared field: what a generated accessor or constructor needs to know about it. */
    public record Field(String type, String name, boolean isFinal) {
    }

    /** One existing member, reduced to the facts that decide whether a generated one would duplicate it. */
    public record Member(String name, int parameterCount, boolean constructor, boolean isStatic) {
    }

    /**
     * The class the caret is on, with everything the generators read from it.
     *
     * @param className   the class's simple name
     * @param fields      its declared fields, in declaration order
     * @param members     its declared methods and constructors
     * @param startOffset the class declaration's first character
     * @param endOffset   one past its last character
     * @param nameLine    the 1-based line its name sits on
     */
    public record Target(String className, List<Field> fields, List<Member> members,
                         int startOffset, int endOffset, int nameLine) {
    }

    /**
     * One type declaration in a source text, with the lines it occupies.
     *
     * <p>What the "which tools make sense at this caret" question needs: a class offers a builder and
     * accessors, a record offers its builder, and the most <em>specific</em> declaration wins when the
     * caret is inside a nested one. Order is source order.</p>
     *
     * @param startLine the line the declaration starts on (its first annotation, if any)
     * @param endLine   the line its closing brace is on
     */
    public record TypeAt(String simpleName, boolean isRecord, int startLine, int endLine) {

        /** How many lines the declaration spans, or {@link Integer#MAX_VALUE} when it is unknown. */
        public int lineCount() {
            return startLine < 1 || endLine < startLine ? Integer.MAX_VALUE : endLine - startLine + 1;
        }
    }

    /**
     * The class in {@code source} nearest {@code line}, or {@code null} when the text declares none.
     *
     * <p>Selection reproduces the JavaParser version: prefer a class whose <em>name</em> line is within
     * five lines of the caret, and otherwise take the first class in the file. The window exists because
     * an editor command is issued with the caret somewhere in or near the class, not on it.</p>
     */
    public Target target(String source, int line) {
        J.ClassDeclaration declaration = classNearest(source, line);
        if (declaration == null || !isUsableName(declaration.getSimpleName())) {
            return null;
        }
        LineLookup.Span span = LineLookup.spanOf(source, declaration.getSimpleName());
        if (span == null) {
            // Without a span the class's body cannot be located, and splicing into the wrong place is
            // worse than doing nothing: the caller's `null` is "no target", which produces no edit.
            return null;
        }
        return new Target(declaration.getSimpleName(), fieldsOf(declaration), membersOf(declaration),
                span.startOffset(), span.endOffset(), span.nameLine());
    }

    /**
     * Whether a declaration's name is a real Java identifier.
     *
     * <p>Not a formality: OpenRewrite <strong>recovers</strong> from a syntax error and hands back a
     * well-formed tree, so {@code public class {} —} a file being edited into an invalid state — yields a
     * class declaration whose name is the error placeholder {@code <error>}. javac reports the same
     * recovered declaration, so a span is found and the target looks usable. Generating members into it
     * would edit a class that does not exist, from source that does not compile; the honest answer is the
     * one the caller already has a branch for — no target, no edit. (The JavaParser version threw here,
     * which is why this is a guard rather than a comment.)</p>
     */
    private static boolean isUsableName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()
                || !Character.isJavaIdentifierStart(simpleName.charAt(0))) {
            return false;
        }
        for (int index = 1; index < simpleName.length(); index++) {
            if (!Character.isJavaIdentifierPart(simpleName.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /** Every type declaration in {@code source}, in source order. */
    public static List<TypeAt> typesIn(String source) {
        List<TypeAt> types = new ArrayList<>();
        J.CompilationUnit unit = parse(source);
        if (unit == null) {
            return types;
        }
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration,
                                                            ExecutionContext ctx) {
                LineLookup.Span span = isUsableName(declaration.getSimpleName())
                        ? LineLookup.spanOf(source, declaration.getSimpleName())
                        : null;
                if (span != null) {
                    types.add(new TypeAt(declaration.getSimpleName(),
                            declaration.getKind() == J.ClassDeclaration.Kind.Type.Record,
                            span.startLine(), span.endLine()));
                }
                return super.visitClassDeclaration(declaration, ctx);
            }
        }.visit(unit, new InMemoryExecutionContext());
        return types;
    }

    /**
     * {@code source} with the missing accessors for {@code target}'s fields added.
     *
     * <p>A getter is {@code getX()} — {@code isX()} for a {@code boolean} — and a setter is
     * {@code setX(T x)}. Either is skipped when the class already declares a member of that name and
     * arity, and a setter is skipped for a {@code final} field: the JavaParser version compared the
     * declared name and the arity, and that is the whole rule.</p>
     */
    public String withAccessors(String source, Target target, boolean getters, boolean setters) {
        if (target == null) {
            return source;
        }
        List<String> members = new ArrayList<>();
        for (Field field : target.fields()) {
            String capitalized = capitalize(field.name());
            if (getters) {
                String getterName = "boolean".equalsIgnoreCase(field.type()) ? "is" + capitalized : "get" + capitalized;
                if (!hasMember(target, getterName, 0, false)) {
                    members.add("public " + field.type() + " " + getterName + "() {\n"
                            + indent + "return " + field.name() + ";\n"
                            + "}");
                }
            }
            if (setters && !field.isFinal()) {
                String setterName = "set" + capitalized;
                if (!hasMember(target, setterName, 1, false)) {
                    members.add("public void " + setterName + "(" + field.type() + " " + field.name() + ") {\n"
                            + indent + "this." + field.name() + " = " + field.name() + ";\n"
                            + "}");
                }
            }
        }
        return splice(source, target, members);
    }

    /**
     * {@code source} with a builder for {@code target}'s class added.
     *
     * <p>Skipped entirely when the class already declares a static no-argument {@code builder()} or a
     * nested type named {@code <Class>Builder}: the JavaParser version did the same, and it is what makes
     * the operation idempotent — a second run finds its own output and leaves it alone.</p>
     */
    public String withBuilder(String source, Target target) {
        if (target == null) {
            return source;
        }
        String builderName = target.className() + "Builder";
        if (hasMember(target, "builder", 0, true) || hasNestedType(source, target, builderName)) {
            return source;
        }
        String nl = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append("public static ").append(target.className()).append("Builder builder() { return new ")
                .append(builderName).append("(); }");
        builder.append(nl).append(nl).append("public static class ").append(builderName).append(" {");
        for (Field field : target.fields()) {
            builder.append(nl).append(indent).append("private ").append(field.type()).append(' ')
                    .append(field.name()).append(';');
        }
        for (Field field : target.fields()) {
            builder.append(nl).append(nl).append(indent).append("public ").append(builderName).append(' ')
                    .append(field.name()).append('(').append(field.type()).append(' ').append(field.name())
                    .append(") {").append(nl).append(indent).append(indent)
                    .append("this.").append(field.name()).append(" = ").append(field.name()).append(';')
                    .append(nl).append(indent).append("return this;").append(nl).append(indent).append('}');
        }
        builder.append(nl).append(nl).append(indent).append("public ").append(target.className())
                .append(" build() { return new ").append(target.className()).append("(); }");
        builder.append(nl).append('}');
        return splice(source, target, List.of(builder.toString()));
    }

    /**
     * {@code source} with an all-arguments constructor and a no-argument constructor added.
     *
     * <p>Each is skipped when the class already declares a constructor of that arity. The all-arguments
     * form is also skipped for a class with no fields, where it would be the same constructor as the
     * no-argument one — the JavaParser version had the same guard, and without it the class ended up with
     * two identical ones.</p>
     */
    public String withConstructors(String source, Target target) {
        if (target == null) {
            return source;
        }
        List<String> members = new ArrayList<>();
        if (!hasMember(target, target.className(), target.fields().size(), true) && !target.fields().isEmpty()) {
            StringBuilder allArgs = new StringBuilder();
            allArgs.append("public ").append(target.className()).append('(');
            for (int index = 0; index < target.fields().size(); index++) {
                Field field = target.fields().get(index);
                if (index > 0) {
                    allArgs.append(", ");
                }
                allArgs.append(field.type()).append(' ').append(field.name());
            }
            allArgs.append(") {");
            for (Field field : target.fields()) {
                allArgs.append(System.lineSeparator()).append(indent)
                        .append("this.").append(field.name()).append(" = ").append(field.name()).append(';');
            }
            allArgs.append(System.lineSeparator()).append('}');
            members.add(allArgs.toString());
        }
        if (!hasMember(target, target.className(), 0, true)) {
            members.add("public " + target.className() + "() {}");
        }
        return splice(source, target, members);
    }

    // ------------------------------------------------------------------ the tree ---

    /** The class declaration nearest {@code line}, or the first class, or {@code null}. */
    private static J.ClassDeclaration classNearest(String source, int line) {
        J.CompilationUnit unit = parse(source);
        if (unit == null) {
            return null;
        }
        List<J.ClassDeclaration> classes = new ArrayList<>();
        new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext ctx) {
                if (declaration.getKind() == J.ClassDeclaration.Kind.Type.Class) {
                    classes.add(declaration);
                }
                return super.visitClassDeclaration(declaration, ctx);
            }
        }.visit(unit, new InMemoryExecutionContext());

        J.ClassDeclaration nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (J.ClassDeclaration candidate : classes) {
            LineLookup.Span span = LineLookup.spanOf(source, candidate.getSimpleName());
            if (span == null) {
                continue;
            }
            int distance = Math.abs(span.nameLine() - line);
            if (distance <= 5 && distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        if (nearest != null) {
            return nearest;
        }
        for (J.ClassDeclaration candidate : classes) {
            if (LineLookup.spanOf(source, candidate.getSimpleName()) != null) {
                return candidate;
            }
        }
        return null;
    }

    /** A class's declared fields, in declaration order. */
    private static List<Field> fieldsOf(J.ClassDeclaration declaration) {
        List<Field> fields = new ArrayList<>();
        if (declaration.getBody() == null) {
            return fields;
        }
        for (Statement statement : declaration.getBody().getStatements()) {
            if (!(statement instanceof J.VariableDeclarations variables) || variables.getVariables() == null) {
                continue;
            }
            String type = variables.getTypeExpression() == null ? "Object" : variables.getTypeExpression().toString();
            boolean isFinal = variables.hasModifier(J.Modifier.Type.Final);
            for (J.VariableDeclarations.NamedVariable variable : variables.getVariables()) {
                fields.add(new Field(type, variable.getSimpleName(), isFinal));
            }
        }
        return fields;
    }

    /** A class's declared methods and constructors. */
    private static List<Member> membersOf(J.ClassDeclaration declaration) {
        List<Member> members = new ArrayList<>();
        if (declaration.getBody() == null) {
            return members;
        }
        for (Statement statement : declaration.getBody().getStatements()) {
            if (!(statement instanceof J.MethodDeclaration method)) {
                continue;
            }
            int parameterCount = method.getParameters() == null || isNoParameters(method)
                    ? 0 : method.getParameters().size();
            members.add(new Member(method.getSimpleName(), parameterCount, method.isConstructor(),
                    method.hasModifier(J.Modifier.Type.Static)));
        }
        return members;
    }

    /** Whether a method declares no parameters, counting the LST's `J.Empty` placeholder as none. */
    private static boolean isNoParameters(J.MethodDeclaration method) {
        List<Statement> parameters = method.getParameters();
        return parameters.isEmpty() || (parameters.size() == 1 && parameters.get(0) instanceof J.Empty);
    }

    /** Whether the class already declares a member of this name, arity and kind. */
    private static boolean hasMember(Target target, String name, int parameterCount, boolean constructor) {
        for (Member member : target.members()) {
            if (member.name().equals(name) && member.parameterCount() == parameterCount
                    && member.constructor() == constructor) {
                return true;
            }
        }
        return false;
    }

    /** Whether the class declares a nested type of this name. */
    private boolean hasNestedType(String source, Target target, String nestedName) {
        J.CompilationUnit unit = parse(source);
        if (unit == null) {
            return false;
        }
        J.ClassDeclaration declaration = classNearest(source, target.nameLine());
        if (declaration == null || declaration.getBody() == null) {
            return false;
        }
        for (Statement statement : declaration.getBody().getStatements()) {
            if (statement instanceof J.ClassDeclaration nested && nested.getSimpleName().equals(nestedName)) {
                return true;
            }
        }
        return false;
    }

    /** Parses source into an LST, or {@code null} when it cannot be read. */
    private static J.CompilationUnit parse(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        ExecutionContext context = new InMemoryExecutionContext();
        // Nothing is written back from this tree, and the inputs are fragments rather than compilation
        // units, so the print-idempotency guard is off. It is *not* off for generation: the text this
        // class returns is produced by splicing, never by printing a tree.
        context.putMessage("org.openrewrite.requirePrintEqualsInput", false);
        // A parser per call, never a field: a parser caches the sources it has parsed and refuses a
        // second set declaring the same fully qualified names — which is exactly what "generate, then
        // generate again" produces (MIGRATION-CAVEATS.md § 1.2).
        JavaParser parser = JavaParser.fromJavaVersion().build();
        Path path = parser.sourcePathFromSourceText(Path.of("Class.java"), source);
        try (var stream = parser.parseInputs(List.of(input(path, source)), null, context)) {
            for (SourceFile file : stream.toList()) {
                if (file instanceof J.CompilationUnit unit) {
                    return unit;
                }
            }
        } catch (RuntimeException unreadable) {
            // "Could not be read" is not "nothing to do": the caller gets no target and makes no edit.
            return null;
        }
        return null;
    }

    /** One in-memory input for the parser, which never touches the disk. */
    private static Parser.Input input(Path path, String source) {
        return new Parser.Input(path, () -> new java.io.ByteArrayInputStream(
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ------------------------------------------------------------------ the text ---

    /**
     * {@code members} inserted before {@code target}'s closing brace, each on its own line at the
     * class's own indentation.
     *
     * <p>Everything outside the class body is untouched, and the class's existing members keep their
     * text: the insert is a single substring splice, which is what {@link SourceSplicer} does for a
     * record and for the same reason.</p>
     */
    private String splice(String source, Target target, List<String> members) {
        if (members.isEmpty()) {
            return source;
        }
        int bodyOpen = bodyOpenBrace(source, target);
        if (bodyOpen < 0) {
            return source;
        }
        int bodyClose = matchingBrace(source, bodyOpen);
        if (bodyClose < 0) {
            return source;
        }
        String classIndent = lineIndentBefore(source, bodyClose);
        String memberIndent = classIndent + indent;
        String nl = System.lineSeparator();
        StringBuilder insertion = new StringBuilder();
        for (String member : members) {
            insertion.append(nl).append(nl).append(memberIndent).append(member.replace("\n", nl + memberIndent));
        }
        return source.substring(0, bodyClose) + insertion + nl + classIndent + source.substring(bodyClose);
    }

    /**
     * The offset of the target class's body-opening brace, or {@code -1}.
     *
     * <p>Searched from the class's own span rather than from its name: two classes in one file can share
     * a simple name only if one is nested, and the span is the one that was measured for this target.</p>
     */
    private static int bodyOpenBrace(String source, Target target) {
        if (target.startOffset() < 0 || target.startOffset() >= source.length()) {
            return -1;
        }
        for (int index = target.startOffset(); index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                return index;
            }
            if (character == ';') {
                // A bodyless declaration: there is nowhere to put a member.
                return -1;
            }
        }
        return -1;
    }

    /** The offset of the brace matching the one at {@code open}, or {@code -1}. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    /** The whitespace at the start of the line containing {@code offset}. */
    private static String lineIndentBefore(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
        StringBuilder indent = new StringBuilder();
        for (int index = lineStart; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character != ' ' && character != '\t') {
                break;
            }
            indent.append(character);
        }
        return indent.toString();
    }
}
