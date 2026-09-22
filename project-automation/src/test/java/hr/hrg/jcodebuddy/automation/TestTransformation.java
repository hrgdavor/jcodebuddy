package hr.hrg.jcodebuddy.automation;

/**
 * A {@link Transformation} for tests: a name and a function from text to text, nothing else.
 *
 * <p>The automation tests need transformations that are trivially correct, because what they are testing is
 * the engine around them — the read/write boundary, the ordering of a chained run, the difference between
 * "changed nothing" and "failed". A real emitter would put a parser between the test and the assertion.</p>
 */
final class TestTransformation implements Transformation {

    /** The body of a test transformation; it may refuse, like a real one. */
    @FunctionalInterface
    private interface Body {
        String apply(String source) throws TransformationException;
    }

    private final String name;
    private final Body body;

    private TestTransformation(String name, Body body) {
        this.name = name;
        this.body = body;
    }

    /** Replaces every occurrence of {@code from} with {@code to}. */
    static TestTransformation replacing(String name, String from, String to) {
        return new TestTransformation(name, source -> source.replace(from, to));
    }

    /** Appends {@code suffix} to whatever it is given. */
    static TestTransformation appending(String name, String suffix) {
        return new TestTransformation(name, source -> source + suffix);
    }

    /** Returns its input unchanged: a legal no-op, and the shape a second idempotent pass has. */
    static TestTransformation identity(String name) {
        return new TestTransformation(name, source -> source);
    }

    /** Always fails, the way a transformation that cannot do its job must. */
    static TestTransformation failing(String name, String message) {
        return new TestTransformation(name, source -> {
            throw new TransformationException(name, message);
        });
    }

    /** Always throws an unchecked exception: a bug in the transformation, not a refusal. */
    static TestTransformation throwing(String name, RuntimeException error) {
        return new TestTransformation(name, source -> {
            throw error;
        });
    }

    /** Always returns {@code null}, which the engine has to refuse. */
    static TestTransformation returningNull(String name) {
        return new TestTransformation(name, source -> null);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "test transformation " + name;
    }

    @Override
    public String apply(String source) throws TransformationException {
        return body.apply(source);
    }
}
