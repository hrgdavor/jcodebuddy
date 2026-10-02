package hr.hrg.jcodebuddy.engine.source;

/**
 * One member the splice path knows how to append: a {@code default} method that returns a new builder over
 * {@code this}.
 *
 * <p>It belongs to the engine rather than to the emitter that first declared it. {@link SourceSplicer} is the
 * write path, and a write path that needs an emitter's nested type to be called is a write path that points
 * the wrong way — the engine would have to know about view codegen to append a member. The shape is two
 * strings; the emitter keeps its own {@code EntryPoint} and converts at the one call site, so its public API
 * and its tests do not move (plan step 3.0f-2).</p>
 *
 * @param methodName  the method to add
 * @param builderType the type it returns
 */
public record EntryPoint(String methodName, String builderType) {
}