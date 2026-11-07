package demo.project;

/**
 * The project source this example is about: one immutable calculation.
 *
 * <p>It is <strong>not</strong> part of the Maven build. {@code run.js} only builds the runner module, and the
 * project's sources live under {@code project/src/...} — Maven's source root is the module's own
 * {@code src/main/java}. {@code demo.runner.ReloadRunner} hands this file to
 * {@code hr.hrg.jcodebuddy.compile.EcjIncrementalCompiler} at run time, and the resulting class bytes are loaded
 * by {@code demo.runner.ProjectClassLoader}.</p>
 *
 * <p><strong>Edit this file while the runner is watching.</strong> Saving it is the whole demo: the fs-meta
 * daemon reports the change, the change is compiled, a new project classloader is created, and the app and the
 * JUnit test run again on the new class.</p>
 */
public record Calculation(int units, int unitPrice) {

    /**
     * The value the app prints and the test asserts.
     *
     * <p>Try changing the operator (for example {@code units * unitPrice} to {@code units + unitPrice}) or the
     * numbers in {@link App}: the console output changes on the next batch.</p>
     */
    public int total() {
        return units * unitPrice;
    }
}
