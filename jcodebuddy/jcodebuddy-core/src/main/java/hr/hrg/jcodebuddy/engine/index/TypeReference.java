package hr.hrg.jcodebuddy.engine.index;

import hr.hrg.jcodebuddy.engine.source.TreeQueries;

import java.util.List;

/**
 * One written type occurrence and what the engine could make of it.
 *
 * <p>The class index deliberately records a type as the source wrote it, because a file-local row cannot
 * know another file's imports or a classpath. This record is the extended-layer answer: it keeps the
 * written form and adds the resolved FQN, how it was resolved, and the candidates when it was not. A
 * consumer can therefore link {@code List<String>} to {@code java.util.List} without re-implementing the
 * resolution rules, while the unresolved case is a reported fact rather than a guess.</p>
 *
 * @param written    the type as written, type arguments included
 * @param raw        the raw type spelling, without type arguments or array brackets
 * @param arguments  resolved type arguments, in order; empty when the type is not parameterized
 * @param resolvedFqn the fully qualified name, or {@code null} when unresolved or ambiguous
 * @param status     the resolution outcome
 * @param via        how it was resolved ({@code import}, {@code same-package}, {@code java.lang},
 *                   {@code wildcard}, {@code fqn}); empty when unresolved
 * @param candidates candidate FQNs when the answer is ambiguous or unresolved
 * @param span       the occurrence's range in the declaring file, or {@code null}
 */
public record TypeReference(String written, String raw, List<TypeReference> arguments, String resolvedFqn,
                            Resolution status, String via, List<String> candidates,
                            TreeQueries.SourceSpan span) {

    /** The resolution outcome; an ambiguous answer is not "absent". */
    public enum Resolution {
        RESOLVED,
        AMBIGUOUS,
        UNRESOLVED
    }

    public TypeReference {
        written = written == null ? "" : written;
        raw = raw == null ? "" : raw;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
        status = status == null ? Resolution.UNRESOLVED : status;
        via = via == null ? "" : via;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean resolved() {
        return status == Resolution.RESOLVED && resolvedFqn != null;
    }
}
