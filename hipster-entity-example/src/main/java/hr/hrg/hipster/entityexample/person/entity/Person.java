package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;

/**
 * The entity marker for the {@code person} package.
 *
 * <p>It deliberately carries no {@code @View(addons = …)}: an addon declaration applies to the one
 * interface that carries it and to nothing else (plan.dsflash § 4.7/DR-1), and this interface is the
 * marker rather than a view, so the declaration was inert and is removed (§ 9/4.1). The addon path
 * is exercised by {@link PersonDetails}, which declares {@code PersonAuditable} itself.</p>
 */
public interface Person extends EntityBase<Long>, Identifiable<Long> {
    String firstName();
    String lastName();

    public record Record(Long id, String firstName, String lastName) implements Person {}
}
