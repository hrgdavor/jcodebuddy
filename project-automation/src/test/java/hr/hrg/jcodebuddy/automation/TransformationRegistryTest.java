package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry's semantics, including the two shortcuts the plan's sketch took that this class refuses.
 *
 * <p>Both refusals are load-bearing rather than stylistic. A registry that replaced a name silently would
 * change what an unrelated caller's name means, and a registry that instantiated a class from a string
 * would be the invisible wiring DEC-019 rejects — so both are asserted here rather than left to the
 * class's javadoc.</p>
 */
class TransformationRegistryTest {

    @Test
    void keepsRegistrationOrder() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestTransformation.identity("first"));
        registry.register(TestTransformation.identity("second"));

        assertEquals(List.of("first", "second"), registry.getAll());
        assertEquals(2, registry.size());
        assertTrue(registry.has("first"));
        assertFalse(registry.has("third"));
        assertFalse(registry.has(null));
    }

    @Test
    void registersAndReturnsTheSameInstance() {
        TransformationRegistry registry = new TransformationRegistry();
        TestTransformation transformation = TestTransformation.identity("one");
        registry.register(transformation);

        assertSame(transformation, registry.get("one"));
        assertSame(transformation, registry.get("one", TestTransformation.class));
    }

    @Test
    void refusesASecondDifferentTransformationUnderOneName() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestTransformation.identity("one"));

        // Same name, a different object: the second registration would silently change what "one" runs.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> registry.register(TestTransformation.identity("one")));
        assertTrue(failure.getMessage().contains("TestTransformation"));
        assertEquals(1, registry.size());
    }

    @Test
    void reRegisteringTheSameInstanceIsIdempotent() {
        TransformationRegistry registry = new TransformationRegistry();
        TestTransformation transformation = TestTransformation.identity("one");
        registry.register(transformation);
        registry.register(transformation);

        assertEquals(List.of("one"), registry.getAll());
    }

    @Test
    void refusesANameThatDisagreesWithTheTransformationsOwn() {
        TransformationRegistry registry = new TransformationRegistry();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.register("alias", TestTransformation.identity("real")));
        assertTrue(failure.getMessage().contains("alias"));
        assertTrue(failure.getMessage().contains("real"));
        assertEquals(0, registry.size());
    }

    @Test
    void refusesNullAndEmptyInput() {
        TransformationRegistry registry = new TransformationRegistry();

        assertThrows(IllegalArgumentException.class, () -> registry.register((Transformation) null));
        assertThrows(IllegalArgumentException.class, () -> registry.register(TestTransformation.identity("")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(null, TestTransformation.identity("one")));
        assertThrows(IllegalArgumentException.class, () -> registry.register("", TestTransformation.identity("one")));
        assertThrows(IllegalArgumentException.class, () -> registry.get(null));
        assertThrows(IllegalArgumentException.class, () -> registry.get(""));
    }

    @Test
    void anUnknownNameListsTheOnesThatExist() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestTransformation.identity("alpha"));

        NoSuchElementException failure = assertThrows(NoSuchElementException.class, () -> registry.get("alfa"));
        // A typo is the common cause, so the message has to contain the names that are actually registered.
        assertTrue(failure.getMessage().contains("alfa"));
        assertTrue(failure.getMessage().contains("alpha"));
    }

    @Test
    void typedAccessRefusesTheWrongTypeInsteadOfFailingLater() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestTransformation.identity("one"));

        // The sketch's `<T> T get(String)` inferred its type from the assignment target and so could not
        // fail here; this one names the mistake at the call.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> registry.get("one", OtherTransformation.class));
        assertTrue(failure.getMessage().contains("not a"));
    }

    @Test
    void clearForgetsEverything() {
        TransformationRegistry registry = new TransformationRegistry();
        registry.register(TestTransformation.identity("one"));
        registry.clear();

        assertEquals(0, registry.size());
        assertEquals(List.of(), registry.getAll());
        assertThrows(NoSuchElementException.class, () -> registry.get("one"));
    }

    /** A second {@link Transformation} implementation, so a typed lookup has a wrong type to refuse. */
    private static final class OtherTransformation implements Transformation {

        @Override
        public String getName() {
            return "other";
        }

        @Override
        public String getDescription() {
            return "the other one";
        }

        @Override
        public String apply(String source) {
            return source;
        }
    }
}
