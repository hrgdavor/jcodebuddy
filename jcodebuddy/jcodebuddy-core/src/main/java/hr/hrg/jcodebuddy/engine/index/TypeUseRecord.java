package hr.hrg.jcodebuddy.engine.index;

/**
 * One dependency edge from a member body: a method call, a constructor call, or a type-name reference.
 *
 * @param ownerFqn the declaring type
 * @param member   the member whose body contains the use
 * @param target   the written target (method name, constructed type, or identifier)
 * @param kind     {@code method-call}, {@code new}, or {@code type-reference}
 * @param line     1-based line when known, otherwise {@code -1}
 */
public record TypeUseRecord(String ownerFqn, String member, String target, String kind, int line) {

    public TypeUseRecord {
        ownerFqn = ownerFqn == null ? "" : ownerFqn;
        member = member == null ? "" : member;
        target = target == null ? "" : target;
        kind = kind == null ? "" : kind;
    }
}
