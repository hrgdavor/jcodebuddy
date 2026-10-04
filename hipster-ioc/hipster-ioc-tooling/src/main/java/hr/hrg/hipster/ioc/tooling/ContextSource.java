package hr.hrg.hipster.ioc.tooling;

import hr.hrg.jcodebuddy.generated.GeneratedCodeMarkers;

import java.util.List;
import java.util.Map;

/**
 * Renders the canonical text of {@code <Context>Impl} (DEC-036 §§ 1–4, 6).
 *
 * <p>Three properties are load-bearing, and each one is a decision rather than a formatting taste:</p>
 *
 * <ul>
 *   <li><strong>The class implements the context interface directly</strong> and every accessor returns a
 *       field. No proxy, no map, no reflective lookup: a reader with a stock IDE follows {@code ctx.mapper()}
 *       to the field holding it (DEC-019).</li>
 *   <li><strong>Creation is in the computed order</strong> and each line names its factory, so the object
 *       graph is readable top to bottom: a bean's dependencies exist before the line that uses them.</li>
 *   <li><strong>The text is a pure function of the model.</strong> Same model, same bytes — which is what
 *       makes a regeneration pass show a diff only when something actually changed, and what lets the
 *       cooperative writer in {@link IocContextGenerator} recognise the file it wrote last time.</li>
 * </ul>
 */
public final class ContextSource {

    private ContextSource() {
    }

    /**
     * @param context         the interface being implemented
     * @param ordered         the beans in creation order
     * @param extraParameters factory parameters the context does not provide; they become constructor
     *                        parameters, so no generated code ever passes a {@code null}
     * @param deferred        the marked circular edges (DEC-036 § 5); each renders as a call to the accessor of
     *                        the bean it supplies, which is what closes the cycle after construction
     * @param indent          one indentation step, taken from the caller so generated code matches the
     *                        project's own style rather than the generator's
     * @return the file's text, ending in a newline
     */
    public static String render(IocModel.Context context, List<IocModel.Bean> ordered,
                                List<IocModel.Parameter> extraParameters,
                                List<DependencyOrder.Deferred> deferred, String indent) {
        String i1 = indent;
        String i2 = indent + indent;
        Map<String, String> deferredTargets = new java.util.LinkedHashMap<>();
        for (DependencyOrder.Deferred edge : deferred) {
            deferredTargets.put(edge.owner() + "." + edge.parameter(), edge.target());
        }
        StringBuilder sb = new StringBuilder();

        // DEC-035's file marker and DEC-021's config line. The marker is what tells a parser, an agent or a
        // reviewer that the whole file is generated without their knowing this generator at all.
        sb.append(GeneratedCodeMarkers.fileHeader(IocContextGenerator.class.getName(),
                "implementation of the " + context.simpleName() + " context."));
        sb.append("package ").append(context.packageName()).append(";\n\n");
        for (String importLine : context.importLines()) {
            sb.append(importLine).append('\n');
        }
        if (!context.importLines().isEmpty()) {
            sb.append('\n');
        }

        sb.append("/**\n");
        sb.append(" * Generated implementation of {@link ").append(context.simpleName()).append("}.\n");
        sb.append(" *\n");
        sb.append(" * <p>Beans are created in dependency order by the constructor");
        if (!extraParameters.isEmpty()) {
            sb.append(", which takes the dependencies this context does not provide");
        }
        sb.append(". Every accessor returns a field, so the object graph is the source you are reading.\n");
        sb.append(" * The class is generated from the interface: delete it to have it regenerated, or set\n");
        sb.append(" * {@code enabled:false} in the header above to take it under manual control (DEC-018).\n");
        sb.append(" */\n");

        sb.append("public class ").append(context.implSimpleName())
                .append(" implements ").append(context.simpleName()).append(" {\n\n");

        for (IocModel.Parameter parameter : extraParameters) {
            sb.append(i1).append("private final ").append(parameter.typeText()).append(' ')
                    .append(parameter.name()).append(";\n");
        }
        if (!extraParameters.isEmpty()) {
            sb.append('\n');
        }
        for (IocModel.Bean bean : ordered) {
            sb.append(i1).append("private final ").append(bean.typeText()).append(' ')
                    .append(bean.name()).append(";\n");
        }
        if (context.hasParent()) {
            sb.append('\n').append(i1).append("private ").append(context.parentType())
                    .append(" parent;\n");
        }
        sb.append('\n');

        sb.append(i1).append("public ").append(context.implSimpleName()).append('(');
        for (int i = 0; i < extraParameters.size(); i++) {
            IocModel.Parameter parameter = extraParameters.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameter.typeText()).append(' ').append(parameter.name());
        }
        sb.append(") {\n");
        for (IocModel.Parameter parameter : extraParameters) {
            sb.append(i2).append("this.").append(parameter.name()).append(" = ").append(parameter.name())
                    .append(";\n");
        }
        for (IocModel.Bean bean : ordered) {
            sb.append(i2).append("this.").append(bean.name()).append(" = ")
                    .append(creationOf(context, bean, deferredTargets)).append(";\n");
            // DEC-036 § 3: the same order that creates the beans drives the init* hooks. The hook for a bean
            // runs immediately after that bean exists and before anything that depends on it is created, which
            // is the guarantee the clause is about — and it is the user's own default method being called, so
            // no generated code lands in the user's edit path.
            String hook = context.initHooks().get(bean.name());
            if (hook != null) {
                sb.append(i2).append(hook).append('(').append(bean.name()).append(");\n");
            }
        }
        sb.append(i1).append("}\n");

