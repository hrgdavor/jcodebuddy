package demo.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The JUnit 5 test the runner executes after every reload, on the freshly loaded {@link Calculation} class.
 *
 * <p>JUnit itself comes from the parent classloader, so {@code @Test} and {@code assertEquals} resolve to the
 * same types the runner's launcher uses — that is what makes the reloaded class testable at all.</p>
 */
class CalculationTest {

    @Test
    void theTotalIsTheProduct() {
        Calculation calculation = new Calculation(6, 7);
        assertEquals(42, calculation.total());
    }
}
