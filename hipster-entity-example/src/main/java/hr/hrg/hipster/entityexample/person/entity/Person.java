package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.EntityBase;
import hr.hrg.hipster.entity.api.Identifiable;
import hr.hrg.hipster.entity.api.View;

@View(addons = {PersonAuditable.class})
public interface Person extends EntityBase<Long>, Identifiable<Long> {
    String firstName();
    String lastName();

    public record Record(Long id, String firstName, String lastName) implements Person {}
}
