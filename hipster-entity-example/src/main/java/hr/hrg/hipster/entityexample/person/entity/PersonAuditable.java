package hr.hrg.hipster.entityexample.person.entity;

import hr.hrg.hipster.entity.api.View;
import hr.hrg.hipster.entityexample.example.Auditable;

@View()
public interface PersonAuditable extends Person, Auditable<Long> {
}
