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
 * <p>A cycle the user <em>did</em> mark is refused too, and that is a limitation of this first
 * implementation rather than a decision: closing a marked cycle needs the dependency to be resolved after
 * construction (a {@code Supplier<Bean>} parameter or a setter the generator can call), and neither shape
 * is expressible through the current API. The diagnostic says so; DEC-036 § 5 carries the amendment.</p>
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
     * @param refused         whether the context was refused (a cycle), in which case nothing is generated
     */
    public record Ordered(List<IocModel.Bean> beans, List<IocModel.Parameter> extraParameters, boolean refused) {

        public Ordered {
            beans = List.copyOf(beans);
            extraParameters = List.copyOf(extraParameters);
        }
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
        boolean refused = false;

        for (IocModel.Bean bean : context.beans()) {
            Set<String> dependsOn = new LinkedHashSet<>();
            IocModel.Factory factory = context.factories().get(bean.name());
            if (factory != null) {
                for (IocModel.Parameter parameter : factory.parameters()) {
                    if (parameter.circular()) {
                        divergences.report("circular_dependency_marked_unsupported",
                                context.qualifiedName() + "." + factory.methodName(),
                                "the parameter '" + parameter.name() + "' is marked @Circular, and the "
                                        + "two-phase form that would close a marked cycle is not implemented",
                                parameter.typeText() + " " + parameter.name(),
                                "a cycle closed after construction",
                                "restructure the factory so the cycle is not needed, or track the "
                                        + "Supplier-parameter form in DEC-036 § 5");
                        refused = true;
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
            return new Ordered(List.of(), List.of(), true);
        }
        return new Ordered(ordered, new ArrayList<>(extras), false);
    }
}
