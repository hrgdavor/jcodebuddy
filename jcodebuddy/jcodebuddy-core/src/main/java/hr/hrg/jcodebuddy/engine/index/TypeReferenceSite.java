package hr.hrg.jcodebuddy.engine.index;

/**
 * One place a type is mentioned: a relation, a member's type, a parameter, or an annotation argument.
 *
 * @param ownerFqn the type that declares the mention
 * @param member   the member name, or {@code ""} for the declaration itself
 * @param role     {@code relation}, {@code field}, {@code return}, {@code parameter}, {@code nested},
 *                 {@code annotation}
 * @param reference the resolved occurrence
 */
public record TypeReferenceSite(String ownerFqn, String member, String role, TypeReference reference) {

    public TypeReferenceSite {
        ownerFqn = ownerFqn == null ? "" : ownerFqn;
        member = member == null ? "" : member;
        role = role == null ? "" : role;
    }
}
