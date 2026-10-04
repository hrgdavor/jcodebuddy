package hr.hrg.hipster.ioc.tooling;

import java.util.List;
import java.util.Map;

/**
 * What the generator reads out of a context interface, as plain data.
 *
 * <p>Separate from the reader and the renderer on purpose: the decision about <em>what the context is</em>
 * (DEC-036) should not be entangled with the OpenRewrite shapes it was read from, and the renderer should be
 * testable without a source file. Everything here is a string a reader would recognise from their own
 * source — no {@code J.} node survives past {@link ContextReader}.</p>
 */
public final class IocModel {

    private IocModel() {
    }

    /**
     * One bean: an abstract no-argument accessor on the context interface.
     *
     * @param name     the accessor's name, which is also the field's name in the generated class
     * @param typeText the declared return type, as written in the interface
     */
    public record Bean(String name, String typeText) {

        /** {@code mapper} becomes {@code Mapper}, which is how a {@code build<Bean>} factory is spelled. */
        public String capitalized() {
            return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    /**
     * One parameter of a factory method.
     *
     * @param circular whether the parameter carries {@code @Circular}, i.e. whether the user has said this
     *                 edge is allowed to close a cycle
     */
    public record Parameter(String typeText, String name, boolean circular) {

        /**
         * The type argument when this parameter is written as a {@code Supplier<X>}, else {@code null}.
         *
         * <p>This is the spelling DEC-036 § 5 settled on for a marked circular edge: the annotation says the edge
         * closes a cycle, and the JDK type says how it is resolved — after construction, by calling the context's
         * own accessor. Both {@code Supplier<X>} and its fully qualified form are accepted, and a raw
         * {@code Supplier} (no type argument) is not: there would be nothing to resolve against.</p>
         */
        public String suppliedType() {
            String text = typeText == null ? "" : typeText.trim();
            int start = text.lastIndexOf("Supplier<");
            if (start < 0 || !text.endsWith(">")) {
                return null;
            }
            String argument = text.substring(start + "Supplier<".length(), text.length() - 1).trim();
            return argument.isEmpty() ? null : argument;
        }
    }

    /**
     * A {@code default build<Bean>(...)} method on the module interface — the user's way of saying how a
     * bean is created, and the reason a context can hold a bean the generator could never construct itself.
     *
     * @param beanName the bean this method builds, derived from the method name
     */
    public record Factory(String beanName, String methodName, List<Parameter> parameters) {

        public Factory {
            parameters = List.copyOf(parameters);
        }
    }

    /**
     * One {@code @HipsterContext} interface.
     *
     * @param dependencyTypes  the class-valued entries of {@code dependencies()}, as written
     * @param parentType       the type argument of a {@code ChildContext<P>} supertype, or empty
     * @param hasImplementation whether {@code impl()} names a class, in which case the generator must not
     *                          emit an implementation at all (DEC-036 § 7)
     * @param importLines      the interface file's own import lines, re-emitted so the generated class can
     *                         name the same types the interface does
     */
    public record Context(String packageName, String simpleName, List<Bean> beans,
                          Map<String, Factory> factories, List<String> dependencyTypes, String parentType,
                          boolean hasImplementation, List<String> importLines) {

        public Context {
            beans = List.copyOf(beans);
            factories = Map.copyOf(factories);
            dependencyTypes = List.copyOf(dependencyTypes);
            importLines = List.copyOf(importLines);
        }

        public String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        /** The generated class's name: DEC-036 § 1 fixes {@code <Context>Impl} as the contract. */
        public String implSimpleName() {
            return simpleName + "Impl";
        }

        /** Whether this context implements {@code ChildContext}, so parent accessors are generated. */
        public boolean hasParent() {
            return parentType != null && !parentType.isEmpty();
        }
    }
}
