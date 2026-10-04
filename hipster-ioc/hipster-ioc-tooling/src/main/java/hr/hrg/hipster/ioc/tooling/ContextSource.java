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

    /** DEC-036 § 9's thresholds: the instance-field section, and both of the other two. */
    private static final int FIELDS_THRESHOLD = 5;
    private static final int SECTION_THRESHOLD = 3;

    /** The region ids, short and stable per DEC-035: a parser pairs by id, and a reader never sees a sentence. */
    private static final String REGION_FIELDS = "fields";
    private static final String REGION_ACCESSORS = "accessors";
    private static final String REGION_FACTORIES = "factories";

    /** The generator a marker names, so a parser can tell which generator owns the region. */
    private static final String GENERATOR_FQN = IocContextGenerator.class.getName();

    private ContextSource() {
    }

    /** One marker line, at the indentation of the section it delimits. */
    private static void marker(StringBuilder sb, String indent, String marker) {
        sb.append(indent).append(marker).append('\n');
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
        // A parameter that a declared dependency can answer is NOT also an extra: it is resolved as
        // `dataContext.rows()`, and keeping it as a constructor parameter too would ask the caller for a value the
        // context already has a source for — the same redundancy in the signature that the extra-parameter rule
        // exists to avoid (DEC-036 § 6's amendment).
        extraParameters = extraParameters.stream()
                .filter(parameter -> dependencyBeanOf(context, parameter.typeText()) == null)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        // DEC-036 § 9: region markers around the three sections the design document names, and only when a
        // section exceeds its threshold — below that the language's own boundaries already delimit the code, and
        // a marker per small section is noise in every generated file forever.
        boolean fieldsRegion = ordered.size() > FIELDS_THRESHOLD;
        boolean accessorsRegion = ordered.size() > SECTION_THRESHOLD;
        boolean factoriesRegion = ordered.size() > SECTION_THRESHOLD;
        StringBuilder sb = new StringBuilder();

        // DEC-035's file marker and DEC-021's config line. The marker is what tells a parser, an agent or a
        // reviewer that the whole file is generated without their knowing this generator at all.
        sb.append(GeneratedCodeMarkers.fileHeader(GENERATOR_FQN,
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

        // DEC-036 § 6's amendment: a context's declared dependencies are RECEIVED, never constructed here. They come
        // first in the constructor because they are the context's outer wiring, and the parameters this context
        // cannot satisfy at all (its extras) follow.
        for (IocModel.ReferencedContext dependency : context.dependencies()) {
            sb.append(i1).append("private final ").append(dependency.typeText()).append(' ')
                    .append(dependency.fieldName()).append(";\n");
        }
        for (IocModel.Parameter parameter : extraParameters) {
            sb.append(i1).append("private final ").append(parameter.typeText()).append(' ')
                    .append(parameter.name()).append(";\n");
        }
        if (!extraParameters.isEmpty() || !context.dependencies().isEmpty()) {
            sb.append('\n');
        }
        if (fieldsRegion) {
            marker(sb, i1, GeneratedCodeMarkers.regionBegin(REGION_FIELDS, GENERATOR_FQN));
        }
        for (IocModel.Bean bean : ordered) {
            sb.append(i1).append("private final ").append(bean.typeText()).append(' ')
                    .append(bean.name()).append(";\n");
        }
        if (fieldsRegion) {
            marker(sb, i1, GeneratedCodeMarkers.regionEnd(REGION_FIELDS));
        }
        if (context.hasParent()) {
            sb.append('\n').append(i1).append("private ").append(context.parentType())
                    .append(" parent;\n");
        }
        sb.append('\n');

        sb.append(i1).append("public ").append(context.implSimpleName()).append('(');
        List<String> constructorParameters = new java.util.ArrayList<>();
        for (IocModel.ReferencedContext dependency : context.dependencies()) {
            constructorParameters.add(dependency.typeText() + " " + dependency.fieldName());
        }
        for (IocModel.Parameter parameter : extraParameters) {
            constructorParameters.add(parameter.typeText() + " " + parameter.name());
        }
        sb.append(String.join(", ", constructorParameters)).append(") {\n");
        for (IocModel.ReferencedContext dependency : context.dependencies()) {
            sb.append(i2).append("this.").append(dependency.fieldName()).append(" = ")
                    .append(dependency.fieldName()).append(";\n");
            // Where the child is attached is where its parent is set (DEC-036 § 6): the assignment goes here, in
            // the constructor, before any bean that might use the child is created.
            if (dependency.childContext()) {
                sb.append(i2).append(dependency.fieldName()).append(".setParent(this);\n");
            }
        }
        for (IocModel.Parameter parameter : extraParameters) {
            sb.append(i2).append("this.").append(parameter.name()).append(" = ").append(parameter.name())
                    .append(";\n");
        }
        if (factoriesRegion) {
            marker(sb, i2, GeneratedCodeMarkers.regionBegin(REGION_FACTORIES, GENERATOR_FQN));
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
        if (factoriesRegion) {
            marker(sb, i2, GeneratedCodeMarkers.regionEnd(REGION_FACTORIES));
        }
        sb.append(i1).append("}\n");

        if (accessorsRegion) {
            marker(sb, i1, GeneratedCodeMarkers.regionBegin(REGION_ACCESSORS, GENERATOR_FQN));
        }
        for (IocModel.Bean bean : ordered) {
            sb.append('\n').append(i1).append("@Override\n");
            sb.append(i1).append("public ").append(bean.typeText()).append(' ').append(bean.name())
                    .append("() {\n");
            sb.append(i2).append("return ").append(bean.name()).append(";\n");
            sb.append(i1).append("}\n");
        }
        if (accessorsRegion) {
            marker(sb, i1, GeneratedCodeMarkers.regionEnd(REGION_ACCESSORS));
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
     *
     * <p>A parameter whose type is a bean of a <em>declared dependency</em> is resolved from that context, by
     * calling its accessor — {@code dataContext.user()} — which is the cross-context wiring DEC-036 § 6's
     * amendment settles (step 3.7). This context's own beans win a tie: a name that this context builds is a
     * closer answer than one it would have to ask another context for.</p>
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
            if (match != null) {
                sb.append(match.name());
                continue;
            }
            IocModel.Bean own = byType(context.beans(), parameter.typeText());
            if (own != null) {
                sb.append(own.name());
                continue;
            }
            IocModel.ReferencedContext dependency = dependencyBeanOf(context, parameter.typeText());
            if (dependency != null) {
                sb.append(dependency.fieldName()).append('.').append(dependencyBeanName(dependency, parameter.typeText()))
                        .append("()");
                continue;
            }
            sb.append(parameter.name());
        }
        return sb.append(')').toString();
    }

    /** The bean of this context whose type is written {@code typeText}, or {@code null}. */
    private static IocModel.Bean byType(List<IocModel.Bean> beans, String typeText) {
        for (IocModel.Bean bean : beans) {
            if (sameType(bean.typeText(), typeText)) {
                return bean;
            }
        }
        return null;
    }

    /**
     * Whether two type texts name the same type, compared without package qualifiers.
     *
     * <p>Necessary because the two sides are written by different readers: a factory parameter is source text
     * ({@code List<String>}), while a bean's type comes from the index, which resolves it ({@code
     * java.util.List<java.lang.String>}). Comparing the texts literally therefore matched nothing, and the parameter
     * silently fell through to the extra-parameter rule — which produces code that does not compile, the worst of
     * the available outcomes. The boundary is deliberate and recorded: two identically-named types in different
     * packages would compare equal, and a context that declares both must disambiguate through an explicit factory
     * parameter name.</p>
     */
    private static boolean sameType(String left, String right) {
        return simpleTypeText(left).equals(simpleTypeText(right));
    }

    /** Type text with every package qualifier removed: {@code java.util.List<java.lang.String>} → {@code List<String>}. */
    private static String simpleTypeText(String typeText) {
        return typeText.trim().replaceAll("(?:[a-zA-Z_$][\\w$]*\\.)+", "");
    }

    /** The declared dependency that provides a bean of the parameter's type, or {@code null} when none does. */
    private static IocModel.ReferencedContext dependencyBeanOf(IocModel.Context context, String typeText) {
        IocModel.ReferencedContext found = null;
        int matches = 0;
        for (IocModel.ReferencedContext dependency : context.dependencies()) {
            if (byType(dependency.beans(), typeText) != null) {
                found = dependency;
                matches++;
            }
        }
        // Two dependencies offering the same type is ambiguous, and the honest answer is this context's own bean or
        // an explicit factory — not whichever dependency happens to be declared first. Returning null leaves the
        // parameter as the caller's (the extra-parameter rule), which is what the generator does for every
        // parameter it cannot answer.
        return matches == 1 ? found : null;
    }

    /** The accessor to call on a dependency for a parameter of this type. */
    private static String dependencyBeanName(IocModel.ReferencedContext dependency, String typeText) {
        IocModel.Bean bean = byType(dependency.beans(), typeText);
        return bean == null ? typeText.trim() : bean.name();
    }
}
