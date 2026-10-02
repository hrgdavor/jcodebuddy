package hr.hrg.jcodebuddy.codegen;

import java.util.List;

/**
 * How a generator turns a type name into a {@link TypeDefinition}.
 *
 * <p>An interface here rather than a full implementation, because the answer differs by environment and
 * the API should not pretend otherwise: a metadata pass resolves types from the sources it has read, a
 * watch agent from the editor's model, a test from a map. What they share is only the question.
 *
 * <p>{@link #empty()} is the honest default for a caller that has no resolver, and it resolves nothing
 * rather than guessing. A generator that needs a type and gets {@code null} back should fail loudly;
 * an implementation that invented a plausible {@code TypeDefinition} would let a wrong type reach
 * committed source, which is the failure this whole boundary exists to prevent.
 */
public interface TypeResolver {

    /**
     * The definition of a type, or {@code null} when this resolver does not know it.
     *
     * @param qualifiedName the type's fully qualified name
     */
    TypeDefinition resolve(String qualifiedName);

    /** The packages this resolver knows about, for a generator that must choose a name that resolves. */
    List<String> knownPackages();

    /** A resolver that knows nothing. */
    static TypeResolver empty() {
        return new EmptyTypeResolver();
    }

    /** The resolver that knows nothing, as a named type so it can be compared or shared. */
    final class EmptyTypeResolver implements TypeResolver {

        @Override
        public TypeDefinition resolve(String qualifiedName) {
            return null;
        }

        @Override
        public List<String> knownPackages() {
            return List.of();
        }
    }
}
