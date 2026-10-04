package hr.hrg.hipster.ioc.tooling;

import hr.hrg.hipster.entity.tooling.DivergenceReporter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a context's beans into a creation order (DEC-036 § 3), or refuses to generate.
 *
 * <h3>Order, and why it is computed rather than written down</h3>
 *
 * <p>A bean's factory method declares its dependencies as parameters, so the edges are the user's own
 * declaration — nothing is inferred from a field, and nothing is ordered by chance. The sort is a plain
 * topological one that prefers declaration order whenever several beans are ready, so the generated
 * constructor reads in the order the interface does. That determinism is not cosmetic: the same input must
 * produce byte-identical output, or a regeneration pass shows a diff nobody caused.</p>
 *
 * <h3>What it refuses, and why refusing is the answer</h3>
 *
 * <p>A cycle the user did not mark with {@code @Circular} cannot be created in any order, and emitting
 * something that runs with a half-built bean in it is exactly the silent failure DEC-019 and DEC-022 exist
 * to prevent. So a cycle is reported as {@code circular_dependency_unmarked} and the context is
 * <strong>refused</strong> — no file is written, and the previous one is left exactly as it is.</p>
 *
 * <p>A cycle the user <em>did</em> mark is closed in two phases, which is what {@code @Circular} asks for: the
 * marked parameter is written {@code @Circular Supplier<Bean>}, it is <strong>not</strong> an ordering edge (the
 * supplier is only called after the context exists), and the renderer emits {@code () -> bean()} — a call to the
 * context's own accessor, so the cycle is closed in source a stock IDE can follow rather than by reflection. A
 * marked parameter that is not a {@code Supplier} is refused with {@code circular_dependency_needs_supplier},
 * because there is no way to resolve it after construction.</p>
 *
 * <p>A factory parameter that matches no bean is not an error: it becomes a constructor parameter of the
 * generated context, so the caller supplies it. The alternative — passing {@code null} — would compile and
 * then fail at the first use, which is the opposite of what this generator is for.</p>
 */
public final class DependencyOrder {

    /**
     * @param beans           the beans in creation order
     * @param extraParameters factory parameters the context does not provide, in a stable order; the
     *                        generated constructor takes them, so the caller decides what they are
     * @param deferred        the marked circular edges, each naming the bean whose factory takes the parameter
     *                        and the bean that parameter resolves to after construction (DEC-036 § 5)
     * @param refused         whether the context was refused (an unmarked cycle, or a marked edge that is not a
     *                        {@code Supplier}), in which case nothing is generated
     */
    public record Ordered(List<IocModel.Bean> beans, List<IocModel.Parameter> extraParameters,
                          List<Deferred> deferred, boolean refused) {

        public Ordered {
            beans = List.copyOf(beans);
            extraParameters = List.copyOf(extraParameters);
            deferred = List.copyOf(deferred);
        }
    }

    /**
     * One marked circular edge: {@code owner}'s factory takes {@code parameter}, which is a {@code Supplier} of
     * {@code target}.
     *
     * <p>It is deliberately <strong>not</strong> an ordering edge: the supplier is only called after the context
     * exists, which is exactly what breaks the cycle. Recording it rather than dropping it is what lets the
     * renderer emit {@code () -> target()} instead of the bare field name.</p>
     */
    public record Deferred(String owner, String parameter, String target) {
    }

    private DependencyOrder() {
    }

    public static Ordered sort(IocModel.Context context, DivergenceReporter divergences) {
        Map<String, IocModel.Bean> byName = new LinkedHashMap<>();
        Map<String, IocModel.Bean> byType = new LinkedHashMap<>();
        for (IocModel.Bean bean : context.beans()) {
            byName.put(bean.name(), bean);
            byType.putIfAbsent(bean.typeText().trim(), bean);
        }

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Set<IocModel.Parameter> extras = new LinkedHashSet<>();
        List<Deferred> deferred = new ArrayList<>();
        boolean refused = false;

        for (IocModel.Bean bean : context.beans()) {
            Set<String> dependsOn = new LinkedHashSet<>();
            IocModel.Factory factory = context.factories().get(bean.name());
            if (factory != null) {
                for (IocModel.Parameter parameter : factory.parameters()) {
                    if (parameter.circular()) {
                        // A marked edge is resolved after construction, so it is NOT an ordering edge: the
                        // supplier is only called once the context exists, which is what breaks the cycle. It
                        // does have to say what it supplies, and that type has to name a bean this context
                        // builds — otherwise the caller provides the supplier like any other extra parameter.
                        String supplied = parameter.suppliedType();
                        if (supplied == null) {
                            divergences.report("circular_dependency_needs_supplier",
                                    context.qualifiedName() + "." + factory.methodName(),
                                    "the parameter '" + parameter.name() + "' is marked @Circular but is not a "
                                            + "Supplier, and only a Supplier can be resolved after construction",
                                    parameter.typeText() + " " + parameter.name(),
                                    "@Circular Supplier<" + parameter.typeText().trim() + "> "
                                            + parameter.name(),
                                    "write the parameter as @Circular Supplier<Bean>, the two-phase form "
                                            + "DEC-036 § 5 settled on");
                            refused = true;
                            continue;
                        }
                        IocModel.Bean target = byName.get(parameter.name());
                        if (target == null) {
                            target = byType.get(supplied);
                        }
                        if (target == null || target.name().equals(bean.name())) {
                            extras.add(parameter);
                        } else {
                            deferred.add(new Deferred(bean.name(), parameter.name(), target.name()));
                        }
                        continue;
                    }
                    IocModel.Bean match = byName.get(parameter.name());
                    if (match == null) {
                        match = byType.get(parameter.typeText().trim());
                    }
                    if (match == null) {
                        extras.add(parameter);
                    } else if (!match.name().equals(bean.name())) {
                        dependsOn.add(match.name());
                    }
                }
            }
            dependencies.put(bean.name(), dependsOn);
        }

        List<IocModel.Bean> remaining = new ArrayList<>(context.beans());
        List<IocModel.Bean> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        while (!remaining.isEmpty()) {
            IocModel.Bean ready = null;
            for (IocModel.Bean candidate : remaining) {
                if (placed.containsAll(dependencies.get(candidate.name()))) {
                    ready = candidate;
                    break;
                }
            }
            if (ready == null) {
                for (IocModel.Bean stuck : remaining) {
                    divergences.report("circular_dependency_unmarked",
                            context.qualifiedName() + "." + stuck.name(),
                            "the factory parameters form a cycle and no parameter is marked @Circular, so "
                                    + "no creation order exists",
                            "bean '" + stuck.name() + "' waits for " + dependencies.get(stuck.name()),
                            "an order in which every dependency exists before its user",
                            "restructure the factories so one edge disappears");
                }
                refused = true;
                break;
            }
            ordered.add(ready);
            placed.add(ready.name());
            remaining.remove(ready);
        }

        if (refused) {
            // Refused means "nothing is generated", not "generate what happened to sort": a partial
            // context would not compile, and a partially written file is worse than an untouched one.
            return new Ordered(List.of(), List.of(), List.of(), true);
        }
        return new Ordered(ordered, new ArrayList<>(extras), deferred, false);
    }
}
