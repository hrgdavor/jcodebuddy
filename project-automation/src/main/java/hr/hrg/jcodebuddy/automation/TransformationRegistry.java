package hr.hrg.jcodebuddy.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The {@link Transformation}s an engine can run, keyed by name.
 *
 * <h3>One registry, owned by the engine</h3>
 * <p>The plan this phase implements gave {@code ProjectAutomation} a registry of its own <em>and</em>
 * the engine a second one, which made {@code registerTransformation(...)} a no-op from the engine's point
 * of view: {@code executeOnFile} and {@code executeAllSequential} looked in the engine's registry, so a
 * transformation registered through the automation facade could never run. This class is the single
 * registry, {@link AutomationEngine} owns it, and {@link ProjectAutomation} registers through the engine.
 * A test asserts exactly that (<code>registeringOnTheFacadeIsVisibleToTheEngine</code>), because the
 * defect is invisible until someone calls the other entry point.</p>
 *
 * <h3>No reflection, no class names</h3>
 * <p>The sketch also offered {@code registerTransformation(name, Class<T>)} — which called itself
 * recursively, and would have instantiated the class reflectively. There is no such overload here. A
 * registry that turns a string into an instance is the invisible wiring DEC-019 rejects, and in this
 * module it would be doubly wrong: the transformations are hand-written classes with constructor
 * dependencies the registry cannot know. {@link #register(Transformation)} takes an instance, and the
 * registration site stays a direct, navigable call.</p>
 *
 * <p>Registration is <strong>fail-fast on a duplicate name</strong>: silently replacing a transformation
 * would mean a second registration changes what an unrelated caller's name does, and the name is the only
 * handle a batch report has.</p>
 */
public final class TransformationRegistry {

    private final Map<String, Transformation> transformations = new LinkedHashMap<>();

    /**
     * Registers a transformation under its own {@link Transformation#getName()}.
     *
     * @throws IllegalArgumentException if the transformation is null, or its name is null or empty
     * @throws IllegalStateException    if a different transformation is already registered under that name
     */
    public void register(Transformation transformation) {
        if (transformation == null) {
            throw new IllegalArgumentException("transformation cannot be null");
        }
        register(transformation.getName(), transformation);
    }

    /**
     * Registers a transformation under {@code name}, which must match
     * {@link Transformation#getName()} when the transformation has one.
     *
     * <p>The explicit overload exists for a caller that wants to register one implementation under a
     * second name (an alias, or a project-specific spelling). It refuses a mismatch rather than allowing
     * it: a name that disagrees with the transformation's own is how a report ends up naming a step the
     * transformation does not recognise.</p>
     */
    public void register(String name, Transformation transformation) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("transformation name cannot be null or empty");
        }
        if (transformation == null) {
            throw new IllegalArgumentException("transformation cannot be null");
        }
        if (!name.equals(transformation.getName())) {
            throw new IllegalArgumentException("registered name '" + name
                    + "' does not match the transformation's own name '" + transformation.getName() + "'");
        }
        Transformation previous = transformations.putIfAbsent(name, transformation);
        if (previous != null && previous != transformation) {
            throw new IllegalStateException("a different transformation is already registered as '"
                    + name + "': " + previous.getClass().getName() + " and "
                    + transformation.getClass().getName()
                    + ". Names are how a batch report identifies a step, so replacing one silently would "
                    + "change what an unrelated caller's name means.");
        }
    }

    /**
     * The transformation registered under {@code name}.
     *
     * @throws NoSuchElementException when nothing is registered under it — the names that <em>are</em>
     *                                registered are part of the message, because a typo is the common cause
     */
    public Transformation get(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("transformation name cannot be null or empty");
        }
        Transformation transformation = transformations.get(name);
        if (transformation == null) {
            throw new NoSuchElementException("no transformation registered as '" + name
                    + "'; registered: " + getAll());
        }
        return transformation;
    }

    /**
     * The transformation registered under {@code name}, as {@code type}.
     *
     * <p>A typed accessor rather than the sketch's unchecked {@code <T> T get(String)}, which inferred
     * its type from the assignment target and so could not fail: {@code Helper h = registry.get("other")}
     * compiled and threw {@link ClassCastException} at the first use, far from the mistake.</p>
     *
     * @throws IllegalArgumentException when the registered transformation is not a {@code type}
     */
    public <T extends Transformation> T get(String name, Class<T> type) {
        Transformation transformation = get(name);
        if (!type.isInstance(transformation)) {
            throw new IllegalArgumentException("'" + name + "' is a " + transformation.getClass().getName()
                    + ", not a " + type.getName());
        }
        return type.cast(transformation);
    }

    /** Whether something is registered under {@code name}. */
    public boolean has(String name) {
        return name != null && transformations.containsKey(name);
    }

    /** Every registered name, in registration order — the order a sequential run should follow. */
    public List<String> getAll() {
        return List.copyOf(transformations.keySet());
    }

    /** The number of registered transformations. */
    public int size() {
        return transformations.size();
    }

    /** Forgets everything registered. For a test that wants a fresh registry, or a caller re-registering. */
    public void clear() {
        transformations.clear();
    }
}