        for (IocModel.Bean bean : ordered) {
            sb.append('\n').append(i1).append("@Override\n");
            sb.append(i1).append("public ").append(bean.typeText()).append(' ').append(bean.name())
                    .append("() {\n");
            sb.append(i2).append("return ").append(bean.name()).append(";\n");
            sb.append(i1).append("}\n");
        }

        if (context.hasParent()) {
            sb.append('\n').append(i1).append("@Override\n");
            sb.append(i1).append("public ").append(context.parentType()).append(" getParent() {\n");
            sb.append(i2).append("return parent;\n");
            sb.append(i1).append("}\n");
            sb.append('\n').append(i1).append("@Override\n");
            sb.append(i1).append("public void setParent(").append(context.parentType())
                    .append(" parent) {\n");
            sb.append(i2).append("this.parent = parent;\n");
            sb.append(i1).append("}\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * How one bean is created: the user's factory when there is one, otherwise the no-argument constructor.
     *
     * <p>The factory's arguments are the very fields the constructor has just filled — that is what makes
     * the generated file a picture of the dependency graph rather than a description of it.</p>
     *
     * <p>A marked circular edge is the one exception, and it is the point of the two-phase form (DEC-036 § 5):
     * the argument is {@code () -> target()}, a call to this context's own accessor, so the dependency is
     * resolved when it is <em>used</em> rather than when it is constructed. The lambda is not invoked during
     * construction, which is why the cycle closes; and because the target is named by its accessor, the edge is
     * navigable in a stock IDE like every other line of the generated file.</p>
     */
    private static String creationOf(IocModel.Context context, IocModel.Bean bean,
                                     Map<String, String> deferredTargets) {
        IocModel.Factory factory = context.factories().get(bean.name());
        if (factory == null) {
            return "new " + bean.typeText() + "()";
        }
        StringBuilder sb = new StringBuilder(factory.methodName()).append('(');
        Map<String, IocModel.Bean> byName = new java.util.LinkedHashMap<>();
        for (IocModel.Bean candidate : context.beans()) {
            byName.put(candidate.name(), candidate);
        }
        for (int i = 0; i < factory.parameters().size(); i++) {
            IocModel.Parameter parameter = factory.parameters().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            String target = deferredTargets.get(bean.name() + "." + parameter.name());
            if (target != null) {
                sb.append("() -> ").append(target).append("()");
                continue;
            }
            IocModel.Bean match = byName.get(parameter.name());
            sb.append(match != null ? match.name() : parameter.name());
        }
        return sb.append(')').toString();
    }
}
