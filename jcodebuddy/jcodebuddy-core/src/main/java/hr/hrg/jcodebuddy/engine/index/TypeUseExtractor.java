package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects body-level dependency edges from one compilation unit.
 *
 * <p>The class index records declarations, not what their bodies call. This extractor adds the missing
 * edges for a dependency view: method invocations, constructor calls and capitalized identifier references.
 * It records the written target and deliberately does not resolve it; a {@link TypeReferenceExtractor} over
 * the same imports is the resolution step. Entries are deduplicated per owner/member/target/kind.</p>
 */
public final class TypeUseExtractor {

    private TypeUseExtractor() {
    }

    public static List<TypeUseRecord> of(J.CompilationUnit unit, String ownerFqn) {
        if (unit == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<TypeUseRecord> uses = new ArrayList<>();
        new JavaIsoVisitor<ExecutionContext>() {
            private String member = "";

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
                                                              ExecutionContext context) {
                String previous = member;
                member = method.getSimpleName();
                try {
                    return super.visitMethodDeclaration(method, context);
                } finally {
                    member = previous;
                }
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                            ExecutionContext context) {
                String target = method.getSelect() == null
                        ? method.getSimpleName()
                        : TreeQueries.expressionText(method.getSelect()) + "#" + method.getSimpleName();
                add(ownerFqn, member, target, "method-call");
                return super.visitMethodInvocation(method, context);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext context) {
                add(ownerFqn, member, TreeQueries.typeText(newClass.getClazz()), "new");
                return super.visitNewClass(newClass, context);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext context) {
                String name = identifier.getSimpleName();
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    add(ownerFqn, member, name, "type-reference");
                }
                return super.visitIdentifier(identifier, context);
            }

            private void add(String owner, String memberName, String target, String kind) {
                String key = owner + "\u0000" + memberName + "\u0000" + target + "\u0000" + kind;
                if (!target.isEmpty() && seen.add(key)) {
                    uses.add(new TypeUseRecord(owner, memberName, target, kind, -1));
                }
            }
        }.visit(unit, new InMemoryExecutionContext());
        return List.copyOf(uses);
    }
}
