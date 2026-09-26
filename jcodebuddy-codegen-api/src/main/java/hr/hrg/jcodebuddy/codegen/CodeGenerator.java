package hr.hrg.jcodebuddy.codegen;

/**
 * Something that generates code for a file, when it applies.
 *
 * <p>Two methods rather than one, and the split is the point: {@link #isApplicable} is a cheap
 * predicate that says whether this generator has anything to do with the file at all, and
 * {@link #generate} runs only when it does. A generator is therefore safe to offer to every file — the
 * cost of asking is the predicate, not a parse — which is what lets a tool hold a list of generators and
 * try them in order without knowing what any of them is for.
 *
 * <p>{@link #generate} returns a value rather than writing: what a generator produces is data, and
 * turning it into a file write is the caller's decision. That keeps generation testable without a
 * filesystem, and it is why a generator can be reused by a watch agent that applies its output through
 * an editor and by a command that writes it to disk.
 *
 * @param <T> what this generator produces
 */
public interface CodeGenerator<T> {

    /** A stable name for diagnostics — this is what a log line or a report calls the generator. */
    String name();

    /** Whether this generator has anything to do with the file the context describes. */
    boolean isApplicable(CodeContext context);

    /** Produce the generated form. Only called when {@link #isApplicable} returned true. */
    T generate(CodeContext context);
}
