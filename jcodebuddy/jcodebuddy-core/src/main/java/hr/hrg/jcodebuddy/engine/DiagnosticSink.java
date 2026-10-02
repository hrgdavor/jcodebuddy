package hr.hrg.jcodebuddy.engine;

/**
 * Where the engine reports something it could not answer.
 *
 * <p>The rule this port exists for is DEC-037's, and it came from a real failure: <strong>a missing answer
 * is reported, never inferred as absent</strong>. A file that could not be read, a type that could not be
 * resolved and a relation the index does not know are all things a consumer must be told about, because the
 * alternative — a quiet empty answer — looks exactly like a fact.</p>
 *
 * <p>The engine defines the port and says nothing about what a report is stored in; the consumer supplies
 * the sink. The entity pass's {@code DivergenceReporter} implements it and owns DEC-022's format (the pairs,
 * the ordering, the {@code kind} vocabulary); this interface deliberately holds one method so that
 * implementing it costs a consumer nothing.</p>
 */
public interface DiagnosticSink {

    /**
     * Reports one diagnostic.
     *
     * @param kind      what kind of problem it is (the consumer owns the vocabulary)
     * @param location  where it is, in the consumer's own path convention
     * @param cause     why it happened
     * @param current   what is there now, or {@code null}
     * @param canonical what the engine expected instead, or {@code null}
     * @param action    what a reader should do about it
     */
    void report(String kind, String location, String cause, String current, String canonical, String action);
}